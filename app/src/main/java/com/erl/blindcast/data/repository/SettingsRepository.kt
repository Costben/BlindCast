package com.erl.blindcast.data.repository

interface SettingsRepository {
    var uiMode: String
    var checkUpdate: Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableBlur: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var pageScale: Float

    // ---- Slice 6.2: HA MQTT 联动（与 HaStatePublisher.startHaStack 对接） ----
    var haEnabled: Boolean
    var haBrokerHost: String
    var haBrokerPort: Int
    var haUsername: String
    var haPassword: String

    // ---- Slice 6.2: 安全（键名与 BlindCastForegroundService.KEY_TOKEN/KEY_PORT 冻结一致） ----
    var streamToken: String
    var serverPort: Int

    // ---- Slice 6.2: 画质（分辨率 720P/1080P/原生 + 帧率 + 码率Mbps） ----
    var videoResolution: String
    var videoFps: Int
    var videoBitrateMbps: Int
    var audioEnabled: Boolean

    // ---- Slice 6.2: scrcpy 反向控制 ----
    var scrcpyTouchEnabled: Boolean
    var scrcpyRightBackEnabled: Boolean
    var scrcpyKeyboardEnabled: Boolean

    // ---- Slice 6.2: 息屏保活（hardware=优先硬件物理熄屏 / overlay=降级全黑遮罩预留） ----
    var blackoutMode: String
    var keepAliveEnabled: Boolean
    var bootStartEnabled: Boolean
}
