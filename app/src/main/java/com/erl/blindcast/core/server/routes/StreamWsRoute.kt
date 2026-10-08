package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.CaptureSocketLink
import com.erl.blindcast.core.scrcpy.JpegTranscoder
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 推流 WebSocket 路由（Slice 4.1 · `GET /ws/stream` 升级后落点；
 * Universal-1 起加 `0x03` JPEG 降级通道，与 H264 共存）。
 *
 * ## 线路协议（二进制帧 = 1 字节通道头 + 负载，4.2 `player.js` 按此分流）
 * - `0x01` 视频：H.264 Annex-B NALU（首包必为附带 SPS/PPS 的 IDR，
 *   由 [ScreenCaptureEngine] 门闩保证，Web 端 `VideoDecoder` 首包即初始化）；
 * - `0x02` 音频：AAC 裸帧（`audio/mp4a-latm`，无 ADTS；
 *   [AudioCaptureEngine] 在 config 就绪前丢帧，对外包均可独立解码）；
 * - `0x03` 图片：JPEG 单帧（Universal-1 · 裸浏览器无 WebCodecs 降级用；
 *   线格式 `[1字节0x03+4字节大端长+JPEG]` 沿用既有帧格式，前端剥 4 字节长后
 *   `createImageBitmap→drawImage`，Canvas 照常；H264 老路不动）；
 * - 建连先下一条文本 `{"type":"hello",...}` 自描述（mime + 通道头取值），
 *   之后只下发二进制；上行内容忽略（Ping 由 [WsConnection] 自动回 Pong）。
 *
 * ## JPEG 按需（省电）
 * - [JpegTranscoder.onJpeg] 在本对象 init 即注册到 [broadcastJpeg]；
 *   解码链仅当有 JPEG 订阅者（control `videoMode=jpeg`）时跑，无订阅停链，
 *   H264 泵不受影响（切回 h264 路不断）。
 *
 * ## 线程模型
 * - 每会话的 [handle] 独占一个连接池线程做读循环（只为消费 Ping/Close，心跳保活）；
 * - 音视频各一条 daemon 广播泵线程（`tryReceive` 轮询 + 2ms 退避，无会话时 50ms 空转，
 *   停服即停，不 close 引擎的复用 Channel）；泵只向各会话**有界队列**入队，永不阻塞；
 * - 每会话一条 daemon 写线程（`BlindCast-WsSession`）独占该 socket 的发送，队列满丢最旧，
 *   慢客户端只丢自己的帧；发送失败即摘除 + 关闭，永不影响其他客户端；
 * - 会话集合 `CopyOnWriteArraySet`；
 * - 仅做推流封装：不启动采集（采集启停归前台 Service，Slice 6.1）。
 */
object StreamWsRoute {

    private const val TAG = "BlindCast-StreamWs"

    /** 视频通道头（H.264 Annex-B NALU）。 */
    const val KIND_VIDEO: Byte = 0x01

    /** 音频通道头（AAC 裸帧）。 */
    const val KIND_AUDIO: Byte = 0x02

    /**
     * JPEG 通道头（Universal-1 · 无 WebCodecs 降级，`[0x03+4字节大端长+JPEG]`）。
     * 前端 `typeof VideoDecoder==="undefined"` 时订阅本通道，
     * 有硬解时走老 `0x01` 路不动。
     */
    const val KIND_JPEG: Byte = 0x03

    /**
     * 逐窗口视频通道头（Win-Stream-1）。线格式
     * `[0x11][1 字节 windowId][H.264 Annex-B…]`：每个应用窗口一路独立虚拟显示/编码会话，
     * 在**同一条** `/ws/stream` 上按 windowId 多路复用；前端每窗口一个 `VideoDecoder` 槽。
     */
    const val KIND_WINDOW_VIDEO: Byte = 0x11

    /** 无包时轮询退避 2ms（单包额外延迟可忽略）。 */
    private const val POLL_IDLE_MS = 2L

