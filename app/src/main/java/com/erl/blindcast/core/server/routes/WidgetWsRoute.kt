package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.widget.WidgetHostManager
import com.erl.blindcast.core.widget.WidgetRenderer
import com.erl.blindcast.core.widget.WidgetStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 桌面 widget WebSocket 路由（Desktop-Widget-1 · `GET /ws/widgets` 升级后落点）。
 *
 * ## 这是什么
 * 把手机上的桌面小部件渲染成 WebP 静帧推到浏览器，并接受浏览器回传的触摸/滚轮注入。
 * 与 `/ws/stream`（屏幕镜像）是**两条独立通道**：widget 更新频率低（时钟/天气/音乐控件），
 * 静帧 + 帧确认比视频流省带宽；且注入走同进程 `dispatchTouchEvent`，
 * **不需要 Shizuku/Root**（与镜像通道的 `InputManager.injectInputEvent` 不同）。
 *
 * ## 线路协议
 * 下行二进制帧：`[4B widgetId 大端][WebP 字节流]`（一帧一个 widget，非拼图——
 * 逆向样本把所有 widget 平铺成一张大图，本实现改为按 widget 单独成帧，
 * 前端按坐标自行排布，尺寸变化时只重传受影响的那一个）。
 *
 * 下行文本帧（JSON）：
 * - `{"type":"widget-state","density":int,"widgets":[{"id","label","provider","stale","active","w","h"}]}`
 *   连上即下发一次，`widget-list` 可再次索取；
 *   `w`/`h`：本会话已声明的用会话尺寸，未声明的用该 provider 的**自然尺寸**
 *   （前端据此定默认盒子大小，避免给 105x105 的部件套一个 320x200 的空壳）；
 * - `{"type":"ack","ok":bool,"for":"<命令>","error":"..."}` 逐条回执；
 * - `{"type":"pong"}` 心跳回执。
 *
 * 上行文本帧（JSON，`type` 判别）：
 * - `widget-list` —— 索取已绑定 widget 清单；
 * - `widget-sync` `{"density":int,"widgets":[{"id","w","h"}]}` —— 声明本会话要显示的
 *   widget 与像素尺寸（`density` 缺省 160，clamp 80..960；`w`/`h` clamp 1..2048）；
 * - `widget-size` `{"id","w","h"}` —— 改单个尺寸；
 * - `widget-ack` `{"id"}` —— 帧确认（**背压**：一 ack 一帧，未确认不再推该 widget）；
 * - `widget-input` `{"id","x","y","a":"down|move|up|cancel","t":long}` 触摸 /
 *   `{"id","x","y","k":"scroll","dx","dy"}` 滚轮（`x`/`y` 为相对该 widget 左上角的像素，
 *   `t` 为**相对本次 `down` 的毫秒偏移**，服务端据此拼出同一手势窗口的 downTime/eventTime）；
 * - `widget-hide` `{"id"}` —— 从本会话移除（保留手机侧绑定）；
 * - `widget-unbind` `{"id"}` —— 解绑并从手机移除（调 `deleteAppWidgetId`）；
 * - `ping` —— 心跳。
 *
 * ## 线程模型
 * - 每会话独占一个连接池线程跑读循环（解析命令 + 同步注入 + 回执）；
 * - 每会话另起一条 daemon 渲染泵（[PASS_INTERVAL_MS] 周期），只推**非在途**且
 *   **内容有变化**的 widget（变化检测在 [WidgetRenderer.render] 内用 `Bitmap.sameAs`）；
 * - 渲染泵与读循环只通过 [Session] 的并发容器交互，不加长锁（注入含主线程往返，
 *   持锁会卡住渲染泵）。
 */
object WidgetWsRoute {

    private const val TAG = "BlindCast-WidgetWs"

    /** 渲染泵周期：widget 内容低频，200ms 足够；变化检测兜住空转开销。 */
    private const val PASS_INTERVAL_MS = 200L

    /** 密度 clamp 区间（对齐逆向样本 `RunnableC0102z.java:330`）。 */
    private const val MIN_DENSITY = 80
    private const val MAX_DENSITY = 960

    /** 单边像素上限（对齐逆向样本 `C0137k.java:122-124`）。 */
    private const val MIN_DIMEN = 1
    private const val MAX_DIMEN = 2048

    /** 单次手势最长时长（前端 `t` 的 clamp 上界，防呆值）。 */
    private const val GESTURE_MAX_MS = 60_000L

    private val sessions = CopyOnWriteArraySet<WsConnection>()

    /** 每会话的期望 widget 集合与在途状态。 */
    private class Session(val conn: WsConnection) {
        /** id -> [w, h]（像素）。 */
        val wanted = ConcurrentHashMap<Int, IntArray>()

        /** 已下发未确认的 widget（背压门闩）。 */
        val inFlight: MutableSet<Int> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

        /** 进行中手势的 downTime（id -> uptime），`up`/`cancel` 时清除。 */
        val gestures = ConcurrentHashMap<Int, Long>()

