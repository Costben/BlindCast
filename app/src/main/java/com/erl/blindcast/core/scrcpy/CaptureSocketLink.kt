package com.erl.blindcast.core.scrcpy

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * 镜像/整屏桌面这一路的特权帧搬运链（App 进程，只做搬运）。
 *
 * 本对象是 [CaptureLink] 的**单例门面**：固定抽象名 [SOCKET_NAME]，视频帧喂进
 * [ScreenCaptureEngine.frameChannel]（再经 `StreamWsRoute` 广播成 `0x01`），
 * 音频帧过 [AudioGate] 后喂进 [AudioCaptureEngine.audioChannel]（`0x02`）。
 *
 * 逐窗口流不经过本对象：`DesktopWindowController` 各建一个 [CaptureLink] 实例，
 * socket 名 `blindcast_win_<id>`，帧直接进 `StreamWsRoute` 的 `0x11` 逐窗口通道。
 *
 * ## running 语义（供 ForegroundService.bootStack）
 * - [isRunning] = 服务端监听中（start 后 true，stop 后 false）；
 * - [hasVideo]/[hasAudio] = 已收到对应通道首帧（首帧门闩；stop 时清零，
 *   故 `isRunning || hasVideo` 即串流实际开态，停后不粘 true）；
 * - [awaitFirstFrame] 等首帧或 3s 超时（bootStack 据此标 running/记 lastError）。
 */
object CaptureSocketLink {

    private const val TAG = "BlindCast"

    /** 与 [PrivilegedCapture.SOCKET_NAME] 同值（抽象命名，防跨类加载耦合写死两份）。 */
    const val SOCKET_NAME = "blindcast_capture"

    const val CHANNEL_VIDEO: Byte = 0x01
    const val CHANNEL_AUDIO: Byte = 0x02

    /** 首帧等待默认 3s（任务包约定）。 */
    const val FIRST_FRAME_TIMEOUT_MS = CaptureLink.FIRST_FRAME_TIMEOUT_MS

    /**
     * 视频泵入分叉（Universal-1 JPEG 降级用 · App 进程内存回调，不做编解码）。
     * [JpegTranscoder] 在 init 中赋值为其 `offer`，每视频帧另调一次。
     * 无订阅时 Jpeg 侧直接丢弃，本回调开销仅一次空函数调用。
     */
    @Volatile
    var videoTap: ((payload: ByteArray, isKey: Boolean) -> Unit)? = null

    /** 便于诊断的累计帧计数（logcat/排障用，不进状态流）。 */
    val videoFrames: AtomicLong get() = link.videoFrames
    val audioFrames: AtomicLong get() = link.audioFrames

    private val link = CaptureLink(
        socketName = SOCKET_NAME,
        onVideo = { payload, isKey, _ -> onVideoFrame(payload, isKey) },
        onAudio = { payload -> onAudioFrame(payload) },
    )

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

    /**
     * 启动搬运服（幂等，同步返回，不阻塞等帧）。
     * 首帧经 [awaitFirstFrame] 另行等待（bootStack 等首帧或 3s 超时再标 running）。
     */
    fun start(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        val ok = link.start(width, height, bitrate, fps)
        if (ok) {
            currentWidth = width
            currentHeight = height
            currentBitrate = bitrate
            currentFps = fps
            hasVideo = false
            hasAudio = false
            lastError = null
            isRunning = true
        } else {
            lastError = link.lastError
        }
        return ok
    }

    /**
     * 等首帧（任意通道）。
     * @return true = 3s 内收到首帧；false = 超时/已停（调用方记 lastError 进状态流）。
     */
    fun awaitFirstFrame(timeoutMs: Long = FIRST_FRAME_TIMEOUT_MS): Boolean = link.awaitFirstFrame(timeoutMs)

    /** 停止搬运（幂等）。不 close 引擎复用 Channel。重复 stop 无害。 */
    fun stop() {
        link.stop()
        isRunning = false
        hasVideo = false
        hasAudio = false
        currentWidth = -1
        currentHeight = -1
        currentBitrate = -1
        currentFps = -1
    }

    /** 取最近失败文案（状态流/Home 回读用）。 */
    fun errorMessage(): String? = lastError?.message ?: lastError?.toString()

    private fun onVideoFrame(payload: ByteArray, isKey: Boolean) {
        hasVideo = true
        val pkt = FramePacket(
            type = if (isKey) FramePacket.NALU_TYPE_IDR else FramePacket.NALU_TYPE_UNKNOWN,
            isKeyFrame = isKey,
            timestampUs = System.nanoTime() / 1000L,
            payload = payload,
            sps = null,
            pps = null,
        )
        ScreenCaptureEngine.frameChannel.trySend(pkt)
        runCatching { ScreenCaptureEngine.onFrame?.invoke(pkt) }.onFailure { t -> lastError = t }
        runCatching { videoTap?.invoke(payload, isKey) }
    }

    private fun onAudioFrame(payload: ByteArray) {
        hasAudio = true
        // AudioGate 在 App 进程内存内生效：关闸直接丢弃（零网络，drain 侧同样不投递）。
        if (!AudioGate.isAudioEnabled) return
        val pkt = AudioPacket(
            timestampUs = System.nanoTime() / 1000L,
            payload = payload,
            config = null,
        )
        AudioCaptureEngine.audioChannel.trySend(pkt)
        runCatching { AudioCaptureEngine.onPacket?.invoke(pkt) }.onFailure { t -> lastError = t }
    }

    init {
        Log.i(TAG, "[CaptureSocketLink] facade ready abstract:$SOCKET_NAME")
    }
}
