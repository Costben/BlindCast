package com.erl.blindcast.core.priv

import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Root 真身执行器（Root-Backend-1 · 单次 `app_process`，不搭常驻 daemon，只做断电/点亮；
 * Universal-1 起加 input 单次反控：tap/drag/key/text 各一次即退，tap 冷起约 1-2s 可接受先正确再快）。
 *
 * 背景（主控真机取证）：MAA-Meow 在本机起 `com.aliothmoon.maameow:root_service` 跑在 uid 0
 * 真 root 身份调断电才生效；我们的 Shizuku UserService 是 shell 身份，被 OPlus 静默忽略。
 * 故加 Root 后端，与 Shizuku/按键形成 Root→Shizuku→按键三段路由
 * （路由见 [com.erl.blindcast.core.blackout.PowerController.setDisplayPowerRouted]；
 * 反控路由见 [com.erl.blindcast.core.priv.PrivilegedBridge] inject* Root→Shizuku 两段）。
 *
 * ## 执行方式（只用 libsu 同步 `Shell.cmd` API）
 * - 后台线程（调用方已在 `Dispatchers.IO`，本对象内同样切 IO）跑：
 *   `CLASSPATH=<apk> app_process /system/bin com.erl.blindcast.core.priv.RootMain
 *   displayPower on|off <resultFile>`（电源）或
 *   `... RootMain input tap x y <resultFile>` /
 *   `... input drag x0 y0 x1 y1 <resultFile>` /
 *   `... input key <code> <resultFile>` /
 *   `... input text '<b64>' <resultFile>`（反控，b64 为 UTF-8 的 Base64 NO_WRAP，
 *   单引号包裹防空串被 shell 吞参，b64 字母表无单引号故安全）；
 *   `Shell.cmd` 本身即跑在 root shell 内，等价于任务包所述 `su -c "..."` 内层，
 *   不再套一层 `su -c`（省一次嵌套引号转义，结果一致）；
 * - `CLASSPATH` 传调用方 `applicationInfo.sourceDir`（[runAsRootDisplayPower] /
 *   [runAsRootInput] 的 `apkPath` 参数，勿硬编码，由
 *   [com.erl.blindcast.core.blackout.PowerController] 经包名解析传入，
 *   反控侧由 [com.erl.blindcast.core.priv.PrivilegedBridge] 同款思路自解）；
 * - 超时约 20s（[ROOT_TIMEOUT_MS]，`withTimeoutOrNull` 包 `exec()`，超时按失败计）；
 * - 读结果文件判 `ok=true`（两行 `ok=`/`err=`，见 [RootMain]），BlindCast 日志记 uid/exit/result；
 * - 真机验证由主控做（本对象不碰 adb/手机之外的任何设备操作，Gradle 侧只保证编译）。
 */
object RootExecutor {

    /** 全链路统一 TAG（与 PowerController / RootMain 一致）。 */
    private const val TAG = "BlindCast"

    /** 单次 root 调用超时约 20s（app_process 冷起 + binder→验效→按键兜底全链路留足余量）。 */
    private const val ROOT_TIMEOUT_MS = 20_000L

    /** shell 段长任务超时约 90s（`vdCreate` 含建屏 + 观察 Home 拉起 + hold，见 RootMain.runVdCreate）。 */
    private const val SHELL_LONG_TIMEOUT_MS = 90_000L

    /** 结果文件目录（与 RootMain 约定，nonce 由本侧生成防并发串扰）。 */
    private const val RESULT_DIR = "/data/local/tmp"

    /** 结果文件前缀（完整形如 `/data/local/tmp/blindcast_root_result_<nonce>`）。 */
    private const val RESULT_PREFIX = "blindcast_root_result_"

    /** Root 可用性缓存（su 存在 + Shell 授权；拒绝/无 su 缓存 false，不抛，见 [isRootAvailable]）。 */
    @Volatile
    private var cachedRootAvailable: Boolean? = null

    private fun tid(): String {
        val t = Thread.currentThread()
        return "t=${t.id}(${t.name})"
    }

