package com.erl.blindcast.core.server.routes

import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.ScrcpyGate
import com.erl.blindcast.core.scrcpy.TouchInjector
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 反向控制 WebSocket 路由（Slice 4.1 · `GET /ws/control` 升级后落点）。
 *
 * ## 指令协议（客户端 → 文本 JSON，服务端逐条回执 `{"type":"ack",...}`）
 * - `{"type":"down","x":0..1,"y":0..1}` / `move` / `up`
 *   → [TouchInjector] 归一化触控注入（左键拖拽，物理熄屏下照常驱动）；
 * - `{"type":"key","keycode":int}` → 完整按键（Down+Up）；
 * - `{"type":"click","button":"right"|"middle"|"left","x":..,"y":..}`
 *   → 右键 = `KEYCODE_BACK`（返回），中键 = `KEYCODE_HOME`，
 *   左键 = 在 (x,y) 点按一次（down+up，触控笔/无拖拽场景用）；
 * - `{"type":"text","text":"..."}` → 键盘打字注入（虚拟键盘映射）；
 * - `{"type":"audio","enabled":bool}` → 音频传输总闸（直通 [AudioCaptureEngine]，
 *   关闸零编码零网络消耗由引擎保证）；
 * - `{"type":"ping"}` → `{"type":"pong"}`（应用层心跳，4.2 延迟显示用）。
 * - 未知 type / 非法参数 → `{ok:false, error:...}`，连接不断（4.2 联调期容错）。
 *
 * 二进制上行帧直接拒收回错（本通道只收文本指令）。
 *
 * ## 线程模型
 * - 每会话独占一个连接池线程做读循环；注入为同步 Binder 调用，
 *   [TouchInjector] 内部串行保序（Down→Move→Up 不乱序）；
 * - 会话结束（对端关闭 / 出错 / 服务停止）必调 [TouchInjector.cancelTouch]
 *   在最后坐标补发 Up，防止屏幕残留卡死触点；
 * - 会话集合只用于计数与停服踢人。
 */
object ControlWsRoute {

    private val sessions = CopyOnWriteArraySet<WsConnection>()

    /** 当前控制在线数（供 `/api/status` 与 6.1 主页绑定）。 */
    val sessionCount: Int get() = sessions.size

    /**
     * 接管一条已升级连接（阻塞直到对端关闭 / 出错 / 服务停止）。
     * 调用方为 [BlindCastServer] 连接池线程；返回后连接已关闭。
     */
    fun handle(conn: WsConnection) {
        sessions.add(conn)
        try {
            while (conn.isOpen) {
                val frame = try {
                    conn.receive()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                } ?: break
                if (!frame.isText) {
                    reply(conn, false, null, "binary frames not accepted")
                    continue
                }
                dispatch(conn, frame.text())
            }
        } finally {
            sessions.remove(conn)
            runCatching { TouchInjector.cancelTouch() }
            runCatching { conn.close() }
        }
    }

