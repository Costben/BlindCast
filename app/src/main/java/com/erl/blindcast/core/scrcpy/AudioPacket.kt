package com.erl.blindcast.core.scrcpy

/**
 * 单个 AAC 系统内音包（Slice 3.2 · 音频采集编码对外产出单元）。
 *
 * ## 约定
 * - [payload] 为 AAC 裸帧（MediaCodec `audio/mp4a-latm` 编码器直出，
 *   **不含 ADTS 头**；Slice 4.1 接入 `/ws/stream` 做复用/封包时再按需加 ADTS，
 *   Web 端则用 [config] 初始化解码器后直接喂裸帧）；
 * - [config] 为本次编码会话的 AudioSpecificConfig 快照（`csd-0`，可能为 null，
 *   当且仅当消费方已缓存 DecoderConfig 时可忽略；引擎在 config 就绪前丢帧，
 *   保证对外发出的包都能独立解码）；
 * - 仅做数据承载：不接网络、不接 UI，投递由 `AudioCaptureEngine`
 *   经回调 / Channel 完成（Slice 4.1 再接入推流，Slice 6.2 绑定设置页开关）。
 *
 * @property timestampUs 采集时间戳（喂编码器时的墙钟基准，微秒，
 *   与视频侧墙钟同源，关闸暂停期间自然留出空隙，A/V 对齐无需重基）。
 * @property payload AAC 裸帧负载（单帧或同次出队的多帧拼接，不含 ADTS）。
 * @property config AudioSpecificConfig 快照（解码器初始化用）。
 */
data class AudioPacket(
    val timestampUs: Long,
    val payload: ByteArray,
    val config: ByteArray? = null,
) {
    // ByteArray 按内容比较（data class 默认引用比较会误判重复帧，这里显式覆盖）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacket) return false
        return timestampUs == other.timestampUs &&
            payload.contentEquals(other.payload) &&
            (config?.contentEquals(other.config) ?: (other.config == null))
    }

    override fun hashCode(): Int {
        var result = timestampUs.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (config?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AudioPacket(timestampUs=$timestampUs, bytes=${payload.size}, " +
            "hasConfig=${config != null})"
}
