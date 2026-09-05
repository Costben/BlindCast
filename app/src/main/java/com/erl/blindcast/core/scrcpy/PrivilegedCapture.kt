package com.erl.blindcast.core.scrcpy

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 特权采集引擎（Stream-Priv-1 · scrcpy 同构：特权采集 + socket 回传；
 * Stream-Priv-3 起建 Display 改道 DisplayManager 隐藏 overload 为主）。
 *
 * 运行身份：必须跑在特权进程内（Shizuku UserService / Root `app_process` 常驻），
 * 以系统身份建 VirtualDisplay 直连屏幕镜像 + `REMOTE_SUBMIX` 内录。
 * 普通 App 进程调同样代码必吃 SecurityException（见实证诊断），本对象捕获后记
 * [lastError] 并返回 false，永不崩溃。
 *
 * ## Display 路线（Stream-Priv-3 · root 免权限镜像）
 * 主路 `DisplayManager.createVirtualDisplay` 无 projection 隐藏重载（要
 * `CAPTURE_VIDEO_OUTPUT` 签名级权限，root uid=0 直接放行）：
 * - flags `VIRTUAL_DISPLAY_FLAG_PUBLIC + AUTO_MIRROR`，encode Surface 直连；
 * - 无 Context 裸进程经 `ActivityThread.currentApplication/getSystemContext`
 *   反射取 Context → `getSystemService(DisplayManager)`，逐个记日志；
 * - 参数签名逐个反射尝试（本 ROM 常见重载全列）：
 *   `(name,w,h,dpi,surface,flags)` /
 *   `(name,w,h,dpi,surface,flags,callback,handler)` /
 *   `(name,w,h,dpi,surface,flags,callback,handler,uniqueId:String)` /
 *   `(name,w,h,dpi,surface,flags,callback,handler,displayId:int)`，
 *   命中即用（callback/handler 传 null，uniqueId 传 null，displayId 传 0）；
 * - 全部 miss/抛错则降级备用路（日志写清）。
 * 备用 `SurfaceControl.createDisplay` scrcpy 路线（保留既有）：
 * - 同样逐个试已知重载 `(String,boolean)` 主 + `(String,boolean,String)` /
 *   `(String,int)` 备选，逐个记日志；
 * - 建屏后 `setDisplaySurface/setDisplayLayerStack/setDisplayProjection` 挂编码面。
 * OPlus Android 15 真机实证：`SurfaceControl.createDisplay(String,boolean)`
 * 根本不存在（NoSuchMethodException），故主路必须走 DisplayManager。
 *
 * - `MediaCodec` H264 Baseline realtime + VBR + 1s I 帧，drain 线出 Annex-B NALU；
 * - 音频 `REMOTE_SUBMIX` PCM 直抓 → AAC-LC（48k 立体声 128k 默认），同 socket 复用。
 *
 * ## socket 帧格式（与 [CaptureSocketLink] / `StreamWsRoute` 对齐）
 * 单条 TCP/LocalSocket 流多路复用：`[1 字节通道 + 4 字节大端长度 + payload]`。
 * - `0x01` 视频：H264 Annex-B NALU（含起始码；关键帧已内联 SPS+PPS，首包必 IDR）；
 * - `0x02` 音频：AAC 裸帧（无 ADTS；config 就绪前丢帧，对外包可独立解码）。
 * App 侧 [CaptureSocketLink] 按此格式读帧后喂入既有 `ScreenCaptureEngine.frameChannel`
 * / `AudioCaptureEngine.audioChannel`，复用其回调给 `StreamWsRoute`，编解码与 WS 路由不动。
 *
 * ## 无 Android 组件依赖
 * 纯静态函数，无 Context/Service/Activity 依赖（仅用 MediaCodec/AudioRecord/
 * LocalSocket/反射），可在 `app_process` 裸进程内运行（含 [RootCaptureMain] 常驻）。
 * DisplayManager 实例同样经反射自取（无需调用方传 Context）。
 *
 * ## 线程模型
 * - [start] 幂等可重配：运行中再次调用先静默 [stop] 再按新参数启动；
 * - video drain + audio capture/drain 均为 daemon，进程退出不阻止 VM 回收；
 * - [stop] 幂等：停线程（各 join 至多 1s）+ 销毁 Display/Surface/Codec/Socket；
 * - socket 写多线程共享，统一 `socketLock` 同步，单帧原子写；
 * - 所有异常记 [lastError] 不抛崩；成功启动后 [lastError] 清零。
 */
object PrivilegedCapture {

    /** 全链路统一 TAG（特权进程 logcat 可见）。 */
    private const val TAG = "BlindCast"

    /** LocalSocket 抽象命名（App 侧 [CaptureSocketLink] 建服， idle 特权侧连服）。 */
    const val SOCKET_NAME = "blindcast_capture"

    /** 视频通道头（与 StreamWsRoute.KIND_VIDEO 同值 0x01）。 */
    const val CHANNEL_VIDEO: Byte = 0x01

    /** 音频通道头（与 StreamWsRoute.KIND_AUDIO 同值 0x02）。 */
    const val CHANNEL_AUDIO: Byte = 0x02

    /** 编码 MIME：H.264 / AVC。 */
    const val VIDEO_MIME = "video/avc"

    /** 音频 MIME：AAC-LC。 */
    const val AUDIO_MIME = "audio/mp4a-latm"

    /** 虚拟屏显示名（logcat / dumpsys 可见，scrcpy 风格）。 */
    const val DISPLAY_NAME = "BlindCastCapture"

    /** I 帧间隔 1s（与 ScreenCaptureEngine 一致，弱网 1s 自愈）。 */
    private const val I_FRAME_INTERVAL_SEC = 1

