package com.erl.blindcast.core.scrcpy

/**
 * scrcpy 反向控制三开关内存门（Slice 6.2）。
 *
 * - [isTouchEnabled] 总闸：关闭即拒绝全部 down/move/up/click/key/text；
 * - [isRightBackEnabled] 右键 Back：仅门控 click-right；
 * - [isKeyboardEnabled] 键盘注入：仅门控 key/text；
 * - 纯内存：持久化归 SettingsRepository（`scrcpy_*` 三键），启动/设置页一次性 sync；
 * - [ControlWsRoute] 每次分发前查闸，线程安全 volatile。
 */
object ScrcpyGate {

    @Volatile
    var isTouchEnabled: Boolean = true
        private set

    @Volatile
    var isRightBackEnabled: Boolean = true
        private set

    @Volatile
    var isKeyboardEnabled: Boolean = true
        private set

    fun setTouchEnabled(enabled: Boolean) {
        isTouchEnabled = enabled
    }

    fun setRightBackEnabled(enabled: Boolean) {
        isRightBackEnabled = enabled
    }

    fun setKeyboardEnabled(enabled: Boolean) {
        isKeyboardEnabled = enabled
    }

    fun sync(touch: Boolean, rightBack: Boolean, keyboard: Boolean) {
        isTouchEnabled = touch
        isRightBackEnabled = rightBack
        isKeyboardEnabled = keyboard
    }
}
