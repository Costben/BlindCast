package com.erl.blindcast.core.server.routes

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC6455 WebSocket 服务端连接（Slice 4.1 · `/ws/stream` 与 `/ws/control` 共用）。
 *
 * ## 为什么手写帧编解码
 * - 质量红线禁 Ktor/Netty 等重型依赖，仓库亦无轻量 WS 库；
 * - `com.sun.net.httpserver` 不在 Android `android.jar` / ART 内（含该 import 即编译失败），
 *   且其 `HttpExchange` 无法劫持底層 TCP 做 `101` 升级——故本 Slice 整站（含 HTTP/1.1）
 *   统一跑在 JDK 原生 `ServerSocket` 上，零新增依赖（见 [BlindCastServer] 类注释）。
 *
 * ## 使用约定
 * - 由 [BlindCastServer] 完成 HTTP 升级握手（校验 + 回 `101` + flush）后构造；
 * - [input] 必须是握手后定位在首个帧字节的同一流（与解析 HTTP 头共用，
 *   禁止在握手阶段用 `BufferedReader` 超读，见 [BlindCastServer.readHttpHead]）；
 * - [receive] 内自动应答 Ping（回 Pong）、忽略 Pong、回敬 Close；
 * - 服务端发出帧永不掩码；客户端发来未掩码帧按协议违规断开（1002）；
 * - 分片消息自动重组（Continuation），上限 [MAX_MESSAGE_BYTES]，超限断开（1009）。
 *
 * ## 线程模型
 * - 单连接 Sender / Receiver 各一线程：发送方法互斥（`synchronized(output)`），
 *   接收为阻塞独占；[close] 幂等，任意线程可调（stop 即关 socket 解阻塞）。
 */
