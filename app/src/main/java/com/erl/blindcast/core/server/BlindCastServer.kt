package com.erl.blindcast.core.server

import android.content.Context
import android.util.Log
import com.erl.blindcast.core.server.auth.TokenAuthenticator
import com.erl.blindcast.core.server.routes.AuthRoute
import com.erl.blindcast.core.server.routes.ControlWsRoute
import com.erl.blindcast.core.server.routes.DeviceApiRoute
import com.erl.blindcast.core.server.routes.StreamWsRoute
import com.erl.blindcast.core.server.routes.WebStaticRoutes
import com.erl.blindcast.core.server.routes.WsConnection
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 嵌入式 HTTP/WebSocket 服务引擎（Slice 4.1 · MVP.md 二(5) + 第四章 core/server）。
 *
 * ## 传输选型说明（必读，偏离任务包字面的工程决策）
 * 任务包建议 `com.sun.net.httpserver`，但该类不在 Android `android.jar` / ART 内——
 * 引用即编译失败；且其 `HttpExchange` 无法劫持底層 TCP 做 RFC6455 `101` 升级，
 * HTTP 与 WS 无法同端口（`0.0.0.0:8888` 铁线要求同端口）。
 * 故本 Slice 整站统一跑在 **JDK 原生 `java.net.ServerSocket`** 上：
 * 手写最小 HTTP/1.1（请求行 + 头 + 固定长 body）与 RFC6455 升级/帧编解码
 * （见 [WsConnection]），**零新增依赖**（无 Ktor/Netty，质量红线守住）。
 *
 * ## 路由表
 * - `GET /`、`/index.html` → [WebStaticRoutes]（公开；4.2 落子前占位页）；
 * - `GET /ws/stream` → [StreamWsRoute]（鉴权后升级，NALU/AAC 二进制下发）；
 * - `GET /ws/control` → [ControlWsRoute]（鉴权后升级，JSON 指令上行）；
 * - `GET /api/auth/status`、`GET|POST /api/auth/verify` → [AuthRoute]（公开）；
 * - `GET|POST /api/screen`、`GET /api/status` → [DeviceApiRoute]（需鉴权）；
 * - 其余 → 404；鉴权失败 → 401 JSON（WS 升级前同样先验，失败直接 401 不升级）。
 *
 * ## 线程模型
 * - [start] 幂等：运行中同端口重复调用直接 true；异端口先静默 stop 再起；
 * - accept 独占一条 daemon 线程；每连接占池中一线程（daemon cached 池）；
 * - [stop] 幂等：关监听（解阻塞 accept）+ `shutdownNow` 连接池 + 踢 WS 会话；
 * - [lastError] 记录最近一次失败，成功 [start] 后清零（供 6.3 诊断消费）；
 * - 仅做服务能力封装：不接 UI（前台 Service 接入留 Slice 6.1），不启动采集。
 */
object BlindCastServer {

    private const val TAG = "BlindCast-Server"

    /** 默认监听端口（MVP.md 二(5) 铁线 `0.0.0.0:8888`）。 */
    const val DEFAULT_PORT = 8888

    /** 监听地址（局域网暴露，铁线）。 */
    const val BIND_HOST = "0.0.0.0"

    /** HTTP 头上限 32KB（防慢速 Lori / 头炸弹）。 */
    private const val MAX_HEAD_BYTES = 32 * 1024

    /** POST body 上限 256KB（鉴权/开关屏 JSON 远小于此）。 */
    private const val MAX_BODY_BYTES = 256 * 1024

    /** 普通 HTTP 连接读超时 10s；WS 接管后由各路由按需放宽。 */
    private const val HTTP_SOCKET_TIMEOUT_MS = 10_000

    /** WS 接管后读超时 30s（Ping/Pong 保活，超时只重试不判死）。 */
    private const val WS_SOCKET_TIMEOUT_MS = 30_000

    /** stop 时 accept 线程 join 上限 1s。 */
    private const val STOP_JOIN_MS = 1_000L

