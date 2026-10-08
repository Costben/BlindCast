package com.erl.blindcast.core.server.routes

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 终端 WebSocket 路由（原版 A17 `term-*` 数据源 · `GET /ws/terminal` 升级后落点）。
 *
 * ## 线协议（与隐藏的原版 `term-*` 语义对齐，但走独立 socket 而非 chan5 envelope）
 * 控制消息为 **文本 JSON**；终端数据为 **二进制帧** `[4 字节大端 sessionId][原始字节]`。
 *
 * 客户端 → 服务端（文本）：
 * - `{"type":"open","cols":C,"rows":R}`（亦接受原版 `{"c":"term-open","reqId":N,"cols":C,"rows":R}`）
 * - `{"type":"input","id":N,"data":"<utf8>"}`
 * - `{"type":"resize","id":N,"cols":C,"rows":R}`
 * - `{"type":"close","id":N}`
 * 客户端 → 服务端（二进制）：`[4B id][原始输入字节]`（原样写入该会话 stdin）。
 *
 * 服务端 → 客户端：
 * - 文本 `{"type":"opened","id":N,"pid":P}` / `{"type":"exit","id":N,"code":C}`
 *   / `{"type":"resized","id":N}` / `{"type":"error","id":N,"error":"..."}`
 * - 二进制 `[4B id][终端输出字节]`
 *
 * ## 实现边界（如实声明）
 * 宿主机以 `su -c 'exec sh -i'` 启动**管道**式交互 shell：命令执行、提示符、stderr 合并、
 * 流式输出均真实工作；但**非真正 PTY**，因此 `resize` 只记录尺寸并回 ack，无法改变内核
 * 窗口大小，全屏 curses 程序（vi/top 交互态）不适用。此限制用真实 `stty -a` 即可复核。
 */
object TerminalWsRoute {

    private const val TAG = "BlindCast-TermWs"

    /** 单连接会话上限。 */
    private const val MAX_SESSIONS = 8

    /** 单次写入进程 stdin 的字节上限（防超大帧打爆）。 */
    private const val MAX_INPUT_BYTES = 256 * 1024

    private val sessionSeq = AtomicInteger(0)

    private class Session(
        val id: Int,
        val process: Process,
        val stdin: OutputStream,
        val cols: Int,
        val rows: Int,
    ) {
        @Volatile
        var closed = false
        val writeLock = Any()
    }

    /** 探测可用的 su 路径（Magisk 常见落点）。 */
    private fun suPath(): String? =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")
            .firstOrNull { File(it).exists() }

