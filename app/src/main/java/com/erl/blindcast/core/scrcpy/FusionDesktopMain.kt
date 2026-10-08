package com.erl.blindcast.core.scrcpy

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.priv.VirtualDeviceAssociation
import com.erl.blindcast.core.priv.VirtualDesktopSession
import java.io.File

/**
 * Phase C 虚拟桌面常驻宿主（Vdm-Host-1）。
 *
 * 由 App 侧经 libsu root shell 下发
 * `su 2000 -c 'CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.scrcpy.FusionDesktopMain desktop <w> <h> <bitrate> <fps>
 * <assocId> <stopFile> [socketName]' &`
 * 拉起，跑在 **shell（uid 2000）** 身份的独立 `app_process` 里（VDM 要求调用方与关联包
 * 一致；root uid0 会被拒，实证见 `outputs/probe/phase-c-probe.md` §3.1）。
 *
 * ## 为什么必须是"一个进程干两件事"
 * `com.erl.blindcast.core.priv.VirtualDesktopSession` 是**进程内单例**，
 * `VirtualDevice` 与其 `VirtualDisplay` 只在建它的进程有效，编码器的 inputSurface 也是
 * 进程内对象。因此「建设备 + 建屏 + 跑 MediaCodec」**必须同进程**：
 * 本宿主即在同一进程内先 [PrivilegedCapture.startDesktop]（内部建设备+建屏+挂 surface+编码），
 * 再显式把 `FusionHomeActivity` 拉到该副屏，**不经任何 IPC 传 Surface**。
 *
 * ## 为什么显式拉 Home（不用 VDM `setHomeComponent`）
 * 真机实证：设了 `setHomeComponent` + `setHomeSupported(true)` 后，系统确实发起 home 启动
 * （`ActivityStartInterceptor: Starting home with component specified`），但 ROM 自带的
 * `com.google.android.apps.nexuslauncher/...SecondaryDisplayLauncher` 会抢走该副屏 Home。
 * 故本宿主不设 VDM home，改用 `am start -W --display <id>` 显式拉起我们自己的 Home。
 *
 * ## 调用契约
 * - `args = ["desktop", w, h, bitrate, fps, assocId, stopFile, socketName?]`；
 * - 阻塞轮询 `<stopFile>` 出现即停（500ms 步进）；
 * - 退出码 0 = 曾成功启动后正常停；1 = 启动失败/异常；
 * - 普通 App 进程不要直接调（只在 shell/root `app_process` 内有意义）。
 *
 * R8 注意：release 启用 minify，需 keep 本类及 main 方法（见 proguard-rules.pro）。
 */
@Keep
object FusionDesktopMain {

    private const val TAG = "BlindCast"

