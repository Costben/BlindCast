package com.erl.blindcast.core.server.routes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import android.view.Display
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.core.priv.RootExecutor
import com.erl.blindcast.core.priv.VirtualDeviceAssociation
import com.erl.blindcast.core.priv.DesktopController
import com.erl.blindcast.core.priv.DesktopTaskController
import com.erl.blindcast.core.priv.DesktopWindowController
import com.erl.blindcast.core.priv.VirtualDeviceBridge
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.AudioGate
import com.erl.blindcast.core.scrcpy.CaptureSocketLink
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import com.erl.blindcast.core.server.auth.TokenAuthenticator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设备 REST 路由（Slice 4.1 · 需鉴权，由 [BlindCastServer] 先验 Token）。
 *
 * - `GET /api/screen` → `{"blackedOut":bool}`（当前物理屏幕电源状态）；
 * - `POST /api/screen` → 开关屏幕（直调 [PowerController]，同步 Binder，
 *   调用方已在后台连接线程，直接同步执行）：
 *   JSON 体 `{"on":bool}` / `{"action":"on"|"off"|"toggle"}`，
 *   或查询串 `?on=true|false` / `?action=on|off|toggle`；
 * - `GET /api/status` → 聚合状态：熄屏、电量、电池温度、充电、音频闸、
 *   音视频引擎运行态、在线 WS 客户端数（供 4.2 悬浮栏“实时电量与延迟显示”与 6.1 主页绑定）。
 * - `GET /api/stream` → `{"streaming":bool}`（特权采集链路实际态）；
 * - `POST /api/stream` → 远控串流开关（开隐含保 HTTP，关只停采集不断端口）。
 *
 * 无状态，任意后台线程可调；返回 `Pair(HTTP状态码, JSON字符串)`。
 * Context 优先取 [appContextOverride]（前台 Service 接入 Slice 6.1 时注入），
 * 退化取全局 `blindCastApp`，再无则电量字段回 `-1/unknown`（不抛异常）。
 */
object DeviceApiRoute {

    @Volatile
    private var appContextOverride: Context? = null

    /** 预存应用上下文（只记 applicationContext，不泄漏；6.1 Service 内调）。 */
    fun init(context: Context) {
        appContextOverride = context.applicationContext ?: context
    }

    fun handleScreen(method: String, rawQuery: String?, body: ByteArray): Pair<Int, String> {
        if (method == "GET") {
            return 200 to JSONObject().put("blackedOut", currentBlackedOut()).toString()
        }
        if (method != "POST") {
            return 405 to err("method not allowed")
        }
        val query = TokenAuthenticator.parseQuery(rawQuery)
        // ScreenSync-1：toggle 必须按融合值算，不能按纯缓存算（手动键会绕过缓存）。
        val rawAction = query["action"]
            ?: runCatching {
                if (body.isEmpty()) null
                else JSONObject(body.toString(Charsets.UTF_8)).optString("action", "").takeIf { it.isNotBlank() }
            }.getOrNull()
        val target = if (rawAction?.lowercase() == "toggle") {
            !currentBlackedOut()
        } else {
            query["on"]?.toBooleanStrictOrNull()?.let { it }
                ?: query["action"]?.let(::actionToOn)
                ?: runCatching {
                    if (body.isEmpty()) null
                    else {
                        val json = JSONObject(body.toString(Charsets.UTF_8))
                        if (json.has("on")) json.optBoolean("on")
                        else if (json.has("action")) actionToOn(json.optString("action", ""))
                        else null
                    }
                }.getOrNull()
        }
        if (target == null) {
            return 400 to err("missing on|action (on|off|toggle)")
        }
        // 网页电源键必须走 routed 入口（Root→Shizuku 特权执行）：直调版只能跑在特权进程内，
        // App 进程调必吃取 token 异常。连接线程上 runBlocking，路由内已切 IO，无死锁。
        val ok = runCatching {
            runBlocking { PowerController.setDisplayPowerRouted(BuildConfig.APPLICATION_ID, target) }
        }.getOrDefault(false)
        return if (ok) {
            200 to JSONObject()
                .put("ok", true)
                .put("blackedOut", PowerController.isBlackedOut)
                .toString()
        } else {
            500 to JSONObject()
                .put("ok", false)
                .put("error", "set_display_power failed")
                .put("detail", PowerController.lastError?.message)
                // 失败也带上当前融合态，前端可据此纠偏按钮（省一次轮询）。
                .put("blackedOut", currentBlackedOut())
                .toString()
        }
    }

