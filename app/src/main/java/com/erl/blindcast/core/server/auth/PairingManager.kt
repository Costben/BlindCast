package com.erl.blindcast.core.server.auth

import android.util.Log
import java.security.SecureRandom

/**
 * 配对会话（Phase A）——未决配对码的生命周期 + 兑换。
 *
 * ## 一次配对的完整时序
 * ```
 * 手机首页「生成配对码」 → issue()  ← 9 字符展示码（TTL 300s，未决上限 8 条）
 * 浏览器输入该码          → redeem() ← 命中即「消费」该码（单次使用），签发凭据
 * 浏览器此后携带 token    → CredentialStore.verify()
 * ```
 *
 * ## 为什么未决码只在内存
 * TTL 只有 300s，落盘只会把「短命秘密」变成「磁盘上的长期秘密」；
 * 进程重启后旧码失效是可接受的（首页会立刻显示新码）。
 *
 * ## 单次消费
 * 兑换成功即从 [pending] 移除。重放同一个码必然失败 —— 这是配对协议里
 * 唯一能挡住「码被旁观者记下后复用」的机制，不能省。
 *
 * 全部方法 `synchronized`，任意线程可调，永不抛异常。
 */
object PairingManager {

    private const val TAG = "BlindCast-Pairing"

    /** 配对码有效期（对齐原版 300s）。 */
    const val TTL_MS = 300_000L

    /** 未决码上限，超出淘汰最旧的一条。 */
    const val MAX_PENDING = 8

    /** master token 的随机字节数（32B → urlsafe base64 无填充 43 字符）。 */
    private const val TOKEN_BYTES = 32

    /** 一条未决配对码。 */
    data class Ticket(
        /** 40 bit 随机值（内部匹配用，不对外暴露语义）。 */
        val value: Long,
        /** 9 字符展示码 `XXXX-XXXXX`（给人手输，也是二维码/链接的内容）。 */
        val display: String,
        val issuedAt: Long,
        val expiresAt: Long,
    ) {
        fun remainingMs(now: Long = System.currentTimeMillis()): Long = (expiresAt - now).coerceAtLeast(0L)
    }

    /** 兑换结果。[Invalid] 不区分「码错 / 过期 / 已用」，对外统一回同一个错误码。 */
    sealed interface Redeem {
        data class Ok(val client: CredentialStore.Client, val token: String) : Redeem
        data object Invalid : Redeem
        data object LimitReached : Redeem
    }

    private val lock = Any()
    private val random = SecureRandom()
    private val pending = ArrayDeque<Ticket>()

    /**
     * 签发一条新配对码（旧的仍然有效，直到过期或被用掉；上限 [MAX_PENDING]）。
     */
    fun issue(now: Long = System.currentTimeMillis()): Ticket {
        val value = random.nextLong() and PairingCode.TICKET_MASK
        val ticket = Ticket(
            value = value,
            display = PairingCode.encodeDisplay(value),
            issuedAt = now,
            expiresAt = now + TTL_MS,
        )
        synchronized(lock) {
            pruneLocked(now)
            pending.addLast(ticket)
            while (pending.size > MAX_PENDING) pending.removeFirst()
        }
        Log.i(TAG, "pairing code issued, expires in ${TTL_MS / 1000}s, pending=${synchronized(lock) { pending.size }}")
        return ticket
    }

    /** 当前仍有效的、最近签发的一条码（首页展示用）；没有则 null。 */
    fun current(now: Long = System.currentTimeMillis()): Ticket? = synchronized(lock) {
        pruneLocked(now)
        pending.lastOrNull()
    }

    /** 清空全部未决码（服务停止 / 用户取消配对时调用）。 */
    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    /**
     * 用展示码兑换一条凭据。展示码命中即被消费（单次使用）。
     * @param clientName 浏览器/设备自报的名字，仅作展示。
     */
    fun redeem(displayCode: String, clientName: String, now: Long = System.currentTimeMillis()): Redeem {
        val value = PairingCode.decodeDisplay(displayCode) ?: run {
            Log.w(TAG, "pairing rejected: malformed code")
            return Redeem.Invalid
        }
        val hit = synchronized(lock) {
            pruneLocked(now)
            val idx = pending.indexOfFirst { it.value == value }
            if (idx < 0) null else pending.removeAt(idx)
        }
        if (hit == null) {
            Log.w(TAG, "pairing rejected: unknown or expired code")
            return Redeem.Invalid
        }
        val issued = CredentialStore.issue(clientName, ADDED_VIA, now) ?: return Redeem.LimitReached
        Log.i(TAG, "pairing accepted, client=${issued.first.id}")
        return Redeem.Ok(issued.first, issued.second)
    }

    /**
     * 生成一个 master token 明文（32 随机字节 → urlsafe base64 无填充）。
     * 供 UI 在「首次开启配对鉴权」时给手机自身链接兜底，见 Phase A 方案。
     */
    fun newMasterToken(): String {
        val buf = ByteArray(TOKEN_BYTES)
        random.nextBytes(buf)
        return android.util.Base64.encodeToString(
            buf,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
    }

    /** `addedVia` 取值，与 [CredentialStore.Client.addedVia] 约定一致。 */
    const val ADDED_VIA = "pairing"

    private fun pruneLocked(now: Long) {
        while (pending.isNotEmpty() && pending.first().expiresAt <= now) pending.removeFirst()
    }
}
