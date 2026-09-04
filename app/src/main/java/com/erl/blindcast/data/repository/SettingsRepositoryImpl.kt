package com.erl.blindcast.data.repository

import android.content.Context
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.ui.UiMode

class SettingsRepositoryImpl : SettingsRepository {

    private val prefs by lazy {
        blindCastApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    override var uiMode: String
        get() = prefs.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE
        set(value) = prefs.edit { putString("ui_mode", value) }

    override var checkUpdate: Boolean
        get() = prefs.getBoolean("check_update", true)
        set(value) = prefs.edit { putBoolean("check_update", value) }

    override var themeMode: Int
        get() = prefs.getInt("color_mode", 0)
        set(value) = prefs.edit { putInt("color_mode", value) }

    override var miuixMonet: Boolean
        get() = prefs.getBoolean("miuix_monet", false)
        set(value) = prefs.edit { putBoolean("miuix_monet", value) }

    override var keyColor: Int
        get() = prefs.getInt("key_color", 0)
        set(value) = prefs.edit { putInt("key_color", value) }

    override var colorStyle: String
        get() = prefs.getString("color_style", PaletteStyle.TonalSpot.name) ?: PaletteStyle.TonalSpot.name
        set(value) = prefs.edit { putString("color_style", value) }

    override var colorSpec: String
        get() = prefs.getString("color_spec", ColorSpec.SpecVersion.Default.name) ?: ColorSpec.SpecVersion.Default.name
        set(value) = prefs.edit { putString("color_spec", value) }

    override var enablePredictiveBack: Boolean
        get() = prefs.getBoolean("enable_predictive_back", false)
        set(value) = prefs.edit { putBoolean("enable_predictive_back", value) }

    override var enableBlur: Boolean
        get() = prefs.getBoolean("enable_blur", false)
        set(value) = prefs.edit { putBoolean("enable_blur", value) }

    override var enableFloatingBottomBar: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar", true)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar", value) }

    override var enableFloatingBottomBarBlur: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar_blur", true)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar_blur", value) }

    override var pageScale: Float
        get() = prefs.getFloat("page_scale", 1.0f)
        set(value) = prefs.edit { putFloat("page_scale", value) }

    override var haEnabled: Boolean
        get() = prefs.getBoolean("ha_enabled", false)
        set(value) = prefs.edit { putBoolean("ha_enabled", value) }

    override var haBrokerHost: String
        get() = prefs.getString("ha_broker_host", "") ?: ""
        set(value) = prefs.edit { putString("ha_broker_host", value) }

    override var haBrokerPort: Int
        get() = prefs.getInt("ha_broker_port", 1883).let { if (it in 1..65535) it else 1883 }
        set(value) = prefs.edit { putInt("ha_broker_port", value.coerceIn(1, 65535)) }

    override var haUsername: String
        get() = prefs.getString("ha_username", "") ?: ""
        set(value) = prefs.edit { putString("ha_username", value) }

    override var haPassword: String
        get() = prefs.getString("ha_password", "") ?: ""
        set(value) = prefs.edit { putString("ha_password", value) }

    override var streamToken: String
        get() = prefs.getString("stream_token", "") ?: ""
        set(value) = prefs.edit { putString("stream_token", value) }

    override var serverPort: Int
        get() = prefs.getInt("server_port", 8888).let { if (it in 1..65535) it else 8888 }
        set(value) = prefs.edit { putInt("server_port", value.coerceIn(1, 65535)) }

    override var videoResolution: String
        get() = prefs.getString("video_resolution", "720P")?.takeIf { it in setOf("720P", "1080P", "原生") } ?: "720P"
        set(value) = prefs.edit { putString("video_resolution", value.takeIf { it in setOf("720P", "1080P", "原生") } ?: "720P") }

    override var videoFps: Int
        get() = prefs.getInt("video_fps", 30).let { if (it in listOf(30, 60)) it else 30 }
        set(value) = prefs.edit { putInt("video_fps", value) }

    override var videoBitrateMbps: Int
        get() = prefs.getInt("video_bitrate_mbps", 4).let { if (it in 2..8) it else 4 }
        set(value) = prefs.edit { putInt("video_bitrate_mbps", value.coerceIn(2, 8)) }

    override var audioEnabled: Boolean
        get() = prefs.getBoolean("audio_enabled", true)
        set(value) = prefs.edit { putBoolean("audio_enabled", value) }

    override var scrcpyTouchEnabled: Boolean
        get() = prefs.getBoolean("scrcpy_touch_enabled", true)
        set(value) = prefs.edit { putBoolean("scrcpy_touch_enabled", value) }

    override var scrcpyRightBackEnabled: Boolean
        get() = prefs.getBoolean("scrcpy_right_back_enabled", true)
        set(value) = prefs.edit { putBoolean("scrcpy_right_back_enabled", value) }

    override var scrcpyKeyboardEnabled: Boolean
        get() = prefs.getBoolean("scrcpy_keyboard_enabled", true)
        set(value) = prefs.edit { putBoolean("scrcpy_keyboard_enabled", value) }

    override var blackoutMode: String
        get() = prefs.getString("blackout_mode", "hardware")?.takeIf { it == "hardware" || it == "overlay" } ?: "hardware"
        set(value) = prefs.edit { putString("blackout_mode", if (value == "overlay") "overlay" else "hardware") }

    override var keepAliveEnabled: Boolean
        get() = prefs.getBoolean("keepalive_enabled", true)
        set(value) = prefs.edit { putBoolean("keepalive_enabled", value) }
}
