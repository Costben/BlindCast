package com.erl.blindcast.core.scrcpy

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

/**
 * 提权 VirtualDisplay 捕获 + MediaCodec H.264 硬件编码引擎（Slice 3.1）。
 *
 * ## 链路
 * `DisplayManager.createVirtualDisplay`（提权 VirtualDisplay，直连屏幕镜像）
 * -> `MediaCodec` Input Surface（AVC Baseline 硬件编码）
 * -> 后台 drain 线程出队 Annex-B NALU
 * -> 经 [onFrame] 回调 / [frameChannel] 对外产出 [FramePacket]
 * （首包必为附带 SPS/PPS 的 IDR 关键帧，供 Slice 4.1 `/ws/stream` 与 Web 端
 * `VideoDecoder` 首包初始化解码器，MVP.md 四(二)(2)）。
 *
 * ## 提权调用点预留（Shizuku / Root · 必读）
 * 带 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 的 `createVirtualDisplay` 需要签名级
 * `CAPTURE_VIDEO_OUTPUT / CAPTURE_SECURE_VIDEO_OUTPUT` 权限，普通 App 进程
 * 直接调用将抛 `SecurityException`（本引擎捕获后记 [lastError] 并返回 false，
 * 永不崩溃）。因此 [start] 系列方法**必须在提权进程内执行**：
 * - Shizuku 方案：由 Shizuku UserService（system_server 上下文）调用，
 *   或经 binder 把编码 Surface 透传给特权端创建 VirtualDisplay；
 * - Root 方案：以 Root 身份启动的 `app_process` 守护进程调用。
 * 后续 Slice 在此接入 Shizuku binder 透传 / Root 守护进程转调时，本对象 API
 * 保持不变：调用方只关心 [start]/[stop]/[frameChannel]。
 * 本引擎彻底绕开 `MediaProjectionManager.createScreenCaptureIntent`，
 * 无系统录屏确认弹窗、无状态栏录屏隐私红点（PLAN.md Slice 3.1 验收目标）。
 *
 * ## 编码配置（低延迟）
 * - MIME `video/avc`，Profile AVC Baseline；
 * - `KEY_PRIORITY = 0`（realtime）、`BITRATE_MODE_VBR`；
 * - I 帧间隔 1s（低 I 帧间隔，丢包后 1s 内自愈关键帧）；
 * - Surface 输入（零拷贝，`COLOR_FormatSurface`）；
 * - 编码器优选硬件实现（`isHardwareAccelerated`），无硬件时回退系统默认。
 *
 * ## 线程模型
 * - [start] 幂等可重配：运行中再次调用先静默 [stop] 再按新参数启动；
 * - drain 线程为 daemon（`isDaemon = true`），进程退出时不阻止 VM 回收；
 * - [stop] 幂等：停线程（join 至多 1s）+ 释放 VirtualDisplay/Surface/Codec；
 * - [frameChannel] 跨启停复用，永不 close（重启无需重订阅；满 64 帧丢最旧）；
 * - 所有异常记 [lastError] 不抛崩；成功启动后 [lastError] 清零。
 *
 * ## 范围声明
 * - 仅做采集编码能力封装：不接网络（Slice 4.1）、不接 UI、不启动保活
 *   （保活归 `UserActivityKeeper`，熔断归 `EmergencyRecovery`）。
 */
object ScreenCaptureEngine {

    /** 编码 MIME：H.264 / AVC。 */
    const val MIME_TYPE = "video/avc"

    /** VirtualDisplay 显示名（logcat / dumpsys 可见）。 */
    const val VIRTUAL_DISPLAY_NAME = "BlindCastCapture"

    /** 默认采集宽度（720P，MVP.md 设置页推荐档）。 */
    const val DEFAULT_WIDTH = 1280

    /** 默认采集高度（720P）。 */
    const val DEFAULT_HEIGHT = 720

    /** 默认码率：4 Mbps（设置页 2 ~ 8 Mbps 中档）。 */
    const val DEFAULT_BITRATE = 4_000_000

    /** 默认帧率：30 FPS（挂机省流档）。 */
    const val DEFAULT_FPS = 30

    /** I 帧间隔：1s（低延迟 + 弱网自愈）。 */
    const val I_FRAME_INTERVAL_SEC = 1

    /** `MediaFormat.KEY_PRIORITY` realtime 取值（0 = 实时优先级，API 26+）。 */
    private const val PRIORITY_REALTIME = 0

