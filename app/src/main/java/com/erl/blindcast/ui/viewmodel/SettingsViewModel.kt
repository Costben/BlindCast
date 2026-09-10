package com.erl.blindcast.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.scrcpy.AudioCaptureEngine
import com.erl.blindcast.core.scrcpy.ScrcpyGate
import com.erl.blindcast.core.scrcpy.ScreenCaptureEngine
import com.erl.blindcast.data.repository.SettingsRepository
import com.erl.blindcast.data.repository.SettingsRepositoryImpl
import com.erl.blindcast.ui.screen.settings.SettingsUiState
import com.erl.blindcast.ui.theme.ColorMode

class SettingsViewModel(
    private val repo: SettingsRepository,
) : ViewModel() {

    /** 显式零参构造：保证 `viewModel()` 反射实例化。 */
    constructor() : this(SettingsRepositoryImpl())

    companion object {
        val RESOLUTIONS = listOf("720P", "1080P", "原生")
        val FPS_OPTIONS = listOf(30, 60)
        val BITRATE_MBPS_OPTIONS = listOf(2, 3, 4, 5, 6, 7, 8)
        val BLACKOUT_MODES = listOf("hardware", "overlay")

        // TouchOffset-Fix-1：竖屏等比（旧横屏 1280x720/1920x1080 硬编码致黑边+触控右偏）。
        // 竖屏 1080x2376 下 720P=720x1584、1080P=1080x2376；横屏机宽高互换等比。
        fun resolutionToSize(label: String, fallbackW: Int = 720, fallbackH: Int = 1280): Pair<Int, Int> {
            return runCatching {
                val m = blindCastApp.resources.displayMetrics
                com.erl.blindcast.core.scrcpy.VideoResolution.resolve(label, m.widthPixels, m.heightPixels, fallbackW, fallbackH)
            }.getOrDefault(com.erl.blindcast.core.scrcpy.VideoResolution.resolve(label, 0, 0, fallbackW, fallbackH))
        }
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val checkUpdate = repo.checkUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val pageScale = repo.pageScale
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val uiMode = repo.uiMode

            val videoResolution = repo.videoResolution
            val videoFps = repo.videoFps
            val videoBitrateMbps = repo.videoBitrateMbps
            val audioEnabled = repo.audioEnabled
            val streamToken = repo.streamToken
            val serverPort = repo.serverPort
            val touch = repo.scrcpyTouchEnabled
            val rightBack = repo.scrcpyRightBackEnabled
            val keyboard = repo.scrcpyKeyboardEnabled
            val blackoutMode = repo.blackoutMode
            val keepAlive = repo.keepAliveEnabled
            val bootStart = repo.bootStartEnabled

            // 内存门与持久化对齐（幂等，可重复调用）。
            runCatching { AudioCaptureEngine.setAudioEnabled(audioEnabled) }
            runCatching { ScrcpyGate.sync(touch, rightBack, keyboard) }

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    pageScale = pageScale,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    videoResolution = videoResolution,
                    videoFps = videoFps,
                    videoBitrateMbps = videoBitrateMbps,
                    audioEnabled = audioEnabled,
                    streamToken = streamToken,
                    serverPort = serverPort,
                    scrcpyTouchEnabled = touch,
                    scrcpyRightBackEnabled = rightBack,
                    scrcpyKeyboardEnabled = keyboard,
                    blackoutMode = blackoutMode,
                    keepAliveEnabled = keepAlive,
                    bootStartEnabled = bootStart,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 画质卡：持久化 + 运行中即时重配 ScreenCaptureEngine
    // ------------------------------------------------------------------

    fun setResolutionIndex(index: Int) {
        val v = RESOLUTIONS.getOrNull(index) ?: return
        repo.videoResolution = v
        _uiState.update { it.copy(videoResolution = v) }
        reconfigureEngine()
    }

    fun setFpsIndex(index: Int) {
        val v = FPS_OPTIONS.getOrNull(index) ?: return
        repo.videoFps = v
        _uiState.update { it.copy(videoFps = v) }
        reconfigureEngine()
    }

    fun setBitrateIndex(index: Int) {
        val v = BITRATE_MBPS_OPTIONS.getOrNull(index) ?: return
        repo.videoBitrateMbps = v
        _uiState.update { it.copy(videoBitrateMbps = v) }
        reconfigureEngine()
    }

    fun setAudioEnabled(enabled: Boolean) {
        repo.audioEnabled = enabled
        _uiState.update { it.copy(audioEnabled = enabled) }
        runCatching { AudioCaptureEngine.setAudioEnabled(enabled) }
    }

    private fun reconfigureEngine() {
        if (!ScreenCaptureEngine.isRunning) return
        val s = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val (w, h) = resolutionToSize(s.videoResolution)
            val bitrate = (s.videoBitrateMbps.coerceIn(2, 8)) * 1_000_000
            val fps = if (s.videoFps == 60) 60 else 30
            runCatching { ScreenCaptureEngine.start(w, h, bitrate, fps) }
        }
    }

    // ------------------------------------------------------------------
    // 安全卡：Token/端口持久化（修改提示重启服务生效，不热重启）
    // ------------------------------------------------------------------

    fun setStreamToken(token: String) {
        repo.streamToken = token
        _uiState.update { it.copy(streamToken = token) }
    }

    fun setServerPort(port: Int) {
        val v = port.coerceIn(1, 65535)
        repo.serverPort = v
        _uiState.update { it.copy(serverPort = v) }
    }

    // ------------------------------------------------------------------
    // scrcpy 卡：持久化 + 内存门即时生效
    // ------------------------------------------------------------------

    fun setTouchEnabled(enabled: Boolean) {
        repo.scrcpyTouchEnabled = enabled
        _uiState.update { it.copy(scrcpyTouchEnabled = enabled) }
        runCatching { ScrcpyGate.setTouchEnabled(enabled) }
    }

    fun setRightBackEnabled(enabled: Boolean) {
        repo.scrcpyRightBackEnabled = enabled
        _uiState.update { it.copy(scrcpyRightBackEnabled = enabled) }
        runCatching { ScrcpyGate.setRightBackEnabled(enabled) }
    }

    fun setKeyboardEnabled(enabled: Boolean) {
        repo.scrcpyKeyboardEnabled = enabled
        _uiState.update { it.copy(scrcpyKeyboardEnabled = enabled) }
        runCatching { ScrcpyGate.setKeyboardEnabled(enabled) }
    }

    // ------------------------------------------------------------------
    // 息屏保活卡：模式持久化 + 喂狗开关即时启停
    // ------------------------------------------------------------------

    fun setBlackoutIndex(index: Int) {
        val v = BLACKOUT_MODES.getOrNull(index) ?: return
        repo.blackoutMode = v
        _uiState.update { it.copy(blackoutMode = v) }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        repo.keepAliveEnabled = enabled
        _uiState.update { it.copy(keepAliveEnabled = enabled) }
        if (enabled) {
            runCatching { UserActivityKeeper.start(blindCastApp.applicationContext) }
        } else {
            runCatching { UserActivityKeeper.stop() }
        }
    }

    fun setBootStartEnabled(enabled: Boolean) {
        repo.bootStartEnabled = enabled
        _uiState.update { it.copy(bootStartEnabled = enabled) }
    }

    // ------------------------------------------------------------------
    // 原有外观/主题绑定（保持不动）
    // ------------------------------------------------------------------

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        val newThemeMode = when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setThemeMode(mode: Int) {
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }
}