    /** 零会话时空转步长 50ms（采集侧 Channel 自带 64/128 缓冲丢最旧，不堆积）。 */
    private const val NO_SESSION_IDLE_MS = 50L

    /** 单会话视频队列容量：满则丢最旧（实时优先，重同步靠下一个关键帧）。 */
    private const val VIDEO_QUEUE_CAP = 8

    /** 单会话音频队列容量：一帧 <100ms，留 32 帧余量。 */
    private const val AUDIO_QUEUE_CAP = 32

    /** 单会话 JPEG 队列容量：只留最新一帧（旧的整帧已无意义）。 */
    private const val JPEG_QUEUE_CAP = 2

    /** 单会话逐窗口队列容量：多窗并发时留足余量，满则丢最旧。 */
    private const val WINDOW_QUEUE_CAP = 24

    /** 写线程空等上限 1s（只为周期性检查 [Session.closed]，非延迟来源）。 */
    private const val WRITER_WAIT_MS = 1000L

    /**
     * 单会话待发帧：通道头 + 负载（写线程按 kind 走
     * [WsConnection.sendBinaryWithPrefix]）。
     */
    private class QueuedFrame(val kind: Byte, val payload: ByteArray)

    /**
     * 推流会话：自带三条有界队列 + 独立写线程（丢帧保实时，对齐 scrcpy/WebRTC 语义）。
     *
     * 为什么不再由泵线程直接写 socket：泵是全局唯一的，任何一个「连上不读」的慢客户端
     * 都会把 `sendBinaryWithPrefix` 阻塞在那里，所有人一起卡（真机实测 20s 收帧 499→155）。
     * 现在泵只做**不阻塞**的入队，慢客户端只丢自己的帧，且永远拿到最新帧。
     *
     * **视频必须从关键帧起头**：半路接入的客户端若先收到 P 帧，WebCodecs `VideoDecoder`
     * 未 configure 会直接报错，画面恒黑（真机实证：控制台 ● 已连接、streamClients=2、
     * canvas 采样全 0）。故关键帧入队先清空视频队列，天然重同步。
     *
     * 锁约定：本类所有队列/计数只在 [lock] 内读写；[closed]/[videoPrimed] 为 [Volatile]
     * 便于外部无锁速判。写线程在锁外发帧（绝不在持锁时做 IO）。
     */
    private class Session(val conn: WsConnection) {

        // 必须是 java.lang.Object：Kotlin 的 Any 不暴露 wait/notify，而本会话的写线程要一个真监视器。
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        private val lock = java.lang.Object()
        private val videoQueue = ArrayDeque<ByteArray>()
        private val audioQueue = ArrayDeque<ByteArray>()
        private val jpegQueue = ArrayDeque<ByteArray>()

        /**
         * 逐窗口队列：**每窗口一条独立队列**（key = windowId，元素为线负载
         * `[1 字节 windowId][Annex-B]`）。为什么要分开：共用一条队列时，任何一个
         * 窗口积压都会把别的窗口的帧挤掉，破坏「一窗一路流」的隔离性。
         */
        private val winQueues = HashMap<Int, ArrayDeque<ByteArray>>()

        /** 本会话已起过头的 windowId 集合（P 帧不得先于该窗口的 IDR 到达）。 */
        private val winPrimed = HashSet<Int>()

        /** 主画面是否处于「丢到下一个 IDR」态（丢过 delta 后只放关键帧）。 */
        private var videoNeedIdr = false

        /** 处于「丢到下一个 IDR」态的窗口（丢过 delta 后只放关键帧）。 */
        private val winNeedIdr = HashSet<Int>()

        /** 窗口队列轮转游标：上一次取走的 windowId（-1 = 尚未取过）。 */
        private var winCursor = -1

        @Volatile
        var videoPrimed: Boolean = false

