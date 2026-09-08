package com.erl.blindcast.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * 常驻前台保活服务（Slice 6.1 · MVP.md 第四章 core/service）。
 *
 * ## 开关分离（HTTP 端口 vs 串流采集）
 * - HTTP 开关 = 轻量端口在线（静态页/状态 API/息屏点亮 API/WS 信令可用，
 *   不起录屏编码，省电）；串流开关 = 特权采集（录屏 + 转码推流，重耗电）。
 * - 串流开隐含 HTTP 开（先保端口再起采集）；HTTP 关则串流同关；
 *   只停串流不断端口（可继续远程息屏/点亮）。
 * - 期望态双落盘（`service_http_enabled`/`service_stream_enabled`），
 *   START_STICKY 粘性重启按盘恢复；后台重试只看本代际 + 期望态，过期自弃。
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
        /** HTTP 监听是否在线（端口开 / 可远程息屏点亮）。 */
        val isStreaming: Boolean = false,
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
        /** 只开 HTTP 端口（可远程息屏/点亮，不起录屏编码）。 */
        private const val ACTION_START_HTTP = "com.erl.blindcast.action.HTTP_START"
        /** 开串流（隐含开 HTTP：先保端口在线，再起特权采集）。 */
        private const val ACTION_START_STREAM = "com.erl.blindcast.action.STREAMING_START"
        /** 只停串流采集（端口保持在线）。 */
        private const val ACTION_STOP_STREAM = "com.erl.blindcast.action.STREAMING_STOP"
        private const val NOTIF_ID = 0xB11DC4
        private const val CHANNEL_ID = "blindcast_stream"

        /** 与设置页共享的偏好文件（6.2 读写同一文件/键，键名冻结）。 */
        const val PREFS_NAME = "settings"

        /** 访问 Token 键（空串 = 免密直通，与 [com.erl.blindcast.core.server.auth.TokenAuthenticator] 语义一致）。 */
        const val KEY_TOKEN = "stream_token"

        /** HTTP 监听端口键（默认 [BlindCastServer.DEFAULT_PORT]）。 */
        const val KEY_PORT = "server_port"

        /**
         * 开关持久化（与 sticky 重启恢复共用，键名冻结）：
         * - [KEY_HTTP_ENABLED] = 端口开关期望态；
         * - [KEY_STREAM_ENABLED] = 串流采集期望态（含隐含 HTTP）。
         * 语义：串流开必含 HTTP 开；HTTP 关必含串流关；只停串流不断端口。
         */
        const val KEY_HTTP_ENABLED = "service_http_enabled"
        const val KEY_STREAM_ENABLED = "service_stream_enabled"

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
            setHttpWanted(context, false)
            setStreamWanted(context, false)
            streamWanted = false
            captureGen.incrementAndGet()
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        /**
         * 只开 HTTP 端口（轻量：可远程息屏/点亮，不起录屏编码；串流期望清零）。
         * 任意线程；HTTP 开关 UI 唯一入口。
         */
        fun startHttp(context: Context) {
            setHttpWanted(context, true)
            setStreamWanted(context, false)
            streamWanted = false
            captureGen.incrementAndGet()
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_START_HTTP)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 开串流（含隐含 HTTP：端口先在线，再起特权采集）。
         * 任意线程；串流开关 UI 唯一入口。
         */
        fun startStreaming(context: Context) {
            setHttpWanted(context, true)
            setStreamWanted(context, true)
            streamWanted = true
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_START_STREAM)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 只停串流采集（端口保持在线，可继续远程息屏/点亮）。
         * 服务未跑时只落持久化、不拉起服务。
         */
        fun stopStreaming(context: Context) {
            setStreamWanted(context, false)
            streamWanted = false
            captureGen.incrementAndGet()
            if (!BlindCastServer.isRunning && !CaptureSocketLink.isRunning && !CaptureSocketLink.hasVideo) {
                runCatching { _status.value = snapshot() }
                return
            }
            val intent = Intent(context, BlindCastForegroundService::class.java)
                .setAction(ACTION_STOP_STREAM)
            context.startService(intent)
        }

        /** 读 HTTP 期望态（缺键默认 false；服务被显式拉起即视为 true）。 */
        private fun readHttpWanted(context: Context): Boolean = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HTTP_ENABLED, false)
        }.getOrDefault(false)

        private fun readStreamWanted(context: Context): Boolean = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_STREAM_ENABLED, false)
        }.getOrDefault(false)

        private fun setHttpWanted(context: Context, value: Boolean) {
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_HTTP_ENABLED, value).apply()
            }
        }

        private fun setStreamWanted(context: Context, value: Boolean) {
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_STREAM_ENABLED, value).apply()
            }
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
         * 串流期望态（内存态，与 [KEY_STREAM_ENABLED] 持久化同写）：
         * 后台采集重试循环据此收敛——用户关串流后重试直接放弃，不复活采集。
         */
        @Volatile
        var streamWanted: Boolean = false
            private set

        /** 采集代际：停串流/切模式即自增，过期异步采集任务见代际不符直接放弃。 */
        private val captureGen = AtomicInteger(0)

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
            val streaming = CaptureSocketLink.isRunning || CaptureSocketLink.hasVideo
            val errText = lastError?.message ?: lastError?.toString()
                ?: captureError?.message ?: captureError?.toString()
                ?: CaptureSocketLink.errorMessage()
                ?: ScreenCaptureEngine.lastError?.message
                ?: AudioCaptureEngine.lastError?.message
            return ServiceStatus(
                isRunning = running,
                isStreaming = streaming,
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

    /**
     * 采集任务活跃标记（主线程置 true 发起，后台任务终局失败或主线程停串流时清零）。
     * 作用：onCreate 与 onStartCommand 同一主线程串行先后进入时，第二个入口看到
     * 已发起即跳过，避免同一代际双任务并发抢绑 Shizuku（见 ensureCaptureStarted）。
     */
    @Volatile
    private var captureActive = false

    // ScreenSync-1：手动电源键同步（广播为主 + DisplayListener 兜底 Doze 过渡）。
    // binder 熄屏是 SF 级断电、DM 恒报 ON，故此处只处理系统广播的真实亮灭，
    // 不用 DisplayManager.getState() 直接覆盖缓存（会把真黑误报成亮）。
    private var screenStateReceiver: BroadcastReceiver? = null
    private var screenDisplayManager: DisplayManager? = null
    private var screenDisplayListener: DisplayManager.DisplayListener? = null

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
        runCatching { registerScreenStateSync() }
        // 开关分离：onCreate 按持久化期望恢复（HTTP 与串流独立）。
        // 公开 start* 入口均先落盘后发 intent，故此处读盘即得本次期望；
        // 双盘皆空视为未知拉起，兼容旧行为默认全开并落盘。
        streamWanted = readStreamWanted(this)
        var httpWanted = readHttpWanted(this)
        if (!httpWanted && !streamWanted) {
            httpWanted = true
            streamWanted = true
            setHttpWanted(this, true)
            setStreamWanted(this, true)
        }
        try {
            if (httpWanted) ensureServerStarted()
            if (streamWanted) {
                ensureCaptureStarted()
            } else {
                runCatching { stopPrivilegedCapture() }
                captureActive = false
            }
            ensureInputDaemon()
            syncKeeper()
            _status.value = snapshot()
            if (lastError != null && BlindCastServer.isRunning) {
                lastError = null
                runCatching { _status.value = snapshot() }
            }
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
        when (intent?.action) {
            ACTION_STOP -> {
                // 总停：双期望清零，stopSelf 进 onDestroy 同步收尾（停服/放锁/熔断）。
                streamWanted = false
                setHttpWanted(this, false)
                setStreamWanted(this, false)
                captureGen.incrementAndGet()
                captureActive = false
                runCatching { _status.value = snapshot() }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_STREAM -> {
                // 只停串流：端口保持在线（异步停，不卡主线程）。
                streamWanted = false
                setStreamWanted(this, false)
                return try {
                    stopCaptureAsync()
                    _status.value = snapshot()
                    START_STICKY
                } catch (se: SecurityException) {
                    recordError(se)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                } catch (t: Exception) {
                    recordError(t)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                }
            }
            ACTION_START_HTTP -> {
                // 只开端口：串流期望清零并确保采集已收。
                setHttpWanted(this, true)
                setStreamWanted(this, false)
                streamWanted = false
                captureGen.incrementAndGet()
                return try {
                    ensureServerStarted()
                    stopCaptureAsync()
                    ensureInputDaemon()
                    syncKeeper()
                    _status.value = snapshot()
                    START_STICKY
                } catch (se: SecurityException) {
                    recordError(se)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                } catch (t: Exception) {
                    recordError(t)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                }
            }
            ACTION_START_STREAM -> {
                // 开串流（含隐含 HTTP）。
                setHttpWanted(this, true)
                setStreamWanted(this, true)
                streamWanted = true
                return try {
                    ensureServerStarted()
                    ensureCaptureStarted()
                    ensureInputDaemon()
                    syncKeeper()
                    _status.value = snapshot()
                    START_STICKY
                } catch (se: SecurityException) {
                    recordError(se)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                } catch (t: Exception) {
                    recordError(t)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                }
            }
            ACTION_START -> {
                // 兼容旧总开关：双开。
                setHttpWanted(this, true)
                setStreamWanted(this, true)
                streamWanted = true
                return try {
                    if (!BlindCastServer.isRunning) bootStack() else {
                        ensureCaptureStarted()
                        ensureInputDaemon()
                        syncKeeper()
                    }
                    _status.value = snapshot()
                    START_STICKY
                } catch (se: SecurityException) {
                    recordError(se)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                } catch (t: Exception) {
                    recordError(t)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                }
            }
            else -> {
                // 粘性重启（系统杀死后拉起，action=null）与未知 action：按持久化期望恢复。
                streamWanted = readStreamWanted(this)
                val httpWanted = readHttpWanted(this) || streamWanted
                return try {
                    if (httpWanted && !BlindCastServer.isRunning) ensureServerStarted()
                    if (streamWanted && BlindCastServer.isRunning) ensureCaptureStarted()
                    if (BlindCastServer.isRunning) {
                        ensureInputDaemon()
                        syncKeeper()
                    }
                    _status.value = snapshot()
                    START_STICKY
                } catch (se: SecurityException) {
                    recordError(se)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                } catch (t: Exception) {
                    recordError(t)
                    runCatching { stopSelf() }
                    START_NOT_STICKY
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterScreenStateSync() }
        runCatching { scope.cancel() }
        captureActive = false
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

    /**
     * ScreenSync-1：注册手动电源键同步（广播为主 + DisplayListener 兜底）。
     * 幂等，主线程调用；失败只记日志（不影响建连主流程）。
     */
    private fun registerScreenStateSync() {
        if (screenStateReceiver != null) return
        // 启动即对齐一次：进程重启会丢 isBlackedOut（默认 false），若当前 DM 已是灭态
        //（用户手动灭屏后重拉服务），先纠成 true，防重启后首查撒谎。
        runCatching {
            val dm = getSystemService(DisplayManager::class.java)
            val st = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.state
            if (st != null && (st == Display.STATE_OFF || st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND)) {
                PowerController.syncExternalState(false)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        runCatching { PowerController.syncExternalState(true) }
                        runCatching { _status.value = snapshot() }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        runCatching { PowerController.syncExternalState(false) }
                        runCatching { _status.value = snapshot() }
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(receiver, filter)
            screenStateReceiver = receiver
        }.onFailure {
            Log.w(TAG, "registerScreenStateSync receiver failed", it)
            return
        }
        // DisplayListener 兜底：部分 ROM 广播延迟时，Display 变化仍能纠偏；
        // 只处理 OFF/DOZE 系（灭）与 ON（亮），UNKNOWN 忽略。
        // 注意：binder 熄屏态 DM 恒报 ON，不会误触发 OFF 分支（Power-Fix-2）。
        runCatching {
            val dm = getSystemService(DisplayManager::class.java) ?: return
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    runCatching {
                        val st = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return
                        when (st) {
                            Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> {
                                PowerController.syncExternalState(false)
                                runCatching { _status.value = snapshot() }
                            }
                            Display.STATE_ON -> {
                                // DM==ON 时不盲目清缓存：binder 真黑态 DM 也是 ON。
                                // 只有缓存说黑、但系统刚报了 SCREEN_ON 广播时才由广播分支清；
                                // 此处仅当 DM 从 OFF 系回到 ON 且伴随用户点亮时做纠偏：
                                // 若缓存为黑且当前是用户可交互态，跟随一次，避免广播漏收。
                                // 保守起见：此处不清零，只记日志，纠偏完全交给广播。
                                Log.d(TAG, "displayChanged ON (keep cached blackedOut=${PowerController.isBlackedOut})")
                            }
                            else -> {}
                        }
                    }
                }
            }
            dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            screenDisplayManager = dm
            screenDisplayListener = listener
        }.onFailure {
            Log.w(TAG, "registerScreenStateSync displayListener failed", it)
        }
        Log.i(TAG, "screenStateSync registered")
    }

    private fun unregisterScreenStateSync() {
        runCatching { screenStateReceiver?.let { unregisterReceiver(it) } }
        screenStateReceiver = null
        runCatching {
            val dm = screenDisplayManager
            val li = screenDisplayListener
            if (dm != null && li != null) dm.unregisterDisplayListener(li)
        }
        screenDisplayManager = null
        screenDisplayListener = null
    }

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
        ensureServerStarted()
        ensureCaptureStarted()
        ensureInputDaemon()
        syncKeeper()
        _status.value = snapshot()
    }

    /**
     * 起 HTTP 监听（幂等，主线程调用）。
     * 含偏好读档（端口/Token）与控制闸同步；失败只记日志不抛，
     * 调用方按需看 [BlindCastServer.isRunning]（采集重试据此决定是否继续）。
     */
    private fun ensureServerStarted(): Boolean {
        val port = configuredPort()
        val token = runCatching { prefs().getString(KEY_TOKEN, "") ?: "" }.getOrDefault("")
        runCatching { syncGates() }
        BlindCastServer.init(this)
        ScreenCaptureEngine.init(this)
        BlindCastServer.setToken(token)
        BlindCastServer.port = port
        val serverOk = runCatching { BlindCastServer.start(port) }.getOrDefault(false)
        if (!serverOk) {
            Log.w(TAG, "BlindCastServer.start($port) failed", BlindCastServer.lastError)
        }
        return serverOk
    }

    /** 控制闸同步（偏好键冻结，与 SettingsRepositoryImpl 同键）。 */
    private fun syncGates() {
        val p = runCatching { prefs() }.getOrNull() ?: return
        val audioEnabled = p.getBoolean("audio_enabled", true)
        val touch = p.getBoolean("scrcpy_touch_enabled", true)
        val rightBack = p.getBoolean("scrcpy_right_back_enabled", true)
        val keyboard = p.getBoolean("scrcpy_keyboard_enabled", true)
        runCatching { AudioCaptureEngine.setAudioEnabled(audioEnabled) }
        runCatching { ScrcpyGate.sync(touch, rightBack, keyboard) }
    }

    private data class CaptureParams(val vw: Int, val vh: Int, val bitrate: Int, val fps: Int)

    /** 读采集档位（分辨率等比自适应，TouchOffset-Fix-1 语义不变）。 */
    private fun readCaptureParams(): CaptureParams {
        val p = runCatching { prefs() }.getOrNull()
        val resolution = p?.getString("video_resolution", "720P")?.takeIf { it in setOf("720P", "1080P", "原生") } ?: "720P"
        val fps = p?.getInt("video_fps", 30)?.takeIf { it == 30 || it == 60 } ?: 30
        val bitrateMbps = p?.getInt("video_bitrate_mbps", 4)?.takeIf { it in 2..8 } ?: 4
        // TouchOffset-Fix-1：竖屏等比自适应（旧 1280x720 横屏硬编码致左右黑边 + 点击右偏）。
        // 竖屏机 1080x2376 下 720P=720x1584、1080P=1080x2376、原生=物理偶数对齐；横屏机宽高互换等比。
        val (vw, vh) = runCatching {
            val m = resources.displayMetrics
            com.erl.blindcast.core.scrcpy.VideoResolution.resolve(resolution, m.widthPixels, m.heightPixels)
        }.getOrDefault(com.erl.blindcast.core.scrcpy.VideoResolution.resolve(resolution, 0, 0))
        Log.i(TAG, "[CaptureRoute] resolution=$resolution phys=${runCatching { resources.displayMetrics.widthPixels }.getOrDefault(-1)}x${runCatching { resources.displayMetrics.heightPixels }.getOrDefault(-1)} capture=${vw}x${vh}")
        return CaptureParams(vw, vh, bitrateMbps * 1_000_000, fps)
    }

    /**
     * 起串流采集（主线程调用，幂等）。
     * [captureActive]/链路运行中重复进入直接返回，避免同代际双任务抢绑；
     * 停串流由 [captureGen] 自增 + [stopPrivilegedCapture] 使旧任务自弃。
     */
    private fun ensureCaptureStarted() {
        if (captureActive || CaptureSocketLink.isRunning || CaptureSocketLink.hasVideo) {
            Log.i(TAG, "[CaptureRoute] ensureCapture skipped (already active)")
            return
        }
        captureActive = true
        val params = readCaptureParams()
        val gen = captureGen.get()
        // 本地引擎不再 App 进程直起（必吃 SecurityException 静默 false，旧根因）：
        // 只做特权链路（搬运服先起，特权建连 + 首帧等待放后台，避免阻塞主线程 ANR）。
        // Stream-Priv-2 解耦降级：HTTP 已在上方先起（UI/API/WS 可用），采集失败只记
        // captureError 进状态流（videoRunning=false），绝不碰 server；后台重试一次
        //（5s 后），仍失败就停等下次开关。
        scope.launch {
            val okFirst = runCatching { runPrivilegedCaptureBlocking(params.vw, params.vh, params.bitrate, params.fps, gen) }.getOrDefault(false)
            runCatching { _status.value = snapshot() }
            Log.i(TAG, "[CaptureRoute] boot privileged done ok=$okFirst mode=$captureMode " +
                "hasVideo=${CaptureSocketLink.hasVideo} hasAudio=${CaptureSocketLink.hasAudio}")
            if (okFirst) return@launch
            if (!isActive || !BlindCastServer.isRunning || !streamWanted || gen != captureGen.get()) {
                Log.i(TAG, "[CaptureRoute] capture failed, skip retry (stopped or superseded)")
                return@launch
            }
            Log.i(TAG, "[CaptureRoute] capture failed, retry once after 5s " +
                "(videoRunning=false, server keeps running)")
            delay(5_000L)
            if (!isActive || !BlindCastServer.isRunning || !streamWanted || gen != captureGen.get()) {
                Log.i(TAG, "[CaptureRoute] retry skipped (stopped or superseded)")
                return@launch
            }
            if (CaptureSocketLink.hasVideo) {
                runCatching { _status.value = snapshot() }
                return@launch
            }
            Log.i(TAG, "[CaptureRoute] retrying privileged capture once")
            val okRetry = runCatching { runPrivilegedCaptureBlocking(params.vw, params.vh, params.bitrate, params.fps, gen) }.getOrDefault(false)
            runCatching { _status.value = snapshot() }
            Log.i(TAG, "[CaptureRoute] retry privileged done ok=$okRetry mode=$captureMode " +
                "hasVideo=${CaptureSocketLink.hasVideo} hasAudio=${CaptureSocketLink.hasAudio}")
            if (!okRetry) {
                // 仍失败就停等下次开关：确保采集已收，不碰 server（HTTP/UI/API/WS 保持可用）。
                // 只处理本代际任务：代际已变说明用户已另起/另停，不碰新状态。
                if (gen == captureGen.get()) {
                    captureActive = false
                    runCatching { stopPrivilegedCapture() }
                    runCatching { _status.value = snapshot() }
                }
                Log.w(TAG, "[CaptureRoute] retry failed, capture stopped waiting next toggle; " +
                    "serverRunning=${BlindCastServer.isRunning}")
            }
        }
    }

    /**
     * Smooth-1 常驻输入 daemon（随服务启停）：后台 ensure，存活即复用；
     * 反控 tap/drag/down/move/up 经 daemon ack（~数十 ms），不再每次冷起 app_process。
     * 失败只记日志（ControlWsRoute 回退单次 Root→Shizuku 老路，反控不断）。
     */
    private fun ensureInputDaemon() {
        scope.launch {
            val ok = runCatching { RootInputDaemon.ensureStarted(packageName) }.getOrDefault(false)
            Log.i(TAG, "[InputDaemon] boot ensure ok=$ok")
            runCatching { _status.value = snapshot() }
        }
    }

    private fun syncKeeper() {
        val keepAlive = runCatching { prefs().getBoolean("keepalive_enabled", true) }.getOrDefault(true)
        if (keepAlive) {
            runCatching { UserActivityKeeper.start(this) }
        } else {
            runCatching { UserActivityKeeper.stop() }
        }
    }

    /**
     * 停串流采集（主线程调用，不阻塞）。
     * Root 常驻停服含 1.5s 宽限 sleep + 搬运线程 join，同步调会卡主线程，
     * 故代际自增作废旧任务后丢后台停，2s 状态轮询自动对齐快照。
     * onDestroy 专属同步版（scope 已 cancel，只能同步收）。
     */
    private fun stopCaptureAsync() {
        captureGen.incrementAndGet()
        captureActive = false
        scope.launch {
            runCatching { stopPrivilegedCapture() }
            runCatching { _status.value = snapshot() }
        }
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
     * @param gen 发起代际：等帧期间用户停串流（代际自增/期望清零）则失败不再留痕，
     *   避免“主动关”被记成“采集错”。
     */
    private fun runPrivilegedCaptureBlocking(vw: Int, vh: Int, bitrate: Int, fps: Int, gen: Int): Boolean {
        // 1. 搬运服先起（App 进程 LocalServerSocket accept，特权侧 connect）。
        val linkOk = runCatching { CaptureSocketLink.start(vw, vh, bitrate, fps) }.getOrDefault(false)
        if (!linkOk) {
            val t = IllegalStateException("搬运服启动失败：${CaptureSocketLink.errorMessage() ?: "unknown"}")
            if (streamWanted && gen == captureGen.get()) recordCaptureError(t)
            else Log.i(TAG, "[CaptureRoute] link start failed but superseded, skip error record")
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
                if (streamWanted && gen == captureGen.get()) recordCaptureError(t)
                else Log.i(TAG, "[CaptureRoute] capture bind failed but superseded, skip error record")
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
        if (streamWanted && gen == captureGen.get()) recordCaptureError(t)
        else Log.i(TAG, "[CaptureRoute] first frame timeout but superseded, skip error record")
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