    /**
     * Root 是否可用：su 存在 + libsu Shell 已授权。
     *
     * 只用同步 `Shell.cmd("id").exec()` 判定（`uid=0` 即有 root），结果缓存；
     * 拒绝/无 su/任何异常一律返回 false 不抛（调用方据此走 Shizuku 段）。
     * 阻塞调用（含首次 root 授权弹框等待），必须在后台线程调
     * （[com.erl.blindcast.core.blackout.PowerController] 路由已在 IO 线程内）。
     *
     * @return true = 可走 Root 段；false = 无 su / 未授权 / 判定异常。
     */
    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
        val available: Boolean = try {
            val res = Shell.cmd("id").exec()
            val ok = try {
                res.isSuccess && res.out.any { it.contains("uid=0") }
            } catch (_: Throwable) {
                false
            }
            runCatching {
                Log.d(TAG, "[RootExecutor] ${tid()} isRootAvailable myUid=$myUid " +
                    "code=${runCatching { res.code }.getOrDefault(-1)} " +
                    "out=${runCatching { res.out }.getOrDefault(emptyList()).take(3)} available=$ok")
            }
            ok
        } catch (t: Throwable) {
            runCatching {
                Log.d(TAG, "[RootExecutor] ${tid()} isRootAvailable myUid=$myUid threw ${t.message}")
            }
            false
        }
        cachedRootAvailable = available
        return available
    }

    /** 清 Root 可用性缓存（用户去 KernelSU 点允许后重试用；常规路由不调）。 */
    fun clearRootAvailableCache() {
        cachedRootAvailable = null
    }

    /**
     * 以 root 身份执行一次断电/点亮（单次 `app_process`，挂起约 20s 超时）。
     *
     * @param packageName 调用方包名（`context.packageName`，仅日志/诊断用，勿硬编码）。
     * @param apkPath 调用方 `applicationInfo.sourceDir`（拼 `CLASSPATH=` 用，勿硬编码；为空直接失败）。
     * @param on true = 点亮，false = 物理熄屏。
     * @return root 进程内 [com.erl.blindcast.core.blackout.PowerController.setDisplayPower]
     *   验效通过（结果文件 `ok=true`）即 true；超时/执行失败/结果 miss/任何异常均 false 不抛。
     */
    suspend fun runAsRootDisplayPower(packageName: String, apkPath: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
            val onOff = if (on) "on" else "off"
            Log.d(TAG, "[RootExecutor] ${tid()} run enter pkg=$packageName on=$on myUid=$myUid")
            if (apkPath.isBlank()) {
                Log.d(TAG, "[RootExecutor] ${tid()} run abort empty apkPath pkg=$packageName on=$on")
                return@withContext false
            }
            // nonce 防并发串扰（同一时刻黑/亮各一次也不会读写同一文件）。
            val nonce = runCatching {
                UUID.randomUUID().toString().replace("-", "").take(8)
            }.getOrDefault(System.currentTimeMillis().toString())
            val resultFile = "$RESULT_DIR/${RESULT_PREFIX}${Process.myPid()}_${nonce}"
            // libsu root shell 内直跑内层（等价 su -c 内层，不再嵌套 su -c 省引号转义）。
            val cmd =
                "CLASSPATH=$apkPath app_process /system/bin com.erl.blindcast.core.priv.RootMain " +
                    "displayPower $onOff $resultFile"
            val shellResult: Shell.Result? = try {
                withTimeoutOrNull(ROOT_TIMEOUT_MS) {
                    Shell.cmd(cmd).exec()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[RootExecutor] ${tid()} run exec threw pkg=$packageName on=$on", t)
                null
            }
            if (shellResult == null) {
                Log.d(TAG, "[RootExecutor] ${tid()} run timeout/exec-null pkg=$packageName on=$on " +
                    "myUid=$myUid timeoutMs=$ROOT_TIMEOUT_MS resultFile=$resultFile")
                runCatching { Shell.cmd("rm -f $resultFile").exec() }
                return@withContext false
            }
            val exitCode = runCatching { shellResult.code }.getOrDefault(-1)
            val out = runCatching { shellResult.out }.getOrDefault(emptyList())
            val err = runCatching { shellResult.err }.getOrDefault(emptyList())
            Log.d(TAG, "[RootExecutor] ${tid()} run shell done pkg=$packageName on=$on " +
                "myUid=$myUid exit=$exitCode out=${out.take(5)} err=${err.take(5)} resultFile=$resultFile")
            // 读结果文件判 ok（直读 + shell cat 双通道，任一读到 ok=true 即成功）。
            val resultText: String? = try {
                readResultText(resultFile)
            } catch (t: Throwable) {
                Log.d(TAG, "[RootExecutor] ${tid()} run read result threw ${t.message}")
                null
            }
            Log.d(TAG, "[RootExecutor] ${tid()} run result pkg=$packageName on=$on " +
                "myUid=$myUid exit=$exitCode result=${resultText?.take(200)}")
            // 尽力清理结果文件（吞错，不掩盖主结果）。
            runCatching { Shell.cmd("rm -f $resultFile").exec() }
            runCatching { File(resultFile).delete() }
            val ok = parseOk(resultText)
            Log.d(TAG, "[RootExecutor] ${tid()} run exit pkg=$packageName on=$on ok=$ok")
            ok
        }

    /**
     * 以 root 身份单次执行反控注入（Universal-1 · 单次 `app_process`，tap 冷起约 1-2s 可接受）。
     *
     * @param packageName 调用方包名（仅日志/诊断用，勿硬编码）。
     * @param apkPath 调用方 `applicationInfo.sourceDir`（拼 `CLASSPATH=` 用，勿硬编码；为空直接失败）。
     * @param subOp `tap` | `drag` | `key` | `text`（对应 [RootMain] input 子操作）。
     * @param params 子操作参数（tap: [x, y] 归一化浮点串；drag: [x0, y0, x1, y1]；
     *  key: [code]；text: [b64] Base64 NO_WRAP，空串传 "" 本方法自动单引号包裹防吞参）。
     * @return first=是否成功（结果文件 `ok=true`）；second=失败明细（成功时 null，
     *  含超时/执行失败/结果 miss/特权侧回传，单行已截断）。
     */
    suspend fun runAsRootInput(
        packageName: String,
        apkPath: String,
        subOp: String,
        params: List<String>,
    ): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
            Log.d(TAG, "[RootExecutor] ${tid()} input enter pkg=$packageName sub=$subOp params=${params.take(4)} myUid=$myUid")
            if (apkPath.isBlank()) {
                Log.d(TAG, "[RootExecutor] ${tid()} input abort empty apkPath pkg=$packageName sub=$subOp")
                return@withContext false to "Root段跳过（取APK路径失败）"
            }
            val sub = subOp.trim()
            if (sub != "tap" && sub != "drag" && sub != "key" && sub != "text") {
                return@withContext false to "Root段非法 input 子操作：$subOp"
            }
            // 参数个数校验（缺参不拉进程，直接失败省一次冷起）。
            // Phase C：尾部最多 3 个可选 [displayId] [width] [height]（目标屏路由），上限放宽。
            val expectSizes = mapOf("tap" to 2, "drag" to 4, "key" to 1, "text" to 1)
            val expect = expectSizes[sub] ?: -1
            if (params.size < expect || params.size > expect + 3) {
                return@withContext false to "Root段 input 参数个数非法（$sub 期望 $expect..${expect + 3} 个，实 ${params.size} 个）"
            }
            val core = params.take(expect)
            val extra = params.drop(expect)
            val nonce = runCatching {
                UUID.randomUUID().toString().replace("-", "").take(8)
            }.getOrDefault(System.currentTimeMillis().toString())
            val resultFile = "$RESULT_DIR/${RESULT_PREFIX}${Process.myPid()}_${nonce}"
            // text 的 b64 单引号包裹（空串 `''` 防 shell 吞参；b64 字母表无单引号故安全）。
            val paramStr = if (sub == "text") {
                val b64 = core[0]
                "'$b64'"
            } else {
                core.joinToString(" ")
            }
            // 目标屏参数必须排在 resultFile **之后**（RootMain 的 args 布局：core.. resultFile 可选屏参）。
            val extraStr = if (extra.isEmpty()) "" else " " + extra.joinToString(" ")
            val cmd =
                "CLASSPATH=$apkPath app_process /system/bin com.erl.blindcast.core.priv.RootMain " +
                    "input $sub $paramStr $resultFile$extraStr"
            val shellResult: Shell.Result? = try {
                withTimeoutOrNull(ROOT_TIMEOUT_MS) {
                    Shell.cmd(cmd).exec()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[RootExecutor] ${tid()} input exec threw pkg=$packageName sub=$sub", t)
                null
            }
            if (shellResult == null) {
                Log.d(TAG, "[RootExecutor] ${tid()} input timeout/exec-null pkg=$packageName sub=$sub " +
                    "myUid=$myUid timeoutMs=$ROOT_TIMEOUT_MS resultFile=$resultFile")
                runCatching { Shell.cmd("rm -f $resultFile").exec() }
                return@withContext false to "Root段 input 超时（约${ROOT_TIMEOUT_MS / 1000}s，见logcat [RootExecutor]/[RootMain]明细）"
            }
            val exitCode = runCatching { shellResult.code }.getOrDefault(-1)
            val out = runCatching { shellResult.out }.getOrDefault(emptyList())
            val err = runCatching { shellResult.err }.getOrDefault(emptyList())
            Log.d(TAG, "[RootExecutor] ${tid()} input shell done pkg=$packageName sub=$sub " +
                "myUid=$myUid exit=$exitCode out=${out.take(5)} err=${err.take(5)} resultFile=$resultFile")
            val resultText: String? = try {
                readResultText(resultFile)
            } catch (t: Throwable) {
                Log.d(TAG, "[RootExecutor] ${tid()} input read result threw ${t.message}")
                null
            }
            Log.d(TAG, "[RootExecutor] ${tid()} input result pkg=$packageName sub=$sub " +
                "myUid=$myUid exit=$exitCode result=${resultText?.take(200)}")
            runCatching { Shell.cmd("rm -f $resultFile").exec() }
            runCatching { File(resultFile).delete() }
            val (ok, errMsg) = parseOkErr(resultText)
            Log.d(TAG, "[RootExecutor] ${tid()} input exit pkg=$packageName sub=$sub ok=$ok err=${errMsg?.take(200)}")
            if (ok) true to null else false to (errMsg?.takeIf { it.isNotBlank() } ?: "Root段 input 失败（exit=$exitCode，见logcat [RootExecutor]/[RootMain]明细）")
        }

    /**
     * 以 root 身份跑一次 Phase C 最小探针（Vdm-Probe-1 · 单次 `app_process` RootMain vdProbe）。
     *
     * @param packageName 调用方包名（仅日志/诊断用，勿硬编码）。
     * @param apkPath 调用方 `applicationInfo.sourceDir`（拼 `CLASSPATH=` 用，勿硬编码；为空直接失败）。
     * @return first=是否执行成功（结果文件 `ok=true`）；second=探针多行报文（成功/失败均有，
     *  失败且无报文时为明细文案）。
     */
    suspend fun runAsRootVdProbe(packageName: String, apkPath: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
            Log.d(TAG, "[RootExecutor] ${tid()} vdProbe enter pkg=$packageName myUid=$myUid")
            if (apkPath.isBlank()) {
                return@withContext false to "Root段跳过（取APK路径失败）"
            }
            val nonce = runCatching {
                UUID.randomUUID().toString().replace("-", "").take(8)
            }.getOrDefault(System.currentTimeMillis().toString())
            val resultFile = "$RESULT_DIR/${RESULT_PREFIX}${Process.myPid()}_${nonce}"
            val cmd =
                "CLASSPATH=$apkPath app_process /system/bin com.erl.blindcast.core.priv.RootMain " +
                    "vdProbe $resultFile"
            val shellResult: Shell.Result? = try {
                withTimeoutOrNull(ROOT_TIMEOUT_MS) { Shell.cmd(cmd).exec() }
            } catch (t: Throwable) {
                Log.e(TAG, "[RootExecutor] ${tid()} vdProbe exec threw pkg=$packageName", t)
                null
            }
            if (shellResult == null) {
                runCatching { Shell.cmd("rm -f $resultFile").exec() }
                return@withContext false to "Root段 vdProbe 超时（约${ROOT_TIMEOUT_MS / 1000}s，见logcat [RootExecutor]/[RootMain]/[BlindCast-VDProbe]明细）"
            }
            val exitCode = runCatching { shellResult.code }.getOrDefault(-1)
            val out = runCatching { shellResult.out }.getOrDefault(emptyList())
            val err = runCatching { shellResult.err }.getOrDefault(emptyList())
            Log.d(TAG, "[RootExecutor] ${tid()} vdProbe shell done pkg=$packageName " +
                "exit=$exitCode out=${out.take(5)} err=${err.take(5)} resultFile=$resultFile")
            val resultText: String? = try {
                readResultText(resultFile)
            } catch (t: Throwable) {
                Log.d(TAG, "[RootExecutor] ${tid()} vdProbe read result threw ${t.message}")
                null
            }
            runCatching { Shell.cmd("rm -f $resultFile").exec() }
            runCatching { File(resultFile).delete() }
            val ok = parseOk(resultText)
            val report = resultText?.lineSequence()?.drop(2)?.joinToString("\n")?.trim()
                ?.takeIf { it.isNotBlank() }
            Log.d(TAG, "[RootExecutor] ${tid()} vdProbe exit pkg=$packageName ok=$ok " +
                "reportLen=${report?.length ?: 0} exit=$exitCode")
            if (ok) true to report else false to (report ?: "Root段 vdProbe 失败（exit=$exitCode，见logcat明细）")
        }

    /**
     * 以 shell（uid 2000）身份跑一次 Phase C 最小探针（Vdm-Shell-1）。
     *
     * ## 为什么不是 root
     * 真机实证（`outputs/probe/phase-c-probe.md` §3.1）：VDM `createVirtualDevice`
     * 的服务端校验「关联包名必须属于调用 uid」，root（uid 0）持有
     * `com.android.shell` 的关联时被拒 —— `SecurityException: Package name
     * com.android.shell does not belong to calling uid 0`。shell（uid 2000）才是
     * 关联/VDM 的正确身份（`cdv.call OK` / `deviceId=3` / `close OK` 均已在真机取得）。
     *
     * ## 执行方式
     * 经既有 libsu root shell 下发 `su 2000 -c '<内层>'`，派生一个 shell 身份的
     * `app_process` 子进程跑同一 [RootMain] `vdProbe` 入口；先跑一次
     * `su 2000 -c id` 取实际 uid 记日志（**不硬编成功**：uid 不是 2000 即报失败）。
     *
     * ## 与 Shizuku 的区别
     * 这是 root 通道内的进程派生，**不是 Shizuku 已授权**：Shizuku 仍为 NOT_RUNNING，
     * 本方法不动其授权状态。
     *
     * @param packageName 调用方包名（仅日志/诊断用，勿硬编码）。
     * @param apkPath 调用方 `applicationInfo.sourceDir`（拼 `CLASSPATH=` 用，勿硬编码；为空直接失败）。
     * @return first=是否执行成功（结果文件 `ok=true`）；second=探针多行报文（含身份行）。
     */
    suspend fun runAsShellVdProbe(packageName: String, apkPath: String): Pair<Boolean, String?> =
        runAsShellOp(packageName, apkPath, "vdProbe", emptyList(), "vdProbe")

    /**
     * 以 shell（uid 2000）身份跑一次 Phase C 虚拟桌面建屏探针（Vds-Shell-1）：
     * `RootMain vdCreate <holdSec> <resultFile>`。身份/通道与 [runAsShellVdProbe] 完全一致。
     */
    suspend fun runAsShellVdCreate(
        packageName: String,
        apkPath: String,
        holdSec: Int,
        flags: Int,
        vdmHome: Boolean,
    ): Pair<Boolean, String?> =
        runAsShellOp(
            packageName, apkPath, "vdCreate",
            listOf(holdSec.toString(), flags.toString(), if (vdmHome) "1" else "0"),
            "vdCreate",
        )

    /**
     * Dtc-Ipc-1-a：以 shell(uid 2000) 身份取 `dumpsys activity activities` 原文。
     *
     * app 进程（uid 10xxx）既无 `DUMP` 权限也无 root，`Shell.cmd("dumpsys ...")` 拿不到内容
     * （真机实测返回空 → 副屏任务列表恒空）；必须经 root 通道派生 uid2000 子进程执行。
     *
     * @return first=是否成功；second=原始 dumpsys 文本（[RootExecutor.runAsShellOp] 的 body）。
     */
    suspend fun runAsShellDumpsysActivities(
        packageName: String,
        apkPath: String,
    ): Pair<Boolean, String?> =
        runAsShellOp(packageName, apkPath, "dumpsysActs", emptyList(), "dumpsysActs")

    /**
     * Dtc-Ipc-1-b：以 shell(uid 2000) 身份把副屏已有 Task 拉回前台。
     *
     * 特权侧先按 taskId 反查其真实 displayId，不属于 [displayId] 一律拒绝
     * （**绝不移动物理主屏 display 0 的 Task**），通过后反射
     * `IActivityTaskManager.moveTaskToFront(taskId, 0)`。
     *
     * @return first=是否切换成功；second=说明/错误文案。
     */
    suspend fun runAsShellTaskFront(
        packageName: String,
        apkPath: String,
        taskId: Int,
        displayId: Int,
    ): Pair<Boolean, String?> =
        runAsShellOp(
            packageName, apkPath, "taskFront",
            listOf(taskId.toString(), displayId.toString()),
            "taskFront",
        )

    /**
     * 通用：以 shell（uid 2000）身份跑一次 [RootMain] 子操作。
     *
     * 经既有 libsu root shell 下发 `su 2000 -c '<内层>'`，派生 shell 身份 `app_process`
     * 子进程；先跑 `su 2000 -c id` 核实 uid（**不硬编成功**：非 2000 即报失败）。
     * 这是 root 通道内的进程派生，**不是 Shizuku 已授权**。
     *
     * @param op [RootMain] 子操作名（`vdProbe` / `vdCreate` / …）。
     * @param extraArgs 子操作参数（结果文件名由本方法补在末尾）。
     * @param label 日志/报文用的入口标签。
     */
    suspend fun runAsShellOp(
        packageName: String,
        apkPath: String,
        op: String,
        extraArgs: List<String>,
        label: String,
    ): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val myUid = runCatching { Process.myUid() }.getOrDefault(-1)
            Log.d(TAG, "[RootExecutor] ${tid()} shellOp enter label=$label op=$op pkg=$packageName myUid=$myUid")
            if (apkPath.isBlank()) {
                return@withContext false to "shell 段跳过（取APK路径失败）"
            }
            val idOut = runCatching {
                val r = withTimeoutOrNull(ROOT_TIMEOUT_MS) { Shell.cmd("su 2000 -c id").exec() }
                (r?.out.orEmpty() + r?.err.orEmpty()).joinToString(" ").trim()
            }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
            val isUid2000 = idOut.contains("uid=2000")
            Log.d(TAG, "[RootExecutor] ${tid()} shellOp su2000id=$idOut isUid2000=$isUid2000")
            val nonce = runCatching {
                UUID.randomUUID().toString().replace("-", "").take(8)
            }.getOrDefault(System.currentTimeMillis().toString())
            val resultFile = "$RESULT_DIR/${RESULT_PREFIX}${Process.myPid()}_${nonce}"
            val argStr = (listOf(op) + extraArgs + resultFile).joinToString(" ")
            val inner = "CLASSPATH=$apkPath app_process /system/bin com.erl.blindcast.core.priv.RootMain $argStr"
            val cmd = "su 2000 -c '$inner'"
            val timeoutMs = if (op == "vdCreate") SHELL_LONG_TIMEOUT_MS else ROOT_TIMEOUT_MS
            val shellResult: Shell.Result? = try {
                withTimeoutOrNull(timeoutMs) { Shell.cmd(cmd).exec() }
            } catch (t: Throwable) {
                Log.e(TAG, "[RootExecutor] ${tid()} shellOp exec threw label=$label", t)
                null
            }
            if (shellResult == null) {
                runCatching { Shell.cmd("rm -f $resultFile").exec() }
                return@withContext false to
                    "shell 段 $label 超时（约${timeoutMs / 1000}s，su2000id=$idOut，见logcat明细）"
            }
            val exitCode = runCatching { shellResult.code }.getOrDefault(-1)
            val out = runCatching { shellResult.out }.getOrDefault(emptyList())
            val err = runCatching { shellResult.err }.getOrDefault(emptyList())
            Log.d(TAG, "[RootExecutor] ${tid()} shellOp done label=$label exit=$exitCode " +
                "out=${out.take(5)} err=${err.take(5)} resultFile=$resultFile")
            val resultText: String? = try {
                readResultText(resultFile)
            } catch (t: Throwable) {
                Log.d(TAG, "[RootExecutor] ${tid()} shellOp read result threw ${t.message}")
                null
            }
            runCatching { Shell.cmd("rm -f $resultFile").exec() }
            runCatching { File(resultFile).delete() }
            val ok = parseOk(resultText)
            val body = resultText?.lineSequence()?.drop(2)?.joinToString("\n")?.trim()
                ?.takeIf { it.isNotBlank() }
            val report = ("[identity] su2000id=$idOut isUid2000=$isUid2000\n" +
                (body ?: "(shell 段无报文)")).trim()
            Log.d(TAG, "[RootExecutor] ${tid()} shellOp exit label=$label ok=$ok isUid2000=$isUid2000 exit=$exitCode")
            when {
                !isUid2000 -> false to "shell 段未取得 uid 2000（$idOut）\n$report"
                ok -> true to report
                else -> false to report
            }
        }

    /**
     * 读结果文件（直读优先，失败回退 `cat`，均失败返回 null）。
     *
     * RootMain 写后已 `chmod 644`，App 进程直读通常可达；SELinux/权限变体时走 root `cat`。
     */
    private fun readResultText(resultFile: String): String? {
        // 通道 1：App 进程直读。
        runCatching {
            val f = File(resultFile)
            if (f.exists()) {
                val text = f.readText()
                if (text.isNotBlank()) return text
            }
        }
        // 通道 2：root shell cat（文件属 root 且直读被拦时）。
        return runCatching {
            val cat = Shell.cmd("cat $resultFile").exec()
            if (cat.isSuccess) {
                val text = cat.out.joinToString("\n")
                text.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    /** 结果文件判 ok（首行 `ok=true` 即成功，其余一律失败）。 */
    private fun parseOk(resultText: String?): Boolean {
        if (resultText.isNullOrBlank()) return false
        val first = resultText.lineSequence().firstOrNull()?.trim() ?: return false
        return first == "ok=true"
    }

    /**
     * 结果文件判 ok + 取 err（两行 `ok=`/`err=` 沿用既有格式）。
     * @return first=是否成功；second=失败明细（成功时 null，失败时为 `err=` 后串，可能空串）。
     */
    private fun parseOkErr(resultText: String?): Pair<Boolean, String?> {
        if (resultText.isNullOrBlank()) return false to null
        val lines = resultText.lineSequence().map { it.trimEnd() }.toList()
        val first = lines.getOrNull(0)?.trim() ?: return false to null
        if (first != "ok=true") {
            val errLine = lines.firstOrNull { it.startsWith("err=") }?.removePrefix("err=")?.trim()
            return false to errLine
        }
        return true to null
    }
}
