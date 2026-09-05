package com.erl.blindcast.core.server.routes

import android.util.Log
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.JpegTranscoder
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 推流 WebSocket 路由（Slice 4.1 · `GET /ws/stream` 升级后落点；
 * Universal-1 起加 `0x03` JPEG 降级通道，与 H264 共存）。
 *
 * ## 线路协议（二进制帧 = 1 字节通道头 + 负载，4.2 `player.js` 按此分流）
 * - `0x01` 视频：H.264 Annex-B NALU（首包必为附带 SPS/PPS 的 IDR，
 *   由 [ScreenCaptureEngine] 门闩保证，Web 端 `VideoDecoder` 首包即初始化）；
 * - `0x02` 音频：AAC 裸帧（`audio/mp4a-latm`，无 ADTS；
 *   [AudioCaptureEngine] 在 config 就绪前丢帧，对外包均可独立解码）；
 * - `0x03` 图片：JPEG 单帧（Universal-1 · 裸浏览器无 WebCodecs 降级用；
 *   线格式 `[1字节0x03+4字节大端长+JPEG]` 沿用既有帧格式，前端剥 4 字节长后
 *   `createImageBitmap→drawImage`，Canvas 照常；H264 老路不动）；
 * - 建连先下一条文本 `{"type":"hello",...}` 自描述（mime + 通道头取值），
 *   之后只下发二进制；上行内容忽略（Ping 由 [WsConnection] 自动回 Pong）。
 *
 * ## JPEG 按需（省电）
 * - [JpegTranscoder.onJpeg] 在本对象 init 即注册到 [broadcastJpeg]；
 *   解码链仅当有 JPEG 订阅者（control `videoMode=jpeg`）时跑，无订阅停链，
 *   H264 泵不受影响（切回 h264 路不断）。
 *
 * ## 线程模型
 * - 每会话的 [handle] 独占一个连接池线程做读循环（只为消费 Ping/Close，心跳保活）；
 * - 音视频各一条 daemon 广播泵线程（`tryReceive` 轮询 + 2ms 退避，无会话时 50ms 空转，
 *   停服即停，不 close 引擎的复用 Channel）；
 * - 会话集合 `CopyOnWriteArraySet`，发送失败即摘除 + 关闭，永不影响其他客户端；
 * - 仅做推流封装：不启动采集（采集启停归前台 Service，Slice 6.1）。
 */
object StreamWsRoute {

    private const val TAG = "BlindCast-StreamWs"

    /** 视频通道头（H.264 Annex-B NALU）。 */
    const val KIND_VIDEO: Byte = 0x01

    /** 音频通道头（AAC 裸帧）。 */
    const val KIND_AUDIO: Byte = 0x02

    /**
     * JPEG 通道头（Universal-1 · 无 WebCodecs 降级，`[0x03+4字节大端长+JPEG]`）。
     * 前端 `typeof VideoDecoder==="undefined"` 时订阅本通道，
     * 有硬解时走老 `0x01` 路不动。
     */
    const val KIND_JPEG: Byte = 0x03

    /** 无包时轮询退避 2ms（单包额外延迟可忽略）。 */
    private const val POLL_IDLE_MS = 2L

    /** 零会话时空转步长 50ms（采集侧 Channel 自带 64/128 缓冲丢最旧，不堆积）。 */
    private const val NO_SESSION_IDLE_MS = 50L

    private val sessions = CopyOnWriteArraySet<WsConnection>()

    @Volatile
    private var pumpRunning = false
    private var videoPump: Thread? = null
    private var audioPump: Thread? = null
    private val pumpLock = Any()

    /** 当前推流在线数（供 `/api/status` 与 6.1 主页绑定）。 */
    val sessionCount: Int get() = sessions.size