    /** 启动后用于给 App 侧回读的运行状态文件（JSON 一行，同目录同 nonce）。 */
    private const val STATUS_SUFFIX = ".status"

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        var code = 1
        var stopFile: File? = null
        var statusFile: File? = null
        try {
            runCatching {
                Log.i(TAG, "[FusionDesktopMain] pid=$pid uid=$uid enter args=${args.toList().take(8)}")
            }
            if (args.getOrNull(0) != "desktop") {
                runCatching { Log.e(TAG, "[FusionDesktopMain] unknown op ${args.getOrNull(0)} (only desktop)") }
                return
            }
            val w = args.getOrNull(1)?.toIntOrNull() ?: 720
            val h = args.getOrNull(2)?.toIntOrNull() ?: 1280
            val bitrate = args.getOrNull(3)?.toIntOrNull() ?: 4_000_000
            val fps = args.getOrNull(4)?.toIntOrNull() ?: 30
            val assocId = args.getOrNull(5)?.toIntOrNull() ?: -1
            val stopPath = args.getOrNull(6)
            if (stopPath.isNullOrBlank()) {
                runCatching { Log.e(TAG, "[FusionDesktopMain] missing stopFile args[6]") }
                return
            }
            val socketName = args.getOrNull(7)?.takeIf { it.isNotBlank() } ?: PrivilegedCapture.SOCKET_NAME
            stopFile = File(stopPath)
            statusFile = File(stopPath + STATUS_SUFFIX)
            runCatching { if (stopFile.exists()) stopFile.delete() }
            runCatching { statusFile.writeText("state=starting\n") }

            // 关联 id 兜底：args 没给就自己查（只认自己的 MAC）。
            val assoc = if (assocId > 0) assocId else VirtualDesktopSession.findOwnAssociationId(0)
            if (assoc <= 0) {
                runCatching { Log.e(TAG, "[FusionDesktopMain] no own association (mac=${VirtualDeviceAssociation.OWN_MAC})") }
                runCatching { statusFile.writeText("state=failed\nerror=no own association\n") }
                return
            }

            // 采集源 = VDM 虚拟桌面（同进程建设备+建屏+编码+挂 surface）。
            val pkg = com.erl.blindcast.BuildConfig.APPLICATION_ID
            val home = android.content.ComponentName(pkg, "$pkg.FusionHomeActivity")
            val ok = PrivilegedCapture.startDesktop(
                width = w, height = h, bitrate = bitrate, fps = fps,
                associationId = assoc,
                displayName = "BlindCastDesktop",
                densityDpi = 320,
                flags = com.erl.blindcast.core.priv.VirtualDeviceBridge.defaultDesktopFlags(),
                home = null, // 不设 VDM home（会被 ROM 自带 SecondaryDisplayLauncher 抢）
                socketName = socketName,
            )
            if (!ok) {
                runCatching {
                    Log.e(TAG, "[FusionDesktopMain] startDesktop failed err=${PrivilegedCapture.errorMessage()}")
                }
                runCatching {
                    statusFile.writeText("state=failed\nerror=${PrivilegedCapture.errorMessage()}\n")
                }
                return
            }
            val did = VirtualDesktopSession.displayId
            runCatching {
                statusFile.writeText(
                    "state=running\ndisplayId=$did\ndeviceId=${VirtualDesktopSession.deviceId}\n" +
                        "width=$w\nheight=$h\ndensityDpi=320\nsource=virtualDisplay\n",
                )
            }
            Log.i(TAG, "[FusionDesktopMain] capturing desktop ${w}x${h} ${bitrate}bps ${fps}fps " +
                "displayId=$did sock=$socketName stop=$stopPath")

            // 显式把我们的 Home 拉上副屏（先等屏稳定）。
            runCatching { Thread.sleep(1200L) }
            val launch = runCatching {
                ProcessBuilder("sh", "-c", "am start -W --display $did -n $pkg/.FusionHomeActivity")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
            runCatching { Log.i(TAG, "[FusionDesktopMain] launch home -> ${launch.replace("\n", " | ")}") }

            code = 0
            var aliveLogged = false
            while (true) {
                runCatching { Thread.sleep(500L) }.onFailure { break }
                if (!aliveLogged) {
                    aliveLogged = true
                    runCatching {
                        Log.i(TAG, "[FusionDesktopMain] alive video=${PrivilegedCapture.videoRunning} " +
                            "audio=${PrivilegedCapture.audioRunning} route=${PrivilegedCapture.displayRouteSnapshot()}")
                    }
                }
                if (stopFile.exists()) {
                    runCatching { Log.i(TAG, "[FusionDesktopMain] stop file hit, exiting") }
                    break
                }
                if (!PrivilegedCapture.isRunning) {
                    runCatching { Log.e(TAG, "[FusionDesktopMain] capture died err=${PrivilegedCapture.errorMessage()}, exiting") }
                    break
                }
            }
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "[FusionDesktopMain] top-level threw", t) }
            runCatching { statusFile?.writeText("state=failed\nerror=${t.message}\n") }
            code = 1
        } finally {
            runCatching { PrivilegedCapture.stop() }
            runCatching { statusFile?.writeText("state=stopped\n") }
            runCatching { Log.i(TAG, "[FusionDesktopMain] pid=$pid uid=$uid exit code=$code") }
            try {
                Runtime.getRuntime().halt(code)
            } catch (_: Throwable) {
                try {
                    System.exit(code)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
