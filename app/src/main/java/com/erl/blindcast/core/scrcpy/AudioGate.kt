package com.erl.blindcast.core.scrcpy

import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局音频传输总闸（Slice 3.2）。
 *
 * ## 职责
 * 单一 `volatile` 开关 [isAudioEnabled] + 监听接口，供三处调用：
 * - App 设置页音频开关（Slice 6.2 绑定）；
 * - Web 端静音/取消静音指令（Slice 4.x `/ws/control`、Slice 6.x 联调）；
 * - `AudioCaptureEngine.setAudioEnabled`（直通本闸并唤醒采集/drain 线程）。
 *
 * ## 语义
 * - 默认开（`true`）：首装即传声，与“服务运行中即有声”直觉一致；
 * - 关闭（`false`）：引擎侧 capture/drain 双线程挂起等待，
 *   **零编码 CPU + 零网络包**（在途残帧直接丢弃）；
 * - 纯内存开关：不落盘、不接 DataStore，持久化偏好归 Slice 6.2 的
 *   `SettingsRepository`，启动时一次性 `setAudioEnabled(saved)` 同步即可；
 * - 线程安全：`@Volatile` 读写 + [CopyOnWriteArraySet] 监听，任意线程调用。
 */
object AudioGate {

    /** 音频传输总开关（volatile，跨线程可见）。 */
    @Volatile
    var isAudioEnabled: Boolean = true
        private set

    /** 开关变化监听（调用线程即触发线程，禁止耗时；抛异常被吞不扩散）。 */
    fun interface OnAudioEnabledChangedListener {
        fun onAudioEnabledChanged(enabled: Boolean)
    }

    private val listeners = CopyOnWriteArraySet<OnAudioEnabledChangedListener>()

    /**
     * 设置总开关（值未变化时直接返回，不打扰监听者）。
     * 引擎侧的线程唤醒由 `AudioCaptureEngine.setAudioEnabled` 另行负责。
     */
    fun setAudioEnabled(enabled: Boolean) {
        if (isAudioEnabled == enabled) return
        isAudioEnabled = enabled
        for (listener in listeners) {
            runCatching { listener.onAudioEnabledChanged(enabled) }
        }
    }

    /** 注册监听（重复注册自动去重）。 */
    fun addListener(listener: OnAudioEnabledChangedListener): Boolean =
        listeners.add(listener)

    /** 注销监听。 */
    fun removeListener(listener: OnAudioEnabledChangedListener): Boolean =
        listeners.remove(listener)
}
