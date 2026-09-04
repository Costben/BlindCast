package com.erl.blindcast.core.server.auth

import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Token 鉴权拦截器（Slice 4.1 · MVP.md 二(5) + 四(二)(4)）。
 *
 * ## 语义（铁线）
 * - Token 留空（空串 / 全空白归一为空）→ 免密直通，所有请求直接放行；
 * - 设置密码后 → 校验请求携带的 Token：
 *   1. 查询串 `?token=xxx`（HA 设备链接预埋免密秒进即走此通道）；
 *   2. `Authorization` 请求头（`Bearer xxx` 或裸 Token 二者兼容）；
 * - 任一通道命中即放行；缺失 / 失配 → 401（由 [BlindCastServer] 统一回包）。
 *
 * ## 线程模型
 * - [token] 为 `@Volatile`，设置页（Slice 6.2）与服务线程可任意时刻读写；
 * - 纯内存开关：持久化偏好归 `SettingsRepository`，启动时一次性 [setToken] 同步即可；
 * - 无状态工具：比较走常量时间 [MessageDigest.isEqual]，防时序侧信道；
 * - 所有方法永不抛异常（解析失败按“未携带”处理）。
 */
object TokenAuthenticator {

    /** 授权头 scheme 前缀（大小写不敏感）。 */
    private const val BEARER_SCHEME = "bearer"

    /**
     * 当前访问 Token（volatile，跨线程可见）。
     * 空串 = 免密直通；非空 = 要求鉴权。写入口请走 [setToken]（空白归一）。
     */
    @Volatile
    var token: String = ""
        private set

    /**
     * 设置 Token（任意线程）。
     * 全空白输入归一为空串（即免密直通），避免“空格密码”把自己锁死的乌龙。
     */
    fun setToken(value: String) {
        token = if (value.isBlank()) "" else value
    }

    /** 是否要求鉴权（Token 非空即要求）。 */
    fun isAuthRequired(): Boolean = token.isNotEmpty()

    /**
     * 从查询串 + 请求头中提取调用方携带的 Token（无则 null）。
     *
     * @param rawQuery 原始查询串（`?` 之后部分，可为 null）。
     * @param headers 请求头（key 必须已小写归一，见 [BlindCastServer]）。
     */
    fun extractToken(rawQuery: String?, headers: Map<String, String>): String? {
        parseQuery(rawQuery)["token"]?.let { return it }
        val auth = headers["authorization"] ?: return null
        if (auth.isBlank()) return null
        val trimmed = auth.trim()
        if (trimmed.length > BEARER_SCHEME.length + 1 &&
            trimmed.startsWith(BEARER_SCHEME, ignoreCase = true) &&
            trimmed[BEARER_SCHEME.length].isWhitespace()
        ) {
            return trimmed.substring(BEARER_SCHEME.length + 1).trim().ifEmpty { null }
        }
        return trimmed.ifEmpty { null }
    }

    /**
     * 鉴权判定（供 HTTP 路由与 WebSocket 升级前调用）。
     * 免密模式直接 true；设密后任一通道命中即 true。
     */
    fun isAuthorized(rawQuery: String?, headers: Map<String, String>): Boolean {
        val expected = token
        if (expected.isEmpty()) return true
        val actual = extractToken(rawQuery, headers) ?: return false
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * 解析 URL 查询串为首值胜出的键值表（key 区分大小写，`token` 全小写约定）。
     * 非法 `%` 转义按原文保留，不抛异常。
     */
    fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val key = urlDecode(rawKey)
            if (key.isEmpty() || out.containsKey(key)) continue
            out[key] = urlDecode(rawValue)
        }
        return out
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
