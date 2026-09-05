package com.erl.blindcast.core.scrcpy

/**
 * 竖屏等比采集分辨率（TouchOffset-Fix-1 · 根治触控横向偏移与黑边失真）。
 *
 * 根因：旧代码把 720P/1080P 硬编码为横屏 1280x720 / 1920x1080。
 * 在 1080x2376 竖屏手机上 VirtualDisplay 被建成横屏，SurfaceFlinger 把细长竖屏
 * 居中塞进横屏画布，左右两侧巨大黑边；前端 normX 相对整画布（含黑边）计算，
 * 后端 normX*physW 落点整体右偏（点第1列中第2列）。
 *
 * 本对象按物理宽高比等比计算采集尺寸，保证编码画面 100% 充满、无黑边，
 * 前端 canvas 比例与物理屏比例 1:1 恒等，normX/Y 直接映射 0~physW/H。
 */
object VideoResolution {

    /**
     * 按档位与物理尺寸算采集尺寸（宽高恒为偶数，AVC 要求）。
     *
     * @param label 720P | 1080P | 原生（其他回退 720P 逻辑）。
     * @param physW 物理屏宽（displayMetrics.widthPixels），<=0 视为未知。
     * @param physH 物理屏高（displayMetrics.heightPixels），<=0 视为未知。
     * @param fallbackW 物理未知时回退宽（默认竖屏 720P 通用 720）。
     * @param fallbackH 物理未知时回退高（默认竖屏 720P 通用 1280）。
     */
    fun resolve(
        label: String,
        physW: Int,
        physH: Int,
        fallbackW: Int = 720,
        fallbackH: Int = 1280,
    ): Pair<Int, Int> {
        if (physW <= 0 || physH <= 0) {
            return when (label) {
                "1080P" -> 1080 to 1920
                "原生" -> fallbackW to fallbackH
                else -> 720 to 1280
            }
        }
        return if (physH >= physW) {
            // 竖屏：宽固定，高按物理比等比（偶数对齐）。
            when (label) {
                "1080P" -> {
                    val vh = (((1080L * physH / physW) and -2L).toInt())
                        .coerceIn(160, 3840)
                    // 钳制后仍保偶数（coerce 可能打出奇数边界，159/3840 场景）。
                    1080 to (vh and -2)
                }
                "原生" -> (physW and -2) to (physH and -2)
                else -> {
                    val vh = (((720L * physH / physW) and -2L).toInt())
                        .coerceIn(160, 3840)
                    720 to (vh and -2)
                }
            }
        } else {
            // 横屏平板/车机：高固定，宽等比。
            when (label) {
                "1080P" -> {
                    val vw = (((1080L * physW / physH) and -2L).toInt())
                        .coerceIn(160, 3840)
                    (vw and -2) to 1080
                }
                "原生" -> (physW and -2) to (physH and -2)
                else -> {
                    val vw = (((720L * physW / physH) and -2L).toInt())
                        .coerceIn(160, 3840)
                    (vw and -2) to 720
                }
            }
        }
    }
}