    /** realtime 优先级（MediaFormat.KEY_PRIORITY=0，API 26+）。 */
    private const val PRIORITY_REALTIME = 0

    /** drain 出队超时 10ms。 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** stop 单线程 join 上限 1s。 */
    private const val STOP_JOIN_MS = 1_000L

    /** socket 连接超时 3s（App 侧服未起时快速失败记 lastError）。 */
    private const val SOCKET_CONNECT_TIMEOUT_MS = 3_000

    /** 单帧上限 8MB（防恶意/错乱长度炸内存，超限丢帧记错）。 */
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    /** 音频默认采样率/声道/码率（与 AudioCaptureEngine 默认一致）。 */
    const val DEFAULT_SAMPLE_RATE = 48000
    const val DEFAULT_CHANNEL_COUNT = 2
    const val DEFAULT_AUDIO_BITRATE = 128_000

    /** 是否正在采集（含视频/音频任一在跑）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 视频是否在跑（display+encoder 存活且 drain 未退）。 */
    @Volatile
    var videoRunning: Boolean = false
        private set

    /** 音频是否在跑（record+encoder 存活；失败时 false 但视频可独跑）。 */
    @Volatile
    var audioRunning: Boolean = false
        private set

    /** 最近一次失败异常；成功 [start] 后清零，供诊断消费。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    @Volatile var currentWidth: Int = -1; private set
    @Volatile var currentHeight: Int = -1; private set
    @Volatile var currentBitrate: Int = -1; private set
    @Volatile var currentFps: Int = -1; private set

    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null
    @Volatile private var firstKeyFrameEmitted = false
    @Volatile private var spsMissingWarned = false
    @Volatile private var cachedAudioConfig: ByteArray? = null
    @Volatile private var audioStartNs: Long = 0L

    private val lock = Any()
    private val socketLock = Any()
    private var clientSocket: LocalSocket? = null
    private var socketOut: OutputStream? = null
    private var ownsSocket = false

    private var videoCodec: MediaCodec? = null
    private var videoSurface: Surface? = null
    private var displayToken: IBinder? = null
    private var virtualDisplay: VirtualDisplay? = null
    /** 本次建屏路由：DisplayManager / SurfaceControl（日志 + 释放分支用）。 */
    @Volatile private var displayRoute: String? = null
    private var videoDrain: Thread? = null

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioCaptureThread: Thread? = null
    private var audioDrainThread: Thread? = null

    private val stopped = AtomicBoolean(true)

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 启动音视频特权采集并回传 App 侧 socket 服（主入口，同步返回）。
     * 内部 `LocalSocket.connect(abstract:blindcast_capture)`，要求 App 侧
     * [CaptureSocketLink] 已 `LocalServerSocket` 监听，否则返回 false。
     */
    @Synchronized
    fun start(width: Int, height: Int, bitrate: Int, fps: Int): Boolean =
        startInternal(width, height, bitrate, fps, SOCKET_NAME)

    /** 同 [start]，socket 名可覆盖（测试/多实例用；常规传 [SOCKET_NAME]）。 */
    @Synchronized
    fun start(width: Int, height: Int, bitrate: Int, fps: Int, socketName: String): Boolean =
        startInternal(width, height, bitrate, fps, socketName)

    /**
     * 单视频启动（任务包 `startVideo(width,height,bitrate,fps,fd/socketOut)` 语义）：
     * 编码输出写给定 [out]（调用方持有 socket/fd 生命期，本对象不关流）。
     * 音频不启动；供单测/Root 单次执行器复用。常规流请走 [start]（音视频复用单 socket）。
     */
    @Synchronized
    fun startVideo(width: Int, height: Int, bitrate: Int, fps: Int, out: OutputStream): Boolean {
        if (isRunning) stopLocked()
        if (!checkVideoParams(width, height, bitrate, fps)) return false
        stopped.set(false)
        return try {
            socketOut = out
            ownsSocket = false
            startVideoEncoderLocked(width, height, bitrate, fps)
            cachedAudioConfig = null
            isRunning = true
            lastError = null
            Log.i(TAG, "[PrivilegedCapture] startVideo ok ${width}x${height} ${bitrate}bps ${fps}fps")
            true
        } catch (t: Throwable) {
            lastError = t
            Log.e(TAG, "[PrivilegedCapture] startVideo failed", t)
            releaseVideoLocked()
            socketOut = null
            false
        }
    }

    /**
     * 单音频启动（`startAudio(...)` 语义）：AAC 输出写给定 [out]。
     * 默认 48k 立体声 128k，可经重载改参。常规流请走 [start]。
     */
    @Synchronized
    fun startAudio(
        out: OutputStream,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channelCount: Int = DEFAULT_CHANNEL_COUNT,
        bitrate: Int = DEFAULT_AUDIO_BITRATE,
    ): Boolean {
        if (isRunning) stopLocked()
        stopped.set(false)
        return try {
            socketOut = out
            ownsSocket = false
            startAudioEncoderLocked(sampleRate, channelCount, bitrate)
            isRunning = true
            lastError = null
            Log.i(TAG, "[PrivilegedCapture] startAudio ok ${sampleRate}Hz x${channelCount} ${bitrate}bps")
            true
        } catch (t: Throwable) {
            lastError = t
            Log.e(TAG, "[PrivilegedCapture] startAudio failed", t)
            releaseAudioLocked()
            socketOut = null
            false
        }
    }

    /** 停止全部采集并释放 display/codec/socket（幂等）。 */
    @Synchronized
    fun stop() = stopLocked()

