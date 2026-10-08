package com.erl.blindcast.core.scrcpy

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.DataInputStream
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * 特权帧搬运链（Stream-Priv-1 · 跑在 App 进程，只做搬运）。
 *
 * 一个实例 = 一条抽象命名 socket 服 = 一路采集。镜像/整屏桌面用默认名
 * [PrivilegedCapture.SOCKET_NAME]（由 [CaptureSocketLink] 单例持有）；
 * 逐窗口流用 `blindcast_win_<id>`，由
 * [com.erl.blindcast.core.priv.DesktopWindowController] 各建一个实例。
 *
 * ## 分工
 * - 本端 `LocalServerSocket(socketName)` 监听，特权侧 [PrivilegedCapture]
 *   `LocalSocket.connect` 上来后按
 *   `[1 字节通道 0x01/0x02 + 4 字节大端长度 + 8 字节大端 pts 微秒 + payload]` 读帧；
 * - 视频帧 → [onVideo]（含是否关键帧 + pts 微秒）；
 * - 音频帧 → [onAudio]（可空，逐窗口流不带音频）；
 * - 不做编解码、不改 WS 路由、不碰偏好键。
 *
 * ## 生命周期语义
 * - [isRunning] = 服务端监听中（start 后 true，stop 后 false）；
 * - [hasVideo]/[hasAudio] = 已收到对应通道首帧（首帧门闩；stop 时清零，
 *   故 `isRunning || hasVideo` 即串流实际开态，停后不粘 true）；
 * - [awaitFirstFrame] 等首帧或超时。
 *
 * ## 为什么不能持锁 join（真机实证）
 * Linux 上 close 一个 fd **不会**唤醒阻塞在 `accept()` 的线程，在途 accept 仍持
 * socket 引用 → socket 不 destroy → 抽象名继续 LISTEN。因此 teardown 必须
 * `shutdown` + 自连唤醒 → **锁外** join 等线程退净 → 最后才关 fd。
 */
