package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.priv.DesktopController
import com.erl.blindcast.core.priv.DesktopWindowController
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 显示系统设置路由（需鉴权）：夜间模式（原版 A20 `display-night-mode`）。
 *
 * ## 语义：**逐虚拟屏**优先，设备全局为显式可选
 * 逆向参考 `MirrorServerMain.java:4115-4185` 用的是
 * `VirtualDevice.setDisplayUiMode(displayId, uiMode)`（`uiMode = 32/16`）——**逐屏**设置，
 * 只影响该虚拟屏，不动设备全局 `cmd uimode night`。本路由照此实现：
 *
 * - `POST /api/display/night-mode {"on":bool,"target":"display"|"device"}`
 *   - `target` 缺省 / `"display"`：走 [DesktopController.setNightMode] → 宿主反射
 *     `setDisplayUiMode(桌面 displayId, 32|16)`。**桌面未运行则 fail closed**（`ok:false`），
 *     绝不退化成全局开关去扰动物理主屏。
 *   - `target:"device"`：显式设备级全局设置（`cmd uimode night yes|no`），回真实成败。
 *   - 返回 `{ok,on,scope,displayId,error}`；`scope` ∈ `display|device`。
 * - `GET /api/display/night-mode` → `{ok,running,displayId,on,scope:"display",deviceOn}`：
 *   `on` = 桌面虚拟屏当前夜间模式（宿主回写），`deviceOn` = 设备全局夜间模式。
 *
 * 浏览器端外观（appearance: light/dark/accent）是**面板本地状态**，不得自动映射到本端点；
 * 只有用户显式切换设备/桌面夜间模式时才调用。
 */
object DisplayApiRoute {

    private const val TAG = "BlindCast-Display"

    fun handleNightMode(method: String, body: ByteArray): Pair<Int, String> = when (method) {
        "GET" -> {
            val st = runCatching { DesktopController.status() }.getOrNull()
            val running = st?.running == true
            val displayId = st?.displayId ?: -1
            val on = if (running) runCatching { DesktopController.nightModeState() }.getOrNull() else null
            200 to JSONObject()
                .put("ok", true)
                .put("running", running)
                .put("displayId", displayId)
                .put("on", on ?: JSONObject.NULL)
                .put("scope", "display")
                .put("deviceOn", runBlocking(Dispatchers.IO) { readGlobalNightMode() })
                .toString()
        }

        "POST" -> {
            val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return 400 to err("invalid json body")
            if (!obj.has("on")) return 400 to err("missing on")
            val want = obj.optBoolean("on")
            when (val target = obj.optString("target", "display").trim().lowercase()) {
                "device" -> {
                    val (ok, detail) = runBlocking(Dispatchers.IO) { setGlobalNightMode(want) }
                    Log.i(TAG, "[night-mode] scope=device on=$want ok=$ok ${detail.take(120)}")
                    200 to JSONObject()
                        .put("ok", ok)
                        .put("on", runBlocking(Dispatchers.IO) { readGlobalNightMode() })
                        .put("scope", "device")
                        .put("error", if (ok) "" else detail)
                        .toString()
                }
                "display", "" -> {
                    val windowId = obj.optInt("windowId", 0)
                    if (obj.has("windowId") && windowId !in 0..255) return 400 to err("invalid windowId")
                    val r = runCatching {
                        if (windowId > 0) DesktopWindowController.setNightMode(windowId, want)
                        else DesktopController.setNightMode(want)
                    }.getOrElse { t ->
                        DesktopController.NightResult(false, null, -1, "${t.javaClass.simpleName}: ${t.message}")
                    }
                    Log.i(TAG, "[night-mode] scope=display on=$want ok=${r.ok} did=${r.displayId} ${r.error}")
                    200 to JSONObject()
                        .put("ok", r.ok)
                        .put("on", r.on ?: JSONObject.NULL)
                        .put("scope", "display")
                        .put("displayId", r.displayId)
                        .put("error", r.error)
                        .toString()
                }
                else -> 400 to err("unknown target: $target (display|device)")
            }
        }

        else -> 405 to err("method not allowed")
    }

    /** 设备全局夜间模式读取：`cmd uimode night` → "Night mode: yes/no"。 */
    fun readGlobalNightMode(): Boolean {
        val res = runCatching { Shell.cmd("cmd uimode night").exec() }.getOrNull() ?: return false
        return (res.out + res.err).joinToString(" ").contains("yes", ignoreCase = true)
    }

    /** 设备全局夜间模式写入（仅显式 `target:"device"` 时调用）。返回 (成功, 详情)。 */
    private fun setGlobalNightMode(enabled: Boolean): Pair<Boolean, String> {
        val res = runCatching { Shell.cmd("cmd uimode night ${if (enabled) "yes" else "no"}").exec() }
            .getOrElse { t -> return false to "shell threw: ${t.javaClass.simpleName}: ${t.message}" }
        val out = (res.out + res.err).joinToString("\n").trim()
        return res.isSuccess to out.ifBlank { if (res.isSuccess) "ok" else "exit=${res.code}" }
    }

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