    /**
     * 接管一条已升级连接（阻塞直到对端关闭 / 出错 / 服务停止）。
     * 调用方为 [BlindCastServer] 连接池线程；返回后连接已关闭。
     */
    fun handle(conn: WsConnection) {
        sessions.add(conn)
        try {
            ensurePump()
            try {
                conn.sendText(HELLO_JSON)
            } catch (_: Exception) {
                return
            }
            while (conn.isOpen && pumpRunning) {
                try {
                    if (conn.receive() == null) break
                    // 推流通道忽略上行业务数据（读循环只为处理 Ping/Close）。
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            sessions.remove(conn)
            runCatching { conn.close() }
        }
    }

    /** 停服时调用：停泵 + 踢掉全部会话（幂等）。 */
    fun shutdown() {
        pumpRunning = false
        synchronized(pumpLock) {
            videoPump?.interrupt()
            audioPump?.interrupt()
            videoPump = null
            audioPump = null
        }
        for (s in sessions) runCatching { s.close(1001, "server stopping") }
        sessions.clear()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun ensurePump() {
        if (pumpRunning) return
        synchronized(pumpLock) {
            if (pumpRunning) return
            pumpRunning = true
            videoPump = daemon("BlindCast-WsVideo") { videoLoop() }.also { it.start() }
            audioPump = daemon("BlindCast-WsAudio") { audioLoop() }.also { it.start() }
        }
    }

    private fun videoLoop() {
        while (pumpRunning && !Thread.currentThread().isInterrupted) {
            if (sessions.isEmpty()) {
                sleep(NO_SESSION_IDLE_MS)
                continue
            }
            val pkt = ScreenCaptureEngine.frameChannel.tryReceive().getOrNull()
            if (pkt == null) {
                sleep(POLL_IDLE_MS)
                continue
            }
            broadcast(KIND_VIDEO, pkt.payload)
        }
    }

    private fun audioLoop() {
        while (pumpRunning && !Thread.currentThread().isInterrupted) {
            if (sessions.isEmpty()) {
                sleep(NO_SESSION_IDLE_MS)
                continue
            }
            val pkt = AudioCaptureEngine.audioChannel.tryReceive().getOrNull()
            if (pkt == null) {
                sleep(POLL_IDLE_MS)
                continue
            }
            broadcast(KIND_AUDIO, pkt.payload)
        }
    }

    private fun broadcast(kind: Byte, payload: ByteArray) {
        if (payload.isEmpty()) return
        for (s in sessions) {
            try {
                s.sendBinaryWithPrefix(kind, payload)
            } catch (t: Exception) {
                sessions.remove(s)
                runCatching { s.close() }
                Log.d(TAG, "drop dead session ${s.remoteAddress}: ${t.message}")
            }
        }
    }

    /**
     * JPEG 广播（Universal-1 · `[0x03+4字节大端长+JPEG]` 沿用既有帧格式）。
     * H264 老泵不动：本广播只在有 JPEG 订阅者且 [JpegTranscoder] 产出时触发，
     * 向**全量** stream 会话下发（H264 端忽略 0x03，JPEG 端忽略 0x01/0x02，
     * 按会话过滤需关联 control/stream 跨连接，复杂度换不来收益，LAN 带宽可承受）。
     */
    fun broadcastJpeg(jpeg: ByteArray) {
        if (jpeg.isEmpty() || sessions.isEmpty()) return
        // 4 字节大端长前缀（与 CaptureSocketLink 特权帧格式同构，前端剥长后解码）。
        val n = jpeg.size
        val withLen = ByteArray(4 + n)
        withLen[0] = ((n ushr 24) and 0xFF).toByte()
        withLen[1] = ((n ushr 16) and 0xFF).toByte()
        withLen[2] = ((n ushr 8) and 0xFF).toByte()
        withLen[3] = (n and 0xFF).toByte()
        jpeg.copyInto(withLen, 4)
        broadcast(KIND_JPEG, withLen)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun daemon(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { isDaemon = true }

    private const val HELLO_JSON =
        """{"type":"hello","video":{"mime":"video/avc","kind":1,"format":"annexb"},""" +
            """"audio":{"mime":"audio/mp4a-latm","kind":2,"format":"raw"},""" +
            """"jpeg":{"mime":"image/jpeg","kind":3,"format":"jpeg"}}"""

    init {
        // JPEG 产出接线（JpegTranscoder→本路由广播；失败吞错不影响 H264 老路）。
        try {
            JpegTranscoder.onJpeg = { jpeg -> runCatching { broadcastJpeg(jpeg) } }
        } catch (_: Throwable) {
        }
        // screencap 兜底轮询（SDK36 Root H264 常驻无 Context 不可用时顶上，无需求时空转）。
        try {
            ScreencapJpegPoller.ensureStarted()
        } catch (_: Throwable) {
        }
    }
}
