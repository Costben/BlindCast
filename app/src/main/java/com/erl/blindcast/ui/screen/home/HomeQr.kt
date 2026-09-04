package com.erl.blindcast.ui.screen.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 局域网直连二维码（Slice 6.1 · zxing:core 纯 Java 编码）。
 *
 * 内容为空时返回 null（调用方展示占位文案，不抛异常）。
 * 编码在 [Dispatchers.Default] 执行，避免阻塞主线程。
 */
suspend fun encodeQrBitmap(content: String, pixels: Int = 640): Bitmap? {
    if (content.isBlank() || pixels <= 0) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}

/** 居中展示的二维码图片（加载中/失败时展示 [fallback]）。 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    fallback: @Composable () -> Unit = {},
) {
    val bitmap = produceState<Bitmap?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) { encodeQrBitmap(content) }
    }.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size),
        )
    } else {
        fallback()
    }
}