    /** 取最近失败文案（binder 跨进程回读用，成功/无记录 null）。 */
    fun errorMessage(): String? = lastError?.message ?: lastError?.toString()

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private fun startInternal(width: Int, height: Int, bitrate: Int, fps: Int, socketName: String): Boolean {
        if (isRunning) stopLocked()
        if (!checkVideoParams(width, height, bitrate, fps)) return false
        // 必须在起任何 drain 线程之前清停止旗：drain 循环首条件即读 stopped，
        // 晚清会导致线程出生即退（编码器空转、出帧堆仓、零帧零错）。
        stopped.set(false)
        var sock: LocalSocket? = null
        return try {
            sock = LocalSocket()
            // 注意：LocalSocket.connect(address, timeout) 在 Android 上直接抛
            // UnsupportedOperationException（未实现），必须用无超时版 + 自管线程实现超时。
            val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
            val connecting = sock
            var connectError: Throwable? = null
            val connectThread = Thread {
                try {
                    connecting.connect(address)
                } catch (t: Throwable) {
                    connectError = t
                }
            }.apply { isDaemon = true }
            connectThread.start()
            connectThread.join(SOCKET_CONNECT_TIMEOUT_MS.toLong())
            if (connectThread.isAlive) {
                runCatching { connecting.close() }
                throw java.net.SocketTimeoutException(
                    "LocalSocket connect $socketName timeout ${SOCKET_CONNECT_TIMEOUT_MS}ms",
                )
            }
            connectError?.let { throw it }
            val out = BufferedOutputStream(sock.outputStream, 64 * 1024)
            clientSocket = sock
            socketOut = out
            ownsSocket = true
            sock = null // 所有权移交字段
            startVideoEncoderLocked(width, height, bitrate, fps)
            // 音频为尽力而为：失败只记错不掀翻视频（挂机无声仍可看）。
            runCatching { startAudioEncoderLocked(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT, DEFAULT_AUDIO_BITRATE) }
                .onFailure { t ->
                    Log.w(TAG, "[PrivilegedCapture] audio start failed (video-only continue)", t)
                    if (lastError == null) lastError = t
                }
            currentWidth = width
            currentHeight = height
            currentBitrate = bitrate
            currentFps = fps
            isRunning = true
            if (videoRunning) lastError = null
            Log.i(TAG, "[PrivilegedCapture] start ok ${width}x${height} ${bitrate}bps ${fps}fps " +
                "video=$videoRunning audio=$audioRunning sock=$socketName")
            videoRunning
        } catch (t: Throwable) {
            if (lastError == null) lastError = t
            Log.e(TAG, "[PrivilegedCapture] start failed", t)
            runCatching { sock?.close() }
            stopLocked()
            // stopLocked 不清 lastError（保留失败明细供 getCaptureError 回读）。
            if (lastError == null) lastError = t
            false
        }
    }

