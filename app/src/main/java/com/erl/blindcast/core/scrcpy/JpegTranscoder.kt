package com.erl.blindcast.core.scrcpy

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * JPEG 降级转码链（Universal-1 阶段二 · App 侧对泵入帧再转一路 JPEG，与 H264 共存）。
 *
 * 背景：裸浏览器经 http 局域网访问为非安全源，`VideoDecoder` 为 undefined，
 * 既有 H264/WebCodecs 管线黑屏。故加一路 App 侧软解→JPEG，供无硬解端用
 * `createImageBitmap→drawImage` 出图（Canvas 照常，单文件零依赖，不塞 WASM）。
 *
 * ## 链路（只用安卓公开 API，不加新依赖）
 * `CaptureSocketLink.videoTap`（每 NALU payload，关键帧已内联 SPS+PPS）
 * → 本对象有界队列（满丢最旧，保实时）
 * → `MediaCodec` AVC **软解**（优选 `!isHardwareAccelerated`，无软解回退默认）
 * → `Image`（YUV_420_888）→ NV21 → `YuvImage.compressToJpeg`（质量 60）
 * → [onJpeg]（`StreamWsRoute` 注册后经 WS `0x03` 广播，
 *   线格式 `[1字节0x03+4字节大端长+JPEG]` 沿用既有帧格式）。
 *
 * ## 按需启停（省电）
 * - 前端经 control 发 `{"type":"videoMode","mode":"jpeg"|"h264"}` 声明，
 *   无声明默认 h264（[ControlWsRoute] 经 [setVideoMode]/[clear] 维护）；
 * - 仅当有 JPEG 订阅者（[hasDemand]）时跑解码链：drain 线程常驻但无需求时
 *   释放 codec + 清空队列 + 200ms 空转；有需求时懒起 codec；
 * - 输出限约 10fps（[JPEG_MIN_INTERVAL_MS]=100ms，中间解码帧只保预测链不转 JPEG）。
 *
 * ## H264 正确性注意
 * P 帧依赖前帧参考，故不可丢输入：输入全喂解码器保链，仅 JPEG 输出节流。
 * I 帧间隔 1s，首个 JPEG 需等首个 IDR 解出（约 <1s）。
 *
 * ## 线程模型
 * - [offer] 跑在 `CaptureSocketLink.readLoop` 线程，非阻塞（队列满丢最旧，不抛）；
 * - drain 单 daemon 线程（`BlindCast-Jpeg`）做 feed→drain→encode 全链；
 * - [setVideoMode]/[clear]/[hasDemand] 任意线程（ConcurrentHashMap）。
 */
object JpegTranscoder {

    private const val TAG = "BlindCast"

    /** JPEG 输出质量（任务包约 60）。 */
    const val JPEG_QUALITY = 60

    /** JPEG 输出最小间隔 80ms（Smooth-1 实测整链约 28ms/帧：解码+NV21+JPEG，
     * 80ms 窗保证动态屏 ≥8fps；静态屏按需出帧不空转，省电语义不变）。 */
    const val JPEG_MIN_INTERVAL_MS = 80L

    /** 无需求时 drain 空转步长 200ms（省电）。 */
    private const val NO_DEMAND_IDLE_MS = 200L

    /** 输入队列空时退避 2ms（与 StreamWsRoute 同量级）。 */
    private const val POLL_IDLE_MS = 2L

    /** 输入有界队列（满丢最旧，保实时不堆积）。 */
    private const val QUEUE_CAPACITY = 16

    /** MediaCodec 出队超时 10ms。 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 单帧上限 8MB（与特权侧一致，超限丢帧）。 */
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    /** 解码 MIME。 */
    private const val MIME = "video/avc"

    /** 订阅表：key=ControlWsRoute 的 WsConnection（以 Any 持有防 scrcpy→routes 循环依赖），value=jpeg|h264。 */
    private val demands = ConcurrentHashMap<Any, String>()

    /** JPEG 产出回调（StreamWsRoute 注册：`{ jpeg -> broadcastJpeg(jpeg) }`，无注册时丢弃省网）。 */
    @Volatile
    var onJpeg: ((ByteArray) -> Unit)? = null