    /** 是否正在监听（volatile，跨线程可见）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 最近一次失败的异常；最近一次成功 [start] 后清零，失败永不崩溃。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 期望端口（stop 状态下可改，下次 [start] 生效）。 */
    @Volatile
    var port: Int = DEFAULT_PORT

    /** 实际绑定端口（运行中为有效值，停止后 -1）。 */
    @Volatile
    var actualPort: Int = -1
        private set

    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val threadSeq = AtomicInteger(0)
    private var appContextRef: WeakReference<Context>? = null

    /** 预存应用上下文（只持 [WeakReference]；供静态页 assets 与电量读取）。 */
    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext ?: context)
        DeviceApiRoute.init(context)
    }

    /** 设置访问 Token（直通 [TokenAuthenticator]，空白即免密）。 */
    fun setToken(value: String) = TokenAuthenticator.setToken(value)

    /** 当前 Token 是否要求鉴权。 */
    fun isAuthRequired(): Boolean = TokenAuthenticator.isAuthRequired()

    /** 供路由读取应用上下文（可能 null，调用方各自退化）。 */
    internal fun appContextOrNull(): Context? = appContextRef?.get()

    /**
     * 启动监听（同步返回，幂等）。
     * @param listenPort 监听端口（默认取 [port]；1..65535 以外直接 false）。
     * @return 已在运行（同端口）/ 本次绑定成功 true；参数非法 / 端口被占 false。
     */
    fun start(listenPort: Int = port): Boolean {
        synchronized(lock) {
            if (isRunning) {
                if (listenPort == actualPort) return true
                stopLocked()
            }
            if (listenPort !in 1..65535) {
                lastError = IllegalArgumentException("BlindCastServer: invalid port $listenPort")
                return false
            }
            var ss: ServerSocket? = null
            return try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(BIND_HOST, listenPort))
                }
                serverSocket = ss
                ss = null // 所有权移交字段，此后异常走 stopLocked 回收。
                port = listenPort
                actualPort = listenPort
                pool = Executors.newCachedThreadPool { r ->
                    Thread(r, "BlindCast-Conn-${threadSeq.incrementAndGet()}").apply {
                        isDaemon = true
                    }
                }
                val t = Thread(::acceptLoop, "BlindCast-Accept")
                t.isDaemon = true
                acceptThread = t
                t.start()
                isRunning = true
                lastError = null
                Log.i(TAG, "listening on $BIND_HOST:$listenPort")
                true
            } catch (t: Throwable) {
                lastError = t
                runCatching { ss?.close() }
                serverSocket = null
                pool?.shutdownNow()
                pool = null
                actualPort = -1
                false
            }
        }
    }

    /** 停止监听（幂等）：关监听 + 停连接池 + 踢 WS 会话。 */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    // ------------------------------------------------------------------
    // 连接 accept + 分发
    // ------------------------------------------------------------------

    private fun stopLocked() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool = null
        try {
            acceptThread?.join(STOP_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            acceptThread = null
        }
        StreamWsRoute.shutdown()
        ControlWsRoute.shutdown()
        actualPort = -1
    }

    private fun acceptLoop() {
        while (isRunning) {
            val ss = synchronized(lock) { serverSocket } ?: break
            try {
                val socket = ss.accept()
                val p = synchronized(lock) { pool }
                if (p == null || p.isShutdown) {
                    runCatching { socket.close() }
                    continue
                }
                p.execute { handleConnection(socket) }
            } catch (e: SocketException) {
                if (isRunning) lastError = e
                break // 监听被关（stop）或致命错误：退出循环。
            } catch (t: Throwable) {
                if (isRunning) lastError = t
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var upgraded = false
        try {
            socket.soTimeout = HTTP_SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val head = readHttpHead(input) ?: run {
                sendText(output, 400, "bad request")
                return
            }
            val request = parseRequest(head) ?: run {
                sendText(output, 400, "bad request")
                return
            }
            // body 超限直接拒收（慢速 Lori 熔断）。
            if (request.contentLength > MAX_BODY_BYTES) {
                sendText(output, 413, "body too large")
                return
            }
            val body = readBody(input, request.contentLength, output) ?: return
            val req = request.copy(body = body)
            upgraded = dispatch(req, input, output, socket)
        } catch (t: Throwable) {
            runCatching { lastError = t }
        } finally {
            if (!upgraded) runCatching { socket.close() }
            // 升级成功时 socket 所有权移交 WS 路由，由其 handle 收尾关闭。
        }
    }

    /**
     * 路由分发。
     * @return true = 已升级为 WS（socket 移交，不可再关）；false = 普通 HTTP（外层关闭）。
     */
    private fun dispatch(req: HttpRequest, input: InputStream, output: OutputStream, socket: Socket): Boolean {
        val path = req.path
        return when {
            path == "/" || path == "/index.html" ->
                serveStatic(req, output)
            path == "/ws/stream" || path == "/ws/control" ->
                serveWebSocket(req, input, output, socket)
            path == "/api/auth/status" ->
                serveJson(output, req.method, AuthRoute.handleStatus())
            path == "/api/auth/verify" ->
                serveJson(output, req.method, AuthRoute.handleVerify(req.method, req.rawQuery, req.headers, req.body))
            path == "/api/screen" || path == "/api/status" -> {
                if (!TokenAuthenticator.isAuthorized(req.rawQuery, req.headers)) {
                    serveJson(output, req.method, 401 to """{"ok":false,"error":"unauthorized"}""")
                } else if (path == "/api/screen") {
                    serveJson(output, req.method, DeviceApiRoute.handleScreen(req.method, req.rawQuery, req.body))
                } else {
                    if (req.method != "GET") {
                        serveJson(output, req.method, 405 to """{"ok":false,"error":"method not allowed"}""")
                    } else {
                        serveJson(output, req.method, DeviceApiRoute.handleStatus())
                    }
                }
            }
            else -> serveJson(output, req.method, 404 to """{"ok":false,"error":"not found"}""")
        }
    }

    private fun serveStatic(req: HttpRequest, output: OutputStream): Boolean {
        val result = WebStaticRoutes.handle(req.method, req.path, appContextOrNull())
        sendResponse(output, result.status, reasonOf(result.status), mapOf("Content-Type" to result.contentType), result.body, headOnly = req.method == "HEAD")
        return false
    }

    private fun serveJson(output: OutputStream, method: String, result: Pair<Int, String>): Boolean {
        val body = result.second.toByteArray(Charsets.UTF_8)
        sendResponse(output, result.first, reasonOf(result.first), mapOf("Content-Type" to "application/json; charset=utf-8"), body, headOnly = method == "HEAD")
        return false
    }

    private fun serveWebSocket(req: HttpRequest, input: InputStream, output: OutputStream, socket: Socket): Boolean {
        // 先鉴权（401 直接回，不升级，避免未授权方探测 WS 能力）。
        if (!TokenAuthenticator.isAuthorized(req.rawQuery, req.headers)) {
            val body = """{"ok":false,"error":"unauthorized"}""".toByteArray(Charsets.UTF_8)
            sendResponse(output, 401, "Unauthorized", mapOf("Content-Type" to "application/json; charset=utf-8"), body, headOnly = false)
            return false
        }
        val key = req.headers["sec-websocket-key"]
        val upgrade = req.headers["upgrade"]
        val connection = req.headers["connection"]
        val version = req.headers["sec-websocket-version"]
        val valid = req.method == "GET" &&
            upgrade != null && upgrade.equals("websocket", ignoreCase = true) &&
            connection != null && connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) } &&
            !key.isNullOrBlank()
        if (!valid) {
            sendText(output, 400, "websocket upgrade required")
            return false
        }
        if (version != null && version.trim() != "13") {
            sendResponse(
                output, 426, "Upgrade Required",
                mapOf("Sec-WebSocket-Version" to "13", "Content-Type" to "text/plain; charset=utf-8"),
                "unsupported websocket version".toByteArray(), headOnly = false,
            )
            return false
        }
        val accept = runCatching { WsConnection.acceptKey(key!!) }.getOrNull() ?: run {
            sendText(output, 400, "bad websocket key")
            return false
        }
        // 101 握手回包（HTTP/1.1 明文，flush 后同一 socket 切帧模式）。
        val sb = StringBuilder()
            .append("HTTP/1.1 101 Switching Protocols\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            .append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
        socket.soTimeout = WS_SOCKET_TIMEOUT_MS
        val conn = WsConnection(socket, input, output)
        if (req.path == "/ws/stream") StreamWsRoute.handle(conn) else ControlWsRoute.handle(conn)
        return true
    }

    // ------------------------------------------------------------------
    // 最小 HTTP/1.1 编解码（无 BufferedReader：逐字节定界，杜绝超读字节丢失）
    // ------------------------------------------------------------------

    private data class HttpRequest(
        val method: String,
        val path: String,
        val rawQuery: String?,
        val version: String,
        val headers: Map<String, String>,
        val contentLength: Int,
        val body: ByteArray = ByteArray(0),
    )

    /**
     * 读到 `\r\n\r\n` 为止的请求头（返回不含终结空行的头字节；超限/异常 null）。
     * 逐字节读，保证不吞掉握手后紧跟的 WS 帧字节（BufferedReader 会超读缓冲）。
     */
    internal fun readHttpHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var match = 0 // 已匹配 "\r\n\r\n" 的前缀长度。
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (out.size() <= MAX_HEAD_BYTES) {
            val b = try {
                input.read()
            } catch (_: Exception) {
                return null
            }
            if (b < 0) return null
            out.write(b)
            match = if (b == terminator[match].toInt()) match + 1 else if (b == 13) 1 else 0
            if (match == terminator.size) {
                val full = out.toByteArray()
                return full.copyOf(full.size - terminator.size)
            }
        }
        return null
    }

    private fun parseRequest(head: ByteArray): HttpRequest? {
        return runCatching {
            val text = head.toString(Charsets.ISO_8859_1)
            val lines = text.split("\r\n")
            if (lines.isEmpty()) return null
            val requestLine = lines[0].split(' ')
            if (requestLine.size != 3) return null
            val method = requestLine[0].uppercase()
            if (method.isEmpty() || method.length > 16) return null
            val rawTarget = requestLine[1]
            if (rawTarget.isEmpty() || rawTarget.length > 4096 || !rawTarget.startsWith("/")) return null
            val version = requestLine[2]
            if (!version.startsWith("HTTP/")) return null
            val q = rawTarget.indexOf('?')
            val path = if (q < 0) rawTarget else rawTarget.substring(0, q)
            val rawQuery = if (q < 0) null else rawTarget.substring(q + 1)
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase()
                if (name.isEmpty() || headers.containsKey(name)) continue
                headers[name] = line.substring(colon + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            HttpRequest(method, path, rawQuery, version, headers, contentLength)
        }.getOrNull()
    }

    /** 按 Content-Length 精确读 body；失败/超限时已回包并返回 null。 */
    private fun readBody(input: InputStream, length: Int, output: OutputStream): ByteArray? {
        if (length == 0) return ByteArray(0)
        return runCatching {
            val buf = ByteArray(length)
            var done = 0
            while (done < length) {
                val r = input.read(buf, done, length - done)
                if (r < 0) throw SocketException("eof in body")
                done += r
            }
            buf
        }.getOrElse {
            runCatching { sendText(output, 400, "bad body") }
            null
        }
    }

    private fun sendText(output: OutputStream, status: Int, text: String) {
        runCatching {
            val body = text.toByteArray(Charsets.UTF_8)
            sendResponse(output, status, reasonOf(status), mapOf("Content-Type" to "text/plain; charset=utf-8"), body, headOnly = false)
        }
    }

    private fun sendResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        body: ByteArray?,
        headOnly: Boolean,
    ) {
        runCatching {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Connection: close\r\n")
            sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            sb.append("\r\n")
            output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (!headOnly && body != null && body.isNotEmpty()) output.write(body)
            output.flush()
        }
    }

    private fun reasonOf(status: Int): String = when (status) {
        101 -> "Switching Protocols"
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Content Too Large"
        426 -> "Upgrade Required"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