        @Volatile
        var closed: Boolean = false
            private set

        /** 因队列满/关键帧重同步丢弃的帧数（只在 [lock] 内自增，日志读取容忍竞态）。 */
        @Volatile
        var drops: Long = 0L
            private set

        /**
         * 入队一帧（不阻塞，只抢锁）。
         *
         * 关键帧入队先清空**本路**待发帧并解除「丢到下一个 IDR」态；
         * 队列满则**清空本路队列 + 进入「丢到下一个 IDR」态**（丢掉的 delta 让依赖链断了，
         * 继续喂后续 delta 只会解出花屏/报错），并回调 [StreamWsRoute.onRequestSync] 要一个新 IDR。
         * 这正是 AndroMeld `RunnableC2868I.java:218-256` 的语义。
         */
        fun enqueue(kind: Byte, payload: ByteArray, isKeyFrame: Boolean = false) {
            var wantSync = -1
            synchronized(lock) {
                if (closed) return
                if (kind == StreamWsRoute.KIND_WINDOW_VIDEO) {
                    if (payload.isEmpty()) return
                    val wid = payload[0].toInt() and 0xFF
                    val q = winQueues.getOrPut(wid) { ArrayDeque() }
                    if (isKeyFrame) {
                        drops += q.size
                        q.clear()
                        winNeedIdr.remove(wid)
                        winPrimed.add(wid)
                    } else {
                        if (winNeedIdr.contains(wid)) return
                        if (q.size >= WINDOW_QUEUE_CAP) {
                            drops += q.size
                            q.clear()
                            winNeedIdr.add(wid)
                            wantSync = wid
                        }
                    }
                    q.addLast(payload)
                } else {
                    val queue = when (kind) {
                        StreamWsRoute.KIND_VIDEO -> videoQueue
                        StreamWsRoute.KIND_AUDIO -> audioQueue
                        else -> jpegQueue
                    }
                    if (kind == StreamWsRoute.KIND_VIDEO) {
                        if (isKeyFrame) {
                            drops += videoQueue.size
                            videoQueue.clear()
                            videoNeedIdr = false
                        } else {
                            if (videoNeedIdr) return
                            if (videoQueue.size >= VIDEO_QUEUE_CAP) {
                                drops += videoQueue.size
                                videoQueue.clear()
                                videoNeedIdr = true
                                wantSync = 0
                            }
                        }
                    } else if (queue.size >= capOf(kind)) {
                        queue.removeFirst()
                        drops++
                    }
                    queue.addLast(payload)
                }
                lock.notifyAll()
            }
            if (wantSync >= 0) StreamWsRoute.notifySyncNeeded(wantSync)
        }

        /** 该窗口是否已在本会话起过头（未起头时泵只补发关键帧）。 */
        fun isWinPrimed(wid: Int): Boolean = synchronized(lock) { winPrimed.contains(wid) }

        /** 标记该窗口已起头。 */
        fun markWinPrimed(wid: Int) {
            synchronized(lock) { winPrimed.add(wid) }
        }

        /** 会话接入补发某窗口的关键帧（清该窗口待发帧 + 入队关键帧 + 标记起头）。 */
        fun primeWindow(wid: Int, keyFrame: ByteArray) {
            synchronized(lock) {
                if (closed) return
                val q = winQueues.getOrPut(wid) { ArrayDeque() }
                q.clear()
                q.addLast(keyFrame)
                winPrimed.add(wid)
                winNeedIdr.remove(wid)
                lock.notifyAll()
            }
        }