    fun handleStatus(): Pair<Int, String> {
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        val battery = readBattery(ctx)
        val storage = storageSnapshot()
        val json = JSONObject()
            .put("blackedOut", currentBlackedOut())
            .put("batteryLevel", battery.level)
            .put("batteryTempC", battery.tempC)
            .put("charging", battery.charging)
            .put("audioEnabled", AudioGate.isAudioEnabled)
            .put("videoRunning", ScreenCaptureEngine.isRunning)
            .put("audioRunning", AudioCaptureEngine.isRunning)
            .put("streaming", isStreamingNow())
            .put("streamClients", StreamWsRoute.sessionCount)
            .put("controlClients", ControlWsRoute.sessionCount)
            .put("widgetClients", WidgetWsRoute.sessionCount)
            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("connection", "wireless")
            .put("connectionType", "wireless")
            .put("storageUsedBytes", storage.first)
            .put("storageTotalBytes", storage.second)
        return 200 to json.toString()
    }

    private fun storageSnapshot(): Pair<Long, Long> = runCatching {
        val stat = StatFs("/storage/emulated/0")
        val block = stat.blockSizeLong
        val total = stat.blockCountLong * block
        val free = stat.availableBlocksLong * block
        (total - free).coerceAtLeast(0L) to total.coerceAtLeast(0L)
    }.getOrDefault(0L to 0L)

    /**
     * 串流远控（需鉴权，由 [BlindCastServer] 先验 Token；侧栏/控制台手动开关投屏用）。
     *
     * - `GET /api/stream` → `{"streaming":bool}`（特权采集链路是否在跑）；
     * - `POST /api/stream` → 开关串流（只调 intent，不阻塞连接线程；
     *   开隐含保 HTTP 在线，关只停采集不断端口）：
     *   JSON 体 `{"enabled":bool}` / `{"on":bool}` / `{"action":"on"|"off"|"toggle"}`，
     *   或查询串 `?enabled=true|false` / `?action=on|off|toggle`。
     * 返回 `{"ok":true,"streaming":bool}`（执行后即时实际态；采集异步起停，
     * 调用方按需轮询 GET 对齐）。
     */
    fun handleStream(method: String, rawQuery: String?, body: ByteArray): Pair<Int, String> {
        if (method == "GET") {
            return 200 to JSONObject().put("streaming", isStreamingNow()).toString()
        }
        if (method != "POST") {
            return 405 to err("method not allowed")
        }
        val query = TokenAuthenticator.parseQuery(rawQuery)
        val target = query["enabled"]?.toBooleanStrictOrNull()
            ?: query["on"]?.toBooleanStrictOrNull()
            ?: query["action"]?.let(::actionToStreaming)
            ?: runCatching {
                if (body.isEmpty()) null
                else {
                    val json = JSONObject(body.toString(Charsets.UTF_8))
                    if (json.has("enabled")) json.optBoolean("enabled")
                    else if (json.has("on")) json.optBoolean("on")
                    else if (json.has("action")) actionToStreaming(json.optString("action", ""))
                    else null
                }
            }.getOrNull()
        if (target == null) {
            return 400 to err("missing enabled|on|action (on|off|toggle)")
        }
        val ctx = appContextOverride
            ?: runCatching { blindCastApp.applicationContext }.getOrNull()
            ?: return 500 to err("no application context")
        // 只发 intent 即返（连接线程不阻塞）：起停重活由前台服务 onStartCommand 承接。
        // 服务未跑时无监听，此路由不可达，故此处必有前台服务承接，不触发后台起 FGS 限制。
        runCatching {
            if (target) {
                com.erl.blindcast.core.service.BlindCastForegroundService.startStreaming(ctx)
            } else {
                com.erl.blindcast.core.service.BlindCastForegroundService.stopStreaming(ctx)
            }
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("streaming", isStreamingNow())
            .toString()
    }

    /** 特权采集链路实际态（与前台服务快照 `isStreaming` 同口径）。 */
    fun isStreamingNow(): Boolean =
        CaptureSocketLink.isRunning || CaptureSocketLink.hasVideo

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * ScreenSync-1 融合上报（GET /api/screen、GET /api/status、toggle 共用）。
     *
     * - 缓存 `isBlackedOut=true`：可信（binder 真黑态 DM 恒报 ON，不能被 DM==ON 清掉，
     *   Power-Fix-2；手动亮屏由广播 [PowerController.syncExternalState] 清）；
     * - DM 报 OFF/DOZE/DOZE_SUSPEND：必是真灭（手动灭 / 进程重启丢缓存），直接 true，
     *   覆盖进程重启后缓存复位 false 的撒谎窗口；
     * - 其余：返回缓存。
     * 单次 DisplayManager 读回，无提权、无 dumpsys，连接线程可调。
     */
    private fun currentBlackedOut(): Boolean {
        val cached = PowerController.isBlackedOut
        if (cached) return true
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        if (ctx == null) return cached
        return runCatching {
            val dm = ctx.getSystemService(DisplayManager::class.java) ?: return cached
            when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.state) {
                Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> {
                    // 顺手回写缓存，后续 toggle 直接正确（省得每次都读 DM）。
                    PowerController.syncExternalState(false)
                    true
                }
                else -> cached
            }
        }.getOrDefault(cached)
    }

