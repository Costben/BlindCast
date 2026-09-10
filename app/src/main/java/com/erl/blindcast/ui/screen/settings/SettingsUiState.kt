package com.erl.blindcast.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.erl.blindcast.ui.UiMode

@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    val checkUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.Default.name,
    val enablePredictiveBack: Boolean = false,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val pageScale: Float = 1.0f,
    // ---- Slice 6.2 BlindCast 全量绑定 ----
    val videoResolution: String = "720P",
    val videoFps: Int = 30,
    val videoBitrateMbps: Int = 4,
    val audioEnabled: Boolean = true,
    val streamToken: String = "",
    val serverPort: Int = 8888,
    val scrcpyTouchEnabled: Boolean = true,
    val scrcpyRightBackEnabled: Boolean = true,
    val scrcpyKeyboardEnabled: Boolean = true,
    val blackoutMode: String = "hardware",
    val keepAliveEnabled: Boolean = true,
    val bootStartEnabled: Boolean = true,
)

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenAbout: () -> Unit,
    // ---- Slice 6.2 ----
    val onSetResolutionIndex: (Int) -> Unit = {},
    val onSetFpsIndex: (Int) -> Unit = {},
    val onSetBitrateIndex: (Int) -> Unit = {},
    val onSetAudioEnabled: (Boolean) -> Unit = {},
    val onSetToken: (String) -> Unit = {},
    val onSetServerPort: (Int) -> Unit = {},
    val onSetTouchEnabled: (Boolean) -> Unit = {},
    val onSetRightBackEnabled: (Boolean) -> Unit = {},
    val onSetKeyboardEnabled: (Boolean) -> Unit = {},
    val onSetBlackoutIndex: (Int) -> Unit = {},
    val onSetKeepAlive: (Boolean) -> Unit = {},
    val onSetBootStart: (Boolean) -> Unit = {},
)
