package com.erl.blindcast.core.server.routes

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import com.erl.blindcast.core.server.BlindCastServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 二进制资源路由（需鉴权，由 [BlindCastServer] 先验 Token）。
 *
 * - `GET /api/apps/icon?package=<pkg>&size=<px>` → `image/png`（自适应图标合成后按
 *   [size] 方形缩放），带 `ETag` + `Cache-Control: public, max-age=86400`；包不存在 404。
 * - `GET /api/desktop/wallpaper[?max=<px>]` → 设备当前系统壁纸，`image/jpeg`（有透明通道时
 *   `image/png`）；取不到 404。**短缓存**（60s）——用户随时可能换壁纸。
 *
 * 与 [DeviceApiRoute] 的区别：本路由返回**二进制体**，由 [BlindCastServer] 用
 * `serveBinary` 直接下发（含 304 条件请求），不走 JSON 封装。
 *
 * 缓存：图标按 `(package,size)` 长缓存；壁纸**按 `max` 分键**短 TTL 缓存
 * （不同请求尺寸各自成条，避免一个尺寸的缓存串到另一个尺寸）。进程重启即失效。
 */
object AssetApiRoute {

    private const val TAG = "BlindCast-AssetApi"

    /** 图标请求的边长 clamp 区间。 */
    private const val ICON_MIN_PX = 16
    private const val ICON_MAX_PX = 512
    private const val ICON_DEFAULT_PX = 96

    /** 壁纸输出最长边上限（超出则等比缩，避免整屏原图过大）。 */
    private const val WALLPAPER_MAX_PX = 2560

    /** 壁纸缓存 TTL（壁纸可能被用户随时更换，短 TTL 兜住）。 */
    private const val WALLPAPER_TTL_MS = 60_000L

    /** 图标可长缓存（APK 更新才变）。 */
    private const val ICON_CACHE_CONTROL = "public, max-age=86400"

    /** 壁纸短缓存：与 [WALLPAPER_TTL_MS] 对齐，过期即带 If-None-Match 回源校验。 */
    private const val WALLPAPER_CACHE_CONTROL = "public, max-age=60"

    /** 二进制响应：状态码 + 内容类型 + 体 + 附加头。 */
    data class Binary(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    private data class Cached(
        val etag: String,
        val contentType: String,
        val body: ByteArray,
        val cacheControl: String,
        val at: Long,
    )

    private val iconCache = ConcurrentHashMap<String, Cached>()

    /** 壁纸缓存**按 `max` 分键**，每条带自己的时间戳。 */
    private val wallpaperCache = ConcurrentHashMap<Int, Cached>()

    /**
     * `GET /api/apps/icon?package=<pkg>&size=<px>`。
     * 只接受 GET；包名缺失 400；包不存在/无图标 404。
     */
    fun handleAppIcon(method: String, rawQuery: String?): Binary {
        if (method != "GET") return text(405, "method not allowed")
        val q = parseQuery(rawQuery)
        val pkg = q["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return jsonError(400, "missing package")
        val size = (q["size"]?.toIntOrNull() ?: ICON_DEFAULT_PX).coerceIn(ICON_MIN_PX, ICON_MAX_PX)
        val ctx = BlindCastServer.appContextOrNull() ?: return jsonError(500, "no context")

        val key = "$pkg|$size"
        iconCache[key]?.let { return binaryOk(it) }

        val png = runCatching { renderIcon(ctx, pkg, size) }.getOrElse { t ->
            Log.w(TAG, "[icon] render failed pkg=$pkg size=$size: ${t.message}")
            null
        } ?: return jsonError(404, "icon not found")

        val cached = Cached(
            etag = WebStaticRoutes.weakEtag(png),
            contentType = "image/png",
            body = png,
            cacheControl = ICON_CACHE_CONTROL,
            at = System.currentTimeMillis(),
        )
        iconCache[key] = cached
        return binaryOk(cached)
    }

    /** `GET /api/desktop/wallpaper[?max=<px>]` → 当前系统壁纸（按 max 分键短缓存）。 */
    fun handleWallpaper(method: String, rawQuery: String?): Binary {
        if (method != "GET") return text(405, "method not allowed")
        val maxPx = (parseQuery(rawQuery)["max"]?.toIntOrNull() ?: WALLPAPER_MAX_PX)
            .coerceIn(64, WALLPAPER_MAX_PX)
        val now = System.currentTimeMillis()
        wallpaperCache[maxPx]?.let { if (now - it.at < WALLPAPER_TTL_MS) return binaryOk(it) }
        val ctx = BlindCastServer.appContextOrNull() ?: return jsonError(500, "no context")
        val rendered = runCatching { renderWallpaper(ctx, maxPx) }.getOrElse { t ->
            Log.w(TAG, "[wallpaper] render failed max=$maxPx: ${t.message}")
            null
        } ?: return jsonError(404, "wallpaper unavailable")
        val cached = Cached(
            etag = WebStaticRoutes.weakEtag(rendered.first),
            contentType = rendered.second,
            body = rendered.first,
            cacheControl = WALLPAPER_CACHE_CONTROL,
            at = now,
        )
        wallpaperCache[maxPx] = cached
        return binaryOk(cached)
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private fun renderIcon(ctx: Context, pkg: String, size: Int): ByteArray? {
        val pm = ctx.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }.getOrNull() ?: return null
        val drawable: Drawable = runCatching { pm.getApplicationIcon(info) }.getOrNull() ?: return null
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return compressPng(bmp)
    }

    /** @return (bytes, contentType) */
    private fun renderWallpaper(ctx: Context, maxPx: Int): Pair<ByteArray, String>? {
        val wm = WallpaperManager.getInstance(ctx)
        @Suppress("DEPRECATION")
        val drawable = runCatching { wm.drawable }.getOrNull() ?: return null
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1080
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1920
        val scale = if (maxOf(w, h) > maxPx) maxPx.toFloat() / maxOf(w, h) else 1f
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, outW, outH)
        drawable.draw(canvas)
        // 有透明通道（部分 ROM 壁纸）走 PNG，否则 JPEG 省带宽。
        return if (bmp.hasAlpha() && hasTransparentPixels(bmp)) {
            compressPng(bmp)?.let { it to "image/png" }
        } else {
            compressJpeg(bmp, 88)?.let { it to "image/jpeg" }
        }
    }

    private fun hasTransparentPixels(bmp: Bitmap): Boolean {
        // 采样四角与中心，避免逐像素全扫（壁纸动辄百万像素）。
        val xs = intArrayOf(0, bmp.width - 1, bmp.width / 2)
        val ys = intArrayOf(0, bmp.height - 1, bmp.height / 2)
        for (x in xs) for (y in ys) {
            if (bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1)) ushr 24 < 255) return true
        }
        return false
    }

    private fun compressPng(bmp: Bitmap): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun binaryOk(c: Cached): Binary = Binary(
        status = 200,
        contentType = c.contentType,
        body = c.body,
        headers = mapOf("ETag" to c.etag, "Cache-Control" to c.cacheControl),
    )

    private fun text(status: Int, msg: String): Binary =
        Binary(status, "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))

    private fun jsonError(status: Int, msg: String): Binary = Binary(
        status,
        "application/json; charset=utf-8",
        JSONObject().put("ok", false).put("error", msg).toString().toByteArray(Charsets.UTF_8),
    )

    /** 最小查询串解析（`k=v&k2=v2`，不做百分号解码，键值均为 ASCII 场景）。 */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            map[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return map
    }
}
