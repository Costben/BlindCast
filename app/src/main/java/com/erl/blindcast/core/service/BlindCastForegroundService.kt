package com.erl.blindcast.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.erl.blindcast.R
import com.erl.blindcast.BuildConfig
import com.erl.blindcast.core.blackout.EmergencyRecovery
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.priv.IPrivilegedOps
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.core.priv.PrivilegedUserService
import com.erl.blindcast.core.priv.RootExecutor
import com.erl.blindcast.core.priv.RootInputDaemon
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.CaptureSocketLink
import com.erl.blindcast.core.scrcpy.ScrcpyGate
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.core.server.routes.ControlWsRoute
import com.erl.blindcast.core.server.routes.StreamWsRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻前台保活服务（Slice 6.1 · MVP.md 第四章 core/service）。
 *
 * ## FGS 类型为什么是 dataSync（Fix-FGS-1）
 * - `connectedDevice` 不可用：targetSDK=37 上以该类型起 FGS 要求
 *   `allOf [FOREGROUND_SERVICE_CONNECTED_DEVICE]` + `anyOf [BLUETOOTH_ADVERTISE/CONNECT/SCAN,
 *   CHANGE_NETWORK_STATE, CHANGE_WIFI_STATE, ...]`，本 App 只持有前者、anyOf 一项没有，
 *   `startForeground` 直接抛 `SecurityException` 致 `onCreate` 崩溃 + `START_STICKY` 重拉起循环。
 * - `mediaProjection` 不可用：该类型要求持有 MediaProjection consent token
 *  （`createScreenCaptureIntent` 用户授权前置），本服务走 Shizuku/Root 的 VirtualDisplay
 *   直采、无 token，声明即错配。
 * - `dataSync` 为通用常驻类型：仅需 `FOREGROUND_SERVICE_DATA_SYNC` 单权限，
 *   与局域网投屏常驻语义兼容（状态轮询/串流保活视作数据同步），故 Manifest 与代码统一用它。
 *
 * ## 保活三件套（为什么是 WakeLock + WifiLock，而不是 FLAG_KEEP_SCREEN_ON）
 * - `FLAG_KEEP_SCREEN_ON` 是 Window 标志，只能附着在前台 Activity 的窗口上；
 *   Service 没有窗口，无法持有。本服务改用等价的电源锁组合达到同一目的：
 * - `PARTIAL_WAKE_LOCK`：CPU 常醒（息屏后渲染管线/编码线程不被挂起）；
 * - `WIFI_MODE_FULL_HIGH_PERF WifiLock`：息屏后 WiFi 不降速不断流（串流不断线）；
 * - [UserActivityKeeper] 每 4s 向 `PowerManager.userActivity()` 喂狗：阻止 Doze 休眠，
 *   与前两者互补（WakeLock 防 CPU 睡，喂狗防系统级 Doze）。
 *
 * ## onCreate 启动顺序（任务包约定）
 * 1. [BlindCastServer]（端口取偏好 `server_port`，默认 8888；Token 取 `stream_token` 同步）；
 * 2. [ScreenCaptureEngine]（引擎默认档 720P/30FPS/4Mbps；画质偏好归 6.2，本 Slice 只读默认）；
 * 3. [AudioCaptureEngine]（默认档 48kHz 立体声；音频开关归 6.2）；
 * 4. [UserActivityKeeper.start]（4s 喂狗）。
 * 采集引擎在无提权普通进程内会返回 false（原因记各自 `lastError`），本服务只记日志
 * 不崩溃：HTTP 静态页与状态 API 照常可用，等提权打通后重启服务即满血。
 *
 * ## onDestroy 销毁顺序（逆序回收 + 熔断）
 * [UserActivityKeeper.stop] → [AudioCaptureEngine.stop] → [ScreenCaptureEngine.stop]
 * → [BlindCastServer.stop] → 释放 WifiLock/WakeLock → [EmergencyRecovery.notifyServiceDestroy]
 * （熄屏态下异步强制点亮，杜绝屏幕变砖）。
 *
 * ## 状态对外暴露
 * [status] 为进程级 [StateFlow]：UI 层（HomeViewModel）与 Service 存活与否无关，
 * 服务未运行时同样可读（此时为 [snapshot] 的停止态快照）。
 */