class CaptureLink(
    /** 抽象命名（App 侧建服，特权侧连服）。 */
    val socketName: String,
    /** 视频帧回调：payload（Annex-B，关键帧已内联 SPS+PPS）+ 是否关键帧 + pts（微秒）。 */
    private val onVideo: (payload: ByteArray, isKey: Boolean, ptsUs: Long) -> Unit,
    /** 音频帧回调（null = 本路不接收音频）。 */
    private val onAudio: ((payload: ByteArray) -> Unit)? = null,
) {

    private val tag = "BlindCast"

    companion object {
        /** 单帧上限 8MB（与特权侧一致，超限断连防炸内存）。 */
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        /** 首帧等待默认 3s（任务包约定）。 */
        const val FIRST_FRAME_TIMEOUT_MS = 3_000L

        const val CHANNEL_VIDEO: Byte = 0x01
        const val CHANNEL_AUDIO: Byte = 0x02

        /** join 上界：唤醒（shutdown + 自连）后正常应在数十 ms 内退出。 */
        private const val JOIN_TIMEOUT_MS = 1_000L
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var hasVideo: Boolean = false
        private set

    @Volatile
    var hasAudio: Boolean = false
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    @Volatile
    var currentWidth: Int = -1
        private set

    @Volatile
    var currentHeight: Int = -1
        private set

    @Volatile
    var currentBitrate: Int = -1
        private set

    @Volatile
    var currentFps: Int = -1
        private set

    /** 便于诊断的累计帧计数（logcat/排障用，不进状态流）。 */
    val videoFrames: AtomicLong = AtomicLong(0)
    val audioFrames: AtomicLong = AtomicLong(0)

    /** 首帧门闩（任意通道首帧即放行；重启重建）。 */
    @Volatile
    private var firstFrameLatch = CountDownLatch(1)

    private val lock = Any()

    /** 生命周期串行锁：start/stop 互斥；**绝不持锁 join accept 线程**。 */
    private val lifecycle = ReentrantLock()

    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 代际令牌：每次成功 start / 每次 teardown 自增。
     * 旧 accept/read 线程据此自弃——stop 后又 start 时，上一代线程**不得**
     * 把陈旧连接写成新 client，也不得在退出前关掉新 client。
     */
    @Volatile
    private var generation: Int = 0

    /**
     * 启动搬运服（幂等，同步返回，不阻塞等帧）。
     *
     * - 加锁幂等：已在运行重复 start 直接返回 true；
     * - 抢绑自愈：bind 遇 EADDRINUSE/BindException 先真拆残留再重试（500ms × 3）。
     */
    fun start(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        lifecycle.lock()
        try {
            if (isRunning) {
                Log.i(tag, "[CaptureLink:$socketName] start skipped (already running)")
                return true
            }
            teardownLocked("start")
            var last: Throwable? = null
            for (attempt in 1..3) {
                try {
                    val srv = LocalServerSocket(socketName)
                    synchronized(lock) {
                        val gen = generation + 1
                        generation = gen
                        server = srv
                        currentWidth = width
                        currentHeight = height
                        currentBitrate = bitrate
                        currentFps = fps
                        hasVideo = false
                        hasAudio = false
                        videoFrames.set(0)
                        audioFrames.set(0)
                        firstFrameLatch = CountDownLatch(1)
                        lastError = null
                        isRunning = true
                        val t = Thread({ acceptLoop(gen) }, "BlindCast-Link-$socketName")
                        t.isDaemon = true
                        acceptThread = t
                        t.start()
                    }
                    Log.i(tag, "[CaptureLink:$socketName] listen ok ${width}x${height} attempt=$attempt")
                    return true
                } catch (t: Throwable) {
                    last = t
                    lastError = t
                    teardownLocked("start-retry")
                    if (!isBindConflict(t)) {
                        Log.e(tag, "[CaptureLink:$socketName] listen failed (non-bind)", t)
                        throw t
                    }
                    if (attempt < 3) {
                        Log.w(tag, "[CaptureLink:$socketName] bind conflict attempt=$attempt/3 err=${t.message}, retry in 500ms")
                        try {
                            Thread.sleep(500L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                        teardownLocked("start-retry-2")
                    }
                }
            }
            val err: Throwable = last ?: IllegalStateException("CaptureLink:$socketName bind failed after 3 retries")
            lastError = err
            Log.e(tag, "[CaptureLink:$socketName] listen failed after 3 retries", err)
            teardownLocked("start-failed")
            throw err
        } finally {
            lifecycle.unlock()
        }
    }

    /** 等首帧（任意通道）。 */
    fun awaitFirstFrame(timeoutMs: Long = FIRST_FRAME_TIMEOUT_MS): Boolean {
        val latch = firstFrameLatch
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 停止搬运（幂等）：真唤醒在途 accept/read 再关 fd，抽象名当次真正释放。 */
    fun stop() {
        lifecycle.lock()
        try {
            teardownLocked("stop")
        } finally {
            lifecycle.unlock()
        }
    }

    /** 取最近失败文案（状态流/Home 回读用）。 */
    fun errorMessage(): String? = lastError?.message ?: lastError?.toString()

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun teardownLocked(reason: String) {
        var thread: Thread? = null
        var srv: LocalServerSocket? = null
        var cli: LocalSocket? = null
        synchronized(lock) {
            if (!isRunning && server == null && client == null && acceptThread == null) {
                return
            }
            generation++
            isRunning = false
            hasVideo = false
            hasAudio = false
            thread = acceptThread
            srv = server
            cli = client
            acceptThread = null
            server = null
            client = null
            currentWidth = -1
            currentHeight = -1
            currentBitrate = -1
            currentFps = -1
        }
        thread?.interrupt()
        // 1) read 阻塞：对已连接端 shutdown 必定唤醒（读到 EOF）。
        shutdownQuietly(cli?.fileDescriptor)
        // 2) accept 阻塞：先试 listen fd shutdown；AF_UNIX 上未必唤醒，故补自连。
        shutdownQuietly(srv?.fileDescriptor)
        wakeAccept()
        // 3) 锁外 join：accept 线程退出路径要拿 lock，持 lock join 会互等。
        if (thread != null) {
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                Log.w(tag, "[CaptureLink:$socketName] accept thread alive after ${JOIN_TIMEOUT_MS}ms ($reason)")
            }
        }
        // 4) 线程退净后才关 fd。
        runCatching { cli?.close() }
        runCatching { srv?.close() }
        Log.i(tag, "[CaptureLink:$socketName] teardown done ($reason) gen=$generation threadAlive=${thread?.isAlive == true}")
    }

    private fun shutdownQuietly(fd: FileDescriptor?) {
        if (fd == null || !fd.valid()) return
        try {
            Os.shutdown(fd, OsConstants.SHUT_RDWR)
        } catch (t: Throwable) {
            Log.d(tag, "[CaptureLink:$socketName] shutdown skipped: ${t.message}")
        }
    }

    private fun wakeAccept() {
        var s: LocalSocket? = null
        try {
            s = LocalSocket()
            s.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (t: Throwable) {
            Log.d(tag, "[CaptureLink:$socketName] wake accept connect skipped: ${t.message}")
        } finally {
            runCatching { s?.close() }
        }
    }

    private fun isBindConflict(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is java.net.BindException) return true
            val msg = cur.message ?: ""
            if (msg.contains("Address already in use", ignoreCase = true) ||
                msg.contains("EADDRINUSE", ignoreCase = true)
            ) return true
            cur = cur.cause
        }
        return false
    }

    private fun acceptLoop(gen: Int) {
        while (isRunning && generation == gen && !Thread.currentThread().isInterrupted) {
            val srv = synchronized(lock) { if (generation == gen) server else null } ?: break
            val sock: LocalSocket = try {
                srv.accept()
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (isRunning && generation == gen) {
                    lastError = t
                    Log.e(tag, "[CaptureLink:$socketName] accept failed", t)
                    runCatching { Thread.sleep(200L) }
                    continue
                } else break
            }
            val stale = synchronized(lock) {
                if (generation != gen) {
                    true
                } else {
                    runCatching { client?.close() }
                    client = sock
                    false
                }
            }
            if (stale) {
                runCatching { sock.close() }
                Log.i(tag, "[CaptureLink:$socketName] drop stale client gen=$gen cur=$generation")
                break
            }
            Log.i(tag, "[CaptureLink:$socketName] client connected")
            try {
                readLoop(sock, gen)
            } finally {
                runCatching { sock.close() }
                synchronized(lock) { if (client === sock) client = null }
                Log.i(tag, "[CaptureLink:$socketName] client disconnected video=${videoFrames.get()} audio=${audioFrames.get()}")
            }
        }
    }

    private fun readLoop(sock: LocalSocket, gen: Int) {
        val input = DataInputStream(sock.inputStream)
        while (isRunning && generation == gen && !Thread.currentThread().isInterrupted) {
            val channel: Byte
            val len: Int
            val ptsUs: Long
            try {
                channel = input.readByte()
                len = input.readInt()
                ptsUs = input.readLong()
            } catch (t: Throwable) {
                Log.d(tag, "[CaptureLink:$socketName] read header eof/err: ${t.message}")
                break
            }
            if (len <= 0 || len > MAX_FRAME_BYTES) {
                Log.w(tag, "[CaptureLink:$socketName] bad frame len=$len ch=$channel, drop conn")
                lastError = IllegalStateException("CaptureLink: bad frame len=$len")
                break
            }
            if (channel != CHANNEL_VIDEO && channel != CHANNEL_AUDIO) {
                Log.w(tag, "[CaptureLink:$socketName] unknown channel=$channel len=$len, drop conn")
                lastError = IllegalStateException("CaptureLink: unknown channel=$channel")
                break
            }
            val payload = ByteArray(len)
            try {
                input.readFully(payload)
            } catch (t: Throwable) {
                Log.d(tag, "[CaptureLink:$socketName] read payload eof/err: ${t.message}")
                break
            }
            try {
                if (channel == CHANNEL_VIDEO) dispatchVideo(payload, ptsUs) else dispatchAudio(payload)
            } catch (t: Throwable) {
                lastError = t
                Log.e(tag, "[CaptureLink:$socketName] dispatch failed", t)
            }
        }
    }

    private fun dispatchVideo(payload: ByteArray, ptsUs: Long) {
        if (payload.isEmpty()) return
        hasVideo = true
        firstFrameLatch.countDown()
        videoFrames.incrementAndGet()
        val type = parseNaluType(payload)
        // isKey 必须扫描全包（关键帧为 sps+pps+idr 内联，首 NALU 是 SPS=7 而非 IDR=5）。
        val isKey = type == FramePacket.NALU_TYPE_IDR || containsNaluType(payload, FramePacket.NALU_TYPE_IDR)
        runCatching { onVideo(payload, isKey, ptsUs) }.onFailure { lastError = it }
    }

    private fun dispatchAudio(payload: ByteArray) {
        if (payload.isEmpty()) return
        hasAudio = true
        firstFrameLatch.countDown()
        val cb = onAudio ?: return
        audioFrames.incrementAndGet()
        runCatching { cb(payload) }.onFailure { lastError = it }
    }

    private fun parseNaluType(annexB: ByteArray): Int {
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()) {
                val headerAt = when {
                    annexB[i + 2] == 1.toByte() -> i + 3
                    i + 3 < annexB.size && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (headerAt in 0 until annexB.size) return annexB[headerAt].toInt() and 0x1F
                if (headerAt >= 0) return FramePacket.NALU_TYPE_UNKNOWN
            }
            i++
        }
        return FramePacket.NALU_TYPE_UNKNOWN
    }

    /** 包内是否含指定 NALU 类型（关键帧内联判定用；起始码 3/4 字节通用）。 */
    private fun containsNaluType(annexB: ByteArray, nalType: Int): Boolean {
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()) {
                val headerAt = when {
                    annexB[i + 2] == 1.toByte() -> i + 3
                    i + 3 < annexB.size && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte() -> i + 4
                    else -> -1
                }
                if (headerAt in 0 until annexB.size) {
                    if ((annexB[headerAt].toInt() and 0x1F) == nalType) return true
                    i = headerAt + 1
                    continue
                }
            }
            i++
        }
        return false
    }
}
