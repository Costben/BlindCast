package com.erl.blindcast.ui.screen.homeassistant

import androidx.compose.runtime.Immutable

/** HA 连接态（Hero 卡三态 + 失败回显，失败仍走重连退避故 UI 归一为重连中+错误详情）。 */
object HaConnectionState {
    const val UNCONFIGURED = "unconfigured"
    const val CONNECTING = "connecting"
    const val CONNECTED = "connected"
    const val FAILED = "failed"
}

@Immutable
data class HomeAssistantUiState(
    val enabled: Boolean = false,
    val brokerHost: String = "",
    val brokerPort: Int = 1883,
    val username: String = "",
    val password: String = "",
    val connectionState: String = HaConnectionState.UNCONFIGURED,
    val statusDetail: String = "",
    val isTesting: Boolean = false,
    val deviceName: String = "BlindCast",
    val entityId: String = "switch.blindcast_screen",
    val restSnippet: String = "",
)

@Immutable
data class HomeAssistantActions(
    val onToggleEnabled: (Boolean) -> Unit = {},
    val onHostChange: (String) -> Unit = {},
    val onPortChange: (Int) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onCopyRest: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
)
