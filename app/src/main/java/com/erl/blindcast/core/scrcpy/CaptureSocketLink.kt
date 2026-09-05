package com.erl.blindcast.core.scrcpy

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 特权帧搬运链（Stream-Priv-1 · 跑在 App 进程，只做搬运）。
 *
 * ## 分工
 * - 本端 `LocalServerSocket(abstract:blindcast_capture)` 监听，特权侧
 *   [PrivilegedCapture] `LocalSocket.connect` 上来后按
 *   `[1 字节通道 0x01/0x02 + 4 字节大端长度 + payload]` 读帧；
 * - 视频帧 → [ScreenCaptureEngine.frameChannel].trySend + `onFrame` 回调透传
 *   （复用其回调给 `StreamWsRoute`，`StreamWsRoute` 仍从既有 Channel 拉帧广播）；
 * - 音频帧 →  d经 [AudioGate] 过滤（关闸丢弃，零网络）后 →
 *   [AudioCaptureEngine.audioChannel].trySend + `onPacket` 透传；
 * - 不做编解码、不改 WS 路由、不碰偏好键。
 *
 * ## running 语义（供 ForegroundService.bootStack）
 * - [isRunning] = 服务端监听中（start 后 true，stop 后 false）；
 * - [hasVideo]/[hasAudio] = 已收到对应通道首帧（首帧门闩）；
 * - [awaitFirstFrame] 等首帧或 3s 超时（bootStack 据此标 running/记 lastError）。
 */
object CaptureSocketLink {

    private const val TAG = "BlindCast"

    /** 与 [PrivilegedCapture.SOCKET_NAME] 同值（抽象命名，防跨类加载耦合写死两份）。 */
    const val SOCKET_NAME = "blindcast_capture"

    const val CHANNEL_VIDEO: Byte = 0x01
    const val CHANNEL_AUDIO: Byte = 0x02

    /** 单帧上限 8MB（与特权侧一致，超限断连防炸内存）。 */
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    /** 首帧等待默认 3s（任务包约定）。 */
    const val FIRST_FRAME_TIMEOUT_MS = 3_000L

    @Volatile var isRunning: Boolean = false; private set
    @Volatile var hasVideo: Boolean = false; private set
    @Volatile var hasAudio: Boolean = false; private set
    @Volatile var lastError: Throwable? = null; private set

    @Volatile var currentWidth: Int = -1; private set
    @Volatile var currentHeight: Int = -1; private set
    @Volatile var currentBitrate: Int = -1; private set
    @Volatile var currentFps: Int = -1; private set

    /** 便于诊断的累计帧计数（logcat/排障用，不进状态流）。 */
    val videoFrames: AtomicLong = AtomicLong(0)
    val audioFrames: AtomicLong = AtomicLong(0)

    /** 首帧门闩（任意通道首帧即放行；重启重建）。 */
    @Volatile private var firstFrameLatch = CountDownLatch(1)

    private val lock = Any()
    private var server: LocalServerSocket? = null
    private var client: LocalSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 启动搬运服（幂等可重配，同步返回，不阻塞等帧）。
     * 首帧经 [awaitFirstFrame] 另行等待（bootStack 等首帧或 3s 超时再标 running）。
     */
    @Synchronized
    fun start(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        if (isRunning) stopLocked()
        return try {
            // 抽象命名残留（上次崩溃未释放）时先尽力重建：失败即记错返回 false。
            val srv = LocalServerSocket(SOCKET_NAME)
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
            val t = Thread(::acceptLoop, "BlindCast-CaptureLink")
            t.isDaemon = true
            acceptThread = t
            t.start()
            Log.i(TAG, "[CaptureSocketLink] listen ok abstract:$SOCKET_NAME ${width}x${height}")
            true
        } catch (t: Throwable) {
            lastError = t
            Log.e(TAG, "[CaptureSocketLink] listen failed abstract:$SOCKET_NAME", t)
            releaseLocked()
            false
        }
    }

