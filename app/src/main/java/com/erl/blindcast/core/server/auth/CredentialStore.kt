package com.erl.blindcast.core.server.auth

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * 配对凭据表（Phase A · 对齐 AndroMeld `clients.json` 的模型，路径与字段自定）。
 *
 * ## 为什么需要它
 * 原鉴权只有一个全局 Token：改一次全体客户端掉线，无法单独吊销，也没法知道
 * 「这个浏览器是哪台设备」。加了文件系统/终端这类危险能力后必须能按设备吊销，
 * 所以凭据要独立成表。
 *
 * ## 存储（`filesDir/pairing/clients.json`）
 * ```json
 * {"clients":[{"id":"<uuid>","name":"MacBook 上的 Chrome","tokenSha256":"<hex64>",
 *   "addedVia":"pairing","createdAt":123,"lastSeenAt":123,"revoked":false}]}
 * ```
 * - **只存 SHA-256**，明文 Token 在签发的那一刻返回一次，此后不可恢复；
 * - 比对走 [MessageDigest.isEqual]（常数时间），防时序侧信道；
 * - 落盘为临时文件 + 原子改名（同 [com.erl.blindcast.core.widget.WidgetStore] 范式）。
 *
 * ## 上限
 * 有效凭据（未吊销）上限 [MAX_ACTIVE]；到顶后新签发直接失败，由调用方回错。
 * 已吊销的记录保留在表里（供审计），不占额度。
 *
 * ## 线程模型
 * 全部方法 `synchronized(lock)` 串行；读一次后缓存在内存，写即落盘。
 * 永不抛异常：IO 失败只记日志，内存态照常生效（丢的是持久化而非运行时）。
 */
object CredentialStore {

    private const val TAG = "BlindCast-Pairing"
    private const val DIR_NAME = "pairing"
    private const val FILE_NAME = "clients.json"

    /** 有效凭据上限（对齐 AndroMeld 的「每客户端 10 个 token」，此处按设备计）。 */
    const val MAX_ACTIVE = 32

    /** Token 明文长度：32 随机字节 → urlsafe base64 无填充 = 43 字符。 */
    private const val TOKEN_BYTES = 32

    /** 一条客户端凭据。[tokenSha256] 为 64 位小写十六进制。 */
    data class Client(
        val id: String,
        val name: String,
        val tokenSha256: String,
        val addedVia: String,
        val createdAt: Long,
        var lastSeenAt: Long,
        var revoked: Boolean,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private var clients = mutableListOf<Client>()
    private var loaded = false
    private var appContext: Context? = null

    /** 预存上下文并首次载入（幂等，任意线程）。 */
    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext ?: context
            if (!loaded) {
                clients = load()
                loaded = true
            }
        }
    }

    /**
     * 签发一条新凭据。
     * @return `(记录, 明文 Token)`；已达 [MAX_ACTIVE] 时返回 null（明文此后不可得）。
     */
    fun issue(name: String, addedVia: String, now: Long = System.currentTimeMillis()): Pair<Client, String>? {
        synchronized(lock) {
            if (activeCount() >= MAX_ACTIVE) {
                Log.w(TAG, "CredentialStore: active limit $MAX_ACTIVE reached, refuse to issue")
                return null
            }
            val token = newToken()
            val client = Client(
                id = UUID.randomUUID().toString(),
                name = name.trim().take(64).ifEmpty { "未命名设备" },
                tokenSha256 = sha256Hex(token),
                addedVia = addedVia,
                createdAt = now,
                lastSeenAt = now,
                revoked = false,
            )
            clients.add(client)
            save()
            return client to token
        }
    }

    /**
     * 校验明文 Token。
     * @return 命中的凭据（并顺手刷新 [Client.lastSeenAt]）；未命中/已吊销返回 null。
     */
    fun verify(token: String): Client? {
        if (token.isEmpty()) return null
        val digest = sha256Hex(token)
        val hit = synchronized(lock) {
            clients.firstOrNull { !it.revoked && MessageDigest.isEqual(it.tokenSha256.toByteArray(), digest.toByteArray()) }
        } ?: return null
        touch(hit.id)
        return hit
    }

    /** 全部凭据（含已吊销），按创建时间倒序。 */
    fun list(): List<Client> = synchronized(lock) { clients.sortedByDescending { it.createdAt } }

    /** 有效凭据数量。 */
    fun activeCount(): Int = synchronized(lock) { clients.count { !it.revoked } }

    /** 是否已有有效凭据（鉴权语义用，见 [TokenAuthenticator.isAuthRequired]）。 */
    fun hasActive(): Boolean = synchronized(lock) { clients.any { !it.revoked } }

    /** 吊销一条凭据；不存在或本就已吊销返回 false。 */
    fun revoke(id: String): Boolean = synchronized(lock) {
        val c = clients.firstOrNull { it.id == id && !it.revoked } ?: return false
        c.revoked = true
        save()
        true
    }

    /** 吊销全部有效凭据，返回吊销条数。 */
    fun revokeAll(): Int = synchronized(lock) {
        val n = clients.count { !it.revoked }
        if (n > 0) {
            clients.forEach { it.revoked = true }
            save()
        }
        n
    }

    private fun touch(id: String) {
        synchronized(lock) {
            val c = clients.firstOrNull { it.id == id } ?: return
            val now = System.currentTimeMillis()
            // 节流落盘：同一凭据 60s 内只刷新一次，避免每个请求都写盘。
            if (now - c.lastSeenAt < 60_000L) return
            c.lastSeenAt = now
            save()
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 32 随机字节 → urlsafe base64 无填充。 */
    private fun newToken(): String {
        val buf = ByteArray(TOKEN_BYTES)
        random.nextBytes(buf)
        return android.util.Base64.encodeToString(buf, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val out = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun dir(): File? = appContext?.let { File(it.filesDir, DIR_NAME) }

    private fun file(): File? = dir()?.let { File(it, FILE_NAME) }

    private fun load(): MutableList<Client> {
        val f = file() ?: return mutableListOf()
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("clients") ?: JSONArray()
            val out = mutableListOf<Client>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val hash = o.optString("tokenSha256", "")
                if (id.isEmpty() || hash.isEmpty()) continue
                out.add(
                    Client(
                        id = id,
                        name = o.optString("name", ""),
                        tokenSha256 = hash,
                        addedVia = o.optString("addedVia", "unknown"),
                        createdAt = o.optLong("createdAt", 0L),
                        lastSeenAt = o.optLong("lastSeenAt", 0L),
                        revoked = o.optBoolean("revoked", false),
                    ),
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "CredentialStore load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun save() {
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val arr = JSONArray()
            for (c in clients) {
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", c.name)
                        .put("tokenSha256", c.tokenSha256)
                        .put("addedVia", c.addedVia)
                        .put("createdAt", c.createdAt)
                        .put("lastSeenAt", c.lastSeenAt)
                        .put("revoked", c.revoked),
                )
            }
            val text = JSONObject().put("clients", arr).toString()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "CredentialStore save failed: ${it.message}") }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
