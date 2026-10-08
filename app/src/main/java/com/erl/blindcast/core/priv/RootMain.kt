package com.erl.blindcast.core.priv

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController
import java.io.File

/**
 * Root 真身单次执行器（Root-Backend-1 · 不搭常驻 daemon，只做断电/点亮两个操作；
 * Universal-1 起加 input 单次反控兜底：tap/drag/key/text 各一次即退）。
 *
 * 运行身份：由 [RootExecutor] 经 `su -c "CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.priv.RootMain displayPower on|off <resultFile>"` 拉起，
 * 以 uid 0 真 root 身份跑在独立 `app_process` 中（实锤结论：MAA-Meow 在本机起
 * `com.aliothmoon.maameow:root_service` 跑在 uid 0 真 root 身份调断电才生效；
 * 我们的 Shizuku UserService 是 shell 身份，被 OPlus 静默忽略）。
 *
 * ## 调用契约（RootExecutor 侧组装，勿硬编码 APK 路径）
 * - `CLASSPATH=<调用方 applicationInfo.sourceDir>`（RootExecutor 传参，勿硬编码）；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain displayPower on|off <resultFile>`；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain input tap <x> <y> <resultFile>`；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain input drag <x0> <y0> <x1> <y1> <resultFile>`；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain input key <keyCode> <resultFile>`；
 * - `app_process /system/bin com.erl.blindcast.core.priv.RootMain input text <b64> <resultFile>`
 *  （`<b64>` 为 UTF-8 文本的 Base64 NO_WRAP，空串传 `''`，防 shell 空格/引号转义）；
 * - `<resultFile>` 为 `/data/local/tmp/blindcast_root_result_<nonce>`（RootExecutor 生成 nonce）。
 *
 * ## 进程内行为
 * - 参数 `displayPower on/off`（`args[0]=="displayPower"`，`args[1]=="on"|"off"`，
 *   `args[2]=结果文件路径`），No-Lock-1 顺序：先 [PowerController.tryBinderDisplayPower]
 *   binder 物理断电/点亮直试（混合路由+日志不动，仅 binder→STATE 严格验效约 2s，
 *   熄屏验 STATE_OFF，点亮验 STATE_ON），成了直接返回 ok（无锁屏、无 AOD 真黑）；
 *   熄屏 binder 验效失败直接返 false，不进任何锁屏/按键兜底；
 *   点亮 binder 验效失败则试 [PowerController.wakeByKey]
 *  （KEYCODE_WAKEUP→KEYCODE_POWER，只点亮不制造新锁）。
 * - 参数 `input tap|drag|key|text ...`（Universal-1）：RootMain 内直接
 *   `new PrivilegedUserService()` 调 `injectTap/Drag/Key/Text`
 *  （该类无 Shizuku 依赖，纯 TouchInjector/InputManager 反射，root 身份可调）；
 *   归一化坐标 0..1（与 ControlWsRoute 同语义），key 为 Android keyCode int，
 *   text 为 Base64 解码后 UTF-8 串；成功判据为返回值 true，失败明细经
 *   `getInputError()` 回读；
 * - 结果写结果文件两行：`ok=true|false` / `err=<message>`（成功时 err 为空）；
 * - 全程 `runCatching` 包住不抛，`finally` 按成功失败 `System.exit(0/1)`；
 * - 普通 App 进程不要直接调本入口（本入口只在 root `app_process` 内有意义）。
 *
 * R8 注意：release 启用 minify，proguard-rules.pro 中 keep 本类及 main 方法。
 */
@Keep
object RootMain {

    /** 全链路统一 TAG（与 PowerController / RootExecutor 一致，root 进程 logcat 可见）。 */
    private const val TAG = "BlindCast"

