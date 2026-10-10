package com.erl.blindcast.core.scrcpy

import android.os.Process
import android.util.Log
import android.util.Base64
import androidx.annotation.Keep
import com.erl.blindcast.core.priv.VirtualDeviceAssociation
import com.erl.blindcast.core.priv.VirtualDesktopSession
import java.io.File

/**
 * Phase C/D 虚拟显示常驻宿主（Vdm-Host-1 · Win-Host-1）。
 *
 * 由 App 侧经 libsu root shell 下发
 * `su 2000 -c 'CLASSPATH=<apk> app_process /system/bin
 * com.erl.blindcast.core.scrcpy.FusionDesktopMain <op> ... &'`
 * 拉起，跑在 **shell（uid 2000）** 身份的独立 `app_process` 里（VDM 要求调用方与关联包
 * 一致；root uid0 会被拒，实证见 `outputs/probe/phase-c-probe.md` §3.1）。
 *
 * ## 两个 op（一个宿主进程 = 一个虚拟设备 + 一张虚拟屏 + 一路编码 + 一条 socket）
 * - `desktop`：整屏 Fusion 桌面（副屏 Home = `FusionHomeActivity`，桌面应用由客户端切）；
 * - `window`：**逐应用窗口**——同一套建设备/建屏/编码，但屏上只拉一个指定应用，
 *   socket 名与 stop 文件由 App 侧按 windowId 分配（`blindcast_win_<id>`），
 *   于是 N 个窗口 = N 个宿主进程 = N 路独立虚拟显示/编码会话/socket/stream。
 *
 * 真机实证（216 / Android 16）：同一 companion 关联下并发建两个虚拟设备
 * （deviceId 81/82、displayId 300/301）均成功、各自拿到 `c2.qti.avc.encoder` 输入面，
 * 释放后虚拟屏计数归零、物理屏焦点不变（探针记录见 `outputs/probe/`）。
 *
 * ## 为什么必须是"一个进程干两件事"
 * `VirtualDesktopSession` 是**进程内单例**，`VirtualDevice` 与其 `VirtualDisplay` 只在
 * 建它的进程有效，编码器的 inputSurface 也是进程内对象。因此「建设备 + 建屏 + 跑
 * MediaCodec」**必须同进程**，**不经任何 IPC 传 Surface**。
 *
 * ## 为什么显式拉 Home/应用（不用 VDM `setHomeComponent`）
 * 真机实证：设了 `setHomeComponent` 后 ROM 自带的
 * `com.google.android.apps.nexuslauncher/...SecondaryDisplayLauncher` 会抢走该副屏 Home。
 * 故本宿主不设 VDM home，改用 `am start -W --display <id>` 显式拉起。
 *
 * ## 调用契约
 * - `desktop <w> <h> <bitrate> <fps> <assocId> <stopFile> [socketName]`；
 * - `window <w> <h> <bitrate> <fps> <assocId> <stopFile> <socketName> <component> [windowId]`；
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

    /** 同步帧请求文件后缀（App 侧 touch → 宿主补一个 IDR，见 Request-Sync-1）。 */
    private const val SYNC_SUFFIX = ".sync"

    /** 逐屏夜间模式请求文件后缀（App 侧写 `yes`/`no` → 宿主 `setDisplayUiMode`）。 */
    private const val UIMODE_SUFFIX = ".uimode"

    /** 逐屏夜间模式结果文件后缀（宿主回写 `on=`/`ok=`/`displayId=`）。 */
    private const val UIMODE_STATE_SUFFIX = ".uimode.state"

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        var code = 1
        var stopFile: File? = null
        var statusFile: File? = null
        var syncFile: File? = null
        var uimodeFile: File? = null
        try {
            runCatching {
                Log.i(TAG, "[FusionDesktopMain] pid=$pid uid=$uid enter args=${args.toList().take(10)}")
            }
            val op = args.getOrNull(0)
            if (op != "desktop" && op != "window") {
                runCatching { Log.e(TAG, "[FusionDesktopMain] unknown op $op (desktop|window)") }
                return
            }
            val isWindow = op == "window"
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
            val component = if (isWindow) args.getOrNull(8)?.takeIf { it.isNotBlank() }?.let { encoded ->
                if (encoded == "-") "" else runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
            }?.takeIf { it.isNotBlank() } else null
            val windowId = if (isWindow) args.getOrNull(9)?.toIntOrNull() ?: 0 else 0
            val intentUrl = if (isWindow) args.getOrNull(10)?.let { encoded ->
                if (encoded == "-") "" else runCatching { String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
            }.orEmpty() else ""
            val kind = if (isWindow) args.getOrNull(11)?.takeIf { it.isNotBlank() } ?: "app" else "app"
            val userId = if (isWindow) args.getOrNull(12)?.toIntOrNull()?.coerceIn(0, 999) ?: 0 else 0
            if (isWindow && component == null && intentUrl.isBlank() && kind == "app") {
                runCatching { Log.e(TAG, "[FusionDesktopMain] window op missing launch target args[8..11]") }
                return
            }
            val stop = File(stopPath)
            val status = File(stopPath + STATUS_SUFFIX)
            // Request-Sync-1：App 侧 touch 本文件即请求一个 IDR（IPC 落文件，宿主轮询消费）。
            val sync = File(stopPath + SYNC_SUFFIX)
            // 逐屏夜间模式：App 侧写 yes/no → 宿主消费 → 回写结果状态文件（IPC 落文件，同 sync）。
            val uimode = File(stopPath + UIMODE_SUFFIX)
            val uimodeState = File(stopPath + UIMODE_STATE_SUFFIX)
            stopFile = stop
            statusFile = status
            syncFile = sync
            uimodeFile = uimode
            runCatching { if (stop.exists()) stop.delete() }
            runCatching { if (sync.exists()) sync.delete() }
            runCatching { if (uimode.exists()) uimode.delete() }
            runCatching { if (uimodeState.exists()) uimodeState.delete() }
            runCatching { status.writeText("state=starting\n") }

            // 关联 id 兜底：args 没给就自己查（只认自己的 MAC）。
            val assoc = if (assocId > 0) assocId else VirtualDesktopSession.findOwnAssociationId(0)
            if (assoc <= 0) {
                runCatching { Log.e(TAG, "[FusionDesktopMain] no own association (mac=${VirtualDeviceAssociation.OWN_MAC})") }
                runCatching { statusFile.writeText("state=failed\nerror=no own association\n") }
                return
            }

            val displayName = if (isWindow) "BlindCastWin$windowId" else "BlindCastDesktop"
            // 采集源 = VDM 虚拟显示（同进程建设备+建屏+编码+挂 surface）。
            val pkg = com.erl.blindcast.BuildConfig.APPLICATION_ID
            val ok = PrivilegedCapture.startDesktop(
                width = w, height = h, bitrate = bitrate, fps = fps,
                associationId = assoc,
                displayName = displayName,
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
                        "width=$w\nheight=$h\ndensityDpi=320\nsource=virtualDisplay\n" +
                        "op=$op\nwindowId=$windowId\ncomponent=${component ?: ""}\n",
                )
            }
            Log.i(TAG, "[FusionDesktopMain] capturing $op ${w}x${h} ${bitrate}bps ${fps}fps " +
                "displayId=$did sock=$socketName stop=$stopPath component=$component wid=$windowId")

            // 显式把目标拉到该屏（先等屏稳定）。
            runCatching { Thread.sleep(if (isWindow) 800L else 1200L) }
            val target = if (isWindow) component else "$pkg/.FusionHomeActivity"
            fun quote(v: String): String = "'" + v.replace("'", "'\\''") + "'"
            val launchCommand = if (!isWindow) {
                "am start -W --user $userId --display $did -f 0x18000000 -n ${quote(target ?: "")}"
            } else if (intentUrl.isNotBlank()) {
                "am start -W --user $userId --display $did -a android.intent.action.VIEW -d ${quote(intentUrl)}"
            } else if (kind == "widget-picker") {
                "am start -W --user $userId --display $did -a android.appwidget.action.APPWIDGET_PICK"
            } else if (kind == "widget-config") {
                "am start -W --user $userId --display $did -a android.appwidget.action.APPWIDGET_CONFIGURE"
            } else {
                "am start -W --user $userId --display $did -f 0x18000000 -n ${quote(target ?: "")}"
            }
            val launch = runCatching {
                ProcessBuilder(
                    "sh", "-c",
                    launchCommand,
                )
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
            runCatching { Log.i(TAG, "[FusionDesktopMain] launch $target -> ${launch.replace("\n", " | ")}") }

            code = 0
            var aliveLogged = false
            // 轮询步长 250ms：stop 响应够快，也让 request-sync 的补帧延迟 ≤250ms
            // （stat 两个文件的开销可忽略，不值得为省 CPU 把补帧拖到半秒以上）。
            while (true) {
                runCatching { Thread.sleep(250L) }.onFailure { break }
                if (!aliveLogged) {
                    aliveLogged = true
                    runCatching {
                        Log.i(TAG, "[FusionDesktopMain] alive video=${PrivilegedCapture.videoRunning} " +
                            "audio=${PrivilegedCapture.audioRunning} route=${PrivilegedCapture.displayRouteSnapshot()}")
                    }
                }
                if (sync.exists()) {
                    runCatching { sync.delete() }
                    runCatching { Log.i(TAG, "[FusionDesktopMain] sync request -> request IDR") }
                    runCatching { PrivilegedCapture.requestSyncFrame() }
                }
                if (uimode.exists()) {
                    val want = runCatching { uimode.readText().trim() }.getOrDefault("")
                    runCatching { uimode.delete() }
                    val on = want.equals("yes", true) || want == "1"
                    val ok = VirtualDesktopSession.setNightMode(on)
                    runCatching {
                        uimodeState.writeText(
                            "on=${if (on) 1 else 0}\nok=${if (ok) 1 else 0}\n" +
                                "displayId=${VirtualDesktopSession.displayId}\n",
                        )
                    }
                    runCatching { Log.i(TAG, "[FusionDesktopMain] uimode want=$want on=$on ok=$ok") }
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
