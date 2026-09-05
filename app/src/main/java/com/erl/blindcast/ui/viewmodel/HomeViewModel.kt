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
import com.erl.blindcast.R
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.ha.HaSensorReporter
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.core.service.BlindCastForegroundService
import com.erl.blindcast.permission.PermissionManager
import com.erl.blindcast.permission.PermissionState
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
            val preservedPermission = _uiState.value.permissionGranted
            val preservedMissing = _uiState.value.missingPermissions
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update {
                baseState.copy(
                    permissionGranted = preservedPermission,
                    missingPermissions = preservedMissing,
                )
            }
            updateFromSnapshot()
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    /**
     * Fix-Home-1：Hero 三态判定收敛入口。
     * 由 HomeScreen 把 PermissionState.requiredGranted 同步进来，
     * UI 层只读 [HomeUiState.permissionGranted] + service.isRunning，不再直读 PermissionState。
     * Fix-Home-2：保留此同步（避免回退），但以 3s 轮询直读值为准；同值不覆写避免闪烁。
     */
    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { current ->
            if (current.permissionGranted == granted) current else current.copy(permissionGranted = granted)
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

    /**
     * 立即息屏挂机：经 Shizuku 特权路由物理熄屏 + 启动 4s 喂狗（后台执行，含跨进程绑定）。
     *
     * Priv-Bridge-2 应用内一键授权：daemon 在跑但未授权时自动走一次
     * [PrivilegedBridge.awaitPermission]（弹系统授权框），允许则继续熄屏，
     * 拒绝/超时才 Toast 报错；daemon 没跑则直接报“去启动 Shizuku”。
     */
    fun blackoutNow() {
        viewModelScope.launch(Dispatchers.IO) {
            if (PrivilegedBridge.isShizukuRunning() && !PrivilegedBridge.isPrivilegedGranted()) {
                runCatching { PrivilegedBridge.awaitPermission() }
            }
            val ok = try {
                PowerController.blackoutRouted(app.packageName)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                runCatching { UserActivityKeeper.start(app) }
            } else {
                // Priv-Bridge-1：未授权/绑定失败只记文案弹 Toast，不抛、不动大结构。
                publishActionError(PowerController.lastError?.message ?: "熄屏失败")
            }
            updateFromSnapshot()
        }
    }

    /** 点亮物理屏幕（后台执行，经 Shizuku 特权路由，失败弹 Toast）。 */
    fun restoreScreen() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                PowerController.restoreRouted(app.packageName)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                publishActionError(PowerController.lastError?.message ?: "点亮失败")
            }
            updateFromSnapshot()
        }
    }

    /** Priv-Bridge-1：发布一次操作失败文案（seq 自增，相同文案可重复触发 Toast）。 */
    private fun publishActionError(message: String) {
        _uiState.update { it.copy(actionError = message, actionErrorSeq = it.actionErrorSeq + 1) }
    }

    /** Priv-Bridge-1：Toast 展示后消费，防重弹。 */
    fun consumeActionError() {
        _uiState.update { if (it.actionError == null) it else it.copy(actionError = null) }
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
        // Fix-Home-2：权限态由 ViewModel 轮询直读自愈，不依赖跨组件同步时序。
        val permSnapshot = runCatching { PermissionManager.readState(app) }.getOrNull()
        val freshGranted = permSnapshot?.requiredGranted
        val freshMissing = permSnapshot?.let { buildMissingLabels(it) }
        _uiState.update { current ->
            val granted = freshGranted ?: current.permissionGranted
            val missing = freshMissing ?: current.missingPermissions
            // 同值不覆写引用，避免缺项清单相同引发冗余重组闪烁。
            val effectiveMissing =
                if (current.missingPermissions == missing) current.missingPermissions else missing
            current.copy(
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
                permissionGranted = granted,
                missingPermissions = effectiveMissing,
            )
        }
    }

    /** Fix-Home-2：按门禁缺项装配中文名清单（文件/通知/麦克风/电池白名单）。 */
    private fun buildMissingLabels(state: PermissionState): List<String> {
        val missing = ArrayList<String>(4)
        if (!state.storage) {
            missing.add(runCatching { app.getString(R.string.blindcast_home_missing_storage) }.getOrDefault("文件"))
        }
        if (!state.notification) {
            missing.add(runCatching { app.getString(R.string.blindcast_home_missing_notification) }.getOrDefault("通知"))
        }
        if (!state.microphone) {
            missing.add(runCatching { app.getString(R.string.blindcast_home_missing_microphone) }.getOrDefault("麦克风"))
        }
        if (!state.batteryWhitelist) {
            missing.add(runCatching { app.getString(R.string.blindcast_home_missing_battery) }.getOrDefault("电池白名单"))
        }
        return missing
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
