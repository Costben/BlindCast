package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.notification.BlindCastNotificationListener
import com.erl.blindcast.core.notification.NotificationStore
import com.erl.blindcast.core.priv.DesktopWindowController
import com.topjohnwu.superuser.Shell
import org.json.JSONObject

/**
 * 通知镜像路由（需鉴权）：原版 A19 `notif-*` 的 REST 落地。
 *
 * - `GET  /api/notifications` → `{ok, connected, active:[{key,package,title,text,time,ongoing,group}], recent:[...], count}`
 * - `GET  /api/notifications/icon?key=<key>` → `image/png`（无图标 404）
 * - `POST /api/notifications {"action":"dismiss","key":...}` → `{ok}`：
 *   也接受原版数值命令 `{"command":1,"key":...}`（1 = dismiss）；
 *   `open`、`action`、`reply` 分别触发通知内容 PendingIntent、动作按钮和 RemoteInput；
 *   `{"action":"dismissAll"}` 清空全部。
 *
 * 数据源为 [BlindCastNotificationListener]（`NotificationListenerService`）。监听器需「通知使用权」，
 * 本路由首次调用时以 root 幂等执行 `cmd notification allow_listener <component>` 自动授权。
 */
object NotificationApiRoute {

    private const val TAG = "BlindCast-NotifApi"

    /** 监听器组件名（applicationId 固定 com.erl.blindcast）。 */
    private const val COMPONENT =
        "com.erl.blindcast/com.erl.blindcast.core.notification.BlindCastNotificationListener"

    @Volatile
    private var grantAttempted = false

    fun handle(method: String, rawQuery: String?, body: ByteArray): Pair<Int, String> = when (method) {
        "GET" -> {
            ensureListenerGranted()
            200 to JSONObject()
                .put("ok", true)
                .put("connected", NotificationStore.connected)
                .put("active", NotificationStore.toJson().getJSONArray("active"))
                .put("recent", NotificationStore.toJson().getJSONArray("recent"))
                .put("count", NotificationStore.snapshot().size)
                .toString()
        }
        "POST" -> {
            ensureListenerGranted()
            val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return 400 to err("invalid json body")
            val action = obj.optString("action", "").trim().lowercase()
            val key = obj.optString("key", "")
            val displayId = obj.optString("sessionId", "").toIntOrNull()?.let { wid ->
                DesktopWindowController.list().firstOrNull { it.windowId == wid }?.displayId
            } ?: 0
            val command = obj.optInt("command", -1)
            when {
                action == "dismissall" -> {
                    val ok = BlindCastNotificationListener.cancelAll()
                    200 to JSONObject().put("ok", ok).put("action", "dismissAll").toString()
                }
                action == "dismiss" || command == 1 -> {
                    if (key.isBlank()) return 400 to err("missing key")
                    val ok = BlindCastNotificationListener.cancel(key)
                    200 to JSONObject().put("ok", ok).put("action", "dismiss").put("key", key).toString()
                }
                action == "open" || command == 5 || command == 6 -> {
                    val ok = NotificationStore.send(com.erl.blindcast.blindCastApp.applicationContext, key, displayId = displayId)
                    200 to JSONObject().put("ok", ok).put("action", "open").put("key", key).toString()
                }
                action == "action" || command == 7 -> {
                    val index = obj.optInt("actionIndex", obj.optInt("index", -1))
                    val ok = NotificationStore.send(com.erl.blindcast.blindCastApp.applicationContext, key, index, displayId = displayId)
                    200 to JSONObject().put("ok", ok).put("action", "action").put("key", key).put("actionIndex", index).toString()
                }
                action == "reply" || command == 8 -> {
                    val index = obj.optInt("actionIndex", obj.optInt("index", -1))
                    val text = obj.optString("text", obj.optString("reply", ""))
                    val ok = NotificationStore.send(com.erl.blindcast.blindCastApp.applicationContext, key, index, text, displayId)
                    200 to JSONObject().put("ok", ok).put("action", "reply").put("key", key).put("actionIndex", index).toString()
                }
                else -> 200 to JSONObject()
                    .put("ok", false)
                    .put("error", "unsupported action: ${action.ifBlank { obj.optInt("command", -1).toString() }}")
                    .toString()
            }
        }
        else -> 405 to err("method not allowed")
    }

    /** `GET /api/notifications/icon?key=<key>` → PNG 或 404。 */
    fun icon(rawQuery: String?): AssetApiRoute.Binary {
        val key = parseKey(rawQuery)
        if (key.isBlank()) return jsonErr(400, "missing key")
        val png = NotificationStore.iconPng(key) ?: return jsonErr(404, "no icon")
        return AssetApiRoute.Binary(
            200,
            "image/png",
            png,
            mapOf(
                "ETag" to WebStaticRoutes.weakEtag(png),
                "Cache-Control" to "public, max-age=86400",
            ),
        )
    }

    /** root 幂等授予通知使用权（进程内只尝试一次）。 */
    fun ensureListenerGranted() {
        if (grantAttempted) return
        grantAttempted = true
        runCatching { Shell.cmd("cmd notification allow_listener $COMPONENT").exec() }
            .onFailure { Log.w(TAG, "[notif] grant listener failed: ${it.message}") }
    }

    private fun parseKey(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        for (pair in rawQuery.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            if (pair.substring(0, i) == "key") {
                return runCatching { java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8") }
                    .getOrDefault(pair.substring(i + 1))
            }
        }
        return ""
    }

    private fun jsonErr(status: Int, msg: String): AssetApiRoute.Binary = AssetApiRoute.Binary(
        status,
        "application/json; charset=utf-8",
        JSONObject().put("ok", false).put("error", msg).toString().toByteArray(Charsets.UTF_8),
    )

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