class BlindCastForegroundService : Service() {

    /** 对外可观察的服务快照。 */
    data class ServiceStatus(
        val isRunning: Boolean = false,
        val port: Int = BlindCastServer.DEFAULT_PORT,
        val fps: Int = -1,
        val bitrateBps: Int = -1,
        val clients: Int = 0,
        val blackedOut: Boolean = false,
        /** Fix-FGS-1：最近一次前台化/引擎启动致命异常信息（null = 无异常，供 Home 只读展示）。 */
        val lastError: String? = null,
    )

    companion object {
        private const val TAG = "BlindCast-FgService"
        private const val ACTION_START = "com.erl.blindcast.action.STREAM_START"
        private const val ACTION_STOP = "com.erl.blindcast.action.STREAM_STOP"
        private const val NOTIF_ID = 0xB11DC4
        private const val CHANNEL_ID = "blindcast_stream"

        /** 与设置页共享的偏好文件（6.2 读写同一文件/键，键名冻结）。 */
        const val PREFS_NAME = "settings"

        /** 访问 Token 键（空串 = 免密直通，与 [com.erl.blindcast.core.server.auth.TokenAuthenticator] 语义一致）。 */
        const val KEY_TOKEN = "stream_token"

        /** HTTP 监听端口键（默认 [BlindCastServer.DEFAULT_PORT]）。 */
        const val KEY_PORT = "server_port"

        /** 状态轮询间隔 2s。 */
        const val POLL_INTERVAL_MS = 2_000L

        private val _status = MutableStateFlow(snapshot())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        /**
         * Fix-FGS-1：服务级致命异常留痕（与各引擎 `lastError` 语义一致：成功清零、失败覆写）。
         * 前台化 `SecurityException` / `bootStack` 外层 catch 均经 [recordError] 进入状态流，
         * Home 经 [status] 只读可见（不改 Home 逻辑，仅读 `ServiceStatus.lastError`）。
         */
        var lastError: Throwable? = null
            private set

        private fun recordError(t: Throwable) {
            lastError = t
            Log.e(TAG, "foreground service fatal", t)
            runCatching { _status.value = snapshot() }
        }

        /** 启动串流总服务（任意线程；UI 层唯一入口）。 */
        fun start(context: Context) {
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止串流总服务（任意线程；UI 层唯一入口）。 */
        fun stop(context: Context) {
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** 刷新一次对外快照（供 UI 在服务未运行时主动对齐）。 */
        fun refreshSnapshot() {
            _status.value = snapshot()
        }

        /**
         * 特权采集路由失败留痕（Stream-Priv-1 · 与服务致命 [lastError] 同语义：
         * 成功清零、失败覆写，进状态流供 Home 只读展示；引擎直起已下线，此处只收特权链路错）。
         */
        @Volatile
        var captureError: Throwable? = null
            private set

        fun recordCaptureError(t: Throwable) {
            captureError = t
            Log.e(TAG, "privileged capture failed", t)
            runCatching { _status.value = snapshot() }
        }

        fun clearCaptureError() {
            if (captureError != null) {
                captureError = null
                runCatching { _status.value = snapshot() }
            }
        }

        /**
         * 当前快照：运行态读特权链路实时值（首帧后），停止态读偏好端口。
         * Stream-Priv-1 改道：server 照常本进程；video/audio 改特权链路，
         * fps/bitrate 优先读 [CaptureSocketLink]（有首帧才有效），无首帧回退本地引擎
         * （本地引擎已不再 App 进程启动，恒 -1，仅作诊断兼容）；lastError 聚合
         * 服务致命 + 特权路由 + 搬运 + 引擎明细，Home 可见。
         */
        private fun snapshot(): ServiceStatus {
            val running = BlindCastServer.isRunning
            val port = BlindCastServer.actualPort
                .takeIf { running && it > 0 }
                ?: BlindCastServer.port
            val linkActive = CaptureSocketLink.hasVideo || CaptureSocketLink.isRunning
            val fps = when {
                CaptureSocketLink.hasVideo && CaptureSocketLink.currentFps > 0 -> CaptureSocketLink.currentFps
                else -> ScreenCaptureEngine.currentFps
            }
            val bitrate = when {
                CaptureSocketLink.hasVideo && CaptureSocketLink.currentBitrate > 0 -> CaptureSocketLink.currentBitrate
                else -> ScreenCaptureEngine.currentBitrate
            }
            @Suppress("UNUSED_VARIABLE")
            val linkHint = linkActive
            val errText = lastError?.message ?: lastError?.toString()
                ?: captureError?.message ?: captureError?.toString()
                ?: CaptureSocketLink.errorMessage()
                ?: ScreenCaptureEngine.lastError?.message
                ?: AudioCaptureEngine.lastError?.message
            return ServiceStatus(
                isRunning = running,
                port = port,
                fps = fps,
                bitrateBps = bitrate,
                clients = StreamWsRoute.sessionCount + ControlWsRoute.sessionCount,
                blackedOut = PowerController.isBlackedOut,
                lastError = errText,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Stream-Priv-1 特权采集常驻句柄（Shizuku daemon(true) 长连 / Root 常驻二选一）。
    private var privConn: android.content.ServiceConnection? = null
    private var privOps: IPrivilegedOps? = null
    private var privArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null
    @Volatile private var captureMode: String = "none" // shizuku | root | none
    @Volatile private var rootStopFile: String? = null

    /**
     * Stream-Priv-2 并发互斥卫兵：onCreate 与 onStartCommand 双路调 bootStack
     * （或重复 startService）并发只跑一份；boot 完成 finally 清标记。
     */
    private val booting = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Fix-FGS-1 纵深兜底：前台化失败（SecurityException，如 FGS 类型权限缺失）绝不掀翻 App。
        // 记录 lastError + 状态流可展示 + stopSelf 保持关态，打断 START_STICKY 重拉起循环。
        try {
            val fgOk = startForegroundInternal()
            if (!fgOk) {
                runCatching { _status.value = snapshot() }
                return
            }
        } catch (se: SecurityException) {
            recordError(se)
            runCatching { stopSelf() }
            return
        } catch (t: Exception) {
            recordError(t)
            runCatching { stopSelf() }
            return
        }
        runCatching { acquireLocks() }
        // 开机读档自启路径（START_STICKY 重建同样走这里）：受 bootStack 内外双层保护。
        try {
            bootStack()
        } catch (se: SecurityException) {
            recordError(se)
            runCatching { stopSelf() }
        } catch (t: Exception) {
            recordError(t)
            runCatching { stopSelf() }
        }
        scope.launch {
            while (isActive) {
                _status.value = snapshot()
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "created, serverRunning=${BlindCastServer.isRunning}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 粘性重启（系统杀死后拉起）：确保协议栈处于启动态；同样受保护，失败保持关态。
        try {
            if (!BlindCastServer.isRunning) bootStack()
            _status.value = snapshot()
        } catch (se: SecurityException) {
            recordError(se)
            runCatching { stopSelf() }
            return START_NOT_STICKY
        } catch (t: Exception) {
            recordError(t)
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        // 逆序回收（Stream-Priv-1）：先停喂狗，先停 socket 搬运，再 destroy 特权采集，
        // 再停本地引擎兜底，最后停监听与熔断。
        runCatching { UserActivityKeeper.stop() }
        runCatching { stopPrivilegedCapture() }
        // Smooth-1：随服务停而停常驻输入 daemon（fire-and-forget，不阻塞销毁）。
        runCatching { RootInputDaemon.stopAsync() }
        Log.i(TAG, "[InputDaemon] stop signaled on destroy")
        runCatching { AudioCaptureEngine.stop() }
        runCatching { ScreenCaptureEngine.stop() }
        runCatching { BlindCastServer.stop() }
        releaseLocks()
        // 熔断：熄屏态下异步强制点亮（EmergencyRecovery 内部判态，点亮态为 no-op）。
        runCatching { EmergencyRecovery.notifyServiceDestroy() }
        _status.value = snapshot()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun configuredPort(): Int {
        val p = runCatching { prefs().getInt(KEY_PORT, BlindCastServer.DEFAULT_PORT) }
            .getOrDefault(BlindCastServer.DEFAULT_PORT)
        return if (p in 1..65535) p else BlindCastServer.DEFAULT_PORT
    }

    private fun bootStack() {
        // Stream-Priv-2：并发互斥，重复进入直接返回，boot 完成 finally 清标记。
        if (!booting.compareAndSet(false, true)) {
            Log.i(TAG, "bootStack skipped (already booting)")
            return
        }
        try {
            bootStackInternal()
            // 同进程重试成功且协议栈健康时清掉陈旧致命痕（失败态已在 catch 留痕，不误删）。
            if (lastError != null && BlindCastServer.isRunning) {
                lastError = null
                runCatching { _status.value = snapshot() }
            }
        } catch (se: SecurityException) {
            // 开机读档自启同样受保护：记 lastError/状态流 + stopSelf 保持关态，绝不抛崩。
            recordError(se)
            runCatching { stopSelf() }
        } catch (t: Exception) {
            recordError(t)
            runCatching { stopSelf() }
        } finally {
            booting.set(false)
        }
    }

    /**
     * bootStack 真体（Stream-Priv-1 改道）：偏好读档 → server 本进程 → video/audio 特权链路 → keeper。
     * 实证根因：旧 bootStack 在普通应用进程起 ScreenCaptureEngine
     * （DisplayManager.createVirtualDisplay 需签名级 CAPTURE_VIDEO_OUTPUT，吃
     * SecurityException 静默 false）与 AudioCaptureEngine，引擎无错、错在跑错进程。
     * 新链路 scrcpy 同构：特权采集（PrivilegedCapture 跑在 Shizuku UserService/Root 常驻）
     * + LocalSocket 回传帧（CaptureSocketLink 搬运进既有 Channel，StreamWsRoute 不动）。
     * 主线程只起服 + 发起异步特权建连；running 标定等 socket 首帧或 3s 超时（后台协程内），
     * 失败记 captureError 进状态流/Home 可见，绝不阻塞 onCreate。
     */
    private fun bootStackInternal() {
        val port = configuredPort()
        val token = runCatching { prefs().getString(KEY_TOKEN, "") ?: "" }.getOrDefault("")
        // 偏好键冻结不动（Slice 6.2 与 SettingsRepositoryImpl 一致，缺键回退默认）。
        val p = runCatching { prefs() }.getOrNull()
        val resolution = p?.getString("video_resolution", "720P")?.takeIf { it in setOf("720P", "1080P", "原生") } ?: "720P"
        val fps = p?.getInt("video_fps", 30)?.takeIf { it == 30 || it == 60 } ?: 30
        val bitrateMbps = p?.getInt("video_bitrate_mbps", 4)?.takeIf { it in 2..8 } ?: 4
        val audioEnabled = p?.getBoolean("audio_enabled", true) ?: true
        val touch = p?.getBoolean("scrcpy_touch_enabled", true) ?: true
        val rightBack = p?.getBoolean("scrcpy_right_back_enabled", true) ?: true
        val keyboard = p?.getBoolean("scrcpy_keyboard_enabled", true) ?: true
        val keepAlive = p?.getBoolean("keepalive_enabled", true) ?: true
        runCatching { AudioCaptureEngine.setAudioEnabled(audioEnabled) }
        runCatching { ScrcpyGate.sync(touch, rightBack, keyboard) }
        BlindCastServer.init(this)
        ScreenCaptureEngine.init(this)
        BlindCastServer.setToken(token)
        BlindCastServer.port = port
        val serverOk = runCatching { BlindCastServer.start(port) }.getOrDefault(false)
        if (!serverOk) {
            Log.w(TAG, "BlindCastServer.start($port) failed", BlindCastServer.lastError)
        }
        val (vw, vh) = when (resolution) {
            "1080P" -> 1920 to 1080
            "原生" -> runCatching {
                val m = resources.displayMetrics
                if (m.widthPixels > 0 && m.heightPixels > 0) m.widthPixels to m.heightPixels else 1280 to 720
            }.getOrDefault(1280 to 720)
            else -> 1280 to 720
        }
        val bitrate = bitrateMbps * 1_000_000
        // 本地引擎不再 App 进程直起（必吃 SecurityException 静默 false，旧根因）：
        // 只做特权链路（搬运服先起，特权建连 + 首帧等待放后台，避免阻塞主线程 ANR）。
        // Stream-Priv-2 解耦降级：HTTP 已在上方先起（UI/API/WS 可用），采集失败只记
        // captureError 进状态流（videoRunning=false），绝不碰 server；后台重试一次
        //（5s 后），仍失败就停等下次开关。
        scope.launch {
            val okFirst = runCatching { runPrivilegedCaptureBlocking(vw, vh, bitrate, fps) }.getOrDefault(false)
            runCatching { _status.value = snapshot() }
            Log.i(TAG, "[CaptureRoute] boot privileged done ok=$okFirst mode=$captureMode " +
                "hasVideo=${CaptureSocketLink.hasVideo} hasAudio=${CaptureSocketLink.hasAudio}")
            if (okFirst) return@launch
            if (!isActive || !BlindCastServer.isRunning) {
                Log.i(TAG, "[CaptureRoute] capture failed, skip retry (service stopped)")
                return@launch
            }
            Log.i(TAG, "[CaptureRoute] capture failed, retry once after 5s " +
                "(videoRunning=false, server keeps running)")
            delay(5_000L)
            if (!isActive || !BlindCastServer.isRunning) {
                Log.i(TAG, "[CaptureRoute] retry skipped (service stopped)")
                return@launch
            }
            if (CaptureSocketLink.hasVideo) {
                runCatching { _status.value = snapshot() }
                return@launch
            }
            Log.i(TAG, "[CaptureRoute] retrying privileged capture once")
            val okRetry = runCatching { runPrivilegedCaptureBlocking(vw, vh, bitrate, fps) }.getOrDefault(false)
            runCatching { _status.value = snapshot() }
            Log.i(TAG, "[CaptureRoute] retry privileged done ok=$okRetry mode=$captureMode " +
                "hasVideo=${CaptureSocketLink.hasVideo} hasAudio=${CaptureSocketLink.hasAudio}")
            if (!okRetry) {
                // 仍失败就停等下次开关：确保采集已收，不碰 server（HTTP/UI/API/WS 保持可用）。
                runCatching { stopPrivilegedCapture() }
                runCatching { _status.value = snapshot() }
                Log.w(TAG, "[CaptureRoute] retry failed, capture stopped waiting next toggle; " +
                    "serverRunning=${BlindCastServer.isRunning}")
            }
        }
        // Smooth-1 常驻输入 daemon（随服务启停）：后台 ensure，存活即复用；
        // 反控 tap/drag/down/move/up 经 daemon ack（~数十 ms），不再每次冷起 app_process。
        // 失败只记日志（ControlWsRoute 回退单次 Root→Shizuku 老路，反控不断）。
        scope.launch {
            val ok = runCatching { RootInputDaemon.ensureStarted(packageName) }.getOrDefault(false)
            Log.i(TAG, "[InputDaemon] boot ensure ok=$ok")
            runCatching { _status.value = snapshot() }
        }
        if (keepAlive) {
            runCatching { UserActivityKeeper.start(this) }
        } else {
            runCatching { UserActivityKeeper.stop() }
        }
        _status.value = snapshot()
    }

    // ------------------------------------------------------------------
    // Stream-Priv-1 特权采集路由（server 本进程 + video/audio 特权链路）
    // ------------------------------------------------------------------

    /** Shizuku 常驻绑定超时 15s（特权进程冷起 app_process 留足余量）。 */
    private val PRIV_BIND_TIMEOUT_MS = 15_000L

    /** Root 常驻停服宽限约 1.5s（Smooth-1：daemon 侧 stop 文件轮询步进 500ms，
     * 宽限须覆盖 ≥2 个周期，防 touch→rm 窗口竞态漏杀致虚拟屏泄漏；实证 500ms 会漏）。 */
    private val ROOT_STOP_POLL_MS = 1_500L

    /**
     * 后台阻塞式跑完特权建连 + 首帧等待（IO 线程调用，bootStack 经 scope.launch 进入）。
     * 成功（3s 内有首帧）清 captureError；失败记 captureError 进状态流/Home 可见。
     */
    private fun runPrivilegedCaptureBlocking(vw: Int, vh: Int, bitrate: Int, fps: Int): Boolean {
        // 1. 搬运服先起（App 进程 LocalServerSocket accept，特权侧 connect）。
        val linkOk = runCatching { CaptureSocketLink.start(vw, vh, bitrate, fps) }.getOrDefault(false)
        if (!linkOk) {
            val t = IllegalStateException("搬运服启动失败：${CaptureSocketLink.errorMessage() ?: "unknown"}")
            recordCaptureError(t)
            return false
        }
        // 2. 特权建连二选一：Shizuku 常驻优先，Root 常驻备用（理由见日志，任务包要求写清）。
        val pkg = packageName
        var connOk = false
        var mode = "none"
        var detail: String? = null
        if (runCatching { PrivilegedBridge.isPrivilegedGranted() }.getOrDefault(false)) {
            val bound = runCatching { bindShizukuCapturePersistent(pkg) }.getOrNull() == true
            if (bound) {
                val started = runCatching { privOps?.startCapture(vw, vh, bitrate, fps) }.getOrDefault(false) == true
                if (started) {
                    connOk = true
                    mode = "shizuku"
                    Log.i(TAG, "[CaptureRoute] 最终选择=Shizuku UserService 常驻（daemon(true))；理由=本机 Shizuku daemon 以 root 启动，" +
                        "其 UserService 特权身份对 SurfaceControl.createDisplay/setDisplayProjection 已够用（scrcpy 同构，" +
                        "libandroid_runtime JNI，shell 上下文可调；不同于 displayPower 被 OPlus 静默忽略个案），" +
                        "避免另起 Root 常驻宿主复杂度；RootCaptureMain 已实现为备用（Shizuku 未授权时启用）。")
                } else {
                    detail = runCatching { privOps?.captureError }.getOrNull()
                        ?: CaptureSocketLink.errorMessage() ?: "shizuku startCapture=false"
                    Log.w(TAG, "[CaptureRoute] shizuku startCapture=false err=$detail，拆常驻并试 Root 备用")
                    runCatching { unbindShizukuCapture() }
                }
            } else {
                detail = "shizuku 常驻绑定失败"
                Log.w(TAG, "[CaptureRoute] shizuku bind failed，试 Root 备用")
            }
        } else {
            val st = runCatching { PrivilegedBridge.shizukuState() }.getOrNull()
            detail = "Shizuku 未授权（state=$st），"
            Log.i(TAG, "[CaptureRoute] Shizuku 不可用（state=$st），试 Root 常驻备用")
        }
        if (!connOk) {
            val rootOk = runCatching { startRootCapturePersistent(vw, vh, bitrate, fps) }.getOrDefault(false)
            if (rootOk) {
                connOk = true
                mode = "root"
                Log.i(TAG, "[CaptureRoute] 最终选择=RootCaptureMain 常驻（libsu app_process daemon）；理由=Shizuku 不可用" +
                    "（${detail ?: "未授权/绑定失败"}），Root su 可用，拉起 uid0 常驻跑 PrivilegedCapture 直到 stop 文件信号；" +
                    "Shizuku 恢复后下次启动仍优先 Shizuku。")
            } else {
                val t = IllegalStateException("特权采集建连失败：Shizuku(${detail ?: "未授权/绑定失败"})；Root(无 su/拉起失败)；" +
                    "请去 Shizuku 管理器启动并授权，或到 KernelSU 授予 Root 后重试")
                runCatching { CaptureSocketLink.stop() }
                runCatching { unbindShizukuCapture() }
                recordCaptureError(t)
                return false
            }
        }
        captureMode = mode
        // 3. 等 socket 首帧或 3s 超时再标 running（任务包约定）。
        val first = runCatching { CaptureSocketLink.awaitFirstFrame(CaptureSocketLink.FIRST_FRAME_TIMEOUT_MS) }.getOrDefault(false)
        if (first) {
            clearCaptureError()
            Log.i(TAG, "[CaptureRoute] 首帧到达 mode=$mode video=${CaptureSocketLink.hasVideo} audio=${CaptureSocketLink.hasAudio}")
            return true
        }
        val linkErr = CaptureSocketLink.errorMessage()
        val privErr = if (mode == "shizuku") runCatching { privOps?.captureError }.getOrNull() else null
        val t = IllegalStateException("特权采集 3s 无首帧（mode=$mode）：linkErr=$linkErr privErr=$privErr；" +
            "特权进程可能被杀或 SurfaceControl 被 ROM 忽略，见特权进程 logcat [PrivilegedCapture] 明细")
        // 超时即收（先停 socket，再 destroy 特权采集，逆序收）。
        runCatching { stopPrivilegedCapture() }
        recordCaptureError(t)
        return false
    }

    /** Shizuku 常驻绑定（daemon(true)，流期间不 destroy；stop 时 destroy 宿主）。 */
    private fun bindShizukuCapturePersistent(packageName: String): Boolean {
        return try {
            val args = rikka.shizuku.Shizuku.UserServiceArgs(
                android.content.ComponentName(packageName, PrivilegedUserService::class.java.name),
            ).daemon(true)
                .processNameSuffix("privileged")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            val latch = java.util.concurrent.CountDownLatch(1)
            var ops: IPrivilegedOps? = null
            var bindErr: Throwable? = null
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    val ping = binder != null && runCatching { binder.pingBinder() }.getOrDefault(false)
                    if (ping && binder != null) {
                        ops = IPrivilegedOps.Stub.asInterface(binder)
                    } else {
                        bindErr = IllegalStateException("特权服务 binder 无效（ping 失败）")
                    }
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    latch.countDown()
                }
            }
            try {
                rikka.shizuku.Shizuku.bindUserService(args, conn)
            } catch (t: Throwable) {
                bindErr = t
                latch.countDown()
            }
            val ok = latch.await(PRIV_BIND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) && ops != null && bindErr == null
            if (!ok) {
                runCatching { rikka.shizuku.Shizuku.unbindUserService(args, conn, true) }
                Log.w(TAG, "[CaptureRoute] bindShizukuCapture timeout/err=${bindErr?.message}")
                return false
            }
            privConn = conn
            privOps = ops
            privArgs = args
            Log.i(TAG, "[CaptureRoute] bindShizukuCapture daemon(true) ok")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[CaptureRoute] bindShizukuCapture threw", t)
            false
        }
    }

    private fun unbindShizukuCapture() {
        val ops = privOps
        val conn = privConn
        val args = privArgs
        privOps = null
        privConn = null
        privArgs = null
        if (ops != null || conn != null) {
            runCatching { ops?.stopCapture() }
            val destroyErr = runCatching { ops?.destroy() }.exceptionOrNull()
            Log.i(TAG, "[CaptureRoute] shizuku destroy err=${destroyErr?.toString() ?: "none"}")
            if (conn != null && args != null) {
                val unbindErr = runCatching { rikka.shizuku.Shizuku.unbindUserService(args, conn, true) }.exceptionOrNull()
                Log.i(TAG, "[CaptureRoute] shizuku unbind err=${unbindErr?.toString() ?: "none"}")
            }
        }
    }

    /**
     * Root 常驻拉起（备用）：libsu `app_process` 跑 RootCaptureMain 常驻直到 stop 文件信号。
     * App 侧仍复用同一 CaptureSocketLink 服（RootCaptureLink 逻辑折叠在此，不另起文件）。
     * Smooth-1：拉起前先清残留 daemon（上次崩溃/强杀无 destroy 时的孤儿，其虚拟屏
     * 占着 DisplayManager；此时搬运服刚起、无合法常驻，故全清安全）。
     */
    private fun startRootCapturePersistent(vw: Int, vh: Int, bitrate: Int, fps: Int): Boolean {
        return try {
            if (!runCatching { RootExecutor.isRootAvailable() }.getOrDefault(false)) {
                Log.i(TAG, "[CaptureRoute] root unavailable (no su/denied)")
                return false
            }
            runCatching { killStaleCaptureDaemons() }
            val apkPath = runCatching { applicationInfo.sourceDir }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return false
            val nonce = runCatching { java.util.UUID.randomUUID().toString().replace("-", "").take(8) }
                .getOrDefault(System.currentTimeMillis().toString())
            val stopFile = "/data/local/tmp/blindcast_capture_stop_${android.os.Process.myPid()}_$nonce"
            rootStopFile = stopFile
            runCatching { com.topjohnwu.superuser.Shell.cmd("rm -f $stopFile").exec() }
            val socketName = com.erl.blindcast.core.scrcpy.PrivilegedCapture.SOCKET_NAME
            val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                "com.erl.blindcast.core.scrcpy.RootCaptureMain capture $vw $vh $bitrate $fps $stopFile $socketName &"
            Log.i(TAG, "[CaptureRoute] root daemon cmd=$cmd")
            val res = runCatching { com.topjohnwu.superuser.Shell.cmd(cmd).exec() }.getOrNull()
            Log.i(TAG, "[CaptureRoute] root daemon launched code=${runCatching { res?.code }.getOrDefault(-1)}")
            // 拉起即认为建连成功与否交由首帧判定（3s 窗口），此处只确认命令已下发。
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[CaptureRoute] startRootCapture threw", t)
            false
        }
    }

    private fun stopRootCapture() {
        val stopFile = rootStopFile
        rootStopFile = null
        if (stopFile.isNullOrBlank()) return
        runCatching {
            com.topjohnwu.superuser.Shell.cmd("touch $stopFile").exec()
            Thread.sleep(ROOT_STOP_POLL_MS)
            com.topjohnwu.superuser.Shell.cmd("rm -f $stopFile").exec()
        }
        // Smooth-1：宽限后仍可能有漏网（进程启动中错过窗口）：按类名补刀，
        // 停服时无合法常驻，全清安全（虚拟屏随进程死自动释放）。
        runCatching { killStaleCaptureDaemons() }
        Log.i(TAG, "[CaptureRoute] root daemon stop signaled file=$stopFile")
    }

    /**
     * 按类名清 Root 采集常驻（Smooth-1 · 孤儿回收）。
     * 只匹配 `com.erl.blindcast.core.scrcpy.RootCaptureMain`（采集 daemon），
     * 输入 daemon（RootInputMain）与电源单次（RootMain）一律不动。
     */
    private fun killStaleCaptureDaemons() {
        try {
            val res = com.topjohnwu.superuser.Shell
                .cmd("pkill -f 'com.erl.blindcast.core.scrcpy.RootCaptureMain'")
                .exec()
            Log.i(TAG, "[CaptureRoute] killStaleCaptureDaemons done code=${runCatching { res.code }.getOrDefault(-1)}")
        } catch (t: Throwable) {
            Log.w(TAG, "[CaptureRoute] killStaleCaptureDaemons threw: ${t.message}")
        }
    }

    /**
     * 停特权采集（逆序收：先停 socket 搬运，再 destroy 特权宿主）。
     * onDestroy 与首帧超时失败共用，幂等。
     */
    private fun stopPrivilegedCapture() {
        runCatching { CaptureSocketLink.stop() }
        // Shizuku 常驻：先 stopCapture 再 destroy 宿主（任务包约定流期间不 destroy）。
        runCatching { unbindShizukuCapture() }
        runCatching { stopRootCapture() }
        captureMode = "none"
    }

    private fun acquireLocks() {
        wakeLock = runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return@runCatching null
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlindCast:StreamLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        if (wakeLock == null) Log.w(TAG, "PARTIAL_WAKE_LOCK not held")
        @Suppress("DEPRECATION") // FULL_HIGH_PERF 为息屏串流唯一不断流模式，无替代。
        wifiLock = runCatching {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WifiManager::class.java)
                ?: return@runCatching null
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BlindCast:StreamWifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        if (wifiLock == null) Log.w(TAG, "WifiLock not held")
    }

    private fun releaseLocks() {
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
        }
        wifiLock = null
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    /**
     * Fix-FGS-1：以 `dataSync` 类型前台化（仅需 `FOREGROUND_SERVICE_DATA_SYNC`）。
     * 不用 `connectedDevice`（缺 anyOf 蓝牙/网络状态权限即 SecurityException），
     * 不用 `mediaProjection`（无用户授权 token，错配）。
     *
     * @return true = 前台化成功；false = 已记 [lastError]/状态流并 `stopSelf`，调用方直接返回。
     */
    private fun startForegroundInternal(): Boolean {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.blindcast_service_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                runCatching { manager?.createNotificationChannel(channel) }
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.blindcast_service_running))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
            // 前台化成功：清掉同进程陈旧致命痕（失败路径已在 catch 留痕）。
            if (lastError != null) {
                lastError = null
                runCatching { _status.value = snapshot() }
            }
            return true
        } catch (se: SecurityException) {
            recordError(se)
            runCatching { stopSelf() }
            return false
        } catch (t: Exception) {
            recordError(t)
            runCatching { stopSelf() }
            return false
        }
    }
}
