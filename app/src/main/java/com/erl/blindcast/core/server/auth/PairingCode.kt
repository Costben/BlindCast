package com.erl.blindcast.core.server.auth

/**
 * 配对码编解码（Phase A）。
 *
 * ## 为什么用这套字母表
 * 32 字符恰好 5 bit，无进位残渣；排除 `0 / O / I / L` 是为了人工手输时不出错
 * （`0↔O` 与 `1↔I↔L` 是最常见的手抄混淆）。`U` 保留以凑满 32 个符号。
 *
 * ## 形态
 * 一个 40 bit 随机数 → `8` 个数据字符 + `1` 个校验字符 = **`9` 字符**，
 * 第 4 个字符后插一个 `-`：`XXXX-XXXXX`。高低位组**一律高位在前**（与 [encodeDisplay]
 * 和 [decodeDisplay] 双向一致，这是上一版踩过的坑）。
 *
 * ## 校验字符算法
 * 从数据段末字符往前遍历，权重自 `2` 起交替 `1`：
 * ```
 * v = 字符下标 × 权重            // 0..62
 * sum += (v % 32) + (v / 32)     // 折叠回 0..32，等价于 mod-32 的数字和
 * 校验下标 = (32 - sum % 32) % 32
 * ```
 * 单字符替换与相邻换位都会被检出（typo 检测，不是密码学校验）。
 *
 * ## 与原版（AndroMeld `AbstractC2900m.java`）的偏离
 * 原版是 `14 bit 版本号 + 40 bit 值 + 1 校验位` = 12 字符。这里砍掉了版本号：
 * 14 + 40 = 54 bit 要塞进 11 个 5 bit 组（55 bit），高出来的那 1 bit 恒为 0，
 * 于是**展示码的第一个字符永远固定**（版本号小于 1024 时都是 `1`）——
 * 既浪费 4 bit 熵，又会让人误以为是前缀而抄漏。我们也不需要与它的客户端互通
 * （它的浏览器面板托管在厂商云上，不参与本项目），所以直接取 40 bit + 校验位，
 * 9 字符、零浪费、每个字符都在变。
 *
 * ## 宽容度
 * 解码时剥离空白与 `-`，统一大写，并把 `I` / `L` 折叠为 `1`；
 * `0` / `O` **不宽容** —— 它们根本不在字母表内，必须报错。
 *
 * 纯函数，无状态，任意线程可调，永不抛异常（非法输入一律返回 null）。
 */
object PairingCode {

    /** 32 符号字母表：排除 `0 / O / I / L`。下标即 5 bit 值。 */
    const val ALPHABET = "123456789ABCDEFGHJKMNPQRSTUVWXYZ"

    private const val RADIX = 32

    /** 随机值位宽。 */
    const val TICKET_BITS = 40

    /** 随机值掩码（低 40 bit）。 */
    const val TICKET_MASK: Long = (1L shl TICKET_BITS) - 1

    /** 数据段字符数：8 × 5 bit = 40 bit 恰好铺满。 */
    private const val DATA_CHARS = 8

    /** 展示码总字符数（含 `1` 个校验字符）。 */
    const val DISPLAY_CHARS = 9

    /** 分组展示的组宽。 */
    private const val GROUP = 4

    /** ASCII 反查表，`-1` = 非法字符。构建时把 `I` / `L` 折叠到 `1`。 */
    private val DECODE = IntArray(128) { -1 }.also { t ->
        for (i in ALPHABET.indices) t[ALPHABET[i].code] = i
        t['I'.code] = t['1'.code]
        t['L'.code] = t['1'.code]
    }

    /**
     * 40 bit 值 → `XXXX-XXXXX` 展示码（高位组在前，末位是校验字符）。
     * 超出 40 bit 的部分被截断。
     */
    fun encodeDisplay(value: Long): String {
        val v = value and TICKET_MASK
        val idx = IntArray(DATA_CHARS)
        for (i in 0 until DATA_CHARS) {
            // i = 0 取最高位组，i = DATA_CHARS-1 取最低位组 —— 与解码方向严格对齐。
            val shift = 5 * (DATA_CHARS - 1 - i)
            idx[i] = ((v ushr shift) and 0x1F).toInt()
        }
        val sb = StringBuilder(DISPLAY_CHARS)
        for (d in idx) sb.append(ALPHABET[d])
        sb.append(ALPHABET[checkIndex(idx)])
        // 只在首个 4 字符组后插一个 `-`：`XXXX-XXXXX`。与网页端 pairFmt 的
        // `slice(0, 4) + "-" + slice(4)` 完全同形，两端显示与提示文案才不会打架。
        val raw = sb.toString()
        return raw.substring(0, GROUP) + "-" + raw.substring(GROUP)
    }

    /**
     * 展示码 → 40 bit 值。
     * 长度不符 / 含非法字符 / 校验位不匹配时返回 null（不区分原因，防 oracle）。
     */
    fun decodeDisplay(text: String): Long? {
        val s = normalize(text)
        if (s.length != DISPLAY_CHARS) return null
        val idx = IntArray(DISPLAY_CHARS)
        for (i in s.indices) {
            idx[i] = digitOf(s[i]) ?: return null
        }
        val data = idx.copyOf(DATA_CHARS)
        if (idx[DISPLAY_CHARS - 1] != checkIndex(data)) return null
        var v = 0L
        for (d in data) v = (v shl 5) or d.toLong()
        return v and TICKET_MASK
    }

    /** 展示码是否语法合法（时效与是否已用归 [PairingManager] 判断）。 */
    fun isWellFormed(text: String): Boolean = decodeDisplay(text) != null

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 剥离空白与 `-`，并统一大写。 */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isWhitespace() || ch == '-') continue
            sb.append(ch.uppercaseChar())
        }
        return sb.toString()
    }

    private fun digitOf(ch: Char): Int? {
        if (ch.code >= DECODE.size) return null
        val d = DECODE[ch.code]
        return if (d < 0) null else d
    }

    /** 校验字符下标（见类注释的算法）。 */
    private fun checkIndex(data: IntArray): Int {
        var sum = 0
        var weight = 2
        for (i in data.indices.reversed()) {
            val v = data[i] * weight
            sum += (v % RADIX) + (v / RADIX)
            weight = if (weight == 2) 1 else 2
        }
        return (RADIX - sum % RADIX) % RADIX
    }
}