    private fun actionToOn(action: String?): Boolean? = when (action?.lowercase()) {
        "on", "wake", "true" -> true
        "off", "sleep", "blackout", "false" -> false
        "toggle" -> !currentBlackedOut()
        else -> null
    }

    private fun actionToStreaming(action: String?): Boolean? = when (action?.lowercase()) {
        "on", "start", "true" -> true
        "off", "stop", "false" -> false
        "toggle" -> !isStreamingNow()
        else -> null
    }

    /**
     * Phase C 最小探针（Vdm-Probe-1 · 需鉴权）：`GET /api/probe/vd`。
     *
     * 在 root（`app_process` 单次）、shell（root→`su 2000` 派生子进程）、Shizuku
     * （UserService，未运行/未授权时如实记明）三个身份各跑一次
     * [com.erl.blindcast.core.priv.VirtualDeviceProbe]，返回逐步报文。
     *
     * shell 段需在**临时自管理关联**下进行：经
     * [com.erl.blindcast.core.priv.VirtualDeviceAssociation] `ensure()` 建立只属自己的
     * `FA:CE:FE:ED:BC:01` 关联，跑完立即 `release()`（只删自己那一条，不动他人关联）。
     * root 段不建关联（root 身份与 `com.android.shell` 关联不匹配，注定被拒，如实记录）。
     *
     * 返回 `{"ok":true,"pkg","apkPath","shizukuState","rootOk","root","shellOk","shell",
     * "assocOwnId","assocStateAfter"}`。
     * 连接线程上 `runBlocking`（同 [handleScreen] 熄屏路径的先例），root 冷起通常 1-2s。
     */
    fun handleVdProbe(): Pair<Int, String> {
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        val pkg = ctx?.packageName ?: BuildConfig.APPLICATION_ID
        val apkPath = runCatching { PowerController.resolveApkPath(pkg) }.getOrNull()

        val rootOk: Boolean
        val rootReport: String
        if (apkPath.isNullOrBlank()) {
            rootOk = false
            rootReport = "root: skip（resolveApkPath 失败）"
        } else {
            val r = runCatching { runBlocking { RootExecutor.runAsRootVdProbe(pkg, apkPath) } }
                .getOrElse { t -> false to "root 段异常：${t.javaClass.simpleName}: ${t.message ?: t}" }
            rootOk = r.first
            rootReport = r.second?.takeIf { it.isNotBlank() } ?: "(root 段无报文)"
        }

        // 桌面会话在跑时直接拒绝（409）：探针会建设备/建屏，与运行中的桌面抢 VDM 资源，
        // 且此时诊断无意义（Vdm-Assoc-Own-1 的另一半防线；即便漏过，per-MAC 也不会误删）。
        if (DesktopController.status().running) {
            val d = DesktopController.status()
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "desktop active; probe refused (stop desktop first)")
                .put("displayId", d.displayId)
                .toString()
        }