    /** Channel 缓冲：满时丢最旧帧（保活优先，挂机场景宁可丢帧不堆积延迟）。 */
    private const val CHANNEL_CAPACITY = 64

    /** drain 出队超时：10ms（stop 响应快，空转 CPU 可忽略）。 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** stop 时 drain 线程 join 上限：1s。 */
    private const val STOP_JOIN_MS = 1_000L

    /** 是否正在采集编码（volatile，跨线程可见）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 最近一次失败的异常；最近一次成功 [start] / 成功喂帧后清零，
     * 供 Slice 6.3 诊断消费。失败永不崩溃。
     */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 当前会话宽度（未运行为 -1）。 */
    @Volatile
    var currentWidth: Int = -1
        private set

    /** 当前会话高度（未运行为 -1）。 */
    @Volatile
    var currentHeight: Int = -1
        private set

    /** 当前会话码率 bps（未运行为 -1）。 */
    @Volatile
    var currentBitrate: Int = -1
        private set

    /** 当前会话帧率（未运行为 -1）。 */
    @Volatile
    var currentFps: Int = -1
        private set

    /** 本次编码会话缓存的 SPS（Annex-B，含起始码；未运行/未就绪为 null）。 */
    @Volatile
    var cachedSps: ByteArray? = null
        private set

    /** 本次编码会话缓存的 PPS（Annex-B，含起始码；未运行/未就绪为 null）。 */
    @Volatile
    var cachedPps: ByteArray? = null
        private set