    /** 停服时调用：踢掉全部控制会话（幂等）。 */
    fun shutdown() {
        for (s in sessions) runCatching { s.close(1001, "server stopping") }
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun dispatch(conn: WsConnection, raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json == null) {
            reply(conn, false, null, "bad_json")
            return
        }
        when (json.optString("type", "")) {
            "down", "move", "up" -> handleTouch(conn, json)
            "key" -> {
                if (!ScrcpyGate.isTouchEnabled) {
                    reply(conn, false, "key", "touch disabled")
                } else if (!ScrcpyGate.isKeyboardEnabled) {
                    reply(conn, false, "key", "keyboard disabled")
                } else {
                    val keyCode = json.optInt("keycode", Int.MIN_VALUE)
                    if (keyCode == Int.MIN_VALUE) {
                        reply(conn, false, "key", "missing keycode")
                    } else {
                        val ok = runCatching { TouchInjector.injectKey(keyCode) }.getOrDefault(false)
                        reply(conn, ok, "key", if (ok) null else lastTouchError())
                    }
                }
            }
            "click" -> handleClick(conn, json)
            "text" -> {
                if (!ScrcpyGate.isTouchEnabled) {
                    reply(conn, false, "text", "touch disabled")
                } else if (!ScrcpyGate.isKeyboardEnabled) {
                    reply(conn, false, "text", "keyboard disabled")
                } else {
                    val text = json.optString("text", "")
                    val ok = runCatching { TouchInjector.injectText(text) }.getOrDefault(false)
                    reply(conn, ok, "text", if (ok) null else lastTouchError())
                }
            }
            "audio" -> {
                if (!json.has("enabled")) {
                    reply(conn, false, "audio", "missing enabled")
                } else {
                    val enabled = json.optBoolean("enabled", true)
                    runCatching { AudioCaptureEngine.setAudioEnabled(enabled) }
                    reply(conn, true, "audio", null)
                }
            }
            "ping" -> replyRaw(conn, """{"type":"pong","ok":true}""")
            else -> reply(conn, false, null, "unknown type")
        }
    }

    private fun handleTouch(conn: WsConnection, json: JSONObject) {
        if (!ScrcpyGate.isTouchEnabled) {
            reply(conn, false, json.optString("type", ""), "touch disabled")
            return
        }
        val type = json.optString("type", "")
        val x = json.optDouble("x", Double.NaN).toFloat()
        val y = json.optDouble("y", Double.NaN).toFloat()
        if (!x.isFinite() || !y.isFinite()) {
            reply(conn, false, type, "missing x|y")
            return
        }
        val ok = runCatching {
            when (type) {
                "down" -> TouchInjector.injectTouchDown(x, y)
                "move" -> TouchInjector.injectTouchMove(x, y)
                else -> TouchInjector.injectTouchUp(x, y)
            }
        }.getOrDefault(false)
        reply(conn, ok, type, if (ok) null else lastTouchError())
    }

    private fun handleClick(conn: WsConnection, json: JSONObject) {
        if (!ScrcpyGate.isTouchEnabled) {
            reply(conn, false, "click", "touch disabled")
            return
        }
        when (json.optString("button", "left")) {
            "right" -> {
                if (!ScrcpyGate.isRightBackEnabled) {
                    reply(conn, false, "click", "right-back disabled")
                    return
                }
                val ok = runCatching {
                    TouchInjector.injectKey(TouchInjector.MOUSE_BUTTON_RIGHT_KEYCODE)
                }.getOrDefault(false)
                reply(conn, ok, "click", if (ok) null else lastTouchError())
            }
            "middle" -> {
                val ok = runCatching {
                    TouchInjector.injectKey(TouchInjector.MOUSE_BUTTON_MIDDLE_KEYCODE)
                }.getOrDefault(false)
                reply(conn, ok, "click", if (ok) null else lastTouchError())
            }
            else -> {
                // 左键点按 = down + up（无拖拽的轻量点击路径）。
                val x = json.optDouble("x", Double.NaN).toFloat()
                val y = json.optDouble("y", Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) {
                    reply(conn, false, "click", "missing x|y")
                    return
                }
                val ok = runCatching {
                    TouchInjector.injectTouchDown(x, y) && TouchInjector.injectTouchUp(x, y)
                }.getOrDefault(false)
                reply(conn, ok, "click", if (ok) null else lastTouchError())
            }
        }
    }

    private fun lastTouchError(): String =
        TouchInjector.lastError?.message ?: "inject rejected (missing privilege?)"

    private fun reply(conn: WsConnection, ok: Boolean, type: String?, error: String?) {
        val json = JSONObject().put("type", "ack").put("ok", ok)
        if (type != null) json.put("for", type)
        if (error != null) json.put("error", error)
        replyRaw(conn, json.toString())
    }

    private fun replyRaw(conn: WsConnection, text: String) {
        runCatching { conn.sendText(text) }
    }
}
