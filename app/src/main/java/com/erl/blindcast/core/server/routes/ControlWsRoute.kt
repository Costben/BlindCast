package com.erl.blindcast.core.server.routes

import com.erl.blindcast.BuildConfig
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.JpegTranscoder
import com.erl.blindcast.core.scrcpy.ScrcpyGate
import com.erl.blindcast.core.scrcpy.TouchInjector
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 反向控制 WebSocket 路由（Slice 4.1 · `GET /ws/control` 升级后落点；
 * Universal-1 起加 `videoMode` 声明供 JPEG 按需启停）。
 *
 * ## 指令协议（客户端 → 文本 JSON，服务端逐条回执 `{"type":"ack",...}`）
 * - `{"type":"down","x":0..1,"y":0..1}` / `move` / `up`
 *   → [TouchInjector] 归一化触控注入（左键拖拽，物理熄屏下照常驱动；
 *   特权经 [PrivilegedBridge] Root优先→Shizuku 两段，无 Shizuku 纯 Root 机可用）；
 * - `{"type":"key","keycode":int}` → 完整按键（Down+Up）；
 * - `{"type":"click","button":"right"|"middle"|"left","x":..,"y":..}`
 *   → 右键 = `KEYCODE_BACK`（返回），中键 = `KEYCODE_HOME`，
 *   左键 = 在 (x,y) 点按一次（down+up，触控笔/无拖拽场景用）；
 * - `{"type":"text","text":"..."}` → 键盘打字注入（虚拟键盘映射）；
 * - `{"type":"audio","enabled":bool}` → 音频传输总闸（直通 [AudioCaptureEngine]，
 *   关闸零编码零网络消耗由引擎保证）；
 * - `{"type":"videoMode","mode":"jpeg"|"h264"}` → 视频降级声明（Universal-1 ·
 *   前端 `typeof VideoDecoder==="undefined"` 时发 `jpeg`，有硬解发 `h264`；
 *   无声明默认 h264；有任一 jpeg 订阅者即跑 [JpegTranscoder] 解码链，
 *   无订阅者停链省电；H264 老泵不受影响，切回不断）；
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

    /**
     * 待决手势（Smooth-1 双模：
     * - 常驻 daemon 存活时 down/move/up 经 daemon 实时直透（[realtime]=true，
     *   daemon 内 TouchInjector 单例跨指令保持手势，move 不再等松手）；
     * - daemon 不可用时只缓存（[realtime]=false），up 时按有无位移一次打成
     *   tap 或 drag 原子注入（Universal-1 老路，Shizuku 按次绑定无状态之必须）；
     *   断开时未 up 的缓存直接丢弃 + daemon cancel 解卡，设备侧无残留触点）。
     */
    private data class PendingTouch(
        val x0: Float, val y0: Float,
        var lastX: Float, var lastY: Float,
        var moved: Boolean = false,
        var realtime: Boolean = false,
    )
    private val pendingGestures = ConcurrentHashMap<WsConnection, PendingTouch>()

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
            // 未 up 即断开：丢缓存 + daemon 侧 cancel 解卡（防屏幕残留按住触点）。
            val pending = pendingGestures.remove(conn)
            if (pending != null && pending.realtime) {
                runCatching { cancelPriv() }
            }
            runCatching { JpegTranscoder.clear(conn) }
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
                        val (ok, err) = injectKeyPriv(keyCode)
                        reply(conn, ok, "key", err)
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
                    val (ok, err) = injectTextPriv(text)
                    reply(conn, ok, "text", err)
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
            "videoMode" -> {
                val mode = json.optString("mode", "").lowercase()
                if (mode != "jpeg" && mode != "h264") {
                    reply(conn, false, "videoMode", "missing mode (jpeg|h264)")
                } else {
                    runCatching { JpegTranscoder.setVideoMode(conn, mode) }
                    reply(conn, true, "videoMode", null)
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
        when (type) {
            "down" -> {
                val p = PendingTouch(x, y, x, y)
                pendingGestures[conn] = p
                // 实时优先：daemon down 透传（~数十 ms），成了后 move/up 直透跟手；
                // 失败则 realtime=false，up 时回退原子 tap/drag（老路兜底）。
                // down ack 恒 true（已缓存；实时 best-effort，不阻塞前端手势流）。
                val (ok, _) = injectDownPriv(x, y)
                p.realtime = ok
                reply(conn, true, type, null)
            }
            "move" -> {
                val p = pendingGestures[conn]
                if (p == null) {
                    reply(conn, false, type, "move without active down")
                } else {
                    p.lastX = x
                    p.lastY = y
                    p.moved = true
                    if (p.realtime) {
                        // 实时直透；透传失败说明 daemon  half-dead：降级批量，
                        // 先 cancel daemon 侧已开手势（防残留按住），up 时走原子。
                        val (ok, _) = injectMovePriv(x, y)
                        if (!ok) {
                            p.realtime = false
                            runCatching { cancelPriv() }
                        }
                    }
                    reply(conn, true, type, null)
                }
            }
            else -> {
                val p = pendingGestures.remove(conn)
                if (p == null) {
                    reply(conn, false, type, "up without active down")
                } else if (p.realtime) {
                    // 实时抬起：结束 daemon 侧手势（跟手已在 move 中生效）。
                    val (ok, err) = injectUpPriv(x, y)
                    if (!ok) {
                        // up  miss 极罕见（daemon 在 move 还活）：cancel 解卡后
                        // 回退原子兜底，保证本次手势必有一次生效。
                        runCatching { cancelPriv() }
                        val (ok2, err2) = if (!p.moved) {
                            injectTapPriv(p.x0, p.y0)
                        } else {
                            injectDragPriv(p.x0, p.y0, p.lastX, p.lastY)
                        }
                        reply(conn, ok2, type, err2)
                    } else {
                        reply(conn, true, type, null)
                    }
                } else {
                    val (ok, err) = if (!p.moved) {
                        injectTapPriv(p.x0, p.y0)
                    } else {
                        injectDragPriv(p.x0, p.y0, p.lastX, p.lastY)
                    }
                    reply(conn, ok, type, err)
                }
            }
        }
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
                val (ok, err) = injectKeyPriv(TouchInjector.MOUSE_BUTTON_RIGHT_KEYCODE)
                reply(conn, ok, "click", err)
            }
            "middle" -> {
                val (ok, err) = injectKeyPriv(TouchInjector.MOUSE_BUTTON_MIDDLE_KEYCODE)
                reply(conn, ok, "click", err)
            }
            else -> {
                // 左键点按 = down + up（无拖拽的轻量点击路径）。
                val x = json.optDouble("x", Double.NaN).toFloat()
                val y = json.optDouble("y", Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) {
                    reply(conn, false, "click", "missing x|y")
                    return
                }
                val (ok, err) = injectTapPriv(x, y)
                reply(conn, ok, "click", err)
            }
        }
    }

    // ------------------------------------------------------------------
    // 特权注入委托（App 进程无 INJECT_EVENTS，一律按次绑定走特权进程；
    // 连接池线程上 runBlocking，桥内已切 IO，无死锁）。
    // ------------------------------------------------------------------

    private fun pkg(): String = BuildConfig.APPLICATION_ID

    private fun injectTapPriv(x: Float, y: Float): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectTap(pkg(), x, y) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun injectDragPriv(x0: Float, y0: Float, x1: Float, y1: Float): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectDrag(pkg(), x0, y0, x1, y1) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun injectKeyPriv(keyCode: Int): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectKey(pkg(), keyCode) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun injectTextPriv(text: String): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectText(pkg(), text) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    // Smooth-1 实时三件套委托（常驻 daemon 直透，无单次/Shizuku 回退；
    // 失败由 handleTouch 降级批量 + up 原子兜底）。
    private fun injectDownPriv(x: Float, y: Float): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectDown(pkg(), x, y) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun injectMovePriv(x: Float, y: Float): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectMove(pkg(), x, y) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun injectUpPriv(x: Float, y: Float): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.injectUp(pkg(), x, y) } }
            .getOrElse { false to (it.message ?: it.toString()) }

    private fun cancelPriv(): Pair<Boolean, String?> =
        runCatching { runBlocking { PrivilegedBridge.cancelInput() } }
            .getOrElse { true to null }

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
