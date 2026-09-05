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
 * Root 真身执行器（Root-Backend-1 · 单次 `app_process`，不搭常驻 daemon，只做断电/点亮）。
 *
 * 背景（主控真机取证）：MAA-Meow 在本机起 `com.aliothmoon.maameow:root_service` 跑在 uid 0
 * 真 root 身份调断电才生效；我们的 Shizuku UserService 是 shell 身份，被 OPlus 静默忽略。
 * 故加 Root 后端，与 Shizuku/按键形成 Root→Shizuku→按键三段路由
 * （路由见 [com.erl.blindcast.core.blackout.PowerController.setDisplayPowerRouted]）。
 *
 * ## 执行方式（只用 libsu 同步 `Shell.cmd` API）
 * - 后台线程（调用方已在 `Dispatchers.IO`，本对象内同样切 IO）跑：
 *   `CLASSPATH=<apk> app_process /system/bin com.erl.blindcast.core.priv.RootMain
 *   displayPower on|off <resultFile>`；
 *   `Shell.cmd` 本身即跑在 root shell 内，等价于任务包所述 `su -c "..."` 内层，
 *   不再套一层 `su -c`（省一次嵌套引号转义，结果一致）；
 * - `CLASSPATH` 传调用方 `applicationInfo.sourceDir`（[runAsRootDisplayPower] 的 `apkPath` 参数，
 *   勿硬编码，由 [com.erl.blindcast.core.blackout.PowerController] 经包名解析传入）；
 * - 超时约 20s（[ROOT_TIMEOUT_MS]，`withTimeoutOrNull` 包 `exec()`，超时按失败计）；
 * - 读结果文件判 `ok=true`（两行 `ok=`/`err=`，见 [RootMain]），BlindCast 日志记 uid/exit/result；
 * - 真机验证由主控做（本对象不碰 adb/手机之外的任何设备操作，Gradle 侧只保证编译）。
 */
object RootExecutor {

    /** 全链路统一 TAG（与 PowerController / RootMain 一致）。 */
    private const val TAG = "BlindCast"

    /** 单次 root 调用超时约 20s（app_process 冷起 + binder→验效→按键兜底全链路留足余量）。 */
    private const val ROOT_TIMEOUT_MS = 20_000L

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
}