    private fun stopLocked() {
        stopped.set(true)
        isRunning = false
        videoRunning = false
        audioRunning = false
        videoDrain?.interrupt()
        audioCaptureThread?.interrupt()
        audioDrainThread?.interrupt()
        try { videoDrain?.join(STOP_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { audioCaptureThread?.join(STOP_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        try { audioDrainThread?.join(STOP_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        videoDrain = null
        audioCaptureThread = null
        audioDrainThread = null
        releaseVideoLocked()
        releaseAudioLocked()
        runCatching { socketOut?.flush() }
        if (ownsSocket) runCatching { socketOut?.close() }
        socketOut = null
        runCatching { clientSocket?.close() }
        clientSocket = null
        ownsSocket = false
        currentWidth = -1
        currentHeight = -1
        currentBitrate = -1
        currentFps = -1
        cachedSps = null
        cachedPps = null
        firstKeyFrameEmitted = false
        spsMissingWarned = false
        cachedAudioConfig = null
    }

    // ------------------------------------------------------------------
    // 视频：DisplayManager 主路 + SurfaceControl 备用 + AVC 编码
    // ------------------------------------------------------------------

    private fun startVideoEncoderLocked(width: Int, height: Int, bitrate: Int, fps: Int) {
        val encoder = createHardwareAvcEncoder()
        try {
            encoder.configure(buildLowLatencyVideoFormat(width, height, bitrate, fps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            runCatching { encoder.release() }
            throw t
        }
        val surface = encoder.createInputSurface()
        videoSurface = surface
        // 主路优先：DisplayManager 隐藏无 projection 重载（root 免 CAPTURE_VIDEO_OUTPUT）。
        // 备用：SurfaceControl.createDisplay scrcpy 路线（OPlus 上主签名已无，需逐个试）。
        var vd: VirtualDisplay? = null
        var token: IBinder? = null
        var route: String? = null
        var dmErr: Throwable? = null
        try {
            vd = createVirtualDisplayViaDisplayManager(surface, width, height)
            route = "DisplayManager"
        } catch (t: Throwable) {
            dmErr = t
            Log.w(TAG, "[PrivilegedCapture] DisplayManager route miss, fallback SurfaceControl", t)
        }
        if (vd != null) {
            virtualDisplay = vd
            displayRoute = route
            Log.i(TAG, "[PrivilegedCapture] display route=DisplayManager ok ${width}x${height} vd=${vd.display?.displayId}")
        } else {
            try {
                token = createPrivilegedDisplay(surface, width, height)
                route = "SurfaceControl"
            } catch (t: Throwable) {
                runCatching { surface.release() }
                videoSurface = null
                runCatching { encoder.release() }
                // 主备双路全灭：把两路异常串起来，方便 getCaptureError 一眼定位。
                val dmMsg = dmErr?.let { "DM:${it.javaClass.simpleName}:${it.message}" } ?: "DM:unknown"
                throw IllegalStateException("Display both routes failed [$dmMsg] [SC:${t.javaClass.simpleName}:${t.message}]", t)
            }
            displayToken = token
            displayRoute = route
            Log.i(TAG, "[PrivilegedCapture] display route=SurfaceControl ok ${width}x${height}")
        }
        videoCodec = encoder
        try {
            encoder.start()
        } catch (t: Throwable) {
            releaseVideoLocked()
            throw t
        }
        cachedSps = null
        cachedPps = null
        firstKeyFrameEmitted = false
        spsMissingWarned = false
        videoRunning = true
        val t = Thread(::videoDrainLoop, "BlindCast-PrivVideo")
        t.isDaemon = true
        videoDrain = t
        t.start()
    }

    private fun releaseVideoLocked() {
        videoRunning = false
        val vd = virtualDisplay
        virtualDisplay = null
        if (vd != null) {
            runCatching { vd.release() }
            Log.d(TAG, "[PrivilegedCapture] virtualDisplay.release ok route=$displayRoute")
        }
        val token = displayToken
        displayToken = null
        if (token != null) runCatching { destroyPrivilegedDisplay(token) }
        displayRoute = null
        runCatching { videoSurface?.release() }
        videoSurface = null
        runCatching {
            videoCodec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        videoCodec = null
    }

    private fun videoDrainLoop() {
        val info = MediaCodec.BufferInfo()
        while (!stopped.get() && videoRunning && !Thread.currentThread().isInterrupted) {
            val encoder = synchronized(lock) { videoCodec } ?: break
            try {
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onVideoFormatChanged(encoder.outputFormat)
                    else -> {
                        if (index >= 0) {
                            try { onVideoBuffer(encoder, index, info) }
                            finally { runCatching { encoder.releaseOutputBuffer(index, false) } }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t
                Log.e(TAG, "[PrivilegedCapture] video drain error", t)
                if (t is MediaCodec.CodecException && !t.isRecoverable) runCatching { Thread.sleep(50L) }
            }
        }
    }

    private fun onVideoFormatChanged(format: MediaFormat) {
        runCatching {
            format.getByteBuffer("csd-0")?.let { cachedSps = annexBOf(it.duplicate()) }
            format.getByteBuffer("csd-1")?.let { cachedPps = annexBOf(it.duplicate()) }
        }
    }

    private fun onVideoBuffer(encoder: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        if (info.size > MAX_FRAME_BYTES) {
            Log.w(TAG, "[PrivilegedCapture] video frame too large ${info.size}, drop")
            return
        }
        val raw = encoder.getOutputBuffer(index)?.let { buf ->
            val dst = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(dst, 0, info.size)
            dst
        } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            cacheCsdFromConfig(raw)
            return
        }
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val sps = cachedSps
        val pps = cachedPps
        if (sps == null || pps == null) {
            if (!spsMissingWarned) {
                spsMissingWarned = true
                Log.w(TAG, "[PrivilegedCapture] drop video frame: sps/pps not ready " +
                    "(sps=${sps != null} pps=${pps != null} size=${info.size} flags=${info.flags})")
            }
            return
        }
        if (!firstKeyFrameEmitted && !isKey) return
        val payload = if (isKey) sps + pps + raw else raw
        if (isKey) firstKeyFrameEmitted = true
        writeFrame(CHANNEL_VIDEO, payload)
    }

    // ------------------------------------------------------------------
    // 音频：REMOTE_SUBMIX 直抓 + AAC
    // ------------------------------------------------------------------

    private fun startAudioEncoderLocked(sampleRate: Int, channelCount: Int, bitrate: Int) {
        val encoder = createHardwareAacEncoder()
        try {
            encoder.configure(buildAacFormat(sampleRate, channelCount, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (t: Throwable) {
            runCatching { encoder.release() }
            throw t
        }
        val record = createSubmixRecord(sampleRate, channelCount)
        try {
            encoder.start()
        } catch (t: Throwable) {
            runCatching { record.release() }
            runCatching { encoder.release() }
            throw t
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            runCatching { record.release() }
            runCatching { encoder.release() }
            throw IllegalStateException("AudioRecord startRecording failed（缺提权？需 Shizuku/Root 进程）", t)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { encoder.release() }
            throw IllegalStateException("AudioRecord not recording (state=${record.recordingState})，缺提权或被占用")
        }
        audioCodec = encoder
        audioRecord = record
        cachedAudioConfig = null
        audioStartNs = System.nanoTime()
        audioRunning = true
        val ct = Thread({ audioCaptureLoop(channelCount) }, "BlindCast-PrivAudioCap")
        ct.isDaemon = true
        audioCaptureThread = ct
        ct.start()
        val dt = Thread(::audioDrainLoop, "BlindCast-PrivAudioDrain")
        dt.isDaemon = true
        audioDrainThread = dt
        dt.start()
    }

    private fun releaseAudioLocked() {
        audioRunning = false
        runCatching {
            audioRecord?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        audioRecord = null
        runCatching {
            audioCodec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        audioCodec = null
        cachedAudioConfig = null
    }

    private fun audioCaptureLoop(channels: Int) {
        val chunk = ByteArray(2048 * channels.coerceIn(1, 2) * 2)
        while (!stopped.get() && audioRunning && !Thread.currentThread().isInterrupted) {
            val record = synchronized(lock) { audioRecord } ?: break
            val n = try { record.read(chunk, 0, chunk.size) } catch (t: Throwable) {
                lastError = t
                if (stopped.get()) break else continue
            }
            when {
                n > 0 -> queueAudioPcm(chunk, n)
                n == AudioRecord.ERROR_DEAD_OBJECT -> {
                    lastError = IllegalStateException("PrivilegedCapture: AudioRecord dead object")
                    break
                }
                n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE -> {
                    lastError = IllegalStateException("PrivilegedCapture: AudioRecord read error=$n")
                    runCatching { Thread.sleep(10L) }
                }
                else -> runCatching { Thread.sleep(10L) }
            }
        }
    }

    private fun queueAudioPcm(data: ByteArray, size: Int) {
        val encoder = synchronized(lock) { audioCodec } ?: return
        try {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return
            val input = encoder.getInputBuffer(index) ?: return
            val n = minOf(size, input.remaining())
            input.put(data, 0, n)
            encoder.queueInputBuffer(index, 0, n, (System.nanoTime() - audioStartNs) / 1000L, 0)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            lastError = t
        }
    }

    private fun audioDrainLoop() {
        val info = MediaCodec.BufferInfo()
        while (!stopped.get() && audioRunning && !Thread.currentThread().isInterrupted) {
            val encoder = synchronized(lock) { audioCodec } ?: break
            try {
                when (val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> runCatching {
                        encoder.outputFormat.getByteBuffer("csd-0")?.let {
                            val dup = it.duplicate()
                            val dst = ByteArray(dup.remaining())
                            dup.get(dst)
                            cachedAudioConfig = dst
                        }
                    }
                    else -> {
                        if (index >= 0) {
                            try { onAudioBuffer(encoder, index, info) }
                            finally { runCatching { encoder.releaseOutputBuffer(index, false) } }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t
                if (t is MediaCodec.CodecException && !t.isRecoverable) runCatching { Thread.sleep(50L) }
            }
        }
    }

    private fun onAudioBuffer(encoder: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        if (info.size <= 0 || info.size > MAX_FRAME_BYTES) return
        val raw = encoder.getOutputBuffer(index)?.let { buf ->
            val dst = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(dst, 0, info.size)
            dst
        } ?: return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            if (raw.isNotEmpty()) cachedAudioConfig = raw
            return
        }
        if (cachedAudioConfig == null) return
        writeFrame(CHANNEL_AUDIO, raw)
    }

    // ------------------------------------------------------------------
    // socket 写帧
    // ------------------------------------------------------------------

    private fun writeFrame(channel: Byte, payload: ByteArray) {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) return
        val out = synchronized(lock) { socketOut } ?: return
        synchronized(socketLock) {
            try {
                out.write(channel.toInt())
                out.write((payload.size ushr 24) and 0xFF)
                out.write((payload.size ushr 16) and 0xFF)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
                out.write(payload)
                // 视频关键帧边界 flush，音频每包不 flush（靠视频 flush 捎带，降 syscall）。
                if (channel == CHANNEL_VIDEO) out.flush()
            } catch (t: Throwable) {
                lastError = t
            }
        }
    }

    // ------------------------------------------------------------------
    // Display 路线：DisplayManager 主路（反射隐藏 overload）+ SurfaceControl 备用
    // ------------------------------------------------------------------

    private fun ensureExempted() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/view/SurfaceControl") }
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/hardware/display/DisplayManager",
                "Landroid/hardware/display/DisplayManagerGlobal",
                "Landroid/hardware/display/VirtualDisplay",
                "Landroid/view/Display",
            )
        }
    }

    private fun surfaceControlClass(): Class<*> {
        ensureExempted()
        return Class.forName("android.view.SurfaceControl")
    }

    /** 裸特权进程取 Context（DisplayManager 来源）：逐个反射尝试并记日志。 */
    private fun tryObtainContext(): Context? {
        // 1) ActivityThread.currentApplication()
        try {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentApplication")
            m.isAccessible = true
            val app = m.invoke(null) as? Context
            if (app != null) {
                Log.d(TAG, "[PrivilegedCapture] ctx via currentApplication ok ${app.javaClass.name}")
                return app
            }
            Log.d(TAG, "[PrivilegedCapture] ctx via currentApplication=null")
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] ctx via currentApplication failed ${t.javaClass.simpleName}:${t.message}")
        }
        // 2) currentActivityThread().getApplication() / getSystemContext()
        try {
            val at = Class.forName("android.app.ActivityThread")
            val curThreadM = at.getDeclaredMethod("currentActivityThread")
            curThreadM.isAccessible = true
            val curThread = curThreadM.invoke(null)
            if (curThread != null) {
                try {
                    val getApp = curThread.javaClass.getDeclaredMethod("getApplication")
                    getApp.isAccessible = true
                    val app = getApp.invoke(curThread) as? Context
                    if (app != null) {
                        Log.d(TAG, "[PrivilegedCapture] ctx via getApplication ok ${app.javaClass.name}")
                        return app
                    }
                    Log.d(TAG, "[PrivilegedCapture] ctx via getApplication=null")
                } catch (t: Throwable) {
                    Log.d(TAG, "[PrivilegedCapture] ctx via getApplication failed ${t.javaClass.simpleName}:${t.message}")
                }
                try {
                    val getSys = curThread.javaClass.getDeclaredMethod("getSystemContext")
                    getSys.isAccessible = true
                    val sys = getSys.invoke(curThread) as? Context
                    if (sys != null) {
                        Log.d(TAG, "[PrivilegedCapture] ctx via getSystemContext ok ${sys.javaClass.name}")
                        return sys
                    }
                    Log.d(TAG, "[PrivilegedCapture] ctx via getSystemContext=null")
                } catch (t: Throwable) {
                    Log.d(TAG, "[PrivilegedCapture] ctx via getSystemContext failed ${t.javaClass.simpleName}:${t.message}")
                }
            } else {
                Log.d(TAG, "[PrivilegedCapture] ctx currentActivityThread=null")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] ctx via currentActivityThread failed ${t.javaClass.simpleName}:${t.message}")
        }
        // 3) AppGlobals.getInitialApplication()
        try {
            val ag = Class.forName("android.app.AppGlobals")
            val getInit = ag.getDeclaredMethod("getInitialApplication")
            getInit.isAccessible = true
            val app = getInit.invoke(null) as? Context
            if (app != null) {
                Log.d(TAG, "[PrivilegedCapture] ctx via AppGlobals ok ${app.javaClass.name}")
                return app
            }
            Log.d(TAG, "[PrivilegedCapture] ctx via AppGlobals=null")
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] ctx via AppGlobals failed ${t.javaClass.simpleName}:${t.message}")
        }
        Log.w(TAG, "[PrivilegedCapture] ctx all means miss (bare app_process?)")
        return null
    }

    private fun obtainDisplayManager(context: Context?): DisplayManager? {
        val ctx = context ?: tryObtainContext()
        if (ctx == null) {
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.obtain ctx=null skip")
            return null
        }
        return try {
            var dm: DisplayManager? = null
            try {
                dm = ctx.getSystemService(DisplayManager::class.java)
            } catch (t: Throwable) {
                Log.d(TAG, "[PrivilegedCapture] DisplayManager.obtain via class failed ${t.javaClass.simpleName}:${t.message}")
            }
            if (dm == null) {
                @Suppress("DEPRECATION")
                dm = runCatching { ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager }.getOrNull()
            }
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.obtain dmNull=${dm == null} ctx=${ctx.javaClass.name}")
            dm
        } catch (t: Throwable) {
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.obtain threw ${t.javaClass.simpleName}:${t.message}")
            null
        }
    }

    private fun getDensityDpi(context: Context?): Int {
        if (context != null) {
            try {
                val dpi = context.resources.displayMetrics.densityDpi
                if (dpi > 0) {
                    Log.d(TAG, "[PrivilegedCapture] dpi via Context=$dpi")
                    return dpi
                }
                Log.d(TAG, "[PrivilegedCapture] dpi via Context invalid=$dpi")
            } catch (t: Throwable) {
                Log.d(TAG, "[PrivilegedCapture] dpi via Context failed ${t.javaClass.simpleName}:${t.message}")
            }
        } else {
            Log.d(TAG, "[PrivilegedCapture] dpi ctx=null skip Context")
        }
        // 回退：DisplayManagerGlobal.getDisplayInfo(0).logicalDensityDpi/densityDpi
        try {
            ensureExempted()
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmgClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val dmg = getInstance.invoke(null)
            if (dmg != null) {
                try {
                    val getInfo = dmgClass.getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                    getInfo.isAccessible = true
                    val info = getInfo.invoke(dmg, 0)
                    if (info != null) {
                        for (fieldName in listOf("logicalDensityDpi", "densityDpi")) {
                            try {
                                val f = info.javaClass.getDeclaredField(fieldName)
                                f.isAccessible = true
                                val v = f.getInt(info)
                                if (v > 0) {
                                    Log.d(TAG, "[PrivilegedCapture] dpi via Global.$fieldName=$v")
                                    return v
                                }
                            } catch (_: Throwable) {
                                Log.d(TAG, "[PrivilegedCapture] dpi via Global.$fieldName miss")
                            }
                        }
                    } else {
                        Log.d(TAG, "[PrivilegedCapture] dpi via Global info=null")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "[PrivilegedCapture] dpi via Global failed ${t.javaClass.simpleName}:${t.message}")
                }
            } else {
                Log.d(TAG, "[PrivilegedCapture] dpi via Global instance=null")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] dpi via Global threw ${t.javaClass.simpleName}:${t.message}")
        }
        Log.d(TAG, "[PrivilegedCapture] dpi fallback 320")
        return 320
    }

    /**
     * 主路：DisplayManager 隐藏无 projection 重载建镜像 VirtualDisplay。
     * 逐个反射尝试本 ROM 常见重载（命中即返，逐个记 BlindCast 日志）。
     */
    private fun createVirtualDisplayViaDisplayManager(surface: Surface, width: Int, height: Int): VirtualDisplay {
        ensureExempted()
        val ctx = tryObtainContext()
        val dm = obtainDisplayManager(ctx)
            ?: throw IllegalStateException("DisplayManager unavailable (no Context in priv process)")
        val dpi = getDensityDpi(ctx)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        Log.d(TAG, "[PrivilegedCapture] DisplayManager.createVirtualDisplay enter ${width}x${height} dpi=$dpi flags=$flags")
        try {
            val sigs = dm.javaClass.declaredMethods
                .filter { it.name == "createVirtualDisplay" }
                .map { m -> m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
            Log.d(TAG, "[PrivilegedCapture] DisplayManager overloads=$sigs")
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] DisplayManager list overloads failed ${t.message}")
        }
        var lastErr: Throwable? = null
        // ① (name,w,h,dpi,surface,flags)
        try {
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.try (String,int,int,int,Surface,int)")
            val m = dm.javaClass.getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
            )
            m.isAccessible = true
            val vd = m.invoke(dm, DISPLAY_NAME, width, height, dpi, surface, flags) as? VirtualDisplay
            if (vd != null) {
                Log.i(TAG, "[PrivilegedCapture] DisplayManager.hit 6-arg (name,w,h,dpi,surface,flags)")
                return vd
            }
            lastErr = IllegalStateException("6-arg returned null")
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.miss 6-arg returned null")
        } catch (t: NoSuchMethodException) {
            lastErr = t
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.miss 6-arg noMethod")
        } catch (t: Throwable) {
            lastErr = t
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.fail 6-arg ${t.javaClass.simpleName}:${t.message}")
        }
        // ② (name,w,h,dpi,surface,flags,callback,handler)
        try {
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.try (String,int,int,int,Surface,int,Callback,Handler)")
            val m = dm.javaClass.getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java,
            )
            m.isAccessible = true
            val vd = m.invoke(dm, DISPLAY_NAME, width, height, dpi, surface, flags, null, null) as? VirtualDisplay
            if (vd != null) {
                Log.i(TAG, "[PrivilegedCapture] DisplayManager.hit 8-arg (name,w,h,dpi,surface,flags,callback,handler)")
                return vd
            }
            lastErr = IllegalStateException("8-arg returned null")
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.miss 8-arg returned null")
        } catch (t: NoSuchMethodException) {
            lastErr = t
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.miss 8-arg noMethod")
        } catch (t: Throwable) {
            lastErr = t
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.fail 8-arg ${t.javaClass.simpleName}:${t.message}")
        }
        // ③ (name,w,h,dpi,surface,flags,callback,handler,uniqueId:String)
        try {
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.try (String,int,int,int,Surface,int,Callback,Handler,String)")
            val m = dm.javaClass.getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java, String::class.java,
            )
            m.isAccessible = true
            val vd = m.invoke(dm, DISPLAY_NAME, width, height, dpi, surface, flags, null, null, null) as? VirtualDisplay
            if (vd != null) {
                Log.i(TAG, "[PrivilegedCapture] DisplayManager.hit 9-arg uniqueId (...,callback,handler,uniqueId)")
                return vd
            }
            lastErr = IllegalStateException("9-arg uniqueId returned null")
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.miss 9-arg uniqueId returned null")
        } catch (t: NoSuchMethodException) {
            lastErr = t
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.miss 9-arg uniqueId noMethod")
        } catch (t: Throwable) {
            lastErr = t
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.fail 9-arg uniqueId ${t.javaClass.simpleName}:${t.message}")
        }
        // ④ (name,w,h,dpi,surface,flags,callback,handler,displayId:int)
        try {
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.try (String,int,int,int,Surface,int,Callback,Handler,int)")
            val m = dm.javaClass.getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Surface::class.java, Int::class.javaPrimitiveType,
                VirtualDisplay.Callback::class.java, Handler::class.java, Int::class.javaPrimitiveType,
            )
            m.isAccessible = true
            val vd = m.invoke(dm, DISPLAY_NAME, width, height, dpi, surface, flags, null, null, 0) as? VirtualDisplay
            if (vd != null) {
                Log.i(TAG, "[PrivilegedCapture] DisplayManager.hit 9-arg displayId (...,callback,handler,displayId=0)")
                return vd
            }
            lastErr = IllegalStateException("9-arg displayId returned null")
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.miss 9-arg displayId returned null")
        } catch (t: NoSuchMethodException) {
            lastErr = t
            Log.d(TAG, "[PrivilegedCapture] DisplayManager.miss 9-arg displayId noMethod")
        } catch (t: Throwable) {
            lastErr = t
            Log.w(TAG, "[PrivilegedCapture] DisplayManager.fail 9-arg displayId ${t.javaClass.simpleName}:${t.message}")
        }
        throw IllegalStateException("DisplayManager all overloads miss ${width}x${height}", lastErr)
    }

    /**
     * 备用：SurfaceControl scrcpy 路线（保留既有，逐个试已知重载签名并记日志）。
     * OPlus Android 15 真机实证主签名 `(String,boolean)` 已无（NoSuchMethodException），
     * 故此处同样逐个 try：`(String,boolean)` 主 + `(String,boolean,String)` /
     * `(String,int)` / `(String,boolean,long)` 备选。
     */
    private fun createPrivilegedDisplay(surface: Surface, width: Int, height: Int): IBinder {
        ensureExempted()
        val sc = surfaceControlClass()
        try {
            val sigs = sc.declaredMethods
                .filter { it.name == "createDisplay" }
                .map { m -> m.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
            Log.d(TAG, "[PrivilegedCapture] SurfaceControl.createDisplay overloads=$sigs")
        } catch (t: Throwable) {
            Log.d(TAG, "[PrivilegedCapture] SurfaceControl list overloads failed ${t.message}")
        }
        var token: IBinder? = null
        var hitSig: String? = null
        var lastErr: Throwable? = null
        // ① 主：(String, boolean)
        try {
            Log.d(TAG, "[PrivilegedCapture] SurfaceControl.try createDisplay(String,boolean)")
            val create = sc.getDeclaredMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
            create.isAccessible = true
            token = create.invoke(null, DISPLAY_NAME, false) as? IBinder
            if (token != null) hitSig = "(String,boolean)"
            else Log.w(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean) returned null")
        } catch (t: NoSuchMethodException) {
            lastErr = t
            Log.d(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean) noMethod")
        } catch (t: Throwable) {
            lastErr = t
            Log.w(TAG, "[PrivilegedCapture] SurfaceControl.fail (String,boolean) ${t.javaClass.simpleName}:${t.message}")
        }
        // ② 备选：(String, boolean, String uniqueId)
        if (token == null) {
            try {
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.try createDisplay(String,boolean,String)")
                val create = sc.getDeclaredMethod(
                    "createDisplay", String::class.java, Boolean::class.javaPrimitiveType, String::class.java,
                )
                create.isAccessible = true
                token = create.invoke(null, DISPLAY_NAME, false, DISPLAY_NAME) as? IBinder
                if (token != null) hitSig = "(String,boolean,String)"
                else Log.w(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean,String) returned null")
            } catch (t: NoSuchMethodException) {
                lastErr = t
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean,String) noMethod")
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "[PrivilegedCapture] SurfaceControl.fail (String,boolean,String) ${t.javaClass.simpleName}:${t.message}")
            }
        }
        // ③ 备选：(String, int secureInt)
        if (token == null) {
            try {
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.try createDisplay(String,int)")
                val create = sc.getDeclaredMethod("createDisplay", String::class.java, Int::class.javaPrimitiveType)
                create.isAccessible = true
                token = create.invoke(null, DISPLAY_NAME, 0) as? IBinder
                if (token != null) hitSig = "(String,int)"
                else Log.w(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,int) returned null")
            } catch (t: NoSuchMethodException) {
                lastErr = t
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,int) noMethod")
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "[PrivilegedCapture] SurfaceControl.fail (String,int) ${t.javaClass.simpleName}:${t.message}")
            }
        }
        // ④ 备选：(String, boolean, long displayId)
        if (token == null) {
            try {
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.try createDisplay(String,boolean,long)")
                val create = sc.getDeclaredMethod(
                    "createDisplay", String::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                )
                create.isAccessible = true
                token = create.invoke(null, DISPLAY_NAME, false, 0L) as? IBinder
                if (token != null) hitSig = "(String,boolean,long)"
                else Log.w(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean,long) returned null")
            } catch (t: NoSuchMethodException) {
                lastErr = t
                Log.d(TAG, "[PrivilegedCapture] SurfaceControl.miss (String,boolean,long) noMethod")
            } catch (t: Throwable) {
                lastErr = t
                Log.w(TAG, "[PrivilegedCapture] SurfaceControl.fail (String,boolean,long) ${t.javaClass.simpleName}:${t.message}")
            }
        }
        val finalToken = token ?: throw IllegalStateException("SurfaceControl all createDisplay overloads miss", lastErr)
        Log.d(TAG, "[PrivilegedCapture] SurfaceControl.hit $hitSig tokenNull=false ${width}x${height}")
        try {
            val setSurface = sc.getDeclaredMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
            setSurface.isAccessible = true
            setSurface.invoke(null, finalToken, surface)
            val setStack = sc.getDeclaredMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
            setStack.isAccessible = true
            setStack.invoke(null, finalToken, 0)
            // setDisplayProjection(IBinder, int, Rect, Rect)：orientation=0 全屏投影。
            val layerRect = Rect(0, 0, width, height)
            val displayRect = Rect(0, 0, width, height)
            val setProj = try {
                sc.getDeclaredMethod("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (setProj != null) {
                setProj.isAccessible = true
                setProj.invoke(null, finalToken, 0, layerRect, displayRect)
            } else {
                Log.w(TAG, "[PrivilegedCapture] setDisplayProjection missing, display may stay blank")
            }
            Log.d(TAG, "[PrivilegedCapture] setDisplaySurface/LayerStack/Projection ok via $hitSig")
        } catch (t: Throwable) {
            runCatching { destroyPrivilegedDisplay(finalToken) }
            throw t
        }
        return finalToken
    }

    private fun destroyPrivilegedDisplay(token: IBinder) {
        runCatching {
            ensureExempted()
            val sc = surfaceControlClass()
            val destroy = sc.getDeclaredMethod("destroyDisplay", IBinder::class.java)
            destroy.isAccessible = true
            destroy.invoke(null, token)
            Log.d(TAG, "[PrivilegedCapture] destroyDisplay ok")
        }.onFailure { t ->
            Log.w(TAG, "[PrivilegedCapture] destroyDisplay failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 编码器/参数小件（与 ScreenCaptureEngine/AudioCaptureEngine 同配）
    // ------------------------------------------------------------------

    private fun checkVideoParams(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        val reason = when {
            width !in 160..3840 || height !in 160..3840 -> "invalid resolution ${width}x${height}"
            bitrate < 100_000 -> "bitrate too low: $bitrate"
            fps !in 1..120 -> "invalid fps: $fps"
            else -> null
        }
        if (reason != null) {
            lastError = IllegalArgumentException("PrivilegedCapture: $reason")
            return false
        }
        return true
    }

    private fun buildLowLatencyVideoFormat(width: Int, height: Int, bitrate: Int, fps: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        format.setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
        return format
    }

    private fun createHardwareAvcEncoder(): MediaCodec {
        val hwName = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains(VIDEO_MIME) }?.name
        }.getOrNull()
        return if (hwName != null) MediaCodec.createByCodecName(hwName) else MediaCodec.createEncoderByType(VIDEO_MIME)
    }

    private fun buildAacFormat(sampleRate: Int, channelCount: Int, bitrate: Int): MediaFormat {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        return format
    }

    private fun createHardwareAacEncoder(): MediaCodec {
        val hwName = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.contains(AUDIO_MIME) }?.name
        }.getOrNull()
        return if (hwName != null) MediaCodec.createByCodecName(hwName) else MediaCodec.createEncoderByType(AUDIO_MIME)
    }

    private fun createSubmixRecord(sampleRate: Int, channelCount: Int): AudioRecord {
        val mask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val pcm = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("PrivilegedCapture: unsupported PCM ${sampleRate}Hz x$channelCount")
        val bufSize = maxOf(minBuf * 4, sampleRate * channelCount * 2)
        return AudioRecord.Builder()
            .setAudioFormat(pcm)
            .setBufferSizeInBytes(bufSize)
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .build()
    }

    private fun cacheCsdFromConfig(raw: ByteArray) {
        for (nalu in splitAnnexB(raw)) {
            if (nalu.isEmpty()) continue
            when (nalu[0].toInt() and 0x1F) {
                7 -> cachedSps = byteArrayOf(0, 0, 0, 1) + nalu
                8 -> cachedPps = byteArrayOf(0, 0, 0, 1) + nalu
                else -> Unit
            }
        }
        if (cachedSps == null && raw.isNotEmpty()) cachedSps = ensureStartCode(raw)
    }

    private fun annexBOf(buf: java.nio.ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val dst = ByteArray(dup.remaining())
        dup.get(dst)
        return ensureStartCode(dst)
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
        return bounds.mapIndexed { i, (_, h) ->
            val end = if (i + 1 < bounds.size) bounds[i + 1].first else data.size
            data.copyOfRange(h, end)
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

    private fun ensureStartCode(data: ByteArray): ByteArray {
        if (findStartCode(data, 0)?.first == 0) return data
        return byteArrayOf(0, 0, 0, 1) + data
    }
}
