package com.erl.blindcast.core.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ConditionVariable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * 系统内音抓取 + MediaCodec AAC 硬编码引擎（Slice 3.2）。
 *
 * ## 链路
 * 系统播放混音（免麦克风，绝不采集 `MIC` 源）
 * -> `AudioRecord` PCM 直抓（16-bit，见“双采集路径”）
 * -> `MediaCodec` AAC-LC 硬编码（`audio/mp4a-latm`，裸帧无 ADTS）
 * -> 后台 capture/drain 双线程
 * -> 经 [onPacket] 回调 / [audioChannel] 对外产出 [AudioPacket]
 * （config 就绪前丢帧，保证每个对外包都能独立解码，供 Slice 4.1
 * `/ws/stream` 音频轨与 Web 端 Web Audio 解码，MVP.md 二(2)）。
 *
 * ## 双采集路径（二选一，调用方按提权现实选择）
 * - **A · 特权内录（免弹窗首选）**：[start](无 projection 重载) 使用
 *   `MediaRecorder.AudioSource.REMOTE_SUBMIX`，需签名级
 *   `CAPTURE_AUDIO_OUTPUT` 权限，**必须在提权进程内执行**：
 *   Shizuku UserService（system_server 上下文）或 Root `app_process` 守护进程。
 *   普通 App 进程调用将在 `build()/startRecording` 抛 `SecurityException`，
 *   本引擎捕获后记 [lastError] 并返回 false，永不崩溃。
 * - **B · 回放捕获（projection 由提权静默下发）**：
 *   [start](带 projection 重载) 经 `AudioPlaybackCaptureConfiguration` +
 *   `AudioRecord` 抓取（`USAGE_MEDIA/GAME/UNKNOWN`），传入的 [MediaProjection]
 *   必须来自提权静默获取（Shizuku 透传 / Root 服务代取），
 *   **严禁经 `MediaProjectionManager.createScreenCaptureIntent` 弹窗获取**
 *   （PLAN.md 铁线：彻底免除系统录屏确认弹窗与隐私红点）。
 *   两种路径后续在 Shizuku binder 透传 / Root 守护进程转调时，本对象 API 不变。
 *
 * ## 前台服务前置（必读）
 * Android 9+ 后台抓取音频要求调用方处于前台服务中；本引擎自身不拉起任何
 * Service（仅做能力封装），后续 Slice 须在 `BlindCastForegroundService` 内
 * 调用 [start]（Manifest 已预声明 `FOREGROUND_SERVICE` /
 * `FOREGROUND_SERVICE_MICROPHONE`，只声明不启用）。
 *
 * ## 编码配置
 * - MIME `audio/mp4a-latm`，Profile AAC-LC；
 * - 默认 48kHz 立体声 128kbps（设置页档位由 Slice 6.2 经 [start] 参数传入）；
 * - 优选硬件实现（`isHardwareAccelerated`），无硬件回退系统默认；
 * - 输出 AAC 裸帧（无 ADTS），时间戳为喂编码器时的墙钟
 *   `（System.nanoTime() - startNs）/ 1000`，与视频侧墙钟同源。
 *
 * ## 全局传输开关（[AudioGate] 直通）
 * - [setAudioEnabled] 写闸 + 唤醒双线程；[isAudioEnabled] 读闸；
 * - 关闸时 capture/drain 双线程在闸门上挂起等待，
 *   **零 PCM 读取、零编码、零网络包**（在途残帧直接丢弃，满足 Slice 3.2
 *   “零编码零网络消耗、可停 drain 线程”验收）；
 * - 开闸瞬间重启 `AudioRecord` 录制以丢弃暂停期间积压的陈旧 PCM，
 *   解冻无旧声 burst；墙钟时间戳自然留出空隙，A/V 对齐无需重基。
 *
 * ## 线程模型
 * - [start] 幂等可重配：运行中再次调用先静默 [stop] 再按新参数启动；
 * - capture/drain 双线程均为 daemon，进程退出时不阻止 VM 回收；
 * - [stop] 幂等：唤醒闸等待 + 停双线程（各 join 至多 1s）+
 *   释放 AudioRecord/MediaCodec；
 * - [audioChannel] 跨启停复用，永不 close（重启无需重订阅；满 128 包丢最旧）；
 * - 所有异常记 [lastError] 不抛崩；成功启动后 [lastError] 清零。
 *
 * ## 范围声明
 * - 仅做采集编码能力封装：不接网络（Slice 4.1）、不接 UI、不启动保活
 *   （保活归 `UserActivityKeeper`，熔断归 `EmergencyRecovery`）。
 */
object AudioCaptureEngine {

