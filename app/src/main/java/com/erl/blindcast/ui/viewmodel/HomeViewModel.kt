package com.erl.blindcast.ui.viewmodel

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.ha.HaSensorReporter
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.core.service.BlindCastForegroundService
import com.erl.blindcast.ui.screen.home.HomeUiState
import com.erl.blindcast.ui.screen.home.HwState
import com.erl.blindcast.ui.screen.home.LanState
import com.erl.blindcast.ui.screen.home.ServiceCardState
import com.erl.blindcast.ui.screen.home.SystemInfo
import com.erl.blindcast.ui.screen.home.getAppVersion
import com.erl.blindcast.ui.util.LatestVersionInfo
import com.erl.blindcast.ui.util.checkNewVersion
import java.net.NetworkInterface
import java.net.URLEncoder

class HomeViewModel : ViewModel() {

    companion object {
        /** 本地状态轮询间隔 3s（电量/内存/WiFi/IP 均为轻量同步读取）。 */
        private const val LOCAL_POLL_MS = 3_000L
    }

    private val app: Context
        get() = blindCastApp

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // 服务状态流：前台服务每 2s 推送快照，此处直接映射进 UI。
        viewModelScope.launch {
            BlindCastForegroundService.status.collect { updateFromSnapshot() }
        }
        // 本地轮询：硬件监控与 LAN 地址/Token 不在服务流里，独立刷新。
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                updateFromSnapshot()
                delay(LOCAL_POLL_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            updateFromSnapshot()
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 快捷操作（Slice 6.1）
    // ------------------------------------------------------------------

    /** 串流总服务开关（一键启动/关闭录屏编码与 HTTP 监听服务）。 */
    fun toggleService(enable: Boolean) {
        if (enable) {
            BlindCastForegroundService.start(app)
        } else {
            BlindCastForegroundService.stop(app)
        }
        viewModelScope.launch(Dispatchers.IO) { updateFromSnapshot() }
    }

    /** 立即息屏挂机：物理熄屏 + 启动 4s 喂狗（后台执行，含 Binder 调用）。 */
    fun blackoutNow() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PowerController.blackoutSuspend() }
            runCatching { UserActivityKeeper.start(app) }
            updateFromSnapshot()
        }
    }

    /** 点亮物理屏幕（后台执行，含 Binder 调用）。 */
    fun restoreScreen() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PowerController.restoreSuspend() }
            updateFromSnapshot()
        }
    }

    // ------------------------------------------------------------------
    // 状态装配
    // ------------------------------------------------------------------

    private fun buildState(): HomeUiState {
        val appVersion = getAppVersion(blindCastApp)

        return HomeUiState(
            checkUpdateEnabled = blindCastApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("check_update", true),
            latestVersionInfo = LatestVersionInfo(),
            currentAppVersionCode = appVersion.versionCode,
            systemInfo = SystemInfo(
                appVersion = "${appVersion.versionName} (${appVersion.versionCode})",
            ),
        )
    }

    /** 同步装配一次服务/LAN/硬件快照（IO 线程调用）。 */
    private fun updateFromSnapshot() {
        val svc = BlindCastForegroundService.status.value
        // 服务未运行时读引擎实时值同样有效（停止态为 -1/0），端口回退偏好值。
        val port = svc.port
            .takeIf { it in 1..65535 }
            ?: BlindCastServer.DEFAULT_PORT
        val prefs = runCatching {
            app.getSharedPreferences(
                BlindCastForegroundService.PREFS_NAME,
                Context.MODE_PRIVATE,
            )
        }.getOrNull()
        val token = prefs?.getString(BlindCastForegroundService.KEY_TOKEN, "") ?: ""
        val prefPort = prefs?.getInt(
            BlindCastForegroundService.KEY_PORT,
            BlindCastServer.DEFAULT_PORT,
        ) ?: BlindCastServer.DEFAULT_PORT
        val effectivePort = port.takeIf { svc.isRunning }
            ?: prefPort.takeIf { it in 1..65535 }
            ?: BlindCastServer.DEFAULT_PORT
        val tokenProtected = token.isNotBlank()

        val ip = readLanIp()
        val hasIp = ip != null
        val baseUrl = if (hasIp) "http://$ip:$effectivePort/" else ""
        val urlWithToken = when {
            !hasIp -> ""
            !tokenProtected -> baseUrl
            else -> baseUrl + "?token=" + runCatching {
                URLEncoder.encode(token, "UTF-8")
            }.getOrDefault(token)
        }

        val hw = readHw()
        _uiState.update {
            it.copy(
                service = ServiceCardState(
                    isRunning = svc.isRunning,
                    port = effectivePort,
                    fps = svc.fps,
                    bitrateMbps = if (svc.bitrateBps > 0) svc.bitrateBps / 1_000_000.0 else -1.0,
                    clients = svc.clients,
                    tokenProtected = tokenProtected,
                    blackedOut = svc.blackedOut,
                    lanIp = ip ?: "",
                ),
                lan = LanState(
                    ip = ip ?: "",
                    port = effectivePort,
                    url = baseUrl,
                    urlWithToken = urlWithToken,
                    hasIp = hasIp,
                ),
                hw = hw,
            )
        }
    }

    /**
     * 读取局域网 IPv4：优先 WifiManager（当前连接），回退枚举网卡首个
     * site-local 非回环 IPv4（兼容有线/USB 网络共享），均无则 null。
     */
    private fun readLanIp(): String? {
        runCatching {
            val wm = app.applicationContext.getSystemService(WifiManager::class.java)
            val raw = wm?.connectionInfo?.ipAddress ?: 0
            if (raw != 0) {
                return "${raw and 0xFF}.${raw shr 8 and 0xFF}.${raw shr 16 and 0xFF}.${raw shr 24 and 0xFF}"
            }
        }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val host = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    if (host.contains(':')) continue // 跳过 IPv6。
                    if (addr.isSiteLocalAddress) return host
                }
            }
        }
        return null
    }

    /** 读取硬件监控：电量/充电/温度（复用 HaSensorReporter）+ 内存 + WiFi 速率。 */
    private fun readHw(): HwState {
        val snap = runCatching { HaSensorReporter.readSnapshot(app) }.getOrNull()
        var availMb = -1L
        var totalMb = -1L
        runCatching {
            val am = app.applicationContext.getSystemService(ActivityManager::class.java)
            if (am != null) {
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                availMb = mem.availMem / (1024 * 1024)
                totalMb = mem.totalMem / (1024 * 1024)
            }
        }
        // WiFi 链路速率：部分 ROM 需定位权限，失败记 -1 由 UI 展示占位。
        val linkMbps = runCatching {
            val wm = app.applicationContext.getSystemService(WifiManager::class.java)
            wm?.connectionInfo?.linkSpeed ?: -1
        }.getOrDefault(-1)
        return HwState(
            batteryPercent = snap?.levelPercent ?: -1,
            charging = snap?.charging ?: false,
            temperatureC = snap?.temperatureC ?: Float.NaN,
            availRamMb = availMb,
            totalRamMb = totalMb,
            wifiLinkMbps = linkMbps,
        )
    }
}
