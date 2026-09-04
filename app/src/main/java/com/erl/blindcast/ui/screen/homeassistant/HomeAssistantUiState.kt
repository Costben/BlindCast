package com.erl.blindcast.ui.screen.homeassistant

import androidx.compose.runtime.Immutable

@Immutable
data class HomeAssistantUiState(
    val enabled: Boolean = false,
    val connectionStatus: String = "Unconfigured",
    val deviceName: String = "BlindCast",
    // NOTE(Slice 1.2): skeleton only. Real MQTT fields (broker/port/auth/topics)
    // and Discovery payloads land in Slice 5.x; persistence bindings in Slice 6.2.
)

@Immutable
data class HomeAssistantActions(
    val onTestConnection: () -> Unit = {},
    val onToggleEnabled: (Boolean) -> Unit = {},
)
