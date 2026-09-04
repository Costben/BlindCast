package com.erl.blindcast.core.server.routes

import com.erl.blindcast.core.server.auth.TokenAuthenticator
import org.json.JSONObject

/**
 * 鉴权 REST 路由（Slice 4.1 · MVP.md 四(二)(4) Token 机制）。
 *
 * - `GET /api/auth/status`（公开）：`{"authRequired":bool}`——网页打开先探活，
 *   要求密码且未持有效 Token 时前端弹毛玻璃密码框；
 * - `GET|POST /api/auth/verify`（公开）：Token 来源按优先级
 *   查询串 `?token=` → JSON 体 `{"token":...}` → `Authorization` 头；
 *   免密模式直接 `{ok:true, authRequired:false}`；
 *   设密后命中 `{ok:true}` / 失配 401 `{ok:false, error:"invalid_token"}`。
 *
 * 无状态，任意线程可调；返回 `Pair(HTTP状态码, JSON字符串)`，由 [BlindCastServer] 发包。
 */
object AuthRoute {

    fun handleStatus(): Pair<Int, String> {
        val json = JSONObject()
            .put("authRequired", TokenAuthenticator.isAuthRequired())
            .toString()
        return 200 to json
    }

    fun handleVerify(
        method: String,
        rawQuery: String?,
        headers: Map<String, String>,
        body: ByteArray,
    ): Pair<Int, String> {
        if (method != "GET" && method != "POST") {
            return 405 to JSONObject().put("ok", false).put("error", "method not allowed").toString()
        }
        if (!TokenAuthenticator.isAuthRequired()) {
            return 200 to JSONObject().put("ok", true).put("authRequired", false).toString()
        }
        // JSON 体里的 token 并入查询串后统一走 TokenAuthenticator 判定
        // （比较收敛一处，保持常量时间，防时序侧信道）。
        val tokenFromBody = runCatching {
            if (body.isEmpty()) null
            else JSONObject(body.toString(Charsets.UTF_8)).optString("token", null)?.ifEmpty { null }
        }.getOrNull()
        val effectiveQuery = listOfNotNull(
            rawQuery?.ifEmpty { null },
            tokenFromBody?.let { "token=${urlEncode(it)}" },
        ).joinToString("&").ifEmpty { null }
        val ok = TokenAuthenticator.isAuthorized(effectiveQuery, headers)
        return if (ok) {
            200 to JSONObject().put("ok", true).toString()
        } else {
            401 to JSONObject().put("ok", false).put("error", "invalid_token").toString()
        }
    }

    private fun urlEncode(s: String): String =
        runCatching { java.net.URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)
}