    /** 编码 MIME：AAC-LC（LATM 封装描述）。 */
    const val MIME_TYPE = "audio/mp4a-latm"

    /** 默认采样率：48kHz（系统混音原生率，重采样损耗最小）。 */
    const val DEFAULT_SAMPLE_RATE = 48000

    /** 默认声道数：2（立体声，游戏/音乐场景保真）。 */
    const val DEFAULT_CHANNEL_COUNT = 2

    /** 默认码率：128kbps（音乐可听与省流折中）。 */
    const val DEFAULT_BITRATE = 128_000

    /** Channel 缓冲：满时丢最旧包（保活优先，宁可丢音频不堆积延迟）。 */
    private const val CHANNEL_CAPACITY = 128

    /** drain/喂编码出入队超时：10ms（stop 响应快，空转 CPU 可忽略）。 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** stop 时单线程 join 上限：1s。 */
    private const val STOP_JOIN_MS = 1_000L

    /** 单次 PCM 抓取帧数：2048 帧（48kHz 立体声约 43ms/块，延迟与 syscall 折中）。 */
    private const val CAPTURE_FRAMES_PER_CHUNK = 2048

    /** 关闸等待步长：500ms（stop 可快速唤醒，无需精确通知）。 */
    private const val GATE_WAIT_MS = 500L

    /** AAC 编码器输入上限：16KB（单块 PCM 最大 2048*2ch*2B = 8KB，留倍余）。 */
    private const val MAX_INPUT_SIZE = 16_384

    /** 是否正在抓取编码（volatile，跨线程可见）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * 最近一次失败的异常；最近一次成功 [start] 后清零，
     * 供 Slice 6.3 诊断消费。失败永不崩溃。
     */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 当前会话采样率（未运行为 -1）。 */
    @Volatile
    var currentSampleRate: Int = -1
        private set

    /** 当前会话声道数（未运行为 -1）。 */
    @Volatile
    var currentChannelCount: Int = -1
        private set

    /** 当前会话码率 bps（未运行为 -1）。 */
    @Volatile
    var currentBitrate: Int = -1
        private set

    /** 本次编码会话的 AudioSpecificConfig（`csd-0` 拷贝；未运行/未就绪为 null）。 */
    @Volatile
    var cachedConfig: ByteArray? = null
        private set