    /**
     * 对外帧通道（`BUFFERED` + 满时丢最旧，跨启停复用永不 close）。
     * 消费者（二选一或兼用）：`for (pkt in frameChannel)` 或 [setOnFrameListener]。
     */
    val frameChannel: Channel<FramePacket> =
        Channel(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 同步帧回调（drain 线程内调用，禁止耗时；抛异常被吞并记 [lastError]）。 */
    @Volatile
    var onFrame: ((FramePacket) -> Unit)? = null
        private set

    /** 本设备 SDK 是否满足编码前置（minSdk 31，恒为 true，保留供 ROM 黑名单扩展）。 */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val lock = Any()
    private var appContextRef: WeakReference<Context>? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null

    /** 首个附带 SPS/PPS 的 IDR 是否已发出（"首包附 SPS/PPS"门闩）。 */
    @Volatile
    private var firstKeyFrameEmitted: Boolean = false

    /**
     * 预存应用上下文，供四参 [start] 使用（内部只持 [WeakReference]，不泄漏）。
     * 调过含 Context 的 [start] 后可省略此调用。
     */
    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext ?: context)
    }

    /** 设置同步帧回调（传 null 清除）。 */
    fun setOnFrameListener(listener: ((FramePacket) -> Unit)?) {
        onFrame = listener
    }

    /**
     * 启动采集编码（主入口，同步返回）。
     *
     * @param context 任一 Context（内部取 applicationContext；提权重申：
     *   本调用必须发生在 Shizuku UserService / Root 守护进程内，见类注释）。
     * @param width 采集宽（160 ~ 3840），通常取物理分辨率等比档（720P/1080P/原生）。
     * @param height 采集高（160 ~ 3840）。
     * @param bitrate 目标码率 bps（>= 100_000，建议 2_000_000 ~ 8_000_000）。
     * @param fps 目标帧率（1 ~ 120）。
     * @return 启动成功 true；参数非法 / 无提权 / 编码器异常返回 false，
     *   原因记 [lastError]，且失败后无残留（已分配资源回滚释放）。
     */
    @Synchronized
    fun start(
        context: Context,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        bitrate: Int = DEFAULT_BITRATE,
        fps: Int = DEFAULT_FPS,
    ): Boolean {
        appContextRef = WeakReference(context.applicationContext ?: context)
        if (isRunning) stopLocked()
        val appContext = appContextRef?.get()
        if (appContext == null) {
            lastError = IllegalStateException("ScreenCaptureEngine: application context lost")
            return false
        }
        if (!checkParams(width, height, bitrate, fps)) return false
        return try {
            val encoder = createHardwareAvcEncoder()
            val format = buildLowLatencyFormat(width, height, bitrate, fps)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            inputSurface = surface
            val displayManager = appContext.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("DisplayManager unavailable")
            val densityDpi = appContext.resources.displayMetrics.densityDpi
            // 提权 VirtualDisplay：PUBLIC + AUTO_MIRROR 直连屏幕镜像，
            // 绕开 MediaProjection 意图，无录屏弹窗、无隐私红点。
            // 普通进程无 CAPTURE_VIDEO_OUTPUT 将在此抛 SecurityException -> false。
            val vd = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ) ?: throw IllegalStateException("createVirtualDisplay returned null")
            virtualDisplay = vd
            codec = encoder
            encoder.start()
            currentWidth = width
            currentHeight = height
            currentBitrate = bitrate
            currentFps = fps
            cachedSps = null
            cachedPps = null
            firstKeyFrameEmitted = false
            isRunning = true
            lastError = null
            val t = Thread(::drainLoop, "BlindCast-ScreenCapture")
            t.isDaemon = true
            drainThread = t
            t.start()
            true
        } catch (t: Throwable) {
            lastError = t
            releaseLocked()
            false
        }
    }

    /**
     * 启动采集编码（四参便捷重载，上下文取自 [init] 或上次含 Context 的 [start]）。
     * 无可用上下文时返回 false 并记 [lastError]。
     */
    fun start(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        val ctx = appContextRef?.get()
        if (ctx == null) {
            lastError = IllegalStateException(
                "ScreenCaptureEngine: no cached context, call start(context, ...) or init(context) first",
            )
            return false
        }
        return start(ctx, width, height, bitrate, fps)
    }

    /** 停止采集编码（幂等）：停 drain 线程 + 释放 VirtualDisplay/Surface/Codec。 */
    @Synchronized
    fun stop() = stopLocked()

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun stopLocked() {
        isRunning = false
        drainThread?.interrupt()
        try {
            drainThread?.join(STOP_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            drainThread = null
        }
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        codec = null
        currentWidth = -1
        currentHeight = -1
        currentBitrate = -1
        currentFps = -1
        cachedSps = null
        cachedPps = null
        firstKeyFrameEmitted = false
    }

    private fun checkParams(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        val reason = when {
            width !in 160..3840 || height !in 160..3840 ->
                "invalid resolution ${width}x${height} (expect 160..3840)"
            bitrate < 100_000 -> "bitrate too low: $bitrate (expect >= 100_000)"
            fps !in 1..120 -> "invalid fps: $fps (expect 1..120)"
            else -> null
        }
        if (reason != null) {
            lastError = IllegalArgumentException("ScreenCaptureEngine: $reason")
            return false
        }
        return true
    }

    /** AVC Baseline 低延迟格式：realtime 优先级 + VBR + 1s I 帧间隔 + Surface 输入。 */
    private fun buildLowLatencyFormat(width: Int, height: Int, bitrate: Int, fps: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        format.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
        )
        // 低延迟总开关：realtime 优先级（0），编码器关闭 lookahead/重排。
        format.setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
        format.setInteger(
            MediaFormat.KEY_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        )
        // KEY_LEVEL 交由编码器按分辨率协商（手填易与分辨率错配导致 configure 失败）。
        format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
        return format
    }

    /**
     * 优选硬件 AVC 编码器：遍历 [MediaCodecList] 取首个硬件加速实现；
     * 无硬件（模拟器 / 裁剪 ROM）回退 `createEncoderByType`（可能为软件实现，
     * 功能照常，仅功耗/延迟非最优，行为记 log 不记错）。
     */
    private fun createHardwareAvcEncoder(): MediaCodec {
        val hwName = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { info ->
                    info.isEncoder &&
                        info.isHardwareAccelerated &&
                        info.supportedTypes.contains(MIME_TYPE)
                }?.name
        }.getOrNull()
        return if (hwName != null) {
            MediaCodec.createByCodecName(hwName)
        } else {
            MediaCodec.createEncoderByType(MIME_TYPE)
        }
    }

    /** drain 主循环：出队 -> 缓存 SPS/PPS -> 门闩首包 IDR -> 回调/Channel 投递。 */
    @Suppress("DEPRECATION") // INFO_OUTPUT_BUFFERS_CHANGED 仍需显式处理分支，取值语义未变。
    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val encoder = synchronized(lock) { codec } ?: break
            try {
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(encoder.outputFormat)
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (index >= 0) {
                            try {
                                onEncodedBuffer(encoder, index, info)
                            } finally {
                                runCatching { encoder.releaseOutputBuffer(index, false) }
                            }
                        }
                    }
                }
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // drain 异常只记录不崩溃；Codec 非法状态则下轮取不到实例自然退出。
                lastError = t
                if (t is MediaCodec.CodecException && t.isRecoverable.not()) {
                    // 不可恢复的编解码错误：短暂退避，避免热循环打爆 log。
                    runCatching { Thread.sleep(50L) }
                }
            }
        }
    }

    private fun onFormatChanged(format: MediaFormat) {
        runCatching {
            format.getByteBuffer("csd-0")?.let { csd0 ->
                cachedSps = annexBOf(csd0)
            }
            format.getByteBuffer("csd-1")?.let { csd1 ->
                cachedPps = annexBOf(csd1)
            }
        }
    }

    private fun onEncodedBuffer(encoder: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        val raw = encoder.getOutputBuffer(index)?.let { buf ->
            val dst = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(dst, 0, info.size)
            dst
        } ?: return
        val flags = info.flags
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // 编解码配置帧：只缓存 SPS/PPS，不对外投递。
            cacheCsdFromConfig(raw)
            return
        }
        val isKeyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val sps = cachedSps
        val pps = cachedPps
        // 门闩 1：SPS/PPS 未就绪前丢帧（首包必须能独立解码）。
        if (sps == null || pps == null) return
        // 门闩 2：首包必须为 IDR（非关键帧等待，不附 SPS/PPS 投递无意义）。
        if (!firstKeyFrameEmitted && !isKeyFrame) return
        val payload = if (isKeyFrame) sps + pps + raw else raw
        if (isKeyFrame) firstKeyFrameEmitted = true
        val packet = FramePacket(
            type = parseNaluType(payload),
            isKeyFrame = isKeyFrame,
            timestampUs = info.presentationTimeUs,
            payload = payload,
            sps = sps,
            pps = pps,
        )
        frameChannel.trySend(packet)
        runCatching { onFrame?.invoke(packet) }.onFailure { t ->
            lastError = t
        }
    }

    /** 从 `BUFFER_FLAG_CODEC_CONFIG` 负载中拆分缓存 SPS/PPS（AVCDecoderConfiguration 风格拼接亦兼容）。 */
    private fun cacheCsdFromConfig(raw: ByteArray) {
        for (nalu in splitAnnexB(raw)) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                FramePacket.NALU_TYPE_SPS -> cachedSps = startCode() + nalu
                FramePacket.NALU_TYPE_PPS -> cachedPps = startCode() + nalu
                else -> Unit
            }
        }
        // 非 Annex-B 风格（如纯 csd 直拼）：整体无法拆分时按序兜底。
        if (cachedSps == null && raw.isNotEmpty()) {
            cachedSps = ensureStartCode(raw)
        }
    }

    private fun annexBOf(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val dst = ByteArray(dup.remaining())
        dup.get(dst)
        return ensureStartCode(dst)
    }

    private fun parseNaluType(annexB: ByteArray): Int {
        val headerAt = findStartCode(annexB, 0)?.second ?: return FramePacket.NALU_TYPE_UNKNOWN
        if (headerAt >= annexB.size) return FramePacket.NALU_TYPE_UNKNOWN
        return annexB[headerAt].toInt() and 0x1F
    }

    /** 按 Annex-B 起始码切分负载为 NALU 裸体（含 header 字节，不含起始码）。 */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var searchFrom = 0
        while (true) {
            val found = findStartCode(data, searchFrom) ?: break
            bounds.add(found)
            searchFrom = found.second
            if (searchFrom >= data.size) break
        }
        if (bounds.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return bounds.mapIndexed { i, (_, headerAt) ->
            val end = if (i + 1 < bounds.size) bounds[i + 1].first else data.size
            data.copyOfRange(headerAt, end)
        }
    }

    /**
     * 从 [from] 起找下一个起始码。
     * @return `Pair(起始码首字节下标, NALU header 下标)`，找不到为 null。
     */
    private fun findStartCode(data: ByteArray, from: Int): Pair<Int, Int>? {
        var i = maxOf(from, 0)
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i to i + 3
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i to i + 4
                }
            }
            i++
        }
        return null
    }

    private fun startCode(): ByteArray = byteArrayOf(0, 0, 0, 1)

    private fun ensureStartCode(data: ByteArray): ByteArray {
        if (findStartCode(data, 0)?.first == 0) return data
        return startCode() + data
    }
}
