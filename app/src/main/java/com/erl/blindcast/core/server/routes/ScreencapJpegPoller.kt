package com.erl.blindcast.core.server.routes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.erl.blindcast.core.priv.RootExecutor
import com.erl.blindcast.core.scrcpy.CaptureSocketLink
import com.erl.blindcast.core.scrcpy.JpegTranscoder
import com.topjohnwu.superuser.Shell
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * screencap JPEG 兜底源（Universal-1 · SDK36 上 Root H264 常驻无 Context 不可用时的第二方案）。
 *
 * 背景（.216 实证，probe）：Android 16 (SDK36) 上
 * `SurfaceControl.createDisplay` 已删（反射计数 0），`DisplayManager` 建屏需 Context，
 * 而 bare `app_process` 内 `currentApplication/currentActivityThread/AppGlobals`
 * 全空、`ActivityThread.systemMain()` 抛错，故 Root H264 常驻（PrivilegedCapture）
 * 在无 Shizuku 纯 Root 机上起不来（`DisplayManager unavailable`），泵入帧为空，
 * 纯 MediaCodec 转码链无米下锅。
 *
 * 兜底：App 侧经既有 libsu root shell（无新增依赖）轮询 `screencap -p` PNG →
 * `BitmapFactory` 解码 → 720p 缩放 → JPEG q60 → [StreamWsRoute.broadcastJpeg]，
 * 与 [JpegTranscoder]（MediaCodec 软解路）共存：H264 泵活着时本轮询让路
 * （只跑一路省电省网），泵无帧且有 JPEG 订阅者时本轮询顶上，保证裸浏览器出图。
 * screencap 单次约 0.2-0.4s，实测约 2-3fps（低于 MediaCodec 路 10fps，
 * 属平台限制下的 best-effort，首图 <2s， verification 以出图为准）。
 *
 * ## 按需启停
 * - 仅当 [JpegTranscoder.hasDemand] 为 true 才跑（control `videoMode=jpeg` 声明，
 *   无声明默认 h264）；无订阅者时 500ms 空转省电；
 * - H264 泵有首帧（[CaptureSocketLink.hasVideo]）时让路停采（MediaCodec 路优先，
 *   帧率质量更优），泵死/无帧时自动顶上；切换无缝（同 WS 0x03 通道）。
 *
 * ## 线程模型
 * - 单 daemon 线程（`BlindCast-ScreencapJpeg`），阻塞式 `Shell.cmd`（libsu 同步 API），
 *   与搬运/转码线程独立；所有异常吞错记日志不抛。
 */
object ScreencapJpegPoller {

    private const val TAG = "BlindCast"

    /** 无需求时空转 500ms（省电）。 */
    private const val NO_DEMAND_IDLE_MS = 500L

    /** H264 泵活着时让路空转 1000ms（MediaCodec 路优先，少抢 CPU）。 */
    private const val PUMP_ALIVE_IDLE_MS = 1000L

    /** 单次 screencap 超时保护：libsu exec 本身阻塞，靠线程中断 + 下轮覆盖（不设硬超时）。 */
    private const val CAP_FILE = "/data/local/tmp/bc_jpeg_cap.png"

    /** JPEG 质量（与转码链一致 60）。 */
    private const val QUALITY = 60

    /** 目标宽 720（1080p 源等比缩，省网；已 ≤720 不放大）。 */
    private const val TARGET_W = 720

    @Volatile
    private var started = false
    private val startLock = Any()

    @Volatile
    var lastCapAtMs: Long = 0L
        private set

    @Volatile
    var cappedFrames: Long = 0L
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    /** 启动轮询线程（幂等；StreamWsRoute init 中调一次）。 */
    fun ensureStarted() {
        if (started) return
        synchronized(startLock) {
            if (started) return
            started = true
            val t = Thread(::loop, "BlindCast-ScreencapJpeg")
            t.isDaemon = true
            t.start()
        }
    }

    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                // 开关分离：串流关着时连兜底也不采（用户要的是零录屏零转码）。
                // 只看采集链路实际态：停后 isRunning 与首帧门闩均为 false。
                if (!CaptureSocketLink.isRunning && !CaptureSocketLink.hasVideo) {
                    sleep(NO_DEMAND_IDLE_MS)
                    continue
                }
                if (!JpegTranscoder.hasDemand()) {
                    sleep(NO_DEMAND_IDLE_MS)
                    continue
                }
                // H264 泵活着 → MediaCodec 路优先，本轮询让路（同通道省网省电）。
                if (CaptureSocketLink.hasVideo) {
                    sleep(PUMP_ALIVE_IDLE_MS)
                    continue
                }
                // Root 可用性（缓存判定，不抛；无 Root 则空转等 Shizuku/泵）。
                val rootOk = try {
                    RootExecutor.isRootAvailable()
                } catch (_: Throwable) {
                    false
                }
                if (!rootOk) {
                    sleep(NO_DEMAND_IDLE_MS)
                    continue
                }
                val jpeg = captureOne()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    lastCapAtMs = SystemClock.uptimeMillis()
                    cappedFrames++
                    lastError = null
                    runCatching { StreamWsRoute.broadcastJpeg(jpeg) }
                } else {
                    sleep(200L)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                lastError = t
                runCatching { Log.e(TAG, "[ScreencapJpeg] loop error", t) }
                sleep(500L)
            }
        }
    }

    /**
     * 单次 screencap → JPEG（阻塞约 0.3-0.6s；失败返回 null 不抛）。
     * 步骤：root `screencap -p` 写临时 PNG → App 直读 → Bitmap 解码 →
     * 720p 等比缩 → JPEG q60 → 删临时文件（实验残留随采随清）。
     */
    private fun captureOne(): ByteArray? {
        return try {
            runCatching { File(CAP_FILE).delete() }
            val res = try {
                Shell.cmd("screencap -p $CAP_FILE").exec()
            } catch (t: Throwable) {
                lastError = t
                return null
            }
            val ok = try {
                res.isSuccess
            } catch (_: Throwable) {
                false
            }
            if (!ok) return null
            val f = File(CAP_FILE)
            if (!f.exists() || f.length() <= 0 || f.length() > 20 * 1024 * 1024) {
                runCatching { f.delete() }
                return null
            }
            val bmp0: Bitmap = try {
                BitmapFactory.decodeFile(CAP_FILE) ?: run {
                    runCatching { f.delete() }
                    return null
                }
            } catch (t: Throwable) {
                lastError = t
                runCatching { f.delete() }
                return null
            }
            try {
                val w0 = bmp0.width
                val h0 = bmp0.height
                if (w0 <= 0 || h0 <= 0) return null
                val bmp: Bitmap = if (w0 > TARGET_W) {
                    val h = (h0.toLong() * TARGET_W / w0).toInt().coerceIn(1, 3840)
                    try {
                        Bitmap.createScaledBitmap(bmp0, TARGET_W, h, true)
                    } catch (_: Throwable) {
                        bmp0
                    }.also {
                        if (it !== bmp0) runCatching { bmp0.recycle() }
                    }
                } else {
                    bmp0
                }
                try {
                    val out = ByteArrayOutputStream(bmp.width * bmp.height / 4)
                    val compressed = bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                    if (!compressed) return null
                    out.toByteArray()
                } finally {
                    if (bmp !== bmp0) runCatching { bmp.recycle() } else runCatching { bmp0.recycle() }
                }
            } finally {
                runCatching { f.delete() }
            }
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }
}
