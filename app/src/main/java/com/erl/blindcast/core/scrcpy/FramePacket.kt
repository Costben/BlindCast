package com.erl.blindcast.core.scrcpy

/**
 * 单个 H.264 Annex-B NALU 视频包（Slice 3.1 · 采集编码对外产出单元）。
 *
 * ## 约定
 * - [payload] 为 Annex-B 字节流（`00 00 00 01` 起始码前缀）；
 * - 关键帧（[isKeyFrame] = true）包的 [payload] 头部已内联拼接 SPS + PPS，
 *   引擎保证对外发出的**首包必为附带 SPS/PPS 的 IDR 关键帧**，
 *   Web 端 `VideoDecoder` 可据首包直接初始化解码器（MVP.md 四(二)(2)）；
 * - [sps]/[pps] 为本次编码会话缓存的序列/图像参数集快照（可能为 null，
 *   当且仅当包本身已内联携带时消费方可忽略）；
 * - 仅做数据承载：不接网络、不接 UI，投递由 `ScreenCaptureEngine`
 *   经回调 / Channel 完成（Slice 4.1 再接入 `/ws/stream`）。
 *
 * @property type NALU 类型（H.264 `nal_unit_type`，见伴生常量；解析失败为 [NALU_TYPE_UNKNOWN]）。
 * @property isKeyFrame 是否为关键帧（以 `MediaCodec.BUFFER_FLAG_KEY_FRAME` 为准）。
 * @property timestampUs 采集时间戳（`MediaCodec.BufferInfo.presentationTimeUs` 基准，微秒）。
 * @property payload Annex-B NALU 负载（含起始码；关键帧已前置 SPS/PPS）。
 */
data class FramePacket(
    val type: Int,
    val isKeyFrame: Boolean,
    val timestampUs: Long,
    val payload: ByteArray,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
) {
    companion object {
        /** 非 IDR 图像片（P/B 帧）。 */
        const val NALU_TYPE_NON_IDR = 1

        /** IDR 图像片（关键帧）。 */
        const val NALU_TYPE_IDR = 5

        /** 补充增强信息。 */
        const val NALU_TYPE_SEI = 6

        /** 序列参数集。 */
        const val NALU_TYPE_SPS = 7

        /** 图像参数集。 */
        const val NALU_TYPE_PPS = 8

        /** 访问单元分隔符。 */
        const val NALU_TYPE_AUD = 9

        /** 起始码缺失 / 解析失败时的兜底类型。 */
        const val NALU_TYPE_UNKNOWN = -1
    }

    // ByteArray 按内容比较（data class 默认引用比较会误判重复帧，这里显式覆盖）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FramePacket) return false
        return type == other.type &&
            isKeyFrame == other.isKeyFrame &&
            timestampUs == other.timestampUs &&
            payload.contentEquals(other.payload) &&
            (sps?.contentEquals(other.sps) ?: (other.sps == null)) &&
            (pps?.contentEquals(other.pps) ?: (other.pps == null))
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (sps?.contentHashCode() ?: 0)
        result = 31 * result + (pps?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "FramePacket(type=$type, isKeyFrame=$isKeyFrame, " +
            "timestampUs=$timestampUs, bytes=${payload.size})"
}