    /**
     * `app_process` 入口（签名必须为 `public static void main(String[])`，Kotlin 侧为
     * `object + @JvmStatic fun main(args: Array<String>)`）。
     *
     * @param args 期望 `["displayPower", "on"|"off", "<resultFile>"]` 或
     *  `["input", "tap", x, y, "<resultFile>"]` /
     *  `["input", "drag", x0, y0, x1, y1, "<resultFile>"]` /
     *  `["input", "key", code, "<resultFile>"]` /
     *  `["input", "text", b64, "<resultFile>"]` /
     *  `["vdProbe", "<resultFile>"]`（Phase C 最小探针，报文写结果文件）。
     */
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        var ok = false
        var errMsg: String = ""
        var resultFile: File? = null
        // Phase C 最小探针报文（`vdProbe` 子操作写入；null 表示本次非探针调用）。
        var vdReport: String? = null
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        try {
            runCatching {
                Log.d(TAG, "[RootMain] pid=$pid uid=$uid enter args=${args.toList().take(3)}")
            }
            // 参数解析（全包住，缺参/非法参数同样写文件 + 非零退出，不抛）。
            val op = args.getOrNull(0)
            // Stream-Priv-1 同名 op：Root 单次执行器不适合视频长流（短进程即退模型），
            // 采集长流请走 RootCaptureMain 常驻（libsu app_process 常驻 + stop 文件信号，
            // 同一 CaptureSocketLink 服）。此处保留同名入口仅作路由指引，不做采集。
            if (op == "startCapture" || op == "captureVideo") {
                val rp = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: args.getOrNull(2)
                if (!rp.isNullOrBlank()) resultFile = File(rp)
                errMsg = "Root 单次执行器不承载长流采集，请走 RootCaptureMain 常驻 " +
                    "（本机优先 Shizuku UserService 常驻：daemon root 启动，SurfaceControl 身份够用，见 ForegroundService [CaptureRoute] 日志）"
                runCatching {
                    Log.i(TAG, "[RootMain] pid=$pid uid=$uid startCapture routed to RootCaptureMain daemon, reject single-shot")
                }
                return
            }
            // Phase C 最小探针（§4.7）：root 身份反射跑 VirtualDeviceManager 全链路，
            // 报文写结果文件（首行仍 ok=/err=，RootExecutor 解析契约不变）。
            if (op == "vdProbe") {
                val rp = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (rp != null) resultFile = File(rp)
                vdReport = runCatching { VirtualDeviceProbe.run(null, "RootMain") }
                    .getOrElse { t -> "probe threw ${t.message ?: t}\n" }
                ok = true
                errMsg = ""
                runCatching {
                    Log.i(TAG, "[RootMain] pid=$pid uid=$uid vdProbe done len=${vdReport?.length ?: 0}")
                }
                return
            }
            // Phase C 虚拟桌面建屏探针（Vds-Probe-1）：shell 身份内建设备+虚拟屏，
            // 观察 Home 是否被系统拉到该屏，再释放。
            // args = ["vdCreate", holdSec, flags, vdmHome(0|1), resultFile]。
            if (op == "vdCreate") {
                val holdSec = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 60) ?: 5
                val flags = args.getOrNull(2)?.toIntOrNull() ?: VirtualDeviceBridge.defaultDesktopFlags()
                val vdmHome = (args.getOrNull(3)?.toIntOrNull() ?: 1) != 0
                val rp = args.getOrNull(4)?.takeIf { it.isNotBlank() }
                if (rp != null && rp.startsWith("/")) resultFile = File(rp)
                vdReport = runCatching { runVdCreate(holdSec, flags, vdmHome, pid, uid) }
                    .getOrElse { t -> "vdCreate threw ${t.javaClass.simpleName}: ${t.message}\n" }
                ok = vdReport!!.contains("[result] start=OK")
                errMsg = if (ok) "" else "vdCreate 未成功建屏（见报文）"
                runCatching { Log.i(TAG, "[RootMain] pid=$pid uid=$uid vdCreate done ok=$ok") }
                return
            }
            // Dtc-Ipc-1：副屏任务查询。app 进程（uid 10xxx）无 DUMP 权限，必须由
            // shell(uid 2000)/root 身份取 `dumpsys activity activities` 原文，正文写结果文件，
            // 由 App 侧 DesktopTaskController 现有解析器消费。
            // args = ["dumpsysActs", <resultFile>]。
            if (op == "dumpsysActs") {
                val rp = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                if (rp != null && rp.startsWith("/")) resultFile = File(rp)
                vdReport = runCatching { runDumpsysActivities(pid, uid) }
                    .getOrElse { t -> "dumpsysActs threw ${t.javaClass.simpleName}: ${t.message}\n" }
                ok = !vdReport.isNullOrBlank()
                errMsg = if (ok) "" else "dumpsysActs 无输出"
                return
            }
            // Dtc-Ipc-1：副屏任务恢复。在 shell/root 身份内**先校验 taskId 真实所属 displayId**，
            // 不属于目标副屏一律拒绝（绝不移动物理主屏 display 0 的 Task），通过后反射
            // IActivityTaskManager.moveTaskToFront(taskId, 0)。
            // args = ["taskFront", taskId, displayId, <resultFile>]。
            if (op == "taskFront") {
                val taskId = args.getOrNull(1)?.toIntOrNull() ?: -1
                val displayId = args.getOrNull(2)?.toIntOrNull() ?: -1
                val rp = args.getOrNull(3)?.takeIf { it.isNotBlank() }
                if (rp != null && rp.startsWith("/")) resultFile = File(rp)
                val r = runCatching { runTaskFront(taskId, displayId, pid, uid) }
                    .getOrElse { t -> false to "taskFront threw ${t.javaClass.simpleName}: ${t.message}" }
                ok = r.first
                errMsg = if (ok) "" else r.second
                vdReport = "taskFront task=$taskId did=$displayId ok=${r.first} note=${r.second}\n"
                return
            }
            // Universal-1：input 单次反控（Root→Shizuku 两段之 Root 段，无 Shizuku 依赖）。
            if (op == "input") {
                val (inputOk, inputErr, inputFile) = runCatching { doInput(args) }.getOrElse { t ->
                    Triple(false, "input执行异常：${t.message ?: t}", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
                }
                if (inputFile != null) resultFile = inputFile
                ok = inputOk
                errMsg = if (inputOk) "" else inputErr
                return
            }
            val onOff = args.getOrNull(1)
            val resultPath = args.getOrNull(2)
            if (op != "displayPower") {
                errMsg = "未知操作：${op ?: "null"}（仅支持 displayPower/input/startCapture/vdProbe）"
                return
            }
            val on: Boolean = when (onOff) {
                "on" -> true
                "off" -> false
                else -> {
                    errMsg = "未知 on/off 参数：${onOff ?: "null"}（仅支持 on|off）"
                    return
                }
            }
            if (resultPath.isNullOrBlank()) {
                errMsg = "缺结果文件路径（args[2] 为空）"
                return
            }
            resultFile = File(resultPath)
            // No-Lock-1 接线：先 binder 物理断电/点亮直试（root 身份下 OPlus 很可能放行，
            // 成了即无锁屏真黑）；熄屏 miss 直接失败不进锁屏链，点亮 miss 试 wakeByKey。
            // 混合路由+日志不动，App 侧
            // isBlackedOut/lastError/日志/Home状态行/Toast/路由契约不变：App 侧 routed 入口
            // 仍据返回值 + 结果文件 ok/err 自行翻转，本进程经 recordPrivResult 记状态行。
            // 直调非 routed 版（必须在提权进程内：此处即 root app_process 本身）。
            val callOk: Boolean = runCatching {
                setDisplayPowerNoLock(on)
            }.getOrElse { t ->
                val msg = t.message ?: t.toString()
                errMsg = "setDisplayPower抛异常：$msg"
                runCatching {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid setDisplayPower on=$on threw", t)
                }
                return
            }
            if (callOk) {
                ok = true
                errMsg = ""
            } else {
                ok = false
                val privErr = runCatching { PowerController.lastError?.message }.getOrNull()
                    ?: runCatching { PowerController.lastPrivError }.getOrNull()
                errMsg = privErr?.takeIf { it.isNotBlank() } ?: "特权执行返回false（见root进程logcat明细）"
            }
        } catch (t: Throwable) {
            // 顶层兜底：任何意外都不抛，只记文案（finally 写文件 + 退出码）。
            ok = false
            errMsg = t.message ?: t.toString()
            runCatching {
                Log.e(TAG, "[RootMain] pid=$pid uid=$uid top-level threw", t)
            }
        } finally {
            val code = if (ok) 0 else 1
            runCatching {
                Log.d(TAG, "[RootMain] pid=$pid uid=$uid exit ok=$ok code=$code err=${errMsg.take(200)}")
            }
            // 结果文件两行：ok=/err=（写失败只记日志，不改变退出码语义）。
            runCatching {
                val f = resultFile ?: args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) }
                if (f != null) {
                    runCatching { f.parentFile?.mkdirs() }.getOrDefault(false)
                    // 单行 err（去换行，防解析歧义，截断防超长）。
                    val singleLineErr = errMsg.replace("\n", " ").replace("\r", " ").take(500)
                    val body = buildString {
                        append("ok=$ok\nerr=$singleLineErr\n")
                        vdReport?.let { append(it) }
                    }
                    f.writeText(body)
                    // 尽力放行给 App 进程读（/data/local/tmp 默认可读，但 root 建文件可能 0600）。
                    runCatching { f.setReadable(true, false) }.getOrDefault(false)
                } else {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid no resultFile, skip write")
                }
            }.exceptionOrNull()?.let { t ->
                runCatching {
                    Log.e(TAG, "[RootMain] pid=$pid uid=$uid write result failed", t)
                }
            }
            // finally 退出码 0/1（0=ok，1=失败；kill 进程防 app_process 驻留）。
            try {
                Runtime.getRuntime().halt(code)
            } catch (_: Throwable) {
                try {
                    System.exit(code)
                } catch (_: Throwable) {
                    // 退出都失败则自然返回（app_process 会自行结束）。
                }
            }
        }
    }

    /**
     * Dtc-Ipc-1：以当前（shell/root）身份取 `dumpsys activity activities` 原文。
     *
     * 不用 `Runtime.exec` 拼串，直接 [ProcessBuilder] 三段参数，避免 shell 注入歧义。
     * 输出原样返回（含换行），由调用方写入结果文件正文。
     */
    private fun runDumpsysActivities(pid: Int, uid: Int): String {
        val pb = ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        runCatching {
            Log.i(TAG, "[RootMain] pid=$pid uid=$uid dumpsysActs exit=$code len=${text.length}")
        }
        return text
    }

    /**
     * Dtc-Ipc-1：副屏任务恢复。**先校验归属再动**：
     * 1. 取 `dumpsys activity activities` 解析 `taskId -> displayId`；
     * 2. taskId 不在 [displayId] 上（含落在物理主屏 0）→ 直接拒绝，不动任何任务；
     * 3. 通过后反射 `IActivityTaskManager.moveTaskToFront(taskId, 0)`。
     *
     * @return first=ok；second=说明/错误文案。
     */
    private fun runTaskFront(taskId: Int, displayId: Int, pid: Int, uid: Int): Pair<Boolean, String> {
        if (taskId <= 0) return false to "taskId<=0 非法"
        if (displayId <= 0) return false to "displayId<=0 非法（拒绝触碰物理主屏）"
        val dump = runCatching { runDumpsysActivities(pid, uid) }.getOrDefault("")
        if (dump.isBlank()) return false to "dumpsys 为空，无法校验任务归属"
        val map = taskDisplayMap(dump)
        val actual = map[taskId]
        if (actual == null) return false to "task $taskId 不存在（dumpsys 未列出）"
        if (actual != displayId) {
            return false to "task $taskId 实际在 display $actual，不在 $displayId，拒绝移动"
        }
        return runCatching {
            VirtualDeviceBridge.addHiddenApiExemptions()
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getService").invoke(null)
                ?: return@runCatching false to "ActivityTaskManager.getService() 返回 null"
            val all = atm.javaClass.methods
            val sigDump = all.filter { m ->
                m.name.contains("TaskToFront") || m.name.contains("FromRecents") || m.name.contains("moveTask")
            }.joinToString("; ") { m ->
                m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
            }
            val bundleCls = Class.forName("android.os.Bundle")
            val cands = all.filter { m ->
                m.name == "moveTaskToFront" &&
                    m.parameterTypes.size >= 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType
            }.sortedBy { it.parameterTypes.size }
            val errors = StringBuilder()
            for (m in cands) {
                val callArgs = arrayOfNulls<Any?>(m.parameterTypes.size)
                callArgs[0] = taskId
                callArgs[1] = 0
                var unsupported = false
                for (i in 2 until m.parameterTypes.size) {
                    if (m.parameterTypes[i] == bundleCls) callArgs[i] = null else unsupported = true
                }
                if (unsupported) {
                    errors.append("skip/${m.parameterTypes.size}; ")
                    continue
                }
                val r = runCatching { m.invoke(atm, *callArgs) }
                if (r.isSuccess) {
                    runCatching { Log.i(TAG, "[RootMain] pid=$pid uid=$uid taskFront ok task=$taskId did=$displayId via moveTaskToFront/${m.parameterTypes.size}") }
                    return@runCatching true to "ok via moveTaskToFront/${m.parameterTypes.size}"
                }
                errors.append("invoke/${m.parameterTypes.size}:").append(r.exceptionOrNull()?.message).append("; ")
            }
            val sar = all.firstOrNull { m ->
                m.name == "startActivityFromRecents" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (sar != null) {
                val r = runCatching { sar.invoke(atm, taskId, null) }
                if (r.isSuccess) {
                    runCatching { Log.i(TAG, "[RootMain] pid=$pid uid=$uid taskFront ok task=$taskId did=$displayId via startActivityFromRecents") }
                    return@runCatching true to "ok via startActivityFromRecents"
                }
                errors.append("startActivityFromRecents:").append(r.exceptionOrNull()?.message).append("; ")
            }
            false to "无可用 moveTaskToFront sigs=[$sigDump] errs=[$errors]"
        }.getOrElse { t -> false to "taskFront 抛异常：${t.javaClass.simpleName}: ${t.message}" }
    }

    /** 解析 `dumpsys activity activities` 的 `taskId -> displayId`（只认 `Display #N` 段落内的 Task 行）。 */
    private fun taskDisplayMap(dump: String): Map<Int, Int> {
        val displayRe = Regex("""Display\s+#(\d+)""")
        val taskRe = Regex("""Task\{[0-9a-fA-F]+\s+#(\d+)""")
        val map = mutableMapOf<Int, Int>()
        var cur = -1
        for (line in dump.lineSequence()) {
            val t = line.trim()
            displayRe.find(t)?.let { cur = it.groupValues[1].toIntOrNull() ?: -1 }
            if (cur <= 0) continue
            taskRe.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { map[it] = cur }
        }
        return map
    }

    /**
     * Phase C 虚拟桌面建屏探针（`vdCreate` 子操作，shell 身份内跑）。
     *
     * 顺序：找自己的关联 → [VirtualDesktopSession.start]（建设备 + 建虚拟屏，
     * Home 组件指向 `FusionHomeActivity`）→ 报 deviceId/displayId → 观察系统是否把
     * Home 拉到该屏（`dumpsys activity`/`dumpsys display`）→ [VirtualDesktopSession.stop]。
     *
     * 全程不抛（异常由调用方 `runCatching` 兜），报文写结果文件供 App 侧解析。
     */
    private fun runVdCreate(holdSec: Int, flags: Int, vdmHome: Boolean, pid: Int, uid: Int): String {
        val sb = StringBuilder()
        fun rec(s: String) {
            sb.append(s).append('\n')
            runCatching { Log.i(TAG, "[RootMain][VdCreate] $s") }
        }
        rec("[env] pid=$pid uid=$uid sdk=${android.os.Build.VERSION.SDK_INT} flags=$flags")
        VirtualDeviceBridge.addHiddenApiExemptions()
        rec("[dmflags] ${VirtualDeviceBridge.dumpFlags()}")
        val assocId = VirtualDesktopSession.findOwnAssociationId(0)
        rec("[assoc] ownId=$assocId")
        if (assocId <= 0) {
            rec("[result] start=FAIL reason=no own association (mac=${VirtualDeviceAssociation.OWN_MAC})")
            return sb.toString()
        }
        val pkg = com.erl.blindcast.BuildConfig.APPLICATION_ID
        val home = android.content.ComponentName(pkg, "$pkg.FusionHomeActivity")
        rec("[home] $home")

        // 关键（findings 口径）：虚拟屏要挂**真实输出 Surface**才会进入 ON；
        // 用 MediaCodec 的 inputSurface（也正是生产采集要用的那个面）。
        var codec: android.media.MediaCodec? = null
        var inputSurface: android.view.Surface? = null
        try {
            val enc = android.media.MediaCodec.createEncoderByType("video/avc")
            val fmt = android.media.MediaFormat.createVideoFormat("video/avc", 720, 1280)
            fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 4_000_000)
            fmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
            fmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            fmt.setInteger(
                android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            enc.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            codec = enc
            rec("[surface] encoder=${enc.name} inputSurface=${inputSurface != null}")
        } catch (t: Throwable) {
            rec("[surface] FAILED ${t.javaClass.simpleName}: ${t.message}")
        }

        val started = VirtualDesktopSession.start(
            associationId = assocId,
            name = "BlindCastDesktop",
            width = 720, height = 1280, densityDpi = 320,
            flags = flags,
            home = if (vdmHome) home else null,
            ime = null,
            surface = inputSurface,
        )
        rec("[result] start=${if (started) "OK" else "FAIL"} error=${VirtualDesktopSession.lastError ?: ""}")
        if (!started) return sb.toString()
        val did = VirtualDesktopSession.displayId
        rec("[ids] deviceId=${VirtualDesktopSession.deviceId} displayId=$did")
        rec("[dinfo] ${VirtualDesktopSession.displayInfo()}")
        // 显式把我们的 Home 拉上副屏（VDM 的 setHomeComponent 会被 ROM 自带
        // SecondaryDisplayLauncher 抢走，实证见 logcat ActivityStartInterceptor/ShellTaskOrganizer）。
        rec("[launch.home] ${VirtualDesktopSession.shellOut("am start -W --display $did -n $pkg/.FusionHomeActivity").replace("\n", " | ")}")
        Thread.sleep(2000L)
        rec("[tasks] ${VirtualDesktopSession.shellOut("dumpsys activity activities | grep -A6 'Display #$did' | grep -E 'Task\\{|Hist |ActivityRecord' | head -8").replace("\n", " | ")}")
        rec("[check.display] ${VirtualDesktopSession.shellOut("dumpsys display | grep -oE 'BlindCastDesktop\\\", displayId [0-9]+.*state [A-Z]+' | head -1")}")
        rec("[hold] ${holdSec}s")
        runCatching { Thread.sleep(holdSec * 1000L) }
        rec("[check.activity2] ${VirtualDesktopSession.shellOut("dumpsys activity activities | grep -i -m 6 'fusionhome'").replace("\n", " | ")}")
        rec("[check.display2] ${VirtualDesktopSession.shellOut("dumpsys display | grep -oE 'BlindCastDesktop\\\", displayId [0-9]+' | head -2")}")
        VirtualDesktopSession.stop()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        rec("[stop] done (displayId released, codec released)")
        return sb.toString()
    }

    /**
     * Universal-1 input 单次执行（root app_process 内直调，同步阻塞）。
     *
     * 直接 `new PrivilegedUserService()` 调 `injectTap/Drag/Key/Text`
     * （该类无 Shizuku 依赖，纯 TouchInjector/InputManager 反射；root 身份下
     * INJECT_EVENTS 放行）。归一化坐标 0..1 越界由特权侧钳制；text 的 b64 为
     * UTF-8 的 Base64 NO_WRAP（空串传 `''`，shell 侧已去引号，此处收到的即 `""`）。
     *
     * @param args 完整 `main` 参数（含 `args[0]=="input"`）。
     * @return Triple(ok, errMsg, resultFile?)：ok=true 时 errMsg 为 ""；失败时为单行文案；
     *  resultFile 为 null 表示连结果文件路径都缺（调用方 finally 按 last arg 兜底）。
     */
    private fun doInput(args: Array<String>): Triple<Boolean, String, File?> {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        val sub = args.getOrNull(1)
        runCatching {
            Log.d(TAG, "[RootMain] pid=$pid uid=$uid input enter sub=$sub args=${args.toList().take(7)}")
        }
        // text 空串容错：shell 把 `''` 去引号后即 `""`（4 参）；若某 shell 把空参吞掉
        // 变成 3 参且 args[2] 即结果路径（以 "/" 开头），则视为空文本。
        fun textArgs(): Pair<String, File?>? {
            if (args.size >= 4) {
                val rp = args[3]
                if (rp.isNullOrBlank()) return null
                return args[2].orEmpty() to File(rp)
            }
            if (args.size == 3) {
                val maybePath = args[2]
                if (!maybePath.isNullOrBlank() && maybePath.startsWith("/")) {
                    return "" to File(maybePath)
                }
            }
            return null
        }
        return try {
            val svc = PrivilegedUserService()
            // Phase C：尾部可选 [displayId] [width] [height]（排在 <resultFile> **之后**，
            // 与 RootExecutor 拼命令行时一致），缺省 0 = 物理主屏 + 由特权侧解析尺寸；
            // 旧调用（只到 resultFile）逐字不变。
            fun optInt(index: Int, def: Int): Int = args.getOrNull(index)?.toIntOrNull() ?: def
            when (sub) {
                "tap" -> {
                    if (args.size < 5) return Triple(false, "tap 缺参（期望 input tap x y <resultFile>）", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
                    val x = args[2].toFloatOrNull()
                    val y = args[3].toFloatOrNull()
                    val rf = args[4].takeIf { it.isNotBlank() }?.let { File(it) }
                    if (rf == null) return Triple(false, "tap 缺结果文件路径（args[4] 为空）", null)
                    if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                        return Triple(false, "tap 非法坐标：${args[2]},${args[3]}（期望 0..1 浮点）", rf)
                    }
                    val displayId = optInt(5, 0)
                    val width = optInt(6, 0)
                    val height = optInt(7, 0)
                    val ok = runCatching { svc.injectTap(x, y, displayId, width, height) }.getOrElse { t ->
                        runCatching { Log.e(TAG, "[RootMain] input tap threw", t) }
                        return Triple(false, "tap抛异常：${t.message ?: t}", rf)
                    }
                    if (ok) {
                        runCatching { Log.d(TAG, "[RootMain] input tap ok x=$x y=$y display=$displayId ${width}x$height") }
                        Triple(true, "", rf)
                    } else {
                        val e = runCatching { svc.inputError }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "tap rejected by system"
                        Triple(false, e, rf)
                    }
                }
                "drag" -> {
                    if (args.size < 7) return Triple(false, "drag 缺参（期望 input drag x0 y0 x1 y1 <resultFile>）", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
                    val x0 = args[2].toFloatOrNull()
                    val y0 = args[3].toFloatOrNull()
                    val x1 = args[4].toFloatOrNull()
                    val y1 = args[5].toFloatOrNull()
                    val rf = args[6].takeIf { it.isNotBlank() }?.let { File(it) }
                    if (rf == null) return Triple(false, "drag 缺结果文件路径（args[6] 为空）", null)
                    if (listOf(x0, y0, x1, y1).any { it == null || !it.isFinite() }) {
                        return Triple(false, "drag 非法坐标（期望 0..1 浮点 x4）", rf)
                    }
                    val displayId = optInt(7, 0)
                    val width = optInt(8, 0)
                    val height = optInt(9, 0)
                    val ok = runCatching { svc.injectDrag(x0!!, y0!!, x1!!, y1!!, displayId, width, height) }.getOrElse { t ->
                        runCatching { Log.e(TAG, "[RootMain] input drag threw", t) }
                        return Triple(false, "drag抛异常：${t.message ?: t}", rf)
                    }
                    if (ok) {
                        runCatching { Log.d(TAG, "[RootMain] input drag ok display=$displayId ${width}x$height") }
                        Triple(true, "", rf)
                    } else {
                        val e = runCatching { svc.inputError }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "drag rejected by system"
                        Triple(false, e, rf)
                    }
                }
                "key" -> {
                    if (args.size < 4) return Triple(false, "key 缺参（期望 input key <code> <resultFile>）", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
                    val code = args[2].toIntOrNull()
                    val rf = args[3].takeIf { it.isNotBlank() }?.let { File(it) }
                    if (rf == null) return Triple(false, "key 缺结果文件路径（args[3] 为空）", null)
                    if (code == null) return Triple(false, "key 非法键码：${args[2]}（期望 int）", rf)
                    val displayId = optInt(4, 0)
                    val ok = runCatching { svc.injectKey(code, displayId) }.getOrElse { t ->
                        runCatching { Log.e(TAG, "[RootMain] input key threw", t) }
                        return Triple(false, "key抛异常：${t.message ?: t}", rf)
                    }
                    if (ok) {
                        runCatching { Log.d(TAG, "[RootMain] input key ok code=$code display=$displayId") }
                        Triple(true, "", rf)
                    } else {
                        val e = runCatching { svc.inputError }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "key rejected by system"
                        Triple(false, e, rf)
                    }
                }
                "text" -> {
                    val (b64, rf) = textArgs()
                        ?: return Triple(false, "text 缺参（期望 input text <b64> <resultFile>）", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
                    val text = if (b64.isEmpty() || b64 == "-") {
                        ""
                    } else {
                        try {
                            val raw = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                            String(raw, Charsets.UTF_8)
                        } catch (t: Throwable) {
                            return Triple(false, "text 非法b64：${t.message ?: t}", rf)
                        }
                    }
                    val displayId = optInt(4, 0)
                    val ok = runCatching { svc.injectText(text, displayId) }.getOrElse { t ->
                        runCatching { Log.e(TAG, "[RootMain] input text threw", t) }
                        return Triple(false, "text抛异常：${t.message ?: t}", rf)
                    }
                    if (ok) {
                        runCatching { Log.d(TAG, "[RootMain] input text ok len=${text.length} display=$displayId") }
                        Triple(true, "", rf)
                    } else {
                        val e = runCatching { svc.inputError }.getOrNull()?.takeIf { !it.isNullOrBlank() } ?: "text rejected by system"
                        Triple(false, e, rf)
                    }
                }
                else -> Triple(false, "未知 input 子操作：${sub ?: "null"}（仅支持 tap|drag|key|text）", args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
            }
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "[RootMain] input top threw", t) }
            Triple(false, t.message ?: t.toString(), args.lastOrNull()?.takeIf { it.startsWith("/") }?.let { File(it) })
        }
    }

    /**
     * No-Lock-1 特权熄屏/点亮（root app_process 内直调，同步阻塞）。
     * 顺序：先 [PowerController.tryBinderDisplayPower] binder 物理直试（混合路由+日志不动，
     * 仅 binder→STATE 严格验效约 2s：熄屏 STATE_OFF，点亮 STATE_ON），成了直接返回 ok
     * （无锁屏、无 AOD 的真黑）；熄屏 binder 验效失败直接返 false，
     * 永不调 lockNow/lockAndSleep/ensureScreenOff，不注入 KEY_SLEEP/KEY_POWER；
     * 点亮 binder 验效失败则试 [PowerController.wakeByKey]
     * （KEYCODE_WAKEUP→KEYCODE_POWER，只点亮不制造新锁，自立无 lockNow）。
     * 成功/失败均经 [PowerController.recordPrivResult] 记状态行（供结果文件 err 回读）；
     * 返回值即验效后最终结果（App 侧据此翻转 isBlackedOut/lastError，契约不变）。
     */
    private fun setDisplayPowerNoLock(on: Boolean): Boolean {
        val op = if (on) "restore" else "blackout"
        // 先 binder 物理直试（root 真身下先试，成了即无锁屏真黑）。
        var binderDesc: String? = null
        var binderRead: String? = null
        try {
            val r = PowerController.tryBinderDisplayPower(on)
            if (r.verified) {
                Log.d(TAG, "[RootMain] binder-first hit on=$on route=${r.route} read=${r.read}")
                PowerController.recordPrivResult(op, true, null)
                return true
            }
            binderDesc = PowerController.binderFirstSegmentDesc(on, r)
            binderRead = r.read
            Log.d(TAG, "[RootMain] binder-first miss on=$on $binderDesc (No-Lock-1 no lock fallback for off)")
        } catch (t: Throwable) {
            binderDesc = "binder-root段异常：${t.message ?: t}"
            Log.e(TAG, "[RootMain] binder-first threw on=$on $binderDesc", t)
        }
        return try {
            if (on) {
                val ok = try {
                    PowerController.wakeByKey()
                } catch (t: Throwable) {
                    Log.e(TAG, "[RootMain] wakeByKey threw", t)
                    PowerController.recordPrivResult(op, false, "点亮失败：${binderDesc}→按键唤醒段异常：${t.message ?: t}")
                    return false
                }
                if (ok) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val wakeErr = runCatching { PowerController.lastError?.message }
                        .getOrNull()?.takeIf { !it.isNullOrBlank() }
                        ?: runCatching { PowerController.lastPrivError }.getOrNull()
                    val msg = "点亮失败：${binderDesc}→${wakeErr ?: "按键唤醒WAKEUP→POWER复验仍未STATE_ON"}（见root进程logcat [PowerController]明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                ok
            } else {
                val read = binderRead ?: runCatching { PowerController.lastError?.message }.getOrNull()
                val msg = "熄屏失败：${binderDesc}；物理断电未生效（本机忽略），未执行锁屏兜底" +
                    (if (!binderRead.isNullOrBlank()) "（当前$read" +
                        "，见root进程logcat [PowerController][BinderFirst]明细）" else "")
                PowerController.recordPrivResult(op, false, msg)
                Log.d(TAG, "[RootMain] blackout binder-only miss, no fallback err=$msg")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[RootMain] setDisplayPowerNoLock threw", t)
            PowerController.recordPrivResult(op, false, t.message ?: t.toString())
            false
        }
    }
}
