package com.erl.blindcast.core.server.routes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.AudioGate
import com.erl.blindcast.core.scrcpy.CaptureSocketLink
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import com.erl.blindcast.core.server.auth.TokenAuthenticator
import kotlinx.coroutines.runBlocking
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
        return 200 to json.toString()
    }

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
