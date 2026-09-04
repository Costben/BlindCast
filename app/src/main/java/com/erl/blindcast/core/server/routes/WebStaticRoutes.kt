package com.erl.blindcast.core.server.routes

import android.content.Context

/**
 * 静态文件路由（Slice 4.1 · `GET /` 与 `/index.html`）。
 *
 * ## 行为
 * - 优先输出 `assets/web/index.html`（Slice 4.2 落子真正的单页控制台后自动生效，
 *   本文件零改动——刻意只做“有则 serve、无则占位”的薄封装）；
 * - 4.2 落子前 assets 缺失 → 返回内嵌占位页（暗黑风，提示控制台即将上线，
 *   附 `/api/auth/status` 与 `/api/status` 快捷探活链接，供联调确认服务存活）；
 * - 仅允许 GET / HEAD，其余 405；未知路径由 [BlindCastServer] 统一 404。
 *
 * 纯内存 + AssetManager 只读，无状态，任意线程可调，永不抛异常（IO 失败走占位）。
 */
object WebStaticRoutes {

    /** 4.2 产物在 assets 中的相对路径。 */
    const val WEB_INDEX_ASSET_PATH = "web/index.html"

    /** 占位页单文件上限兜底：assets 体积超过此值视为异常包，改走占位（防 OOM）。 */
    private const val MAX_ASSET_BYTES = 5 * 1024 * 1024

    /** 路由结果：HTTP 状态码 + 响应体（HEAD 由 server 压掉 body 只发头）。 */
    data class StaticResult(val status: Int, val contentType: String, val body: ByteArray)

    fun handle(method: String, path: String, appContext: Context?): StaticResult {
        if (method != "GET" && method != "HEAD") {
            return StaticResult(405, "text/plain; charset=utf-8", "method not allowed".toByteArray())
        }
        val body = loadIndex(appContext)
        return StaticResult(200, "text/html; charset=utf-8", body)
    }

    private fun loadIndex(appContext: Context?): ByteArray {
        if (appContext != null) {
            runCatching {
                appContext.assets.open(WEB_INDEX_ASSET_PATH).use { ins ->
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
                    return out.toByteArray()
                }
            }.getOrNull()?.let { return it }
        }
        return PLACEHOLDER_HTML.toByteArray(Charsets.UTF_8)
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
