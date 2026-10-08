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
         * 视频关键帧入队先清空视频队列；队列满丢最旧；[closed] 后直接丢弃。
         */
        fun enqueue(kind: Byte, payload: ByteArray, isKeyFrame: Boolean = false) {
            synchronized(lock) {
                if (closed) return
                val queue = when (kind) {
                    StreamWsRoute.KIND_VIDEO -> videoQueue
                    StreamWsRoute.KIND_AUDIO -> audioQueue
                    else -> jpegQueue
                }
                if (kind == StreamWsRoute.KIND_VIDEO && isKeyFrame && videoQueue.isNotEmpty()) {
                    drops += videoQueue.size
                    videoQueue.clear()
                }
                if (queue.size >= capOf(kind)) {
                    queue.removeFirst()
                    drops++
                }
                queue.addLast(payload)
                lock.notifyAll()
            }
        }

        /**
         * 取下一帧：三队皆空且未关闭时在 [lock] 上等（上限 [WRITER_WAIT_MS]）。
         * 优先级 video > audio > jpeg；返回 null 表示会话已关闭且队列排空，写线程应退出。
         */
        fun poll(): QueuedFrame? {
            synchronized(lock) {
                while (videoQueue.isEmpty() && audioQueue.isEmpty() && jpegQueue.isEmpty() && !closed) {
                    try {
                        lock.wait(WRITER_WAIT_MS)
                    } catch (_: InterruptedException) {
                        closed = true
                        break
                    }
                }
                return when {
                    videoQueue.isNotEmpty() -> QueuedFrame(StreamWsRoute.KIND_VIDEO, videoQueue.removeFirst())
                    audioQueue.isNotEmpty() -> QueuedFrame(StreamWsRoute.KIND_AUDIO, audioQueue.removeFirst())
                    jpegQueue.isNotEmpty() -> QueuedFrame(StreamWsRoute.KIND_JPEG, jpegQueue.removeFirst())
                    else -> null
                }
            }
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
                lock.notifyAll()
                return true
            }
        }

        /** 判定会话死亡：置位 + 清队 + 唤醒写线程（幂等，任意线程可调）。 */
        fun kill() {
            synchronized(lock) {
                closed = true
                videoQueue.clear()
                audioQueue.clear()
                jpegQueue.clear()
                lock.notifyAll()
            }
        }

        private fun capOf(kind: Byte): Int = when (kind) {
            StreamWsRoute.KIND_VIDEO -> VIDEO_QUEUE_CAP
            StreamWsRoute.KIND_AUDIO -> AUDIO_QUEUE_CAP
            else -> JPEG_QUEUE_CAP
        }
    }

    private val sessions = CopyOnWriteArraySet<Session>()

    /**
     * 最近一个关键帧视频包（内联 SPS/PPS 的 IDR）+ 其所属尺寸。
     * 用途：新会话接入时补发一次，保证「首包即 IDR」——桌面源在客户端连上之前
     * 就已开编，客户端必然错过开局那个 IDR。
     */
    @Volatile
    private var lastKeyFrame: ByteArray? = null

    @Volatile
    private var lastKeyFrameSize: String = ""

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
            """"jpeg":{"mime":"image/jpeg","kind":3,"format":"jpeg"}}"""

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