        /**
         * 取下一帧：各队皆空且未关闭时在 [lock] 上等（上限 [WRITER_WAIT_MS]）。
         * 优先级 video > window > audio > jpeg；窗口之间**轮转**取（round-robin，游标
         * [winCursor]），避免低 windowId 积压时饿死高 id。
         * 返回 null 表示会话已关闭且队列排空。
         */
        fun poll(): QueuedFrame? {
            synchronized(lock) {
                while (videoQueue.isEmpty() && winQueues.values.all { it.isEmpty() } &&
                    audioQueue.isEmpty() && jpegQueue.isEmpty() && !closed
                ) {
                    try {
                        lock.wait(WRITER_WAIT_MS)
                    } catch (_: InterruptedException) {
                        closed = true
                        break
                    }
                }
                if (videoQueue.isNotEmpty()) return QueuedFrame(StreamWsRoute.KIND_VIDEO, videoQueue.removeFirst())
                val pickWid = pickWindowRoundRobin()
                if (pickWid >= 0) {
                    val q = winQueues[pickWid]!!
                    val f = q.removeFirst()
                    if (q.isEmpty()) winQueues.remove(pickWid)
                    return QueuedFrame(StreamWsRoute.KIND_WINDOW_VIDEO, f)
                }
                if (audioQueue.isNotEmpty()) return QueuedFrame(StreamWsRoute.KIND_AUDIO, audioQueue.removeFirst())
                if (jpegQueue.isNotEmpty()) return QueuedFrame(StreamWsRoute.KIND_JPEG, jpegQueue.removeFirst())
                return null
            }
        }

        /**
         * 轮转取一个非空窗口队列的 wid：从游标之后的第一个非空 id 起找，找不到就回绕到最小的
         * 非空 id。只在 [lock] 内调用。单窗口时恒取该窗口；多窗口时按 id 环形轮流，
         * 任一窗口都不会被别的窗口的积压饿死。
         */
        private fun pickWindowRoundRobin(): Int {
            val ids = winQueues.keys.filter { winQueues[it]?.isNotEmpty() == true }.sorted()
            if (ids.isEmpty()) return -1
            val next = ids.firstOrNull { it > winCursor } ?: ids.first()
            winCursor = next
            return next
        }

        /**
         * 会话接入补发：**未起头**才在锁内入队缓存关键帧（[enqueue] 同锁，
         * 避免与泵的起头路径各自清队、把已排队的 P 帧丢掉造成解码缺口）。
         * @return 是否由本次调用完成起头。
         */
        fun primeWith(keyFrame: ByteArray): Boolean {
            synchronized(lock) {
                if (closed || videoPrimed) return false
                drops += videoQueue.size
                videoQueue.clear()
                videoQueue.addLast(keyFrame)
                videoPrimed = true
                videoNeedIdr = false
                lock.notifyAll()
                return true
            }
        }

        /** 判定会话死亡：置位 + 清队 + 唤醒写线程（幂等，任意线程可调）。 */
        fun kill() {
            synchronized(lock) {
                closed = true
                videoQueue.clear()
                winQueues.clear()
                audioQueue.clear()
                jpegQueue.clear()
                lock.notifyAll()
            }
        }

        private fun capOf(kind: Byte): Int = when (kind) {
            StreamWsRoute.KIND_VIDEO -> VIDEO_QUEUE_CAP
            StreamWsRoute.KIND_AUDIO -> AUDIO_QUEUE_CAP
            StreamWsRoute.KIND_WINDOW_VIDEO -> WINDOW_QUEUE_CAP
            else -> JPEG_QUEUE_CAP
        }
    }

    private val sessions = CopyOnWriteArraySet<Session>()

    /**
     * 同步帧需求回调（Request-Sync-1）：某路（windowId，`0` = 整屏桌面源）需要一个新 IDR。
     *
     * 触发点：① WS 背压进入「丢到下一个 IDR」态；② 新会话接入；③ 客户端上报解码出错。
     * 由 [com.erl.blindcast.core.server.BlindCastServer] 接到
     * [com.erl.blindcast.core.priv.DesktopWindowController.requestSync] /
     * [com.erl.blindcast.core.priv.DesktopController.requestSync] 的 `.sync` 文件信号上。
     * 没有回调（如未接线的测试环境）时是空操作，丢帧行为仍正确，只是恢复要等周期 I 帧。
     */
    @Volatile
    var onRequestSync: ((windowId: Int) -> Unit)? = null

