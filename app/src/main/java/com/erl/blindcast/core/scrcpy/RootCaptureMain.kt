package com.erl.blindcast.core.scrcpy

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.File

/**
 * Root 常驻采集宿主（Stream-Priv-1 · Root 备用链路）。
 *
 * 运行身份：由 App 侧经 libsu `Shell.cmd("CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.scrcpy.RootCaptureMain capture <w> <h> <bitrate> <fps>
 * <stopFile> [socketName]")` 以 uid 0 真 root 身份拉起的独立 `app_process` 常驻进程，
 * 阻塞式跑 [PrivilegedCapture] 直到 stop 信号（与 [com.erl.blindcast.core.priv.RootMain]
 * 单次执行器不同：Root 单次执行器只做断电/点亮一次调用即退出，不适合视频长流）。
 *
 * ## 为什么另起常驻而不是复用 RootMain
 * - RootMain 为单次 `displayPower on|off <resultFile>` 即退出的短进程模型；
 * - 视频流需分钟级常驻编码 + socket 长连，单次模型无法承载，故另起本常驻宿主；
 * - App 侧仍复用同一 [CaptureSocketLink] 服（抽象命名同 [PrivilegedCapture.SOCKET_NAME]），
 *   本宿主即 [PrivilegedCapture.start] 的 root 身份调用方（RootCaptureLink 逻辑折叠在
 *   ForegroundService 内：建服 + 拉起本宿主 + 等首帧，不另起文件）。
 *
 * ## 当前路由选择（任务包要求日志写清理由）
 * 本机 Shizuku daemon 以 root 启动，其 UserService 特权身份对
 * `SurfaceControl.createDisplay/setDisplayProjection` 已够用（scrcpy 同构，shell 上下文
 * 即可调 libandroid_runtime JNI，不同于 displayPower 被 OPlus 静默忽略的个案），
 * 为降复杂度 ForegroundService 优先走 Shizuku UserService 常驻（daemon(true)）承载采集，
 * 本 Root 常驻仅为备用（Shizuku 未运行/未授权但 su 可用时启用）。详见 ForegroundService
 * `[CaptureRoute]` 日志（最终选择 + 理由每次启动均打印）。
 *
 * ## 调用契约
 * - `args = ["capture", w, h, bitrate, fps, stopFile, socketName?]`；
 * - `args = ["probe", resultFile?]`（Smooth-1 显示路由探针：只清点反射家底 +
 *   Context 各路实测，不建屏不编码，报告进 logcat `BlindCast/[Probe]`，可选写文件）；
 * - 启动后阻塞轮询 `<stopFile>` 出现即停（500ms 步进，stop 侧 `touch` 即退）；
 * - 全程 runCatching 不抛，退出码 0=曾成功出帧后正常停，1=启动失败/异常；
 * - 普通 App 进程不要直接调（只在 root `app_process` 内有意义）。
 *
 * R8 注意：release 启用 minify，需 keep 本类及 main 方法（见 proguard-rules.pro）。
 */
@Keep
object RootCaptureMain {

    private const val TAG = "BlindCast"