    /** 最近一次失败（诊断用，不抛）。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 累计 H264 输入 / JPEG 输出计数（诊断用）。 */
    @Volatile
    var inputFrames: Long = 0L
        private set

    @Volatile
    var outputFrames: Long = 0L
        private set

    /** 解码器当前配置宽高（-1=未起，供重配判定）。 */
    @Volatile
    var decodedWidth: Int = -1
        private set

    @Volatile
    var decodedHeight: Int = -1
        private set

    private data class QueuedNalu(val payload: ByteArray, val isKey: Boolean, val atMs: Long)

    private val queue = LinkedBlockingQueue<QueuedNalu>(QUEUE_CAPACITY)

    @Volatile
    private var drainStarted = false
    private val drainLock = Any()

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var cachedSpsRaw: ByteArray? = null

    @Volatile
    private var cachedPpsRaw: ByteArray? = null

    @Volatile
    private var lastJpegAtMs: Long = 0L

    @Volatile
    private var configuredW: Int = -1

    @Volatile
    private var configuredH: Int = -1

    init {
        // 泵入帧分叉：CaptureSocketLink 每帧另调本侧 offer（同包内直接赋值，无循环依赖）。
        try {
            CaptureSocketLink.videoTap = { payload, isKey -> offer(payload, isKey) }
        } catch (_: Throwable) {
        }
        ensureDrain()
    }

    // ------------------------------------------------------------------
    // 订阅（ControlWsRoute 经此维护，无声明默认 h264）
    // ------------------------------------------------------------------

    /** 声明某控制连接的视频模式（仅 jpeg|h264，其余忽略）。 */
    fun setVideoMode(key: Any, mode: String) {
        val m = mode.lowercase()
        if (m != "jpeg" && m != "h264") return
        demands[key] = m
        if (m == "jpeg") ensureDrain()
        runCatching {
            Log.d(TAG, "[JpegTranscoder] setMode mode=$m demand=${demandCount()}")
        }
    }

    /** 清除某控制连接的声明（断开时调）。 */
    fun clear(key: Any) {
        demands.remove(key)
    }

    /** 是否有 JPEG 订阅者（有即跑解码链）。 */
    fun hasDemand(): Boolean = demands.values.any { it == "jpeg" }

    /** 当前 JPEG 订阅数（诊断/按需启停用）。 */
    fun demandCount(): Int = demands.values.count { it == "jpeg" }

    // ------------------------------------------------------------------
    // 输入（CaptureSocketLink.videoTap，经 init 接线）
    // ------------------------------------------------------------------

