package com.erl.blindcast.core.server.routes

import com.erl.blindcast.BuildConfig
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.priv.DesktopController
import com.erl.blindcast.core.priv.DesktopWindowController
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

    /** `KeyEvent.KEYCODE_PASTE`（API 30+）：Unicode 文本经「设剪贴板 + 粘贴」注入。 */
    private const val KEYCODE_PASTE = 279

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
        var wid: Int = 0,
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
                        val (ok, err) = injectKeyPriv(keyCode, json.optInt("wid", 0))
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
                    val (ok, err) = injectTextPriv(text, json.optInt("wid", 0))
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
            // Request-Sync-1：客户端解码出错 / 丢过帧后要一个关键帧。
            // wid=0（缺省）= 整屏桌面源；wid>0 = 对应窗口源。
            "requestIDR", "requestSync", "request-sync" -> {
                val wid = json.optInt("wid", 0)
                runCatching { StreamWsRoute.requestSync(wid) }
                // 客户端显式要帧：静态窗口的编码器产不出新帧，必须重发缓存关键帧，否则永久黑窗。
                if (wid > 0) runCatching { StreamWsRoute.resendWindowKeyFrame(wid) }
                reply(conn, true, "requestIDR", null)
            }
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
        val wid = json.optInt("wid", 0)
        if (!x.isFinite() || !y.isFinite()) {
            reply(conn, false, type, "missing x|y")
            return
        }
        when (type) {
            "down" -> {
                val p = PendingTouch(x, y, x, y, wid = wid)
                pendingGestures[conn] = p
                // 实时优先：daemon down 透传（~数十 ms），成了后 move/up 直透跟手；
                // 失败则 realtime=false，up 时回退原子 tap/drag（老路兜底）。
                // down ack 恒 true（已缓存；实时 best-effort，不阻塞前端手势流）。
                val (ok, _) = injectDownPriv(x, y, wid)
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
                        val (ok, _) = injectMovePriv(x, y, p.wid)
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
                    val (ok, err) = injectUpPriv(x, y, p.wid)
                    if (!ok) {
                        // up  miss 极罕见（daemon 在 move 还活）：cancel 解卡后
                        // 回退原子兜底，保证本次手势必有一次生效。
                        runCatching { cancelPriv() }
                        val (ok2, err2) = if (!p.moved) {
                            injectTapPriv(p.x0, p.y0, p.wid)
                        } else {
                            injectDragPriv(p.x0, p.y0, p.lastX, p.lastY, p.wid)
                        }
                        reply(conn, ok2, type, err2)
                    } else {
                        reply(conn, true, type, null)
                    }
                } else {
                    val (ok, err) = if (!p.moved) {
                        injectTapPriv(p.x0, p.y0, p.wid)
                    } else {
                        injectDragPriv(p.x0, p.y0, p.lastX, p.lastY, p.wid)
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
        val wid = json.optInt("wid", 0)
        when (json.optString("button", "left")) {
            "right" -> {
                if (!ScrcpyGate.isRightBackEnabled) {
                    reply(conn, false, "click", "right-back disabled")
                    return
                }
                val (ok, err) = injectKeyPriv(TouchInjector.MOUSE_BUTTON_RIGHT_KEYCODE, wid)
                reply(conn, ok, "click", err)
            }
            "middle" -> {
                // 逐窗口（wid>0）：该窗口是独立虚拟屏上的单个应用，没有自己的 Home，
                // 注入 KEYCODE_HOME 只会把该屏清空——语义上等价于「回桌面」，
                // 客户端最小化窗口即可看到别的窗口，故这里直接注入到该窗口的屏。
                if (wid > 0) {
                    val (ok, err) = injectKeyPriv(TouchInjector.MOUSE_BUTTON_MIDDLE_KEYCODE, wid)
                    reply(conn, ok, "click", err)
                    return
                }
                // 整屏桌面源下的「Home」**必须显式启动我们自己的 Home**，不能注入 KEYCODE_HOME：
                // 真机实证（displayId 219）KEYCODE_HOME 落在副屏也不会拉起 FusionHome
                // （ROM 自带 SecondaryDisplayLauncher 抢注），且系统级 home 解析有回落到
                // 物理主屏的风险。桌面模式 → `am start -W --display <id>` 显式拉起；
                // 镜像模式 → 维持原 KEYCODE_HOME 注入（display 0）。
                val dst = runCatching { DesktopController.status() }.getOrNull()
                if (dst != null && dst.running && dst.displayId > 0) {
                    val pkgName = pkg()
                    val apk = runCatching { PowerController.resolveApkPath(pkgName) }.getOrNull().orEmpty()
                    val res = runCatching { runBlocking { DesktopController.home(pkgName, apk) } }
                        .getOrElse { dst.copy(error = it.message ?: it.toString()) }
                    android.util.Log.i(
                        "BlindCast",
                        "[ControlWs] desktop home explicit did=${dst.displayId} ok=${res.error.isBlank()} " +
                            "err=${res.error.take(120)}",
                    )
                    reply(conn, res.error.isBlank(), "click", res.error.ifBlank { null })
                } else {
                    val (ok, err) = injectKeyPriv(TouchInjector.MOUSE_BUTTON_MIDDLE_KEYCODE, 0)
                    reply(conn, ok, "click", err)
                }
            }
            else -> {
                // 左键点按 = down + up（无拖拽的轻量点击路径）。
                val x = json.optDouble("x", Double.NaN).toFloat()
                val y = json.optDouble("y", Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) {
                    reply(conn, false, "click", "missing x|y")
                    return
                }
                val (ok, err) = injectTapPriv(x, y, wid)
                reply(conn, ok, "click", err)
            }
        }
    }

    // ------------------------------------------------------------------
    // 特权注入委托（App 进程无 INJECT_EVENTS，一律按次绑定走特权进程；
    // 连接池线程上 runBlocking，桥内已切 IO，无死锁）。
    // ------------------------------------------------------------------

    private fun pkg(): String = BuildConfig.APPLICATION_ID

    /**
     * 当前注入目标屏 `(displayId, width, height)`。
     * 桌面源 = VDM 虚拟屏（必须带其真实尺寸：虚拟屏不一定进 IWindowManager 的
     * display 列表，特权侧解析不到）；镜像源 = 物理主屏 0，同样**显式带物理尺寸**。
     * **每次注入都重取**，故模式切换后下一条指令即落到新目标屏。
     *
     * 真机实证（R5）：镜像源若把尺寸留成 0（size=auto），实时三件套的 `down` 会直接失败
     * `TouchInjector: unknown display size`（桌面会话把特权侧 targetDisplay 改成虚拟屏后，
     * 回到 display 0 没人再 configure 过）。故这里对物理屏取真实像素尺寸一并下发。
     */
    private fun targetDisplay(wid: Int = 0): Triple<Int, Int, Int> {
        // 逐窗口优先：windowId → 该窗口自己的虚拟屏 + 自己的尺寸。
        // **fail-closed**：wid>0 时若该窗口不存在/未就绪，返回哨兵 `(-1,-1,-1)`，
        // 由各注入函数直接判失败 —— 绝不回落到整屏桌面/镜像（否则一次点击会落到别的
        // 窗口或物理主屏，正是「逐窗口输入隔离」要禁止的）。did<0 即哨兵（did==0 是
        // 合法的物理镜像屏，不能用 <=0 判）。
        if (wid > 0) {
            val did = runCatching { DesktopWindowController.displayIdOf(wid) }.getOrDefault(-1)
            val size = runCatching { DesktopWindowController.sizeOf(wid) }.getOrNull()
            if (did > 0 && size != null && size.first > 0 && size.second > 0) {
                return Triple(did, size.first, size.second)
            }
            return Triple(-1, -1, -1)
        }
        val st = runCatching { com.erl.blindcast.core.priv.DesktopController.status() }.getOrNull()
        if (st != null && st.running && st.displayId > 0) {
            return Triple(st.displayId, st.width, st.height)
        }
        val (w, h) = physicalSize()
        return Triple(0, w, h)
    }

    /** 物理主屏真实像素尺寸（app 进程可读，无需特权）；失败回落 0 交特权侧自解析。 */
    private fun physicalSize(): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        cachedPhysicalSize?.let { if (now - cachedPhysicalAt < 60_000) return it }
        val p = runCatching {
            val ctx = runCatching { com.erl.blindcast.blindCastApp.applicationContext }.getOrNull()
                ?: return@runCatching null
            val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
            val d = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return@runCatching null
            val pt = android.graphics.Point()
            d.getRealSize(pt)
            if (pt.x > 0 && pt.y > 0) pt.x to pt.y else null
        }.getOrNull()
        if (p != null) {
            cachedPhysicalSize = p
            cachedPhysicalAt = now
        }
        return p ?: (0 to 0)
    }

    @Volatile private var cachedPhysicalSize: Pair<Int, Int>? = null

    @Volatile private var cachedPhysicalAt: Long = 0L

    /**
     * 最近一次实际注入落到的屏：解卡 cancel 必须打在同一屏，
     * 否则“桌面手势中断”会往物理主屏补一发 Up（错屏残留触点）。
     */
    @Volatile private var lastInjectDisplayId: Int = 0

    /**
     * 采集源切换（桌面 ↔ 镜像）时调用：丢弃全部待决手势并让特权侧在**原目标屏**补 Up 解卡，
     * 防上一源的按下状态残留到新目标屏；切回镜像后目标屏语义回到 display 0。
     */
    fun resetForSourceSwitch(reason: String) {
        val pending = pendingGestures.size
        if (pending > 0) {
            android.util.Log.i("BlindCast", "[ControlWs] source switch ($reason): drop $pending pending gesture(s)")
        }
        pendingGestures.clear()
        runCatching { TouchInjector.cancelTouch() }
        val (ok, err) = runCatching {
            runBlocking { PrivilegedBridge.cancelInput(lastInjectDisplayId) }
        }.getOrElse { true to (it.message ?: it.toString()) }
        android.util.Log.i(
            "BlindCast",
            "[ControlWs] source switch ($reason) cancel display=$lastInjectDisplayId ok=$ok err=${err?.take(120)}",
        )
        lastInjectDisplayId = 0
    }

    /**
     * 注入证据行：每条注入都打印**实际目标屏**与来源尺寸，供真机验收核对
     * 「指令落在 displayId 几」而不是靠猜测。tag 固定 `BlindCast`。
     */
    private fun logInject(kind: String, did: Int, w: Int, h: Int, ok: Boolean, err: String?, detail: String = "") {
        val size = if (w > 0 && h > 0) "size=${w}x$h" else "size=auto"
        android.util.Log.i(
            "BlindCast",
            "[ControlWs] inject kind=$kind did=$did $size ok=$ok${if (detail.isEmpty()) "" else " $detail"} err=${err?.take(120)}",
        )
    }

    private fun injectTapPriv(x: Float, y: Float, wid: Int = 0): Pair<Boolean, String?> {
        val (did, w, h) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectTap(pkg(), x, y, did, w, h) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("tap", did, w, h, r.first, r.second, detail = "wid=$wid x=$x y=$y")
        return r
    }

    private fun injectDragPriv(x0: Float, y0: Float, x1: Float, y1: Float, wid: Int = 0): Pair<Boolean, String?> {
        val (did, w, h) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectDrag(pkg(), x0, y0, x1, y1, did, w, h) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("drag", did, w, h, r.first, r.second, detail = "wid=$wid from=$x0,$y0 to=$x1,$y1")
        return r
    }

    private fun injectKeyPriv(keyCode: Int, wid: Int = 0): Pair<Boolean, String?> {
        val (did, _, _) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectKey(pkg(), keyCode, did) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("key", did, 0, 0, r.first, r.second, detail = "wid=$wid keycode=$keyCode")
        return r
    }

    private fun injectTextPriv(text: String, wid: Int = 0): Pair<Boolean, String?> {
        val (did, _, _) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        // Virtual editors accept Unicode directly through their display-specific IME.
        // Keep key mapping only for phone mirroring, which uses the user's own IME.
        val asciiOnly = text.all { c -> c.code in 32..126 }
        val r = if (did > 0) {
            com.erl.blindcast.core.input.FusionInputMethodService.commit(text, did)
        } else if (asciiOnly) {
            runCatching { runBlocking { PrivilegedBridge.injectText(pkg(), text, did) } }
                .getOrElse { false to (it.message ?: it.toString()) }
        } else {
            injectUnicodeViaPaste(text, did)
        }
        logInject("text", did, 0, 0, r.first, r.second, detail = "wid=$wid len=${text.length} ascii=$asciiOnly")
        return r
    }

    /**
     * Unicode 文本注入：临时把设备剪贴板设为 [text] → 向目标屏注入 `KEYCODE_PASTE`(279) → 还原剪贴板。
     *
     * 为什么不能只靠虚拟键盘映射：`KeyCharacterMap.VIRTUAL_KEYBOARD` 无法产出 CJK/全角标点
     * （[com.erl.blindcast.core.scrcpy.TouchInjector.injectText] 对不可映射字符记错跳过）。
     * 「设剪贴板 + 粘贴」是可注入任意 Unicode 的通行做法（scrcpy 同类）。粘贴后尽力还原原剪贴板。
     */
    private fun injectUnicodeViaPaste(text: String, did: Int): Pair<Boolean, String?> {
        val ctx = runCatching { com.erl.blindcast.blindCastApp.applicationContext }.getOrNull()
            ?: return false to "no context"
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false to "no clipboard service"
        // 尽力保存原剪贴板文本（后台读取可能受限，拿不到就不还原）。
        val prev = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()
        val setOk = runCatching {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("BlindCast", text)); true
        }.getOrDefault(false)
        if (!setOk) return false to "setPrimaryClip failed"
        try {
            Thread.sleep(80)
            return runCatching { runBlocking { PrivilegedBridge.injectKey(pkg(), KEYCODE_PASTE, did) } }
                .getOrElse { false to (it.message ?: it.toString()) }
        } finally {
            if (prev != null) {
                runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("BlindCast", prev)) }
            }
        }
    }

    // Smooth-1 实时三件套委托（常驻 daemon 直透，无单次/Shizuku 回退；
    // 失败由 handleTouch 降级批量 + up 原子兜底）。
    private fun injectDownPriv(x: Float, y: Float, wid: Int = 0): Pair<Boolean, String?> {
        val (did, w, h) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectDown(pkg(), x, y, did, w, h) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("down", did, w, h, r.first, r.second, detail = "wid=$wid x=$x y=$y")
        return r
    }

    private fun injectMovePriv(x: Float, y: Float, wid: Int = 0): Pair<Boolean, String?> {
        val (did, w, h) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectMove(pkg(), x, y, did, w, h) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("move", did, w, h, r.first, r.second, detail = "wid=$wid x=$x y=$y")
        return r
    }

    private fun injectUpPriv(x: Float, y: Float, wid: Int = 0): Pair<Boolean, String?> {
        val (did, w, h) = targetDisplay(wid)
        lastInjectDisplayId = did
        if (did < 0) {
            logInject("fail-closed", did, 0, 0, false, "window $wid not ready", detail = "wid=$wid")
            return false to "window $wid not ready"
        }
        val r = runCatching { runBlocking { PrivilegedBridge.injectUp(pkg(), x, y, did, w, h) } }
            .getOrElse { false to (it.message ?: it.toString()) }
        logInject("up", did, w, h, r.first, r.second, detail = "wid=$wid x=$x y=$y")
        return r
    }

    private fun cancelPriv(): Pair<Boolean, String?> {
        val r = runCatching { runBlocking { PrivilegedBridge.cancelInput(lastInjectDisplayId) } }
            .getOrElse { true to null }
        logInject("cancel", lastInjectDisplayId, 0, 0, r.first, r.second)
        return r
    }

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