        // shell 段：临时自管理关联 → su 2000 子进程跑探针 → 立即清关联。
        // **只清探针自己那条**（PROBE_MAC），绝不碰桌面会话的 OWN_MAC（Vdm-Assoc-Own-1）。
        var shellOk = false
        var shellReport: String
        var assocOwnId = -1
        var assocStateAfter = ""
        if (apkPath.isNullOrBlank()) {
            shellReport = "shell 段跳过（resolveApkPath 失败）"
        } else {
            assocOwnId = runCatching { VirtualDeviceAssociation.ensure(VirtualDeviceAssociation.PROBE_MAC) }
                .getOrElse { t ->
                    Log.w("BlindCast", "[DeviceApiRoute] vdProbe assoc ensure threw: ${t.message}")
                    -1
                }
            shellReport = if (assocOwnId > 0) {
                val r = runCatching { runBlocking { RootExecutor.runAsShellVdProbe(pkg, apkPath) } }
                    .getOrElse { t -> false to "shell 段异常：${t.javaClass.simpleName}: ${t.message ?: t}" }
                shellOk = r.first
                r.second?.takeIf { it.isNotBlank() } ?: "(shell 段无报文)"
            } else {
                "shell 段未执行：自管理关联建立失败（见 logcat BlindCast-VDAssoc）"
            }
            // 无论成败都清掉本轮探针自己建立的关联（只删 PROBE_MAC 那条）。
            runCatching { VirtualDeviceAssociation.release(VirtualDeviceAssociation.PROBE_MAC) }
                .onFailure { Log.w("BlindCast", "[DeviceApiRoute] vdProbe assoc release threw: ${it.message}") }
            assocStateAfter = runCatching {
                VirtualDeviceAssociation.stateLine(VirtualDeviceAssociation.PROBE_MAC)
            }.getOrDefault("")
        }

        var shizukuOk = false
        var shizukuReport: String
        try {
            shizukuReport = runBlocking { PrivilegedBridge.probeVirtualDevice(pkg) }
            shizukuOk = true
        } catch (t: Throwable) {
            shizukuReport = "shizuku 段未执行：${t.javaClass.simpleName}: ${t.message ?: t}"
        }