    /** 新会话接入回调（在补发缓存关键帧之后触发，供实现方给各路要一个新 IDR）。 */
    @Volatile
    var onSessionAttached: (() -> Unit)? = null

    /** 每路最近一次请求 IDR 的时间戳（节流：丢帧是突发的，别把 touch 刷爆）。 */
    private val lastSyncAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** 同一路两次 request-IDR 的最小间隔。 */
    private const val SYNC_MIN_INTERVAL_MS = 300L

    /** 内部：某路进入「丢到下一个 IDR」态后要一个新 IDR（节流后回调 [onRequestSync]）。 */
    private fun notifySyncNeeded(windowId: Int) {
        val now = System.currentTimeMillis()
        val prev = lastSyncAt[windowId]
        if (prev != null && now - prev < SYNC_MIN_INTERVAL_MS) return
        lastSyncAt[windowId] = now
        runCatching { onRequestSync?.invoke(windowId) }
    }

    /** 显式请求某路补一个 IDR（客户端上报解码出错时走这里）。 */
    fun requestSync(windowId: Int) {
        if (windowId < 0) return
        notifySyncNeeded(windowId)
    }

    /**
     * 最近一个关键帧视频包（内联 SPS/PPS 的 IDR）+ 其所属尺寸。
     * 用途：新会话接入时补发一次，保证「首包即 IDR」——桌面源在客户端连上之前
     * 就已开编，客户端必然错过开局那个 IDR。
     */
    @Volatile
    private var lastKeyFrame: ByteArray? = null

    @Volatile
    private var lastKeyFrameSize: String = ""

    /**
     * 逐窗口最近关键帧缓存：windowId → 线负载 `[1 字节 windowId][Annex-B IDR]`。
     * 新会话接入时逐窗口补发一次，静态应用下也不必等下一次内容变化才出画。
     * 窗口重建（resize/close）时经 [forgetWindow] 失效，避免旧 SPS 把解码器带偏。
     */
    private val windowKeyFrames = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

    @Volatile
    private var pumpRunning = false
    private var videoPump: Thread? = null
    private var audioPump: Thread? = null
    private val pumpLock = Any()

    /** 当前推流在线数（供 `/api/status` 与 6.1 主页绑定）。 */
    val sessionCount: Int get() = sessions.size

    /**
     * 首个会话接入回调（会话数 0→1 时触发；并发下可能重复触发，实现方须幂等）。
     *
     * 开关下线后采集改为按需：由 [com.erl.blindcast.core.server.BlindCastServer]
     * 注册成「拉起采集」，任何客户端连上 `/ws/stream` 都能等到画面，
     * 不必先调 `/api/stream` 或依赖自家页面的唤醒逻辑。
     */
    @Volatile
    var onFirstSession: (() -> Unit)? = null

