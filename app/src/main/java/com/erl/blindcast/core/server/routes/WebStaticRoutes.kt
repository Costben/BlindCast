package com.erl.blindcast.core.server.routes

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 静态资源路由（`GET|HEAD /` 与 `assets/web/` 下任意资源）。
 *
 * ## 行为
 * - `GET /`、`GET /index.html` → `assets/web/index.html`；
 * - 其它路径 → 按 URL 相对路径映射到 `assets/web/<rel>`（原版面板的
 *   `css/panel.css`、`js/shell.js`、`icons/`、`img/`、`manifest.webmanifest`
 *   等全部走这里），缺失 404；
 * - 仅允许 GET / HEAD，其余 405；
 * - 正确 MIME（按扩展名）+ ETag/304 + 全部资源 `no-cache`：
 *   面板资源随 APK 一起更新，而它们的 URL 在两次构建之间不变，任何 `max-age`
 *   都会让新装的应用继续跑上一版的脚本（一次 304 换掉这类"改了却没生效"），
 *   所以统一走 ETag 重校验，未变即 304。
 *
 * ## 安全
 * - **路径防穿越**：只接受 `[A-Za-z0-9._~/-]`，显式拒绝 `..`、反斜杠、`%`（未解码的
 *   百分号编码一律拒绝，杜绝 `%2e%2e` 之类绕过）、空段与超长路径；解析后仍须落在
 *   `web/` 前缀内，否则 404。绝不 `File` 拼接用户输入。
 * - 单文件上限 [MAX_ASSET_BYTES]，超限视为异常包走 404（防 OOM）。
 *
 * 纯内存 + AssetManager 只读，无状态，任意线程可调，永不抛异常（IO 失败走占位/404）。
 */
object WebStaticRoutes {

    /** 4.2 产物在 assets 中的相对路径。 */
    const val WEB_INDEX_ASSET_PATH = "web/index.html"

    /**
     * 并行解码模块（Phase E）在 assets 中的相对路径。
     *
     * 由 `index.html` 以 `<script src="h264-player.js">` 同步加载（在控制台内联脚本之前），
     * 提供 `window.H264Player`：普通 LAN HTTP（非安全上下文，无 WebCodecs）下走
     * MSE + fMP4 兜底，安全上下文走 `VideoDecoder`。
     */
    const val H264_PLAYER_ASSET_PATH = "web/h264-player.js"

    /** 模块对外路径（与 assets 相对路径同名，便于同目录引用）。 */
    const val H264_PLAYER_ROUTE = "/h264-player.js"

    /** assets 中的 Web 根目录前缀。 */
    private const val WEB_ASSET_ROOT = "web"

    /** 占位页单文件上限兜底：assets 体积超过此值视为异常包，改走占位（防 OOM）。 */
    private const val MAX_ASSET_BYTES = 5 * 1024 * 1024

    /** URL 路径总长上限（防病态输入）。 */
    private const val MAX_PATH_CHARS = 1024

    /**
     * 静态资源 Cache-Control。统一 `no-cache`（= 每次使用前重校验，命中 ETag 即 304），
     * 不用 `max-age`：资源 URL 不随构建变化，缓存新鲜期会让新装的应用继续跑旧脚本。
     */
    private const val STATIC_CACHE_CONTROL = "no-cache"

    /** 路由结果：HTTP 状态码 + 响应头 + 响应体（HEAD 由 server 压掉 body 只发头）。 */
    data class StaticResult(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    )