    /**
     * `app_process` 入口（签名必须 `public static void main(String[])`）。
     */
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        var code = 1
        var stopFile: File? = null
        try {
            runCatching { Log.i(TAG, "[RootCaptureMain] pid=$pid uid=$uid enter args=${args.toList().take(7)}") }
            if (args.getOrNull(0) == "probe") {
                // Smooth-1 探针：裸进程显示路由家底（无副作用，报告进 logcat + 可选文件）。
                val report = runCatching { PrivilegedCapture.probeDisplayRoutes() }
                    .getOrElse { t -> "probe threw ${t.message ?: t}\n" }
                runCatching {
                    args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { rp ->
                        val f = File(rp)
                        runCatching { f.parentFile?.mkdirs() }
                        f.writeText(report)
                        runCatching { f.setReadable(true, false) }
                    }
                }
                code = 0
                return
            }
            if (args.getOrNull(0) == "probeCreate") {
                // Smooth-1 建屏编码探针：无 socket 纯验证（编码器+三路建屏+drain 出帧写内存，
                // 证明泵在裸进程能出帧；App 侧 CaptureSocketLink 另行验证）。
                // args = ["probeCreate", w, h, bitrate, fps, seconds, resultFile?]。
                val w = args.getOrNull(1)?.toIntOrNull() ?: 1280
                val h = args.getOrNull(2)?.toIntOrNull() ?: 720
                val bitrate = args.getOrNull(3)?.toIntOrNull() ?: 4_000_000
                val fps = args.getOrNull(4)?.toIntOrNull() ?: 30
                val seconds = args.getOrNull(5)?.toIntOrNull()?.coerceIn(1, 30) ?: 5
                val mem = java.io.ByteArrayOutputStream(256 * 1024)
                val okStart = runCatching {
                    PrivilegedCapture.startVideo(w, h, bitrate, fps, mem)
                }.getOrDefault(false)
                runCatching { Log.i(TAG, "[RootCaptureMain][ProbeCreate] start=$okStart err=${PrivilegedCapture.errorMessage()}") }
                if (okStart) {
                    try {
                        Thread.sleep(seconds * 1000L)
                    } catch (_: InterruptedException) {
                    }
                }
                val route = PrivilegedCapture.displayRouteSnapshot()
                val running = PrivilegedCapture.videoRunning
                val bytes = mem.size()
                runCatching { PrivilegedCapture.stop() }
                val report = "probeCreate start=$okStart route=$route running=$running bytes=$bytes " +
                    "err=${PrivilegedCapture.errorMessage()}\n"
                runCatching { Log.i(TAG, "[RootCaptureMain][ProbeCreate] $report") }
                runCatching {
                    args.getOrNull(6)?.takeIf { it.isNotBlank() }?.let { rp ->
                        val f = File(rp)
                        runCatching { f.parentFile?.mkdirs() }
                        f.writeText(report)
                        runCatching { f.setReadable(true, false) }
                    }
                }
                code = if (okStart && running && bytes > 0) 0 else 1
                return
            }
            if (args.getOrNull(0) != "capture") {
                runCatching { Log.e(TAG, "[RootCaptureMain] unknown op ${args.getOrNull(0)} (only capture|probe)") }
                return
            }
            val w = args.getOrNull(1)?.toIntOrNull() ?: 1280
            val h = args.getOrNull(2)?.toIntOrNull() ?: 720
            val bitrate = args.getOrNull(3)?.toIntOrNull() ?: 4_000_000
            val fps = args.getOrNull(4)?.toIntOrNull() ?: 30
            val stopPath = args.getOrNull(5)
            if (stopPath.isNullOrBlank()) {
                runCatching { Log.e(TAG, "[RootCaptureMain] missing stopFile args[5]") }
                return
            }
            val socketName = args.getOrNull(6)?.takeIf { it.isNotBlank() } ?: PrivilegedCapture.SOCKET_NAME
            stopFile = File(stopPath)
            // 陈旧 stop 文件先清（防上次崩溃残留导致秒退）。
            runCatching { if (stopFile.exists()) stopFile.delete() }
            val ok = PrivilegedCapture.start(w, h, bitrate, fps, socketName)
            if (!ok) {
                runCatching { Log.e(TAG, "[RootCaptureMain] PrivilegedCapture.start failed err=${PrivilegedCapture.errorMessage()}") }
                return
            }
            runCatching { Log.i(TAG, "[RootCaptureMain] capturing ${w}x${h} ${bitrate}bps ${fps}fps sock=$socketName stop=$stopPath") }
            // 阻塞直到 stop 信号（App 侧 touch stopFile）或进程被杀。
            var framesLogged = false
            while (true) {
                runCatching { Thread.sleep(500L) }.onFailure { break }
                if (!framesLogged) {
                    framesLogged = true
                    runCatching { Log.i(TAG, "[RootCaptureMain] alive video=${PrivilegedCapture.videoRunning} audio=${PrivilegedCapture.audioRunning}") }
                }
                if (stopFile.exists()) {
                    runCatching { Log.i(TAG, "[RootCaptureMain] stop file hit, exiting") }
                    break
                }
                if (!PrivilegedCapture.isRunning) {
                    runCatching { Log.e(TAG, "[RootCaptureMain] capture died err=${PrivilegedCapture.errorMessage()}, exiting") }
                    break
                }
            }
            code = 0
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "[RootCaptureMain] top-level threw", t) }
            code = 1
        } finally {
            runCatching { PrivilegedCapture.stop() }
            runCatching { Log.i(TAG, "[RootCaptureMain] pid=$pid uid=$uid exit code=$code") }
            try {
                Runtime.getRuntime().halt(code)
            } catch (_: Throwable) {
                try { System.exit(code) } catch (_: Throwable) { }
            }
        }
    }
}