class WsConnection(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {

    companion object {
        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        /** 握手 GUID（RFC6455 §1.3）。 */
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** 单条重组后消息上限 2MB（控制指令远小于此；推流只发不收）。 */
        const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024

        /** 控制帧负载上限 125B（协议硬性规定）。 */
        private const val MAX_CONTROL_BYTES = 125

        /**
         * 由客户端 `Sec-WebSocket-Key` 计算 `Sec-WebSocket-Accept`。
         * `base64(sha1(trim(key) + GUID))`。
         */
        fun acceptKey(clientKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest((clientKey.trim() + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(digest)
        }
    }

    /** 服务端视角的完整消息（分片已重组；控制帧永不经此返回）。 */
    data class Frame(val opcode: Int, val payload: ByteArray) {
        val isText: Boolean get() = opcode == OP_TEXT
        val isBinary: Boolean get() = opcode == OP_BINARY
        fun text(): String = payload.toString(Charsets.UTF_8)
    }

    /** 远端地址（log / 6.1 在线人数统计用）。 */
    val remoteAddress: String = runCatching { socket.remoteSocketAddress?.toString() }.getOrNull() ?: "?"

    @Volatile
    private var open = true

    /** 连接是否仍可用（握手后 close / 对端关闭 / socket 断开即 false）。 */
    val isOpen: Boolean
        get() = open && !socket.isClosed && socket.isConnected

    /**
     * 接收下一条完整消息（阻塞）。
     * @return 文本 / 二进制消息；对端正常关闭或 EOF 返回 null。
     * @throws SocketTimeoutException 读超时（调用方可继续循环，连接未坏）。
     * @throws java.io.IOException 其他 IO 异常（调用方应关闭连接）。
     */
    @Throws(java.io.IOException::class)
    fun receive(): Frame? {
        var messageOpcode = -1
        var message = ByteArray(0)
        while (true) {
            val header = readFully(2) ?: return null
            val fin = header[0].toInt() and 0x80 != 0
            val opcode = header[0].toInt() and 0x0F
            val masked = header[1].toInt() and 0x80 != 0
            var length = header[1].toInt() and 0x7F
            when (length) {
                126 -> {
                    val ext = readFully(2) ?: return null
                    length = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
                }
                127 -> {
                    val ext = readFully(8) ?: return null
                    var big = 0L
                    for (b in ext) big = (big shl 8) or (b.toLong() and 0xFF)
                    if (big > MAX_MESSAGE_BYTES || big < 0) {
                        close(1009, "message too large")
                        return null
                    }
                    length = big.toInt()
                }
            }
            if (opcode >= OP_CLOSE) {
                // 控制帧：禁止分片、负载 ≤125。
                if (!fin || length > MAX_CONTROL_BYTES) {
                    close(1002, "bad control frame")
                    return null
                }
            }
            if (!masked) {
                // 客户端帧必须掩码（RFC6455 §5.1），否则协议违规。
                discard(length)
                close(1002, "client frame not masked")
                return null
            }
            if (message.size + length > MAX_MESSAGE_BYTES) {
                discard(length + 4)
                close(1009, "message too large")
                return null
            }
            val mask = readFully(4) ?: return null
            val payload = readFully(length) ?: return null
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                OP_PING -> {
                    sendFrame(OP_PONG, payload)
                    continue
                }
                OP_PONG -> continue
                OP_CLOSE -> {
                    runCatching { sendFrame(OP_CLOSE, payload) }
                    open = false
                    return null
                }
                OP_TEXT, OP_BINARY -> {
                    if (messageOpcode != -1) {
                        close(1002, "concurrent fragments")
                        return null
                    }
                    messageOpcode = opcode
                    message = payload
                    if (fin) return Frame(opcode, message)
                }
                OP_CONT -> {
                    if (messageOpcode == -1) {
                        close(1002, "stray continuation")
                        return null
                    }
                    message = message + payload
                    if (fin) {
                        val done = Frame(messageOpcode, message)
                        messageOpcode = -1
                        message = ByteArray(0)
                        return done
                    }
                }
                else -> {
                    close(1002, "unknown opcode")
                    return null
                }
            }
        }
    }

    /** 发送文本消息（UTF-8）。失败抛 IOException，调用方应移除该会话。 */
    @Throws(java.io.IOException::class)
    fun sendText(text: String) {
        sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /** 发送二进制消息。失败抛 IOException，调用方应移除该会话。 */
    @Throws(java.io.IOException::class)
    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        sendFrame(OP_BINARY, data, offset, length, null)
    }

    /**
     * 发送带 1 字节通道前缀的二进制消息（推流协议：`kind + payload` 一次写出，
     * 免大 NALU 拷贝；与 [sendBinary] 同一互斥域）。
     */
    @Throws(java.io.IOException::class)
    fun sendBinaryWithPrefix(kind: Byte, payload: ByteArray) {
        sendFrame(OP_BINARY, payload, 0, payload.size, byteArrayOf(kind))
    }

    /** 发送关闭帧并关 socket（幂等，任意线程）。 */
    fun close(code: Int = 1000, reason: String = "") {
        val already = synchronized(output) {
            val was = open
            open = false
            !was
        }
        if (already) {
            runCatching { socket.close() }
            return
        }
        try {
            val reasonBytes = reason.toByteArray(Charsets.UTF_8).take(MAX_CONTROL_BYTES - 2).toByteArray()
            val body = ByteArray(2 + reasonBytes.size)
            body[0] = (code shr 8).toByte()
            body[1] = code.toByte()
            reasonBytes.copyInto(body, 2)
            sendFrame(OP_CLOSE, body)
        } catch (_: Exception) {
            // 关闭帧尽力而为，失败直接关 socket。
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        sendFrame(opcode, payload, 0, payload.size, null)
    }

    private fun sendFrame(opcode: Int, payload: ByteArray, offset: Int, length: Int, prefix: ByteArray?) {
        synchronized(output) {
            if (!isOpen) throw EOFException("ws closed")
            val prefixLen = prefix?.size ?: 0
            val total = prefixLen + length
            output.write(0x80 or opcode)
            when {
                total <= MAX_CONTROL_BYTES -> output.write(total)
                total <= 0xFFFF -> {
                    output.write(126)
                    output.write(total shr 8)
                    output.write(total and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) {
                        output.write((total.toLong() shr shift).toInt() and 0xFF)
                    }
                }
            }
            prefix?.let { output.write(it) }
            output.write(payload, offset, length)
            output.flush()
        }
    }

    /** 精确读满 [n] 字节；EOF 返回 null（n==0 返回空数组）。 */
    private fun readFully(n: Int): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = input.read(buf, done, n - done)
            if (r < 0) return null
            done += r
        }
        return buf
    }

    /** 丢弃 [n] 字节（协议违规时跳过非法负载；读错即 EOF，按关闭处理）。 */
    private fun discard(n: Int) {
        var left = n
        val tmp = ByteArray(4096)
        while (left > 0) {
            val r = input.read(tmp, 0, minOf(tmp.size, left))
            if (r < 0) return
            left -= r
        }
    }
}