    /**
     * 接管一条已升级连接（阻塞直到对端关闭 / 出错 / 服务停止）。
     * 调用方为 [BlindCastServer] 连接池线程；返回后连接已关闭。
     */
    fun handle(conn: WsConnection) {
        // 0→1：有人接入才需要采集（开关下线后的按需语义，收尾见 BlindCastForegroundService.recycleIdleCapture）。
        // 并发下可能重复触发，requestCapture 幂等，无害。
        if (sessions.isEmpty()) runCatching { onFirstSession?.invoke() }
        val session = Session(conn)
        sessions.add(session)
        daemon("BlindCast-WsSession") { writerLoop(session) }.start()
        try {
            ensurePump()
            try {
                conn.sendText(HELLO_JSON)
            } catch (_: Exception) {
                return
            }
            // 新会话接入即补发缓存关键帧：静态桌面下不必等下一次内容变化才出画。
            // 放在 hello 之后，保证协议里「首条文本 hello」不被二进制抢跑。
            cachedKeyFrame()?.let { session.primeWith(it) }
            // 逐窗口流同样补发一次缓存关键帧（静态应用也能立刻出画）。
            for ((wid, kf) in windowKeyFrames) session.primeWindow(wid, kf)
            // 补发只是让画面立刻出，缓存关键帧可能已过期（换源/换几何）：再要一批新 IDR
            // 把整条链拉齐（AndroMeld 新客户端接入即补 config + 请求新 IDR，`C2882X.java:1053-1079`）。
            runCatching { onSessionAttached?.invoke() }
            while (conn.isOpen && pumpRunning) {
                try {
                    if (conn.receive() == null) break
                    // 推流通道忽略上行业务数据（读循环只为处理 Ping/Close）。
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            killSession(session)
        }
    }

    /** 停服时调用：停泵 + 踢掉全部会话（幂等）。 */
    fun shutdown() {
        pumpRunning = false
        synchronized(pumpLock) {
            videoPump?.interrupt()
            audioPump?.interrupt()
            videoPump = null
            audioPump = null
        }
        for (s in sessions) killSession(s, 1001, "server stopping")
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 会话唯一的写线程：锁内取帧（空则 wait），**锁外**发帧。
     * 通道序固定 video > audio > jpeg；发帧抛异常即判定会话死亡。
     * [Session.poll] 返回 null 说明会话已关闭且队列排空，线程退出。
     */
    private fun writerLoop(s: Session) {
        while (true) {
            val frame = s.poll() ?: return
            try {
                s.conn.sendBinaryWithPrefix(frame.kind, frame.payload)
            } catch (t: Exception) {
                sessions.remove(s)
                s.kill()
                runCatching { s.conn.close() }
                Log.d(TAG, "drop dead session ${s.conn.remoteAddress}: ${t.message} (drops=${s.drops})")
                return
            }
        }
    }

    /** 摘会话 + 置 closed（唤醒写线程退出）+ 关连接；幂等，任意线程可调。 */
    private fun killSession(s: Session, code: Int = 1000, reason: String = "") {
        sessions.remove(s)
        s.kill()
        runCatching { s.conn.close(code, reason) }
    }

    private fun ensurePump() {
        if (pumpRunning) return
        synchronized(pumpLock) {
            if (pumpRunning) return
            pumpRunning = true
            videoPump = daemon("BlindCast-WsVideo") { videoLoop() }.also { it.start() }
            audioPump = daemon("BlindCast-WsAudio") { audioLoop() }.also { it.start() }
        }
    }

    private fun videoLoop() {
        while (pumpRunning && !Thread.currentThread().isInterrupted) {
            if (sessions.isEmpty()) {
                sleep(NO_SESSION_IDLE_MS)
                continue
            }
            val pkt = ScreenCaptureEngine.frameChannel.tryReceive().getOrNull()
            if (pkt == null) {
                sleep(POLL_IDLE_MS)
                continue
            }
            if (pkt.isKeyFrame) {
                // 只缓存一个关键帧（含内联 SPS/PPS 的 IDR），供后来者补发。
                lastKeyFrameSize = currentSizeTag()
                lastKeyFrame = pkt.payload
            }
            broadcast(KIND_VIDEO, pkt.payload, pkt.isKeyFrame)
        }
    }

    private fun audioLoop() {
        while (pumpRunning && !Thread.currentThread().isInterrupted) {
            if (sessions.isEmpty()) {
                sleep(NO_SESSION_IDLE_MS)
                continue
            }
            val pkt = AudioCaptureEngine.audioChannel.tryReceive().getOrNull()
            if (pkt == null) {
                sleep(POLL_IDLE_MS)
                continue
            }
            broadcast(KIND_AUDIO, pkt.payload)
        }
    }

    /** 当前采集尺寸标签（"WxH"；停采为 "-1x-1"）——缓存关键帧按它判是否过期。 */
    private fun currentSizeTag(): String =
        "${CaptureSocketLink.currentWidth}x${CaptureSocketLink.currentHeight}"

    /** 缓存关键帧，尺寸不符（换源/换分辨率）一律不补，避免过期 SPS 把解码器带偏。 */
    private fun cachedKeyFrame(): ByteArray? {
        val kf = lastKeyFrame ?: return null
        return if (lastKeyFrameSize == currentSizeTag()) kf else null
    }

    /**
     * 广播一帧（**只入队，不阻塞**——实际写 socket 交给各会话的写线程）。
     *
     * 视频会话**必须从关键帧起头**：未起头的会话先补发缓存关键帧（或当前这帧本身就是
     * 关键帧），补发后才开始收后续帧；缓存缺失就本帧跳过、等下一个关键帧——
     * 宁可不发，也不让 P 帧先到（P 帧先到 = 解码器未 configure = 画面恒黑）。
     */
    private fun broadcast(kind: Byte, payload: ByteArray, isKeyFrame: Boolean = false) {
        if (payload.isEmpty()) return
        for (s in sessions) {
            if (kind == KIND_VIDEO && !s.videoPrimed) {
                val prime: ByteArray? = if (isKeyFrame) payload else cachedKeyFrame()
                if (prime == null) continue
                s.enqueue(KIND_VIDEO, prime, isKeyFrame = true)
                s.videoPrimed = true
                if (prime !== payload) s.enqueue(KIND_VIDEO, payload)
                continue
            }
            s.enqueue(kind, payload, isKeyFrame)
        }
    }

    /**
     * JPEG 广播（Universal-1 · `[0x03+4字节大端长+JPEG]` 沿用既有帧格式）。
     * H264 老泵不动：本广播只在有 JPEG 订阅者且 [JpegTranscoder] 产出时触发，
     * 向**全量** stream 会话下发（H264 端忽略 0x03，JPEG 端忽略 0x01/0x02，
     * 按会话过滤需关联 control/stream 跨连接，复杂度换不来收益，LAN 带宽可承受）。
     */
    fun broadcastJpeg(jpeg: ByteArray) {
        if (jpeg.isEmpty() || sessions.isEmpty()) return
        // 4 字节大端长前缀（与 CaptureSocketLink 特权帧格式同构，前端剥长后解码）。
        val n = jpeg.size
        val withLen = ByteArray(4 + n)
        withLen[0] = ((n ushr 24) and 0xFF).toByte()
        withLen[1] = ((n ushr 16) and 0xFF).toByte()
        withLen[2] = ((n ushr 8) and 0xFF).toByte()
        withLen[3] = (n and 0xFF).toByte()
        jpeg.copyInto(withLen, 4)
        broadcast(KIND_JPEG, withLen)
    }

    /**
     * 逐窗口视频广播（Win-Stream-1）：线格式
     * `[0x11][1 字节 windowId][8 字节大端 pts 微秒][Annex-B]`。
     *
     * 由 [com.erl.blindcast.core.priv.DesktopWindowController] 为每个窗口建的那条
     * [com.erl.blindcast.core.scrcpy.CaptureLink] 逐帧调用。与整屏 `0x01` 完全并列：
     * 每个窗口一路独立虚拟显示/编码会话，只在同一条 WS 上按 windowId 复用。
     *
     * pts 来自编码器 `presentationTimeUs`（微秒，大端），供前端 fMP4 封装 / 解码时间戳；
     * 缺省 0 时交前端用本地时钟。线负载里的 pts 位于 windowId 之后、Annex-B 之前。
     *
     * **每个窗口必须从自己的关键帧起头**：未起头的会话先补发该窗口缓存关键帧，
     * 缓存缺失就跳过该帧、等下一个关键帧——宁可不发，也不让 P 帧先到（解码器未 configure = 恒黑）。
     */
    fun broadcastWindowVideo(windowId: Int, payload: ByteArray, isKey: Boolean, ptsUs: Long = 0L) {
        if (windowId <= 0 || windowId > 255 || payload.isEmpty()) return
        val line = ByteArray(payload.size + 9)
        line[0] = (windowId and 0xFF).toByte()
        var i = 1
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            line[i++] = ((ptsUs ushr shift) and 0xFF).toByte()
        }
        payload.copyInto(line, 9)
        if (isKey) windowKeyFrames[windowId] = line
        if (sessions.isEmpty()) return
        for (s in sessions) {
            if (isKey) {
                s.markWinPrimed(windowId)
                s.enqueue(KIND_WINDOW_VIDEO, line, isKeyFrame = true)
            } else if (s.isWinPrimed(windowId)) {
                s.enqueue(KIND_WINDOW_VIDEO, line, isKeyFrame = false)
            } else {
                val cached = windowKeyFrames[windowId] ?: continue
                s.primeWindow(windowId, cached)
            }
        }
    }

    /** 窗口关闭/重建时失效其关键帧缓存（旧 SPS 不得带偏新解码器）。 */
    fun forgetWindow(windowId: Int) {
        windowKeyFrames.remove(windowId)
        lastSyncAt.remove(windowId)
    }

    /**
     * 客户端显式请求某窗口补帧（重进桌面 / 切镜像回来 / 解码出错）时，
     * **重发该窗口最近缓存的关键帧**，向所有会话广播。
     *
     * 为什么不能只靠 request-sync：静态应用的 VirtualDisplay 没有新的 surface 输入，
     * `setParameters({"request-sync":0})` 只会在**下一帧输入**时产出 IDR —— 内容不变时
     * 编码器根本不产帧。真机实证（216 / Android 16）：对静态 Settings 窗口 touch
     * `.stop.sync`，宿主日志有 `request-sync -> IDR`，但 `/ws/stream` 上该窗口 0x11
     * 帧数为 0；客户端遂永久停在「等待该窗口的独立视频流」。
     * 重发 IDR 对解码器是安全的（IDR 本就是重同步点）；前端 fMP4 时间轴用本地累加，
     * 倒退的 pts 只会被钳成最小帧时长，不会报错。
     *
     * @return 是否成功重发（无缓存关键帧 / 无会话时 false）。
     */
    fun resendWindowKeyFrame(windowId: Int): Boolean {
        if (windowId <= 0) return false
        val line = windowKeyFrames[windowId] ?: return false
        if (sessions.isEmpty()) return false
        var sent = false
        for (s in sessions) {
            // isKeyFrame=true：清该窗口待发帧、解除「丢到下一个 IDR」态、标记起头并入队。
            s.enqueue(KIND_WINDOW_VIDEO, line, isKeyFrame = true)
            sent = true
        }
        return sent
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun daemon(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true }

    private const val HELLO_JSON =
        """{"type":"hello","video":{"mime":"video/avc","kind":1,"format":"annexb"},""" +
            """"audio":{"mime":"audio/mp4a-latm","kind":2,"format":"raw"},""" +
            """"jpeg":{"mime":"image/jpeg","kind":3,"format":"jpeg"},""" +
            """"window":{"mime":"video/avc","kind":17,"format":"annexb+wid"}}"""

    init {
        // JPEG 产出接线（JpegTranscoder→本路由广播；失败吞错不影响 H264 老路）。
        try {
            JpegTranscoder.onJpeg = { jpeg -> runCatching { broadcastJpeg(jpeg) } }
        } catch (_: Throwable) {
        }
        // screencap 兜底轮询（SDK36 Root H264 常驻无 Context 不可用时顶上，无需求时空转）。
        try {
            ScreencapJpegPoller.ensureStarted()
        } catch (_: Throwable) {
        }
    }
}