    /**
     * 等首帧（任意通道）。
     * @return true = 3s 内收到首帧；false = 超时/已停（调用方记 lastError 进状态流）。
     */
    fun awaitFirstFrame(timeoutMs: Long = FIRST_FRAME_TIMEOUT_MS): Boolean {
        val latch = firstFrameLatch
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 停止搬运（幂等）：先停 socket（逆序收第一步），不 close 引擎复用 Channel。 */
    @Synchronized
    fun stop() = stopLocked()

    /** 取最近失败文案（状态流/Home 回读用）。 */
    fun errorMessage(): String? = lastError?.message ?: lastError?.toString()

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun stopLocked() {
        isRunning = false
        acceptThread?.interrupt()
        try { acceptThread?.join(1_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        acceptThread = null
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { client?.close() }
        client = null
        runCatching { server?.close() }
        server = null
        currentWidth = -1
        currentHeight = -1
        currentBitrate = -1
        currentFps = -1
    }

    private fun acceptLoop() {
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val srv = synchronized(lock) { server } ?: break
            // accept 前快照（stop 关闭 server 会抛异常退出循环）。
            val sock: LocalSocket = try {
                srv.accept()
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (isRunning) {
                    lastError = t
                    Log.e(TAG, "[CaptureSocketLink] accept failed", t)
                    runCatching { Thread.sleep(200L) }
                    continue
                } else break
            }
            synchronized(lock) {
                runCatching { client?.close() }
                client = sock
            }
            Log.i(TAG, "[CaptureSocketLink] client connected")
            try {
                readLoop(sock)
            } finally {
                runCatching { sock.close() }
                synchronized(lock) { if (client === sock) client = null }
                Log.i(TAG, "[CaptureSocketLink] client disconnected video=${videoFrames.get()} audio=${audioFrames.get()}")
            }
        }
    }

    private fun readLoop(sock: LocalSocket) {
        val input = DataInputStream(sock.inputStream)
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val channel: Byte
            val len: Int
            try {
                channel = input.readByte()
                len = input.readInt()
            } catch (t: Throwable) {
                // EOF/断连为常态（特权侧 stop 关流），只记 debug 不记 lastError 污染状态流。
                Log.d(TAG, "[CaptureSocketLink] read header eof/err: ${t.message}")
                break
            }
            if (len <= 0 || len > MAX_FRAME_BYTES) {
                Log.w(TAG, "[CaptureSocketLink] bad frame len=$len ch=$channel, drop conn")
                lastError = IllegalStateException("CaptureSocketLink: bad frame len=$len")
                break
            }
            if (channel != CHANNEL_VIDEO && channel != CHANNEL_AUDIO) {
                Log.w(TAG, "[CaptureSocketLink] unknown channel=$channel len=$len, drop conn")
                lastError = IllegalStateException("CaptureSocketLink: unknown channel=$channel")
                break
            }
            val payload = ByteArray(len)
            try {
                input.readFully(payload)
            } catch (t: Throwable) {
                Log.d(TAG, "[CaptureSocketLink] read payload eof/err: ${t.message}")
                break
            }
            try {
                if (channel == CHANNEL_VIDEO) onVideoFrame(payload) else onAudioFrame(payload)
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "[CaptureSocketLink] dispatch failed", t)
            }
        }
    }

    private fun onVideoFrame(payload: ByteArray) {
        if (payload.isEmpty()) return
        hasVideo = true
        firstFrameLatch.countDown()
        videoFrames.incrementAndGet()
        // NALU 类型解析（与 ScreenCaptureEngine.parseNaluType 同规则，供 FramePacket.type）。
        val type = parseNaluType(payload)
        val isKey = type == FramePacket.NALU_TYPE_IDR
        val pkt = FramePacket(
            type = type,
            isKeyFrame = isKey,
            timestampUs = System.nanoTime() / 1000L,
            payload = payload,
            sps = null,
            pps = null,
        )
        ScreenCaptureEngine.frameChannel.trySend(pkt)
        runCatching { ScreenCaptureEngine.onFrame?.invoke(pkt) }.onFailure { t -> lastError = t }
    }

    private fun onAudioFrame(payload: ByteArray) {
        if (payload.isEmpty()) return
        hasAudio = true
        firstFrameLatch.countDown()
        // AudioGate 在 App 进程内存内生效：关闸直接丢弃（零网络， drain 侧同样不投递）。
        if (!AudioGate.isAudioEnabled) return
        audioFrames.incrementAndGet()
        val pkt = AudioPacket(
            timestampUs = System.nanoTime() / 1000L,
            payload = payload,
            config = null,
        )
        AudioCaptureEngine.audioChannel.trySend(pkt)
        runCatching { AudioCaptureEngine.onPacket?.invoke(pkt) }.onFailure { t -> lastError = t }
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
}