        val json = JSONObject()
            .put("ok", true)
            .put("pkg", pkg)
            .put("apkPath", apkPath ?: "")
            .put("shizukuState", runCatching { PrivilegedBridge.shizukuState().name }.getOrDefault("UNKNOWN"))
            .put("rootOk", rootOk)
            .put("root", rootReport)
            .put("shellOk", shellOk)
            .put("shell", shellReport)
            .put("assocOwnId", assocOwnId)
            .put("assocStateAfter", assocStateAfter)
            .put("shizukuOk", shizukuOk)
            .put("shizuku", shizukuReport)
        return 200 to json.toString()
    }

    /**
     * Phase C 虚拟桌面建屏探针（Vds-Probe-1 · 需鉴权）：`GET /api/probe/vdcreate[?hold=N]`。
     *
     * 在 App 生产路径上：root 建自管理关联 → `su 2000` 子进程 `RootMain vdCreate`
     * （建设备 + 建虚拟屏，Home 组件 = `FusionHomeActivity`，观察系统是否把 Home 拉到该屏）
     * → 立即清关联。用于在接采集/路由之前，先确证「虚拟屏真的存在、Home 真的被拉起」。
     *
     * 返回 `{"ok":true,"pkg","apkPath","holdSec","assocOwnId","assocStateAfter","createOk","create"}`。
     */
    fun handleVdCreate(rawQuery: String?): Pair<Int, String> {
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        val pkg = ctx?.packageName ?: BuildConfig.APPLICATION_ID
        val apkPath = runCatching { PowerController.resolveApkPath(pkg) }.getOrNull()
        val hold = rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("hold=") }
            ?.substringAfter("hold=")
            ?.toIntOrNull()
            ?.coerceIn(1, 60)
            ?: 5
        // flags 可经 query 覆盖，便于真机上换 flag 组合验证而不必重编（默认 OWN_CONTENT_ONLY）。
        val flags = rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("flags=") }
            ?.substringAfter("flags=")
            ?.toIntOrNull()
            ?: VirtualDeviceBridge.defaultDesktopFlags()
        // vdmHome=1 走 VDM setHomeComponent（实测被 ROM 自带 SecondaryDisplayLauncher 抢走）；
        // 默认 0：不设 VDM home，改用特权侧显式 am start 把 FusionHomeActivity 拉上副屏。
        val vdmHome = rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("vdmhome=") }
            ?.substringAfter("vdmhome=")
            ?.toIntOrNull() != 0

        // 桌面会话在跑时直接拒绝（409）：同上，探针不得与运行中的桌面抢 VDM 资源。
        if (DesktopController.status().running) {
            val d = DesktopController.status()
            return 409 to JSONObject()
                .put("ok", false)
                .put("error", "desktop active; probe refused (stop desktop first)")
                .put("displayId", d.displayId)
                .toString()
        }

        var createOk = false
        var createReport: String
        var assocOwnId = -1
        var assocStateAfter = ""
        if (apkPath.isNullOrBlank()) {
            createReport = "跳过（resolveApkPath 失败）"
        } else {
            // **只建探针自己那条关联**（PROBE_MAC），与桌面会话的 OWN_MAC 分开（Vdm-Assoc-Own-1）。
            assocOwnId = runCatching { VirtualDeviceAssociation.ensure(VirtualDeviceAssociation.PROBE_MAC) }
                .getOrElse { t ->
                    Log.w("BlindCast", "[DeviceApiRoute] vdCreate assoc ensure threw: ${t.message}")
                    -1
                }
            createReport = if (assocOwnId > 0) {
                val r = runCatching { runBlocking { RootExecutor.runAsShellVdCreate(pkg, apkPath, hold, flags, vdmHome) } }
                    .getOrElse { t -> false to "shell 段异常：${t.javaClass.simpleName}: ${t.message ?: t}" }
                createOk = r.first
                r.second?.takeIf { it.isNotBlank() } ?: "(shell 段无报文)"
            } else {
                "未执行：自管理关联建立失败（见 logcat BlindCast-VDAssoc）"
            }
            // 只清探针自己那条。
            runCatching { VirtualDeviceAssociation.release(VirtualDeviceAssociation.PROBE_MAC) }
                .onFailure { Log.w("BlindCast", "[DeviceApiRoute] vdCreate assoc release threw: ${it.message}") }
            assocStateAfter = runCatching {
                VirtualDeviceAssociation.stateLine(VirtualDeviceAssociation.PROBE_MAC)
            }.getOrDefault("")
        }

        val json = JSONObject()
            .put("ok", true)
            .put("pkg", pkg)
            .put("apkPath", apkPath ?: "")
            .put("holdSec", hold)
            .put("assocOwnId", assocOwnId)
            .put("assocStateAfter", assocStateAfter)
            .put("createOk", createOk)
            .put("create", createReport)
        return 200 to json.toString()
    }

    /**
     * Phase C 虚拟桌面（Vdm-Api-1 · 需鉴权）：
     * - `GET /api/desktop` → 状态对象；
     * - `POST /api/desktop` body `{"action":"on|off|home|toggle|status"}`（也接受 `?action=`）。
     *
     * 契约与前端冻结版一致（见 `assets/web/index.html`）：失败一律 `ok:false` + `error` 文案，
     * 且 `mode` 已回落 `mirror`——**不静默成功**。
     */
    fun handleDesktop(method: String, rawQuery: String?, body: ByteArray): Pair<Int, String> {
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        val pkg = ctx?.packageName ?: BuildConfig.APPLICATION_ID
        val apkPath = runCatching { PowerController.resolveApkPath(pkg) }.getOrNull() ?: ""

        if (method == "GET") return 200 to desktopJson(DesktopController.status())
        if (method != "POST") return 405 to err("method not allowed")

        val action = runCatching {
            val q = TokenAuthenticator.parseQuery(rawQuery)["action"]
            if (!q.isNullOrBlank()) {
                q
            } else if (body.isEmpty()) {
                null
            } else {
                JSONObject(body.toString(Charsets.UTF_8)).optString("action", "").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        if (action.isNullOrBlank()) return 400 to err("missing action (on|off|home|toggle|status)")

        // Phase C：进桌面源必须先确认 App 侧搬运服（`abstract:blindcast_capture`）已监听，
        // 否则特权宿主 FusionDesktopMain 立刻 connect 会吃 Connection refused（真机实证）。
        // 顺序：ensureDesktopSocket（停物理镜像采集 → 只起搬运服）→ DesktopController.on。
        val onAction: suspend () -> DesktopController.Status = on@{
            val c = ctx ?: return@on DesktopController.Status(
                false, -1, -1, DesktopController.DEFAULT_WIDTH, DesktopController.DEFAULT_HEIGHT,
                DesktopController.DEFAULT_DENSITY, "mirror", "无应用上下文，无法起桌面搬运服", -1,
            )
            val ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.erl.blindcast.core.service.BlindCastForegroundService.ensureDesktopSocket(c)
            }
            if (!ready) {
                DesktopController.Status(
                    false, -1, -1, DesktopController.DEFAULT_WIDTH, DesktopController.DEFAULT_HEIGHT,
                    DesktopController.DEFAULT_DENSITY, "mirror",
                    "桌面搬运服 10s 内未就绪（见 logcat BlindCast-FgService [DesktopRoute]）", -1,
                )
            } else {
                // 切源前先解卡：镜像源残留的按下手势必须在 display 0 上补 Up，
                // 否则会带到虚拟屏（此时 lastInjectDisplayId 仍为 0，正是镜像屏）。
                ControlWsRoute.resetForSourceSwitch("desktop on")
                DesktopController.on(pkg, apkPath)
            }
        }
        val offAction: suspend () -> DesktopController.Status = off@{
            val st = DesktopController.off(pkg)
            // 切回镜像前解卡：桌面上的按下手势必须在**副屏**补 Up 后清掉，
            // 否则残留触点跟着回到物理主屏（用户可见的“卡住”）。
            ControlWsRoute.resetForSourceSwitch("desktop off")
            // 关桌面必须等搬运服**真正释放**（抽象名回收），否则下一次 on 直接 EADDRINUSE。
            ctx?.let { c ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.erl.blindcast.core.service.BlindCastForegroundService.stopDesktopBlocking(c)
                }
            }
            st
        }
        val st: DesktopController.Status = when (action) {
            "on" -> runBlocking { onAction() }
            "off" -> runBlocking { offAction() }
            "home" -> runBlocking { DesktopController.home(pkg, apkPath) }
            "toggle" -> runBlocking {
                if (DesktopController.status().running) offAction() else onAction()
            }
            "status" -> DesktopController.status()
            else -> return 400 to err("unknown action: $action")
        }
        return 200 to desktopJson(st)
    }

    /** 状态对象序列化（GET/POST 同一形状，前端按此解析）。 */
    private fun desktopJson(st: DesktopController.Status): String = JSONObject()
        .put("ok", st.error.isBlank())
        .put("mode", st.mode)
        .put("running", st.running)
        .put("displayId", st.displayId)
        .put("deviceId", st.deviceId)
        .put("width", st.width)
        .put("height", st.height)
        .put("densityDpi", st.densityDpi)
        .put("source", st.source)
        .put("streaming", isStreamingNow())
        .put("homeComponent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.APPLICATION_ID}.FusionHomeActivity")
        .put("error", st.error)
        .toString()

    /**
     * Phase C · 副屏任务管理（Vdm-Api-2，需鉴权）：
     * - `GET /api/desktop/tasks` → 目标副屏 Task 列表（严格按 displayId 过滤）；
     * - `POST /api/desktop/tasks` body `{"action":"switch","taskId":N}` → 复用副屏已有 Task 拉前台。
     * - `POST /api/desktop/tasks` body `{"action":"close","taskId":N}` → 移除该副屏 Task（原版「关闭应用」）。
     *
     * Task 过滤与切换由 [DesktopTaskController] 实现：切换前按 taskId 反查其真实 displayId，
     * 不属于当前副屏一律拒绝，**永不移动物理主屏 display 0 的 Task**。
     */
    fun handleDesktopTasks(method: String, body: ByteArray): Pair<Int, String> {
        val st = DesktopController.status()
        if (!st.running || st.displayId <= 0) {
            return 200 to JSONObject()
                .put("ok", false)
                .put("displayId", -1)
                .put("tasks", JSONArray())
                .put("error", "桌面未运行")
                .toString()
        }
        return when (method) {
            "GET" -> {
                val arr = JSONArray()
                DesktopTaskController.listTasks(st.displayId).forEach { arr.put(it.toJson()) }
                val json = JSONObject()
                    .put("ok", true)
                    .put("displayId", st.displayId)
                    .put("tasks", arr)
                    .put("error", "")
                Log.i("BlindCast", "[ControlWs] desktop tasks did=${st.displayId} n=${arr.length()}")
                200 to json.toString()
            }
            "POST" -> {
                val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                    ?: return 400 to err("invalid json body")
                when (val action = obj.optString("action", "")) {
                    "switch" -> {
                        val taskId = obj.optInt("taskId", -1)
                        if (taskId <= 0) {
                            400 to err("missing taskId")
                        } else {
                            val res = DesktopTaskController.switchTask(taskId, st.displayId)
                            Log.i(
                                "BlindCast",
                                "[ControlWs] desktop switch taskId=$taskId did=${st.displayId} " +
                                    "switched=${res.switched} actualDid=${res.displayId} ok=${res.ok} err=${res.error.take(120)}",
                            )
                            200 to res.toJson().toString()
                        }
                    }
                    "close" -> {
                        val taskId = obj.optInt("taskId", -1)
                        if (taskId <= 0) {
                            400 to err("missing taskId")
                        } else {
                            val res = DesktopTaskController.closeTask(taskId, st.displayId)
                            Log.i(
                                "BlindCast",
                                "[ControlWs] desktop close taskId=$taskId did=${st.displayId} " +
                                    "closed=${res.closed} ok=${res.ok} err=${res.error.take(120)}",
                            )
                            200 to res.toJson().toString()
                        }
                    }
                    else -> 400 to err("unknown action: $action (switch|close)")
                }
            }
            else -> 405 to err("method not allowed")
        }
    }

    /**
     * 逐应用窗口（Win-Api-1，需鉴权）：`/api/desktop/windows`
     * - `GET` → `{ok, windows:[{windowId,displayId,taskId,packageName,component,width,height,state,error}]}`；
     * - `POST {action:"open", package, component?, width?, height?}` → 起一个窗口（该应用独占
     *   一张虚拟显示 + 一路编码 + 一条 socket）；
     * - `POST {action:"close", windowId}` → 真关：宿主释放自己的虚拟屏/设备/编码器，
     *   屏上应用任务一并销毁，**物理主屏与整屏桌面不受影响**；
     * - `POST {action:"resize", windowId, width, height}` → 同 id 重建（源几何随窗口变）；
     * - `POST {action:"relaunch", windowId}` → 关闭并以原组件和尺寸重新启动；
     * - `POST {action:"closeAll"}` → 全关。
     *
     * 每个窗口的帧走 `/ws/stream` 的 `0x11 + windowId` 通道；输入走 `/ws/control` 的 `wid`。
     */
    fun handleDesktopWindows(method: String, body: ByteArray): Pair<Int, String> = when (method) {
        "GET" -> 200 to windowsJson()
        "POST" -> {
            val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return 400 to err("invalid json body")
            when (val action = obj.optString("action", "")) {
                "list" -> 200 to windowsJson()
                "open" -> {
                    val pkgName = obj.optString("package", "").trim()
                    val intentUrl = obj.optString("intentUrl", "").trim()
                    val kind = obj.optString("kind", "app").trim().ifBlank { "app" }
                    val user = obj.optInt("user", obj.optInt("userId", 0))
                    if (pkgName.isBlank() && intentUrl.isBlank() && kind == "app") {
                        400 to err("missing package or intentUrl")
                    } else {
                        val w = obj.optInt("width", DesktopWindowController.DEFAULT_WIDTH)
                        val h = obj.optInt("height", DesktopWindowController.DEFAULT_HEIGHT)
                        val comp = obj.optString("component", "")
                        val info = runBlocking { DesktopWindowController.open(pkgName, comp, w, h, intentUrl = intentUrl, kind = kind, userId = user) }
                        Log.i(
                            "BlindCast",
                            "[WinApi] open pkg=$pkgName ${w}x${h} wid=${info.windowId} did=${info.displayId} " +
                                "state=${info.state} err=${info.error.take(120)}",
                        )
                        200 to JSONObject()
                            .put("ok", info.state == "running")
                            .put("window", windowJson(info))
                            .put("error", info.error)
                            .toString()
                    }
                }
                "close" -> {
                    val wid = obj.optInt("windowId", -1)
                    if (wid <= 0) 400 to err("missing windowId") else {
                        val ok = runBlocking { DesktopWindowController.close(wid) }
                        Log.i("BlindCast", "[WinApi] close wid=$wid ok=$ok")
                        200 to JSONObject().put("ok", ok).put("windowId", wid).put("error", "").toString()
                    }
                }
                "resize" -> {
                    val wid = obj.optInt("windowId", -1)
                    val w = obj.optInt("width", 0)
                    val h = obj.optInt("height", 0)
                    if (wid <= 0 || w <= 0 || h <= 0) {
                        400 to err("missing windowId|width|height")
                    } else {
                        val info = runBlocking { DesktopWindowController.resize(wid, w, h) }
                        Log.i(
                            "BlindCast",
                            "[WinApi] resize wid=$wid ${w}x$h state=${info.state} err=${info.error.take(120)}",
                        )
                        200 to JSONObject()
                            .put("ok", info.state == "running")
                            .put("window", windowJson(info))
                            .put("error", info.error)
                            .toString()
                    }
                }
                "relaunch" -> {
                    val wid = obj.optInt("windowId", -1)
                    if (wid <= 0) {
                        400 to err("missing windowId")
                    } else {
                        val current = DesktopWindowController.list().firstOrNull { it.windowId == wid }
                        if (current == null) {
                            200 to JSONObject().put("ok", false).put("error", "window not found").toString()
                        } else {
                            val info = runBlocking {
                                DesktopWindowController.close(wid)
                                DesktopWindowController.open(
                                    current.packageName,
                                    current.component,
                                    current.width,
                                    current.height,
                                    wid,
                                    current.intentUrl,
                                    current.kind,
                                    current.userId,
                                )
                            }
                            200 to JSONObject()
                                .put("ok", info.state == "running")
                                .put("window", windowJson(info))
                                .put("error", info.error)
                                .toString()
                        }
                    }
                }
                "closeAll" -> {
                    val n = runBlocking { DesktopWindowController.closeAll() }
                    200 to JSONObject().put("ok", true).put("closed", n).put("error", "").toString()
                }
                else -> 400 to err("unknown action: $action (list|open|close|resize|relaunch|closeAll)")
            }
        }
        else -> 405 to err("method not allowed")
    }

    private fun windowsJson(): String {
        val arr = JSONArray()
        runCatching { DesktopWindowController.list() }.getOrDefault(emptyList())
            .forEach { arr.put(windowJson(it)) }
        return JSONObject()
            .put("ok", true)
            .put("windows", arr)
            .put("max", DesktopWindowController.MAX_WINDOWS)
            .put("error", "")
            .toString()
    }

    private fun windowJson(w: DesktopWindowController.WindowInfo): JSONObject = JSONObject()
        .put("windowId", w.windowId)
        .put("displayId", w.displayId)
        .put("taskId", w.taskId)
        .put("packageName", w.packageName)
        .put("component", w.component)
        .put("width", w.width)
        .put("height", w.height)
        .put("state", w.state)
        .put("intentUrl", w.intentUrl)
        .put("kind", w.kind)
        .put("userId", w.userId)
        .put("error", w.error)

    /**
     * 应用列表（「打开应用」选择器用）：`GET /api/apps[?all=1]`。
     *
     * - 默认：只列**有 launcher activity** 的应用（与 [DesktopWindowController.resolveLauncherComponent]
     *   同口径，保证列表里点开的包一定能起窗口），按 label 排序、按包名去重；
     * - `?all=1`：列**全部已安装应用**（原版 `app-list` 的全量语义），含系统应用，附 `system` 标记。
     *
     * 返回 `{ok, apps:[{package,label,system,appCategory}], error}`（`appCategory` = Android
     * `ApplicationInfo.category`，`0` 为游戏、`-1` 未分类；供面板如实上报 `game`）。只读 PackageManager，无副作用。
     */
    fun handleApps(method: String, rawQuery: String? = null): Pair<Int, String> {
        if (method != "GET") return 405 to err("method not allowed")
        val ctx = runCatching { com.erl.blindcast.blindCastApp.applicationContext }.getOrNull()
            ?: return 500 to err("no context")
        val all = rawQuery?.split('&')?.any { it == "all=1" || it == "all=true" } == true
        val arr = JSONArray()
        runCatching {
            val pm = ctx.packageManager
            data class AppRow(val pkg: String, val label: String, val system: Boolean, val category: Int)
            val rows = ArrayList<AppRow>()
            if (all) {
                for (ai in pm.getInstalledApplications(0)) {
                    val pkgName = ai.packageName ?: continue
                    val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkgName)
                    val system = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    rows.add(AppRow(pkgName, label, system, ai.category))
                }
            } else {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val pkgName = ri.activityInfo?.packageName ?: continue
                    if (!seen.add(pkgName)) continue
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkgName)
                    val category = ri.activityInfo?.applicationInfo?.category ?: -1
                    rows.add(AppRow(pkgName, label, false, category))
                }
            }
            rows.sortBy { it.label.lowercase() }
            for (r in rows) {
                arr.put(
                    JSONObject().put("package", r.pkg).put("label", r.label)
                        .put("system", r.system).put("appCategory", r.category)
                )
            }
        }
        return 200 to JSONObject().put("ok", true).put("apps", arr).put("error", "").toString()
    }

    private data class Battery(val level: Int, val tempC: Float, val charging: Boolean)

    private fun readBattery(ctx: Context?): Battery {
        if (ctx == null) return Battery(-1, -1f, false)
        return runCatching {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10)?.div(10f) ?: -1f
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            Battery(level, tempC, charging)
        }.getOrDefault(Battery(-1, -1f, false))
    }

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