    /**
     * 对外音频通道（`BUFFERED` + 满时丢最旧，跨启停复用永不 close）。
     * 消费者（二选一或兼用）：`for (pkt in audioChannel)` 或 [setOnPacketListener]。
     */
    val audioChannel: Channel<AudioPacket> =
        Channel(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 同步音频回调（drain 线程内调用，禁止耗时；抛异常被吞并记 [lastError]）。 */
    @Volatile
    var onPacket: ((AudioPacket) -> Unit)? = null
        private set

    /** 本设备 SDK 是否满足抓取前置（minSdk 31，恒为 true，保留供 ROM 黑名单扩展）。 */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val lock = Any()

    /** 关闸挂起门：capture/drain 双线程在闸闭时于此阻塞，[setAudioEnabled]/[stop] 放行。 */
    private val gateOpened = ConditionVariable(true)

    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    /** 本次会话墙钟起点（`System.nanoTime()`，喂编码器打 PTS 用）。 */
    @Volatile
    private var startNs: Long = 0L

    /** 设置同步音频回调（传 null 清除）。 */
    fun setOnPacketListener(listener: ((AudioPacket) -> Unit)?) {
        onPacket = listener
    }

    /** 全局传输开关镜像读闸（供设置页 / Web 静音指令查询）。 */
    fun isAudioEnabled(): Boolean = AudioGate.isAudioEnabled

    /**
     * 全局传输开关（直通 [AudioGate] 并唤醒双线程）。
     * 关闭时零编码零网络消耗（双线程挂起 + 在途残帧丢弃）；开启时即时恢复。
     * 未 [start] 时调用仅记闸状态，下次启动即按闸行事。
     */
    fun setAudioEnabled(enabled: Boolean) {
        AudioGate.setAudioEnabled(enabled)
        if (enabled) gateOpened.open() else gateOpened.close()
    }

    /**
     * 启动抓取编码·特权内录路径（主入口之一，同步返回）。
     *
     * 使用 `REMOTE_SUBMIX` 直抓系统混音，**必须在 Shizuku UserService /
     * Root 守护进程内执行**（见类注释）；普通进程无 `CAPTURE_AUDIO_OUTPUT`
     * 将返回 false（原因记 [lastError]），且失败后无残留。
     *
     * @param sampleRate 采样率（8000 ~ 96000，建议 44100/48000）。
     * @param channelCount 声道数（1 单声道 / 2 立体声）。
     * @param bitrate 目标码率 bps（>= 8000，建议 64_000 ~ 192_000）。
     */
    @Synchronized
    fun start(
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channelCount: Int = DEFAULT_CHANNEL_COUNT,
        bitrate: Int = DEFAULT_BITRATE,
    ): Boolean = startInternal(
        projection = null,
        sampleRate = sampleRate,
        channelCount = channelCount,
        bitrate = bitrate,
    )

    /**
     * 启动抓取编码·回放捕获路径（主入口之二，同步返回）。
     *
     * @param projection 提权静默下发的 [MediaProjection]
     *  （严禁来自录屏确认弹窗，见类注释）；其余参数同无参重载。
     */
    @Synchronized
    fun start(
        projection: MediaProjection,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channelCount: Int = DEFAULT_CHANNEL_COUNT,
        bitrate: Int = DEFAULT_BITRATE,
    ): Boolean = startInternal(
        projection = projection,
        sampleRate = sampleRate,
        channelCount = channelCount,
        bitrate = bitrate,
    )

    /** 停止抓取编码（幂等）：唤醒闸等待 + 停双线程 + 释放 AudioRecord/Codec。 */
    @Synchronized
    fun stop() = stopLocked()

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun startInternal(
        projection: MediaProjection?,
        sampleRate: Int,
        channelCount: Int,
        bitrate: Int,
    ): Boolean {
        if (isRunning) stopLocked()
        if (!checkParams(sampleRate, channelCount, bitrate)) return false
        var encoder: MediaCodec? = null
        var record: AudioRecord? = null
        return try {
            encoder = createHardwareAacEncoder()
            encoder.configure(
                buildAacFormat(sampleRate, channelCount, bitrate),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            // AudioRecord 构建（无提权时 REMOTE_SUBMIX 在此抛 SecurityException -> false）。
            record = createAudioRecord(sampleRate, channelCount, projection)
            encoder.start()
            try {
                record.startRecording()
            } catch (t: Throwable) {
                throw IllegalStateException("AudioRecord startRecording failed（缺提权？需 Shizuku/Root 进程）", t)
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(
                    "AudioRecord not recording (state=${record.recordingState})，缺提权或被系统占用",
                )
            }
            // 所有权移交字段（此后异常走 releaseLocked 回收）。
            codec = encoder
            encoder = null
            audioRecord = record
            record = null
            currentSampleRate = sampleRate
            currentChannelCount = channelCount
            currentBitrate = bitrate
            cachedConfig = null
            isRunning = true
            lastError = null
            startNs = System.nanoTime()
            val ct = Thread(::captureLoop, "BlindCast-AudioCapture")
            ct.isDaemon = true
            captureThread = ct
            ct.start()
            val dt = Thread(::drainLoop, "BlindCast-AudioDrain")
            dt.isDaemon = true
            drainThread = dt
            dt.start()
            true
        } catch (t: Throwable) {
            lastError = t
            runCatching { record?.release() }
            runCatching { encoder?.release() }
            releaseLocked()
            false
        }
    }

    private fun stopLocked() {
        isRunning = false
        gateOpened.open()
        captureThread?.interrupt()
        drainThread?.interrupt()
        try {
            captureThread?.join(STOP_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            drainThread?.join(STOP_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            captureThread = null
            drainThread = null
        }
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching {
            audioRecord?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        audioRecord = null
        runCatching {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        codec = null
        currentSampleRate = -1
        currentChannelCount = -1
        currentBitrate = -1
        cachedConfig = null
    }

    private fun checkParams(sampleRate: Int, channelCount: Int, bitrate: Int): Boolean {
        val reason = when {
            sampleRate !in 8000..96000 ->
                "invalid sampleRate: $sampleRate (expect 8000..96000)"
            channelCount !in 1..2 ->
                "invalid channelCount: $channelCount (expect 1..2)"
            bitrate < 8000 -> "bitrate too low: $bitrate (expect >= 8000)"
            else -> null
        }
        if (reason != null) {
            lastError = IllegalArgumentException("AudioCaptureEngine: $reason")
            return false
        }
        return true
    }

    /** AAC-LC 低延迟格式：采样率/声道直透 + LC profile + 输入上限。 */
    private fun buildAacFormat(sampleRate: Int, channelCount: Int, bitrate: Int): MediaFormat {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount)
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        return format
    }

    /**
     * 优选硬件 AAC 编码器：遍历 [MediaCodecList] 取首个硬件加速实现；
     * 无硬件（模拟器 / 裁剪 ROM）回退 `createEncoderByType`（功能照常，
     * 仅功耗非最优，行为记 log 不记错）。
     */
    private fun createHardwareAacEncoder(): MediaCodec {
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

    /**
     * 构建 PCM 抓取器（二选一）：
     * - projection != null：`AudioPlaybackCaptureConfiguration` 回放捕获
     *  （`USAGE_MEDIA/GAME/UNKNOWN`，不碰麦克风；minSdk 31 >= 29 可直调）；
     * - projection == null：`REMOTE_SUBMIX` 特权内录（需 `CAPTURE_AUDIO_OUTPUT`，
     *   无提权时 `build()` 即抛 `SecurityException`，由调用方感知为 false）。
     */
    private fun createAudioRecord(
        sampleRate: Int,
        channelCount: Int,
        projection: MediaProjection?,
    ): AudioRecord {
        val channelMask = if (channelCount == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException(
                "AudioCaptureEngine: unsupported PCM combo ${sampleRate}Hz x$channelCount " +
                    "(minBuffer=$minBuf)",
            )
        }
        val bufSize = maxOf(minBuf * 4, sampleRate * channelCount * 2)
        val builder = AudioRecord.Builder()
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(bufSize)
        if (projection != null) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(captureConfig)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
        }
        return builder.build()
    }

    /** capture 主循环：闸闭挂起 -> 开闸去陈旧 -> 读 PCM -> 喂编码器。 */
    private fun captureLoop() {
        val channels = currentChannelCount.coerceIn(1, 2)
        val chunk = ByteArray(CAPTURE_FRAMES_PER_CHUNK * channels * 2)
        var wasMuted = false
        while (isRunning && !Thread.currentThread().isInterrupted) {
            if (!AudioGate.isAudioEnabled) {
                // 关闸：零读取零编码，挂起等待（stop/setAudioEnabled(true) 唤醒）。
                wasMuted = true
                awaitUnmuted()
                continue
            }
            val record = synchronized(lock) { audioRecord } ?: break
            if (wasMuted) {
                // 开闸瞬间重启录制，丢弃暂停期间积压的陈旧 PCM，解冻无旧声。
                wasMuted = false
                runCatching { record.stop() }
                runCatching { record.startRecording() }
                continue
            }
            val n = try {
                record.read(chunk, 0, chunk.size)
            } catch (t: Throwable) {
                lastError = t
                if (!isRunning) break else continue
            }
            when {
                n > 0 -> queuePcm(chunk, n)
                n == AudioRecord.ERROR_DEAD_OBJECT -> {
                    lastError = IllegalStateException("AudioCaptureEngine: AudioRecord dead object")
                    break
                }
                n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE -> {
                    lastError = IllegalStateException("AudioCaptureEngine: AudioRecord read error=$n")
                    sleepBriefly()
                }
                else -> sleepBriefly() // n == 0 / ERROR：短暂退避，避免热循环。
            }
        }
    }

    private fun awaitUnmuted() {
        // 关闸阻塞（500ms 步进，stop/setAudioEnabled(true) 经 gateOpened 放行）。
        while (isRunning && !AudioGate.isAudioEnabled) {
            gateOpened.block(GATE_WAIT_MS)
        }
    }

    private fun sleepBriefly() {
        runCatching { Thread.sleep(10L) }
    }

    /** PCM 喂编码器：10ms 出队输入口，墙钟 PTS，异常只记不崩。 */
    private fun queuePcm(data: ByteArray, size: Int) {
        val encoder = synchronized(lock) { codec } ?: return
        try {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return
            val input = encoder.getInputBuffer(index) ?: return
            val n = minOf(size, input.remaining())
            input.put(data, 0, n)
            val ptsUs = (System.nanoTime() - startNs) / 1000L
            encoder.queueInputBuffer(index, 0, n, ptsUs, 0)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            lastError = t
        }
    }

    /** drain 主循环：出队 -> 缓存 config -> 门闩关闸丢弃 -> 回调/Channel 投递。 */
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
                val dup = csd0.duplicate()
                val dst = ByteArray(dup.remaining())
                dup.get(dst)
                cachedConfig = dst
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
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // 编解码配置帧：只缓存 AudioSpecificConfig，不对外投递。
            if (raw.isNotEmpty()) cachedConfig = raw
            return
        }
        // 门闩 1：config 未就绪前丢帧（对外包必须能独立解码）。
        val snapshot = cachedConfig ?: return
        // 门闩 2：关闸期间在途残帧直接丢弃（零网络消耗）。
        if (!AudioGate.isAudioEnabled) return
        val packet = AudioPacket(
            timestampUs = info.presentationTimeUs,
            payload = raw,
            config = snapshot,
        )
        audioChannel.trySend(packet)
        runCatching { onPacket?.invoke(packet) }.onFailure { t ->
            lastError = t
        }
    }
}