        /** 目标密度（dpi），前端可按自身 DPR 指定。 */
        @Volatile
        var density: Int = 160

        @Volatile
        var running: Boolean = true
    }

    /** 当前 widget 在线数（供 `/api/status` 与主页绑定）。 */
    val sessionCount: Int get() = sessions.size

    /**
     * 接管一条已升级连接（阻塞直到对端关闭 / 出错 / 服务停止）。
     * 调用方为 [BlindCastServer] 连接池线程；返回后连接已关闭。
     */
    fun handle(conn: WsConnection) {
        val session = Session(conn)
        sessions.add(conn)
        val pump = Thread({ pumpLoop(session) }, "BlindCast-WidgetPump").apply { isDaemon = true }
        try {
            pump.start()
            pushState(session)
            while (conn.isOpen && session.running) {
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
                dispatch(session, frame.text())
            }
        } finally {
            session.running = false
            pump.interrupt()
            sessions.remove(conn)
            runCatching { conn.close() }
        }
    }

    /** 停服时调用：踢掉全部 widget 会话（幂等）。 */
    fun shutdown() {
        for (s in sessions) runCatching { s.close(1001, "server stopping") }
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // 命令分发（读循环线程）
    // ------------------------------------------------------------------

    private fun dispatch(session: Session, raw: String) {
        val conn = session.conn
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json == null) {
            reply(conn, false, null, "bad_json")
            return
        }
        when (json.optString("type", "")) {
            "widget-list" -> {
                pushState(session)
                reply(conn, true, "widget-list", null)
            }
            "widget-sync" -> handleSync(session, json)
            "widget-size" -> handleSize(session, json)
            "widget-ack" -> {
                val id = json.optInt("id", -1)
                session.inFlight.remove(id)
                reply(conn, true, "widget-ack", null)
            }
            "widget-input" -> handleInput(session, json)
            "widget-hide" -> {
                val id = json.optInt("id", -1)
                session.wanted.remove(id)
                session.inFlight.remove(id)
                reply(conn, true, "widget-hide", null)
            }
            "widget-unbind" -> handleUnbind(session, json)
            "ping" -> replyRaw(conn, """{"type":"pong","ok":true}""")
            else -> reply(conn, false, null, "unknown type")
        }
    }

