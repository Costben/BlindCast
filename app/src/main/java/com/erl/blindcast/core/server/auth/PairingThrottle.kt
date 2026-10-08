package com.erl.blindcast.core.server.auth

/**
 * 配对尝试的 per-IP 退避（Phase A · 对齐 AndroMeld `C0012f.java:405-422`）。
 *
 * ## 规则
 * - 连续失败 `n` 次后，该来源 IP 的等待时间为 `2000 << min(20, n)` 毫秒，封顶 **120s**；
 * - 任意一次成功立即清零（正常用户输对一次就恢复，不会被历史失败拖累）；
 * - 表容量上限 [MAX_ENTRIES]，超出时淘汰最久未更新的条目（防伪造源 IP 撑爆内存）。
 *
 * ## 为什么不直接用「锁定 1 小时」
 * 原版在极端失败下会进入 1h 锁定。配对码本身 TTL 只有 300s 且**单次消费**，
 * 攻击者就算爆破成功也必须抢在窗口内命中一个未使用的码；120s 封顶的退避
 * 已把每秒尝试压到 `1/120`，足以让 40 bit 空间在 300s 内不可枚举。
 * 更激进的锁定会把「用户自己手抖几次」变成「一小时内彻底连不上」，得不偿失。
 *
 * 全部方法 `synchronized`，任意线程可调，永不抛异常。
 */
object PairingThrottle {

    private const val BASE_MS = 2_000L
    private const val MAX_BACKOFF_MS = 120_000L
    private const val MAX_SHIFT = 20

    /** 表容量上限（一个局域网里不可能有这么多来源）。 */
    private const val MAX_ENTRIES = 256

    private class Entry(var fails: Int, var blockedUntil: Long, var updatedAt: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    /** 该来源当前是否允许尝试配对。 */
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val e = entries[key] ?: return true
        now >= e.blockedUntil
    }

    /** 距离可再次尝试还有多少毫秒（0 = 现在就可以）。 */
    fun retryAfterMs(key: String, now: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        val e = entries[key] ?: return 0L
        (e.blockedUntil - now).coerceAtLeast(0L)
    }

    /**
     * 记一次失败，拉长退避窗口。
     * `[Entry.fails]` 记的是**累计失败次数**，首次失败（fails 由 0 变 1）对应 0 次幂
     * 即 `2000ms`，因此 [backoffOf] 收的是「本次失败之前的失败次数」。
     */
    fun onFailure(key: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val e = entries.getOrPut(key) { Entry(0, 0L, now) }
            e.blockedUntil = now + backoffOf(e.fails)
            e.fails = (e.fails + 1).coerceAtMost(MAX_SHIFT)
            e.updatedAt = now
            trimIfNeeded()
        }
    }

    /** 记一次成功，清空该来源的失败历史。 */
    fun onSuccess(key: String) {
        synchronized(lock) { entries.remove(key) }
    }

    /** 当前被退避的来源数量（诊断用）。 */
    fun blockedCount(now: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        entries.values.count { now < it.blockedUntil }
    }

    private fun backoffOf(fails: Int): Long {
        val shift = fails.coerceIn(0, MAX_SHIFT)
        val raw = if (shift >= 62) MAX_BACKOFF_MS else BASE_MS shl shift
        return raw.coerceAtMost(MAX_BACKOFF_MS)
    }

    /** 超容时淘汰最久未更新的条目。 */
    private fun trimIfNeeded() {
        if (entries.size <= MAX_ENTRIES) return
        val victim = entries.minByOrNull { it.value.updatedAt }?.key ?: return
        entries.remove(victim)
    }
}