    /** 扩展名 → MIME（小写扩展名，无点）。 */
    private val MIME_BY_EXT: Map<String, String> = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "application/javascript; charset=utf-8",
        "mjs" to "application/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "webmanifest" to "application/manifest+json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
    )

    /**
     * 每个资源的强 ETag 缓存（key = 规范化后的 assets 相对路径）。
     * 只在本进程生命周期内有效（重装即重启进程，天然失效），避免每次请求重算哈希。
     */
    private val etagCache = ConcurrentHashMap<String, String>()

    fun handle(method: String, path: String, appContext: Context?): StaticResult {
        if (method != "GET" && method != "HEAD") {
            return StaticResult(405, "text/plain; charset=utf-8", "method not allowed".toByteArray())
        }
        // 入口：`/` 与 `/index.html` 都落 assets/web/index.html（缺则占位页）。
        if (path == "/" || path == "/index.html") {
            val body = loadAsset(appContext, WEB_INDEX_ASSET_PATH) ?: PLACEHOLDER_HTML.toByteArray(Charsets.UTF_8)
            return assetResult(appContext, WEB_INDEX_ASSET_PATH, body)
        }
        val assetPath = resolveAssetPath(path)
            ?: return StaticResult(404, "text/plain; charset=utf-8", "not found".toByteArray())
        // 目录路径（`/window/` 与 `/window`）落该目录下的 index.html：原版 Fusion 逐应用窗口
        // 就是打开 `/window/` 这个路径，资产里对应 `assets/web/window/index.html`。
        val direct = loadAsset(appContext, assetPath)
        val resolvedPath: String
        val body: ByteArray
        if (direct != null) {
            resolvedPath = assetPath
            body = direct
        } else {
            val indexPath = "$assetPath/index.html"
            val indexBody = loadAsset(appContext, indexPath)
                ?: return StaticResult(404, "text/plain; charset=utf-8", "not found".toByteArray())
            resolvedPath = indexPath
            body = indexBody
        }
        return assetResult(appContext, resolvedPath, body)
    }

    /**
     * URL 路径 → assets 相对路径（带防穿越）。非法/越界返回 null（调用方 404）。
     *
     * 规则：拒绝 `%`、`\`、`\u0000`；按 `/` 切分后拒绝空段、`.`、`..` 与以 `.` 开头的
     * 隐藏段；每段只允许 `[A-Za-z0-9._~-]`；最终前缀必须是 `web/`。
     */
    internal fun resolveAssetPath(urlPath: String): String? {
        if (urlPath.isEmpty() || urlPath.length > MAX_PATH_CHARS) return null
        if (!urlPath.startsWith("/")) return null
        if (urlPath.contains('%') || urlPath.contains('\\') || urlPath.contains('\u0000')) return null
        val segments = urlPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        for (seg in segments) {
            if (seg == "." || seg == "..") return null
            if (seg.startsWith(".")) return null
            for (ch in seg) {
                val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                    ch == '.' || ch == '_' || ch == '-' || ch == '~'
                if (!ok) return null
            }
        }
        // 双保险：拼接结果不得出现 `..` 段，且必须落在 web/ 前缀内。
        val rel = segments.joinToString("/")
        if (rel.split('/').any { it == ".." }) return null
        return "$WEB_ASSET_ROOT/$rel"
    }

    /** 组装响应头：Content-Type + ETag + Cache-Control（见 [STATIC_CACHE_CONTROL]）。 */
    private fun assetResult(appContext: Context?, assetPath: String, body: ByteArray): StaticResult {
        val contentType = mimeOf(assetPath)
        val etag = etagCache[assetPath] ?: weakEtag(body).also { etagCache[assetPath] = it }
        return StaticResult(
            status = 200,
            contentType = contentType,
            body = body,
            headers = mapOf("ETag" to etag, "Cache-Control" to STATIC_CACHE_CONTROL),
        )
    }

    /**
     * 弱 ETag：`W/"<len>-<fnv1a32 hex>"`。只用长度 + 32 位内容哈希，
     * 足够区分构建产物，且避免整包 SHA-256 开销。
     */
    internal fun weakEtag(body: ByteArray): String {
        var h = -0x7ee3623b // FNV-1a 32 位 offset basis
        for (b in body) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return "W/\"${body.size}-${(h.toLong() and 0xFFFFFFFFL).toString(16)}\""
    }

    /** 按扩展名取 MIME；未知扩展名按二进制流（`application/octet-stream`）。 */
    internal fun mimeOf(assetPath: String): String {
        val dot = assetPath.lastIndexOf('.')
        if (dot < 0 || dot == assetPath.length - 1) return "application/octet-stream"
        val ext = assetPath.substring(dot + 1).lowercase()
        return MIME_BY_EXT[ext] ?: "application/octet-stream"
    }

    /** 通用 assets 读取（超限/缺失返回 null，不抛）。 */
    private fun loadAsset(appContext: Context?, assetPath: String): ByteArray? {
        if (appContext == null) return null
        return runCatching {
            appContext.assets.open(assetPath).use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var total = 0
                while (true) {
                    val r = ins.read(tmp)
                    if (r < 0) break
                    total += r
                    if (total > MAX_ASSET_BYTES) return@runCatching null
                    out.write(tmp, 0, r)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * 4.2 落子前的占位页（内嵌常量，不占 assets）。
     * 探活脚本先调 `/api/auth/status` 决定是否弹 Token 框——与 MVP.md 四(二)(4) 一致。
     */
    @Suppress("MaxLineLength")
    private const val PLACEHOLDER_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>BlindCast · 隐播</title>
<style>
body{background:#0b0d12;color:#e8eaf0;font-family:system-ui,sans-serif;margin:0;
display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center}
.card{background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);
border-radius:16px;padding:32px 40px;backdrop-filter:blur(12px);max-width:420px}
h1{margin:0 0 8px;font-size:22px}p{opacity:.7;font-size:14px;line-height:1.7}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#34d399;margin-right:6px}
a{color:#7dd3fc;font-size:13px;margin:0 6px}
</style>
</head>
<body>
<div class="card">
<h1>BlindCast · 隐播</h1>
<p><span class="dot"></span>串流服务运行中<br>Web 控制台即将上线（Slice 4.2 落子单页播放器）</p>
<p><a href="/api/auth/status">鉴权状态</a><a href="/api/status">设备状态</a></p>
</div>
</body>
</html>"""
}
