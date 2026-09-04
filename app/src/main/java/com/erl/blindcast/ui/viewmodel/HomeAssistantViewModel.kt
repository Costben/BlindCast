package com.erl.blindcast.ui.viewmodel

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.ha.HaDiscoveryPayload
import com.erl.blindcast.core.ha.HaMqttClient
import com.erl.blindcast.core.ha.HaMqttConfig
import com.erl.blindcast.core.ha.HaStatePublisher
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.data.repository.SettingsRepository
import com.erl.blindcast.data.repository.SettingsRepositoryImpl
import com.erl.blindcast.ui.screen.homeassistant.HaConnectionState
import com.erl.blindcast.ui.screen.homeassistant.HomeAssistantUiState
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Slice 6.2 HA 页 ViewModel：MQTT 配置持久化 + startHaStack 测试重上报。
 *
 * - 偏好经 SettingsRepository（SharedPreferences `settings`）持久化；
 * - [testConnection] 后台调 HaStatePublisher.startHaStack，状态回显 Hero 卡；
 * - 未配置（开关关 / 地址空）→ UNCONFIGURED；已连 → CONNECTED；
 *   使能+已配但未连 → CONNECTING/FAILED（Paho 指数退避后台重连中）。
 */
class HomeAssistantViewModel(
    private val repo: SettingsRepository,
) : ViewModel() {

    /** 显式零参构造：保证 `viewModel()` 反射实例化（Kotlin 默认参不生成 JVM 零参重载）。 */
    constructor() : this(SettingsRepositoryImpl())

    private val _uiState = MutableStateFlow(HomeAssistantUiState())
    val uiState: StateFlow<HomeAssistantUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val enabled = repo.haEnabled
        val host = repo.haBrokerHost
        val port = repo.haBrokerPort
        val username = repo.haUsername
        val password = repo.haPassword
        val serverPort = repo.serverPort
        val token = repo.streamToken
        val connected = HaMqttClient.isConnected
        val (state, detail) = classify(enabled, host, connected, HaMqttClient.lastError?.message)
        _uiState.update {
            it.copy(
                enabled = enabled,
                brokerHost = host,
                brokerPort = port,
                username = username,
                password = password,
                connectionState = state,
                statusDetail = if (it.isTesting) it.statusDetail else detail,
                deviceName = HaDiscoveryPayload.DEVICE_NAME,
                entityId = "switch.${HaDiscoveryPayload.DEFAULT_NODE_ID}_screen",
                restSnippet = buildRestSnippet(serverPort, token),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        repo.haEnabled = enabled
        _uiState.update { it.copy(enabled = enabled) }
        if (!enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { HaStatePublisher.stopHaStack() }
                refreshOnIo()
            }
        } else {
            // 打开联动后若已配好地址，直接起栈（免再点一次测试）；未配置则仅回显 UNCONFIGURED。
            if (repo.haBrokerHost.isNotBlank()) {
                testConnection()
            } else {
                refresh()
            }
        }
    }

    fun setHost(host: String) {
        val v = host.trim()
        repo.haBrokerHost = v
        _uiState.update { it.copy(brokerHost = v) }
        refreshConnectionOnly()
    }

    fun setPort(port: Int) {
        val v = port.coerceIn(1, 65535)
        repo.haBrokerPort = v
        _uiState.update { it.copy(brokerPort = v) }
        refreshConnectionOnly()
    }

    fun setUsername(name: String) {
        val v = name.trim()
        repo.haUsername = v
        _uiState.update { it.copy(username = v) }
    }

    fun setPassword(pwd: String) {
        repo.haPassword = pwd
        _uiState.update { it.copy(password = pwd) }
    }

    /**
     * 测试连接并重上报 Discovery（后台执行，禁止主线程直调 connect）。
     * 成功 → CONNECTED；失败 → FAILED + 错误详情（后台仍按退避自动重试）。
     */
    fun testConnection() {
        val host = repo.haBrokerHost.trim()
        if (host.isBlank()) {
            _uiState.update {
                it.copy(
                    connectionState = HaConnectionState.UNCONFIGURED,
                    statusDetail = "请先填写 MQTT Broker 地址",
                    isTesting = false,
                )
            }
            return
        }
        _uiState.update { it.copy(isTesting = true, connectionState = HaConnectionState.CONNECTING, statusDetail = "") }
        viewModelScope.launch(Dispatchers.IO) {
            val port = repo.haBrokerPort
            val username = repo.haUsername.trim().ifBlank { null }
            val password = repo.haPassword.ifEmpty { null }
            val config = HaMqttConfig(
                host = host,
                port = port,
                username = username,
                password = password,
            )
            val app = blindCastApp.applicationContext
            val discoveryCtx = HaDiscoveryPayload.Context(
                nodeId = HaDiscoveryPayload.DEFAULT_NODE_ID,
                webBaseUrl = readWebBaseUrl(app),
                token = repo.streamToken,
            )
            val ok = runCatching { HaStatePublisher.startHaStack(app, config, discoveryCtx) }.getOrDefault(false)
            val err = HaMqttClient.lastError?.message.orEmpty()
            _uiState.update {
                it.copy(
                    isTesting = false,
                    connectionState = if (ok) HaConnectionState.CONNECTED else HaConnectionState.FAILED,
                    statusDetail = if (ok) {
                        "Discovery 已重上报（${discoveryCtx.nodeId}）"
                    } else {
                        err.ifBlank { "连接失败，后台自动重连中" }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun refreshConnectionOnly() {
        val s = _uiState.value
        val (state, detail) = classify(s.enabled, s.brokerHost, HaMqttClient.isConnected, HaMqttClient.lastError?.message)
        _uiState.update { it.copy(connectionState = state, statusDetail = detail) }
    }

    private fun refreshOnIo() {
        val enabled = repo.haEnabled
        val host = repo.haBrokerHost
        val (state, detail) = classify(enabled, host, HaMqttClient.isConnected, HaMqttClient.lastError?.message)
        _uiState.update {
            it.copy(
                enabled = enabled,
                brokerHost = host,
                brokerPort = repo.haBrokerPort,
                username = repo.haUsername,
                password = repo.haPassword,
                connectionState = state,
                statusDetail = detail,
                restSnippet = buildRestSnippet(repo.serverPort, repo.streamToken),
            )
        }
    }

    private fun classify(enabled: Boolean, host: String, connected: Boolean, lastErr: String?): Pair<String, String> {
        if (!enabled || host.isBlank()) return HaConnectionState.UNCONFIGURED to ""
        if (connected) return HaConnectionState.CONNECTED to ""
        return if (lastErr.isNullOrBlank()) {
            HaConnectionState.CONNECTING to "后台重连中…"
        } else {
            HaConnectionState.FAILED to lastErr
        }
    }

    private fun readWebBaseUrl(app: Context): String {
        val ip = readLanIp(app) ?: return ""
        val port = repo.serverPort.takeIf { it in 1..65535 } ?: BlindCastServer.DEFAULT_PORT
        return "http://$ip:$port"
    }

    private fun readLanIp(app: Context): String? {
        runCatching {
            val wm = app.getSystemService(WifiManager::class.java)
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
                    val h = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    if (h.contains(':')) continue
                    if (addr.isSiteLocalAddress) return h
                }
            }
        }
        return null
    }

    companion object {
        /**
         * 免 MQTT 备选：HA `configuration.yaml` REST 片段（复制即用，IP 按需替换为手机局域网地址）。
         * 开关经 POST /api/screen 切换，传感器经 GET /api/status 轮询。
         */
        fun buildRestSnippet(serverPort: Int, token: String): String {
            val port = serverPort.takeIf { it in 1..65535 } ?: BlindCastServer.DEFAULT_PORT
            val tokenLine = if (token.isBlank()) {
                "    # 未设 Token：免密直通；若 App 内设置了 Token，下方 URL 追加 ?token=你的Token"
            } else {
                "    # 已按当前 Token 预填（更换 Token 后需重新复制）"
            }
            val tokenSuffix = if (token.isBlank()) "" else "?token=$token"
            return buildString {
                appendLine("# BlindCast REST 备选（免 MQTT）：粘贴进 Home Assistant configuration.yaml 后重启 HA")
                appendLine("# 把 192.168.x.x 换成手机局域网 IP（主页局域网卡片可见），端口 $port")
                appendLine("switch:")
                appendLine("  - platform: rest")
                appendLine("    name: BlindCast Screen")
                appendLine("    resource: http://192.168.x.x:$port/api/screen$tokenSuffix")
                appendLine("    body_on: '{\"action\":\"off\"}'")
                appendLine("    body_off: '{\"action\":\"on\"}'")
                appendLine("    is_on_template: \"{{ value_json.blackedOut == false }}\"")
                appendLine("    headers:")
                appendLine("      Content-Type: application/json")
                appendLine(tokenLine)
                appendLine("sensor:")
                appendLine("  - platform: rest")
                appendLine("    name: BlindCast Status")
                appendLine("    resource: http://192.168.x.x:$port/api/status$tokenSuffix")
                appendLine("    value_template: \"{{ value_json.batteryLevel }}%\"")
                appendLine("    json_attributes:")
                appendLine("      - batteryTempC")
                appendLine("      - charging")
                appendLine("      - blackedOut")
            }.trimEnd()
        }
    }
}
