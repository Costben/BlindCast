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
import com.erl.blindcast.core.blackout.EmergencyRecovery
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
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

/**
 * 常驻前台保活服务（Slice 6.1 · MVP.md 第四章 core/service）。
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

        /** 当前快照：运行态读各引擎实时值，停止态读偏好端口。 */
        private fun snapshot(): ServiceStatus {
            val running = BlindCastServer.isRunning
            val port = BlindCastServer.actualPort
                .takeIf { running && it > 0 }
                ?: BlindCastServer.port
            return ServiceStatus(
                isRunning = running,
                port = port,
                fps = ScreenCaptureEngine.currentFps,
                bitrateBps = ScreenCaptureEngine.currentBitrate,
                clients = StreamWsRoute.sessionCount + ControlWsRoute.sessionCount,
                blackedOut = PowerController.isBlackedOut,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundInternal()
        acquireLocks()
        bootStack()
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
        // 粘性重启（系统杀死后拉起）：确保协议栈处于启动态。
        if (!BlindCastServer.isRunning) bootStack()
        _status.value = snapshot()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        // 逆序回收：先停喂狗与采集，再停监听，最后熔断。
        runCatching { UserActivityKeeper.stop() }
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
        val port = configuredPort()
        val token = runCatching { prefs().getString(KEY_TOKEN, "") ?: "" }.getOrDefault("")
        // Slice 6.2 偏好：画质/音频/scrcpy/保活（缺键回退默认，与 SettingsRepositoryImpl 一致）。
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
        val videoOk = runCatching {
            ScreenCaptureEngine.start(this, vw, vh, bitrateMbps * 1_000_000, fps)
        }.getOrDefault(false)
        if (!videoOk) {
            Log.w(TAG, "ScreenCaptureEngine.start failed (no privilege?)", ScreenCaptureEngine.lastError)
        }
        val audioOk = runCatching { AudioCaptureEngine.start() }.getOrDefault(false)
        if (!audioOk) {
            Log.w(TAG, "AudioCaptureEngine.start failed (no privilege?)", AudioCaptureEngine.lastError)
        }
        if (keepAlive) {
            runCatching { UserActivityKeeper.start(this) }
        } else {
            runCatching { UserActivityKeeper.stop() }
        }
        _status.value = snapshot()
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

    private fun startForegroundInternal() {
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }
}
