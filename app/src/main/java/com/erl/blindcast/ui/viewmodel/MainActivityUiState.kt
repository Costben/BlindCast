package com.erl.blindcast.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val uiMode: UiMode,
)