    fun handle(conn: WsConnection) {
        val sessions = ConcurrentHashMap<Int, Session>()
        val procs = ConcurrentHashMap<Int, Thread>()

        fun sendJson(obj: JSONObject) = runCatching { conn.sendText(obj.toString()) }

        // 错误回执：原样回带 open 时收到的 reqId（原版面板按 reqId 关联 pending；缺失则不带）。
        // reqId 是不透明值：原版面板发的是字符串（形如 "1:1"），也可能有数值 —— 一律原样回带。
        fun errJson(msg: String, reqId: Any?): JSONObject {
            val o = JSONObject().put("type", "error").put("error", msg)
            if (reqId != null) o.put("reqId", reqId)
            return o
        }

        fun startSession(cols: Int, rows: Int): Session {
            val su = suPath()
            val pb = if (su != null) {
                ProcessBuilder(su, "-c", "exec sh -i")
            } else {
                ProcessBuilder("sh", "-i")
            }
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["COLUMNS"] = cols.toString()
            env["LINES"] = rows.toString()
            env["PS1"] = "\\w # "
            val proc = pb.start()
            return Session(sessionSeq.incrementAndGet(), proc, proc.outputStream, cols, rows)
        }

        /** 泵某会话的 stdout → 二进制帧。 */
        fun pumpOutput(s: Session) {
            val ins: InputStream = s.process.inputStream
            val buf = ByteArray(8192)
            try {
                while (!s.closed) {
                    val n = try {
                        ins.read(buf)
                    } catch (e: InterruptedException) {
                        break
                    }
                    if (n < 0) break
                    if (n > 0) {
                        val frame = ByteArray(4 + n)
                        frame[0] = (s.id shr 24).toByte()
                        frame[1] = (s.id shr 16).toByte()
                        frame[2] = (s.id shr 8).toByte()
                        frame[3] = s.id.toByte()
                        System.arraycopy(buf, 0, frame, 4, n)
                        conn.sendBinary(frame)
                    }
                }
            } catch (_: Throwable) {
                // 连接关闭 / 进程结束，静默收敛。
            } finally {
                val code = runCatching { s.process.waitFor() }.getOrDefault(-1)
                s.closed = true
                sessions.remove(s.id)
                procs.remove(s.id)
                sendJson(JSONObject().put("type", "exit").put("id", s.id).put("code", code))
            }
        }

        fun closeSession(s: Session) {
            s.closed = true
            runCatching { s.process.destroy() }
            runCatching { s.stdin.close() }
            sessions.remove(s.id)
        }

        try {
            while (true) {
                val frame = try {
                    conn.receive()
                } catch (_: SocketTimeoutException) {
                    continue
                } ?: break
                if (frame.isBinary) {
                    if (frame.payload.size < 4) continue
                    val id = ((frame.payload[0].toInt() and 0xFF) shl 24) or
                        ((frame.payload[1].toInt() and 0xFF) shl 16) or
                        ((frame.payload[2].toInt() and 0xFF) shl 8) or
                        (frame.payload[3].toInt() and 0xFF)
                    val s = sessions[id] ?: continue
                    if (s.closed) continue
                    runCatching {
                        synchronized(s.writeLock) {
                            s.stdin.write(frame.payload, 4, frame.payload.size - 4)
                            s.stdin.flush()
                        }
                    }
                    continue
                }
                if (!frame.isText) continue
                val obj = runCatching { JSONObject(frame.text()) }.getOrNull() ?: continue
                // 兼容原版命令名（c）与本端点 type 两套写法。
                val kind = obj.optString("type").ifBlank { obj.optString("c") }
                when (kind) {
                    "open", "term-open" -> {
                        // reqId 不透明：字符串（原版 "1:1"）或数值都原样回带，绝不 optInt 丢字符串。
                        val reqId: Any? = obj.opt("reqId")
                        if (sessions.size >= MAX_SESSIONS) {
                            sendJson(errJson("limit", reqId))
                            continue
                        }
                        val cols = obj.optInt("cols", 80).coerceIn(1, 1000)
                        val rows = obj.optInt("rows", 24).coerceIn(1, 1000)
                        val s = runCatching { startSession(cols, rows) }.getOrNull()
                        if (s == null) {
                            sendJson(errJson("spawn-failed", reqId))
                            continue
                        }
                        sessions[s.id] = s
                        val t = Thread({ pumpOutput(s) }, "BlindCast-Term-${s.id}")
                        t.isDaemon = true
                        procs[s.id] = t
                        t.start()
                        val opened = JSONObject().put("type", "opened").put("c", "term-opened").put("id", s.id)
                        if (reqId != null) opened.put("reqId", reqId)
                        sendJson(opened)
                        Log.i(TAG, "term session ${s.id} opened ${cols}x${rows}")
                    }
                    "input" -> {
                        val id = obj.optInt("id", -1)
                        val s = sessions[id] ?: continue
                        val data = obj.optString("data")
                        if (data.isEmpty() || s.closed) continue
                        runCatching {
                            synchronized(s.writeLock) {
                                s.stdin.write(data.toByteArray(Charsets.UTF_8))
                                s.stdin.flush()
                            }
                        }
                    }
                    "resize", "term-resize" -> {
                        val id = obj.optInt("id", -1)
                        val s = sessions[id] ?: continue
                        // 非 PTY：记录并 ack。尝试 stty（无 tty 会失败，忽略）。
                        runCatching {
                            synchronized(s.writeLock) {
                                s.stdin.write("stty cols ${obj.optInt("cols", 80)} rows ${obj.optInt("rows", 24)} 2>/dev/null\n".toByteArray())
                                s.stdin.flush()
                            }
                        }
                        sendJson(JSONObject().put("type", "resized").put("id", id))
                    }
                    "close", "term-close" -> {
                        val id = obj.optInt("id", -1)
                        val s = sessions[id] ?: continue
                        closeSession(s)
                    }
                    else -> sendJson(errJson("unknown:$kind", obj.opt("reqId")))
                }
                if (frame.payload.size > MAX_INPUT_BYTES) continue
            }
        } catch (e: Throwable) {
            Log.d(TAG, "terminal ws ended: ${e.message}")
        } finally {
            sessions.values.toList().forEach { closeSession(it) }
            runCatching { conn.close() }
        }
    }
}
