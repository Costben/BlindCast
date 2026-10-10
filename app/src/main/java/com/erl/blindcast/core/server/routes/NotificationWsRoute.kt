package com.erl.blindcast.core.server.routes

import com.erl.blindcast.core.notification.NotificationStore
import java.net.SocketTimeoutException
import org.json.JSONObject

/** Pushes notification snapshots as soon as Android's listener changes. */
object NotificationWsRoute {
    fun handle(conn: WsConnection) {
        NotificationApiRoute.ensureListenerGranted()
        val listener: () -> Unit = {
            runCatching {
                conn.sendText(snapshot())
            }
        }
        NotificationStore.addListener(listener)
        try {
            conn.sendText(snapshot())
            while (conn.isOpen) {
                try {
                    val frame = conn.receive() ?: break
                    if (frame.isText) {
                        val obj = runCatching { JSONObject(frame.text()) }.getOrNull()
                        if (obj?.optString("type") == "ping") conn.sendText("{\"type\":\"pong\"}")
                    }
                } catch (_: SocketTimeoutException) {
                    Thread.sleep(250L)
                }
            }
        } finally {
            NotificationStore.removeListener(listener)
            runCatching { conn.close() }
        }
    }

    private fun snapshot(): String {
        val obj = NotificationStore.toJson()
        obj.put("type", "notif-snapshot")
        obj.put("connected", NotificationStore.connected)
        return obj.toString()
    }
}