    private fun handleSync(session: Session, json: JSONObject) {
        val density = clamp(json.optInt("density", 160), MIN_DENSITY, MAX_DENSITY)
        session.density = density
        val arr = json.optJSONArray("widgets")
        if (arr == null) {
            reply(session.conn, false, "widget-sync", "missing widgets")
            return
        }
        val bound = boundIdSet()
        val next = LinkedHashMap<Int, IntArray>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id < 0) continue
            if (id !in bound) continue // 未绑定到本 host 的 id 一律忽略（防越权枚举）
            val natural = WidgetRenderer.naturalSizePx(id, density)
            val w = clamp(o.optInt("w", natural?.first ?: 320), MIN_DIMEN, MAX_DIMEN)
            val h = clamp(o.optInt("h", natural?.second ?: 160), MIN_DIMEN, MAX_DIMEN)
            next[id] = intArrayOf(w, h)
        }
        val dropped = session.wanted.keys.filter { !next.containsKey(it) }
        session.wanted.clear()
        session.wanted.putAll(next)
        // 本会话不再显示的 widget：释放离屏视图与位图（手机侧绑定保留，`widget-unbind` 才解绑）。
        for (id in dropped) {
            session.inFlight.remove(id)
            session.gestures.remove(id)
            WidgetRenderer.release(id)
        }
        reply(session.conn, true, "widget-sync", null)
    }

    private fun handleSize(session: Session, json: JSONObject) {
        val id = json.optInt("id", -1)
        val current = session.wanted[id]
        if (current == null) {
            reply(session.conn, false, "widget-size", "widget not in session")
            return
        }
        val w = clamp(json.optInt("w", current[0]), MIN_DIMEN, MAX_DIMEN)
        val h = clamp(json.optInt("h", current[1]), MIN_DIMEN, MAX_DIMEN)
        session.wanted[id] = intArrayOf(w, h)
        reply(session.conn, true, "widget-size", null)
    }

    private fun handleInput(session: Session, json: JSONObject) {
        val id = json.optInt("id", -1)
        if (!session.wanted.containsKey(id)) {
            reply(session.conn, false, "widget-input", "widget not in session")
            return
        }
        val x = json.optDouble("x", Double.NaN).toFloat()
        val y = json.optDouble("y", Double.NaN).toFloat()
        if (!x.isFinite() || !y.isFinite()) {
            reply(session.conn, false, "widget-input", "missing x|y")
            return
        }
        val kind = json.optString("k", "")
        if (kind == "scroll") {
            val dx = json.optDouble("dx", 0.0).toFloat()
            val dy = json.optDouble("dy", 0.0).toFloat()
            val now = android.os.SystemClock.uptimeMillis()
            val ok = WidgetRenderer.dispatchScroll(id, x, y, dx, dy, now, now)
            reply(session.conn, ok, "widget-input", if (ok) null else "scroll not consumed")
            return
        }
        val action = when (json.optString("a", "")) {
            "down" -> android.view.MotionEvent.ACTION_DOWN
            "up" -> android.view.MotionEvent.ACTION_UP
            "cancel" -> android.view.MotionEvent.ACTION_CANCEL
            "move" -> android.view.MotionEvent.ACTION_MOVE
            else -> {
                reply(session.conn, false, "widget-input", "bad action")
                return
            }
        }
        // 时间轴：`t` 是相对本次手势 `down` 的毫秒偏移（前端用 performance.now() 差值），
        // 服务端把 down 那一刻的 uptime 记成 downTime，保证 down/move/up 落在同一手势窗口内，
        // 且不受两端时钟差影响（逆向样本同构：`RunnableC0102z.java:462-486`）。
        val now = android.os.SystemClock.uptimeMillis()
        val t = json.optLong("t", 0L).coerceIn(0L, GESTURE_MAX_MS)
        val downTime: Long
        val eventTime: Long
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            session.gestures[id] = now
            downTime = now
            eventTime = now
        } else {
            val start = session.gestures[id] ?: (now - t).coerceAtLeast(0L)
            downTime = start
            eventTime = start + t
        }
        if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            session.gestures.remove(id)
        }
        val ok = WidgetRenderer.dispatchTouch(id, action, x, y, downTime, eventTime)
        reply(session.conn, ok, "widget-input", if (ok) null else "not consumed")
    }

    private fun handleUnbind(session: Session, json: JSONObject) {
        val id = json.optInt("id", -1)
        if (id < 0) {
            reply(session.conn, false, "widget-unbind", "missing id")
            return
        }
        session.wanted.remove(id)
        session.inFlight.remove(id)
        WidgetRenderer.release(id)
        WidgetHostManager.delete(id)
        WidgetStore.remove(id)
        reply(session.conn, true, "widget-unbind", null)
        pushState(session)
    }

    // ------------------------------------------------------------------
    // 渲染泵（每会话一条 daemon 线程）
    // ------------------------------------------------------------------

    private fun pumpLoop(session: Session) {
        while (session.running && session.conn.isOpen) {
            runCatching { renderPass(session) }
                .onFailure { Log.d(TAG, "renderPass failed: ${it.message}") }
            try {
                Thread.sleep(PASS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun renderPass(session: Session) {
        val conn = session.conn
        for ((id, size) in session.wanted) {
            if (!conn.isOpen || !session.running) return
            if (id in session.inFlight) continue
            val w = size[0]
            val h = size[1]
            if (!WidgetRenderer.ensure(id, w, h, session.density)) continue
            val webp = WidgetRenderer.render(id) ?: continue
            sendFrame(conn, id, webp)
            session.inFlight.add(id)
        }
    }

    private fun sendFrame(conn: WsConnection, id: Int, webp: ByteArray) {
        val out = ByteArray(4 + webp.size)
        out[0] = ((id ushr 24) and 0xFF).toByte()
        out[1] = ((id ushr 16) and 0xFF).toByte()
        out[2] = ((id ushr 8) and 0xFF).toByte()
        out[3] = (id and 0xFF).toByte()
        webp.copyInto(out, 4)
        conn.sendBinary(out)
    }

    // ------------------------------------------------------------------
    // 状态下发
    // ------------------------------------------------------------------

    private fun pushState(session: Session) {
        val conn = session.conn
        runCatching { conn.sendText(buildState(session)) }
            .onFailure { Log.d(TAG, "pushState failed: ${it.message}") }
    }

    private fun buildState(session: Session): String {
        val arr = JSONArray()
        for (id in boundIdList()) {
            val record = WidgetStore.find(id)
            val info = WidgetHostManager.providerInfo(id)
            val label = WidgetHostManager.labelOf(info).ifEmpty { record?.label.orEmpty() }
            val spec = session.wanted[id]
            // 未声明的 widget 报 provider 的自然尺寸，前端拿它当默认盒子大小——
            // 否则前端只能瞎猜一个（实测 320x200 对 105x105 的部件会空掉一大片）。
            val natural = if (spec == null) WidgetRenderer.naturalSizePx(id, session.density) else null
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("provider", record?.provider ?: WidgetHostManager.componentString(info))
                    .put("stale", info == null)
                    .put("active", spec != null)
                    .put("w", spec?.get(0) ?: natural?.first ?: 0)
                    .put("h", spec?.get(1) ?: natural?.second ?: 0),
            )
        }
        return JSONObject()
            .put("type", "widget-state")
            .put("density", session.density)
            .put("widgets", arr)
            .toString()
    }

    /** 本 host 已绑定的 id 列表（以 `AppWidgetHost.getAppWidgetIds()` 为准）。 */
    private fun boundIdList(): List<Int> {
        val host = WidgetHostManager.hostOrNull() ?: return emptyList()
        return runCatching { host.appWidgetIds?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    private fun boundIdSet(): Set<Int> = boundIdList().toHashSet()

    // ------------------------------------------------------------------

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

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
