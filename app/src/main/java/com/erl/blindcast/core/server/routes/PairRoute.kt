package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.server.auth.CredentialStore
import com.erl.blindcast.core.server.auth.PairingManager
import com.erl.blindcast.core.server.auth.PairingThrottle
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配对与凭据管理路由（Phase A）。
 *
 * ## 端点
 * - `POST /api/pair`（**公开**，受 per-IP 退避约束）
 *   体 `{"code":"XXXX-XXXXX","name":"MacBook 上的 Chrome"}` →
 *   - 200 `{"ok":true,"token":"<43字符>","clientId":"<uuid>","name":"..."}`
 *   - 400 `{"ok":false,"error":"bad_request"}`
 *   - 401 `{"ok":false,"error":"invalid_code"}`（码错 / 过期 / 已用，**不区分原因**）
 *   - 429 `{"ok":false,"error":"too_many_attempts","retryAfterMs":N}`
 *   - 503 `{"ok":false,"error":"too_many_clients"}`
 * - `GET /api/clients`（**需鉴权**）→ `{"ok":true,"clients":[...],"active":N}`
 * - `POST /api/clients/revoke`（**需鉴权**）体 `{"id":"..."}` → `{"ok":true,"revoked":1}`
 *
 * ## 公开端点的风险控制
 * `/api/pair` 不需要鉴权（它本身就是取得凭据的途径），因此它是本服务唯一
 * 可被匿名调用的写端点。防线有三层：per-IP 指数退避、40 bit 随机码空间、
 * 单次消费 + 300s TTL。编码层不区分「码错 / 过期 / 已用」，避免把
 * 「这个码存在过」这一位信息漏给攻击者。
 *
 * 无状态，任意后台线程可调；返回 `Pair(HTTP状态码, JSON字符串)`。
 */
object PairRoute {

    private const val TAG = "BlindCast-Pairing"

    /** 单次配对请求的体上限（远小于全局 256KB）。 */
    const val MAX_BODY = 4096

    /** `POST /api/pair`（公开）。 */
    fun handlePair(method: String, body: ByteArray, remoteKey: String): Pair<Int, String> {
        if (method != "POST") return 405 to err("method not allowed")
        if (remoteKey.isBlank()) return 400 to err("bad_request")
        if (!PairingThrottle.allow(remoteKey)) {
            val wait = PairingThrottle.retryAfterMs(remoteKey)
            return 429 to JSONObject()
                .put("ok", false)
                .put("error", "too_many_attempts")
                .put("retryAfterMs", wait)
                .toString()
        }
        val json = runCatching {
            if (body.isEmpty()) null else JSONObject(body.toString(Charsets.UTF_8))
        }.getOrNull() ?: run {
            PairingThrottle.onFailure(remoteKey)
            return 400 to err("bad_request")
        }
        val code = json.optString("code", "").trim()
        val name = json.optString("name", "").trim()
        if (code.isEmpty()) {
            PairingThrottle.onFailure(remoteKey)
            return 400 to err("bad_request")
        }
        return when (val result = PairingManager.redeem(code, name)) {
            is PairingManager.Redeem.Ok -> {
                PairingThrottle.onSuccess(remoteKey)
                Log.i(TAG, "paired client '${result.client.name}' via $remoteKey")
                200 to JSONObject()
                    .put("ok", true)
                    .put("token", result.token)
                    .put("clientId", result.client.id)
                    .put("name", result.client.name)
                    .toString()
            }
            PairingManager.Redeem.Invalid -> {
                PairingThrottle.onFailure(remoteKey)
                401 to err("invalid_code")
            }
            PairingManager.Redeem.LimitReached -> {
                PairingThrottle.onFailure(remoteKey)
                503 to err("too_many_clients")
            }
        }
    }

    /** `GET /api/clients`（需鉴权）。 */
    fun handleClients(method: String): Pair<Int, String> {
        if (method != "GET") return 405 to err("method not allowed")
        val arr = JSONArray()
        for (c in CredentialStore.list()) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("addedVia", c.addedVia)
                    .put("createdAt", c.createdAt)
                    .put("lastSeenAt", c.lastSeenAt)
                    .put("revoked", c.revoked),
            )
        }
        return 200 to JSONObject()
            .put("ok", true)
            .put("clients", arr)
            .put("active", CredentialStore.activeCount())
            .toString()
    }

    /** `POST /api/clients/revoke`（需鉴权）；体 `{"id":"..."}` 或 `{"id":"all"}`。 */
    fun handleRevoke(method: String, body: ByteArray): Pair<Int, String> {
        if (method != "POST") return 405 to err("method not allowed")
        val id = runCatching {
            if (body.isEmpty()) "" else JSONObject(body.toString(Charsets.UTF_8)).optString("id", "")
        }.getOrDefault("").trim()
        if (id.isEmpty()) return 400 to err("bad_request")
        val removed = if (id == "all") CredentialStore.revokeAll() else if (CredentialStore.revoke(id)) 1 else 0
        return 200 to JSONObject().put("ok", true).put("revoked", removed).toString()
    }

    private fun err(code: String): String = JSONObject().put("ok", false).put("error", code).toString()
}
