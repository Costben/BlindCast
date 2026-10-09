package com.erl.blindcast.core.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/** AAC capture remains compatible; the original panel receives decoded, interleaved PCM16. */
class PcmAudioDecoder : AutoCloseable {
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    private var sampleRate = AudioCaptureEngine.DEFAULT_SAMPLE_RATE
    private var channels = AudioCaptureEngine.DEFAULT_CHANNEL_COUNT
    private val info = MediaCodec.BufferInfo()

    fun decode(packet: AudioPacket, emit: (ByteArray) -> Unit) {
        // Root capture uses the same fixed 48 kHz stereo AAC-LC format. In-process
        // capture supplies csd-0 explicitly; its config takes precedence.
        val asc = packet.config ?: byteArrayOf(0x11, 0x90.toByte())
        require(asc.size >= 2) { "invalid AAC config" }
        if (codec == null || config?.contentEquals(asc) != true) {
            close()
            val frequencyIndex = ((asc[0].toInt() and 7) shl 1) or ((asc[1].toInt() and 0x80) ushr 7)
            val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
            sampleRate = rates.getOrNull(frequencyIndex) ?: error("unsupported AAC sample rate")
            channels = (asc[1].toInt() ushr 3) and 15
            require(channels in 1..2)
            val decoder = MediaCodec.createDecoderByType(AudioCaptureEngine.MIME_TYPE)
            try {
                val format = MediaFormat.createAudioFormat(AudioCaptureEngine.MIME_TYPE, sampleRate, channels)
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
                decoder.configure(format, null, null, 0)
                decoder.start()
                codec = decoder
                config = asc.copyOf()
            } catch (t: Throwable) { decoder.release(); throw t }
        }
        val decoder = codec ?: return
        drain(decoder, emit)
        val index = decoder.dequeueInputBuffer(10000)
        if (index >= 0) {
            val buffer = decoder.getInputBuffer(index) ?: error("no AAC input buffer")
            buffer.clear()
            buffer.put(packet.payload)
            decoder.queueInputBuffer(index, 0, packet.payload.size, packet.timestampUs, 0)
        }
        drain(decoder, emit)
    }

    private fun drain(decoder: MediaCodec, emit: (ByteArray) -> Unit) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                continue
            }
            if (index < 0) return
            try {
                val buffer = decoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    // Wire: sampleRate:u32, channels:u32, ptsUs:u64, PCM16LE.
                    val frame = ByteBuffer.allocate(16 + info.size)
                    frame.putInt(sampleRate).putInt(channels).putLong(info.presentationTimeUs)
                    buffer.position(info.offset).limit(info.offset + info.size)
                    frame.put(buffer)
                    emit(frame.array())
                }
            } finally { decoder.releaseOutputBuffer(index, false) }
        }
    }

    override fun close() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        config = null
    }
}