    /** 泵入一帧 H264 Annex-B（非阻塞；无需求/超限直接丢，不抛）。 */
    fun offer(payload: ByteArray, isKey: Boolean) {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) return
        if (!hasDemand()) return
        inputFrames++
        val q = QueuedNalu(payload, isKey, SystemClock.uptimeMillis())
        // 满丢最旧（保实时；offer 不阻塞搬运线程）。
        if (!queue.offer(q)) {
            runCatching { queue.poll() }
            runCatching { queue.offer(q) }
        }
    }

    // ------------------------------------------------------------------
    // drain（单 daemon 线程：feed→drain→JPEG 节流产出）
    // ------------------------------------------------------------------

    private fun ensureDrain() {
        if (drainStarted) return
        synchronized(drainLock) {
            if (drainStarted) return
            drainStarted = true
            val t = Thread(::drainLoop, "BlindCast-Jpeg")
            t.isDaemon = true
            t.start()
        }
    }

    private fun drainLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (!hasDemand()) {
                    releaseCodecLocked("no-demand")
                    runCatching { queue.clear() }
                    cachedSpsRaw = null
                    cachedPpsRaw = null
                    sleep(NO_DEMAND_IDLE_MS)
                    continue
                }
                val item = queue.poll()
                if (item == null) {
                    // 无输入时仍需 pump 输出（解码器内可能已就绪帧），短轮询一次。
                    pumpOutput(allowJpeg = true)
                    sleep(POLL_IDLE_MS)
                    continue
                }
                // 宽高跟随搬运服当前值（默认 1280x720；变档时重建解码器）。
                val wantW = CaptureSocketLink.currentWidth.takeIf { it > 0 } ?: 1280
                val wantH = CaptureSocketLink.currentHeight.takeIf { it > 0 } ?: 720
                if (item.isKey) extractSpsPps(item.payload)
                if (!ensureCodec(wantW, wantH)) {
                    sleep(POLL_IDLE_MS)
                    continue
                }
                feedInput(item)
                // 每输入一包即 pump 一次输出（JPEG 节流在 encode 侧）。
                pumpOutput(allowJpeg = true)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t
                runCatching { Log.e(TAG, "[JpegTranscoder] drain error", t) }
                runCatching { Thread.sleep(50L) }
            }
        }
    }

    private fun feedInput(item: QueuedNalu) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (item.payload.size > buf.remaining()) {
                runCatching { c.queueInputBuffer(idx, 0, 0, item.atMs * 1000L, 0) }
                return
            }
            buf.put(item.payload)
            val flags = if (item.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(idx, 0, item.payload.size, item.atMs * 1000L, flags)
        } catch (t: Throwable) {
            lastError = t
        }
    }

    private fun pumpOutput(allowJpeg: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        // 每轮最多 pump 2 个输出防单轮饿死输入。
        var rounds = 0
        while (rounds < 2) {
            rounds++
            val idx: Int = try {
                c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (t: Throwable) {
                lastError = t
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        val f = c.outputFormat
                        decodedWidth = f.getInteger(MediaFormat.KEY_WIDTH)
                        decodedHeight = f.getInteger(MediaFormat.KEY_HEIGHT)
                    } catch (_: Throwable) {
                    }
                    return
                }
                idx >= 0 -> {
                    try {
                        if (info.size <= 0) {
                            return
                        }
                        val now = SystemClock.uptimeMillis()
                        val due = allowJpeg && onJpeg != null && (now - lastJpegAtMs >= JPEG_MIN_INTERVAL_MS)
                        if (!due) {
                            return
                        }
                        val jpeg = imageToJpeg(c, idx, info)
                        if (jpeg != null && jpeg.isNotEmpty()) {
                            lastJpegAtMs = now
                            outputFrames++
                            lastError = null
                            runCatching { onJpeg?.invoke(jpeg) }
                        }
                    } finally {
                        runCatching { c.releaseOutputBuffer(idx, false) }
                    }
                    // 转出一张即回（节流由时间窗保证，不一次耗尽）。
                    return
                }
                else -> return
            }
        }
    }

    private fun ensureCodec(w: Int, h: Int): Boolean {
        val c = codec
        if (c != null && configuredW == w && configuredH == h) return true
        // 宽高变档或未起：重建（需 SPS/PPS 就绪，否则等关键帧）。
        val sps = cachedSpsRaw
        val pps = cachedPpsRaw
        if (sps == null || pps == null) return false
        releaseCodecLocked("reconfig")
        return try {
            val dec = createSoftwareAvcDecoder()
            val format = MediaFormat.createVideoFormat(MIME, w, h)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            dec.configure(format, null, null, 0)
            dec.start()
            codec = dec
            configuredW = w
            configuredH = h
            lastError = null
            runCatching { Log.i(TAG, "[JpegTranscoder] decoder start ${w}x${h} soft sps=${sps.size} pps=${pps.size}") }
            true
        } catch (t: Throwable) {
            lastError = t
            runCatching { Log.e(TAG, "[JpegTranscoder] decoder start failed ${w}x${h}", t) }
            releaseCodecLocked("start-failed")
            // SPS/PPS 可能错配当前档：清掉等下个 IDR 重提。
            cachedSpsRaw = null
            cachedPpsRaw = null
            false
        }
    }

    private fun releaseCodecLocked(reason: String) {
        val c = codec
        codec = null
        configuredW = -1
        configuredH = -1
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
            runCatching { Log.i(TAG, "[JpegTranscoder] decoder released ($reason)") }
        }
    }

    /** 优选软解 AVC decoder（`!isHardwareAccelerated`），无软解回退默认。 */
    private fun createSoftwareAvcDecoder(): MediaCodec {
        val soft = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                try {
                    !info.isEncoder && !info.isHardwareAccelerated && info.supportedTypes.contains(MIME)
                } catch (_: Throwable) {
                    false
                }
            }?.name
        }.getOrNull()
        return if (!soft.isNullOrBlank()) {
            MediaCodec.createByCodecName(soft)
        } else {
            MediaCodec.createDecoderByType(MIME)
        }
    }

    /** 从关键帧 Annex-B 中提取 SPS/PPS 裸体（去起始码，供 csd-0/1）。 */
    private fun extractSpsPps(annexB: ByteArray) {
        if (cachedSpsRaw != null && cachedPpsRaw != null) return
        try {
            for (nalu in splitAnnexB(annexB)) {
                if (nalu.isEmpty()) continue
                when (nalu[0].toInt() and 0x1F) {
                    7 -> if (cachedSpsRaw == null) cachedSpsRaw = nalu.copyOf()
                    8 -> if (cachedPpsRaw == null) cachedPpsRaw = nalu.copyOf()
                    else -> Unit
                }
                if (cachedSpsRaw != null && cachedPpsRaw != null) break
            }
        } catch (t: Throwable) {
            lastError = t
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val f = findStartCode(data, from) ?: break
            bounds.add(f)
            from = f.second
            if (from >= data.size) break
        }
        if (bounds.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return bounds.mapIndexed { i, (_, headerAt) ->
            val end = if (i + 1 < bounds.size) bounds[i + 1].first else data.size
            data.copyOfRange(headerAt, end)
        }
    }

    private fun findStartCode(data: ByteArray, from: Int): Pair<Int, Int>? {
        var i = maxOf(from, 0)
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i to i + 3
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i to i + 4
            }
            i++
        }
        return null
    }

    /** 输出 Image → NV21 → JPEG（质量 60；失败返回 null 不抛）。 */
    private fun imageToJpeg(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ByteArray? {
        // info 仅用于占位（实际取 Image 尺寸）；保留参数防误调。
        @Suppress("UNUSED_PARAMETER")
        val unused = info
        val image: Image = try {
            c.getOutputImage(index) ?: return null
        } catch (_: Throwable) {
            return null
        }
        try {
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0 || w > 3840 || h > 3840) return null
            val nv21 = imageToNv21(image) ?: return null
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream(w * h)
            val ok = yuv.compressToJpeg(Rect(0, 0, w, h), JPEG_QUALITY, out)
            if (!ok) return null
            return out.toByteArray()
        } catch (t: Throwable) {
            lastError = t
            return null
        } finally {
            runCatching { image.close() }
        }
    }

    /** `YUV_420_888` Image → NV21（处理 rowStride/pixelStride 通用路径）。 */
    private fun imageToNv21(image: Image): ByteArray? {
        return try {
            val w = image.width
            val h = image.height
            val planes = image.planes
            if (planes.size < 3) return null
            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val yRow = planes[0].rowStride
            val uRow = planes[1].rowStride
            val vRow = planes[2].rowStride
            val uPix = planes[1].pixelStride
            val vPix = planes[2].pixelStride
            val nv21 = ByteArray(w * h * 3 / 2)
            // Y 平面逐行拷（处理 rowStride padding）。
            var dst = 0
            val yRowTmp = ByteArray(yRow)
            for (row in 0 until h) {
                yBuf.position(row * yRow)
                val take = minOf(w, yBuf.remaining())
                yBuf.get(nv21, dst, take)
                dst += w
                // 若 rowStride>w，position 下轮重定，无需 skip。
                @Suppress("UNUSED_VARIABLE")
                val ignore = yRowTmp
            }
            // UV 交织 VU（NV21）：逐像素采样（兼容 pixelStride 1/2 任意）。
            var uvDst = w * h
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    val vuPos = row * vRow + col * vPix
                    val uuPos = row * uRow + col * uPix
                    val v: Byte = if (vuPos < vBuf.limit()) {
                        vBuf.get(vuPos)
                    } else {
                        0
                    }
                    val u: Byte = if (uuPos < uBuf.limit()) {
                        uBuf.get(uuPos)
                    } else {
                        0
                    }
                    nv21[uvDst++] = v
                    nv21[uvDst++] = u
                }
            }
            nv21
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }
}
