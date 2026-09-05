package com.erl.blindcast.core.server.routes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.AudioGate
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
            return 200 to JSONObject().put("blackedOut", PowerController.isBlackedOut).toString()
        }
        if (method != "POST") {
            return 405 to err("method not allowed")
        }
        val query = TokenAuthenticator.parseQuery(rawQuery)
        val target = query["on"]?.toBooleanStrictOrNull()?.let { it }
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
                .toString()
        }
    }

    fun handleStatus(): Pair<Int, String> {
        val ctx = appContextOverride ?: runCatching { blindCastApp.applicationContext }.getOrNull()
        val battery = readBattery(ctx)
        val json = JSONObject()
            .put("blackedOut", PowerController.isBlackedOut)
            .put("batteryLevel", battery.level)
            .put("batteryTempC", battery.tempC)
            .put("charging", battery.charging)
            .put("audioEnabled", AudioGate.isAudioEnabled)
            .put("videoRunning", ScreenCaptureEngine.isRunning)
            .put("audioRunning", AudioCaptureEngine.isRunning)
            .put("streamClients", StreamWsRoute.sessionCount)
            .put("controlClients", ControlWsRoute.sessionCount)
        return 200 to json.toString()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun actionToOn(action: String?): Boolean? = when (action?.lowercase()) {
        "on", "wake", "true" -> true
        "off", "sleep", "blackout", "false" -> false
        "toggle" -> !PowerController.isBlackedOut
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
