// Ported from Aliothmoon/MAA-Meow (AGPL-3.0, main 2026-09-05)，逻辑未改，仅包名/日志/按键注入适配。
// 上游原文：app/src/main/java/com/aliothmoon/maameow/remote/internal/WakeUnlockController.kt
// 适配：包名 com.aliothmoon.maameow→com.erl.blindcast；Ln→BlindCast TAG 日志（消息体保留 "WakeUnlock:" 前缀，逻辑 1:1）；
// 按键注入调本项目既有 TouchInjector（不移植 maa/InputControlUtils.java）；
// 手势 unlockWithGesture 依赖 UnlockGesture/ScreenGeometry/UnlockGestureReplay 体量大，保留签名内部记 TODO 返回 GESTURE_EMPTY；
// ensureScreenOn/ensureScreenOff 由上游 private 改为 public（仅可见性适配，供特权侧接线调用，逻辑未改）。
package com.erl.blindcast.core.blackout.meow

import android.util.Log
import android.view.KeyEvent
import com.erl.blindcast.core.scrcpy.TouchInjector

/** 唤醒/解锁/锁屏；提权进程内完成，凭证支持纯数字 PIN 与录制手势 */
object WakeUnlockController {

    private const val LOG_TAG = "BlindCast"
    private const val TAG = "WakeUnlock"

    private const val SCREEN_ON_TIMEOUT_MS = 5_000L
    private const val KEY_WAKE_TIMEOUT_MS = 2_000L
    private const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L
    private const val BOUNCER_SETTLE_MS = 1_200L
    private const val DIGIT_GAP_MS = 50L

    /** 手势回放前等锁屏首屏稳定；不弹 bouncer，比 PIN 那条路要短 */
    private const val GESTURE_SETTLE_MS = 800L

    /** 测试：上锁/息屏后等待系统稳定再解锁 */
    private const val LOCK_SETTLE_MS = 500L
    private const val SCREEN_OFF_TIMEOUT_MS = 3_000L

    /**
     * 设置页自测：先 [lockAndSleep]，等待 [LOCK_SETTLE_MS] 后再 [unlock]
     * 整段在提权进程内完成，避免息屏后 App 侧协程被挂起
     */
    fun testUnlock(credential: String): Int {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return lockCode
        Log.i(LOG_TAG, "$TAG: locked for test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        return unlock(credential)
    }

    /** 设置页自测：手势版 */
    fun testUnlockGesture(gestureJson: String): Int {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return lockCode
        Log.i(LOG_TAG, "$TAG: locked for gesture test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        return unlockWithGesture(gestureJson)
    }

    /** lockNow 上锁并 goToSleep 息屏 */
    fun lockAndSleep(): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        if (!wm.lockNow()) {
            Log.w(LOG_TAG, "$TAG: lockNow unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked == true }) {
            // 锁屏方式为「无」时 lockNow 后 keyguard 永不出现；滑动/密码锁屏均会出现，
            // 超时且非 secure 即视为未设置锁屏，此时也无需息屏验证
            if (wm.isKeyguardSecure(0) != true) {
                Log.i(LOG_TAG, "$TAG: keyguard never appeared and not secure — no lock screen configured")
                return WakeUnlockResult.NO_KEYGUARD
            }
            Log.w(LOG_TAG, "$TAG: keyguard did not lock after lockNow")
            return WakeUnlockResult.LOCK_FAILED
        }

        if (!ensureScreenOff(pm)) {
            Log.w(LOG_TAG, "$TAG: screen still on after sleep attempts (keyguard already locked)")
        } else {
            Log.i(LOG_TAG, "$TAG: screen locked and off")
        }
        return WakeUnlockResult.OK
    }

    /**
     * 亮屏并确认 keyguard 还在
     * @return 非 null 即调用方应当直接返回的结果码
     */
    private fun wakeAndRequireKeyguard(pm: PowerManager, wm: WindowManager): Int? {
        if (!ensureScreenOn(pm)) {
            Log.w(LOG_TAG, "$TAG: screen did not turn on after wakeUp and key fallback")
            return WakeUnlockResult.WAKE_FAILED
        }
        Log.i(LOG_TAG, "$TAG: screen on")

        val locked = wm.isKeyguardLocked
        if (locked == null) {
            Log.w(LOG_TAG, "$TAG: isKeyguardLocked unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!locked) {
            Log.i(LOG_TAG, "$TAG: keyguard not showing, nothing to do")
            return WakeUnlockResult.OK
        }
        return null
    }

    /** 亮屏并解除锁屏；@param credential 纯数字 PIN，无凭证锁屏传空串 */
    fun unlock(credential: String): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        wakeAndRequireKeyguard(pm, wm)?.let { return it }

        val secure = wm.isKeyguardSecure(0) ?: false
        Log.i(LOG_TAG, "$TAG: keyguard locked, secure=$secure")

        if (!wm.dismissKeyguard()) {
            Log.w(LOG_TAG, "$TAG: dismissKeyguard unavailable on this ROM")
            return WakeUnlockResult.UNSUPPORTED
        }

        if (!secure) {
            return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == false }) {
                Log.i(LOG_TAG, "$TAG: unlocked (insecure keyguard)")
                WakeUnlockResult.OK
            } else {
                Log.w(LOG_TAG, "$TAG: insecure keyguard did not dismiss")
                WakeUnlockResult.CREDENTIAL_REJECTED
            }
        }

        if (credential.isEmpty()) {
            Log.w(LOG_TAG, "$TAG: secure keyguard but no credential configured")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }
        if (credential.any { !it.isDigit() }) {
            Log.w(LOG_TAG, "$TAG: only numeric PIN is supported")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }

        // bouncer 弹出期间 isKeyguardLocked 仍为 true，先 settle
        Thread.sleep(BOUNCER_SETTLE_MS)
        Log.i(LOG_TAG, "$TAG: injecting ${credential.length} PIN digits after ${BOUNCER_SETTLE_MS}ms settle")

        for (c in credential) {
            val keyCode = KeyEvent.KEYCODE_0 + (c - '0')
            injectKey(keyCode)
            Thread.sleep(DIGIT_GAP_MS)
        }
        // 部分 ROM 会自动提交；补 ENTER 兼容需确认的 PIN
        injectKey(KeyEvent.KEYCODE_ENTER)

        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == false }) {
            Log.i(LOG_TAG, "$TAG: unlocked (PIN accepted)")
            WakeUnlockResult.OK
        } else {
            // 不重试，避免连续输错触发系统锁定
            Log.w(LOG_TAG, "$TAG: still locked after PIN injection — wrong PIN, or keyguard ignores injected keys")
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /**
     * 亮屏并回放录制的解锁手势
     * @param gestureJson 空串表示未录制
     */
    fun unlockWithGesture(gestureJson: String): Int {
        // TODO(meow-port-1): 上游依赖 UnlockGesture/ScreenGeometry/UnlockGestureReplay（录制/回放/坐标映射）体量大，
        // 本 slice 保留签名暂不移植，内部直接返回 GESTURE_EMPTY；PIN 路径不受影响。
        Log.w(LOG_TAG, "$TAG: unlockWithGesture not ported (gestureJson len=${gestureJson.length}), return GESTURE_EMPTY")
        return WakeUnlockResult.GESTURE_EMPTY
    }

    /** 只亮屏，不碰 keyguard；录制手势时用，和回放走同一条唤醒路径 */
    fun wakeScreen(): Boolean = ensureScreenOn(ServiceManager.getPowerManager())

    // 适配：上游为 private fun ensureScreenOn，为供特权侧接线（点亮）改为 public，逻辑 1:1。
    fun ensureScreenOn(pm: PowerManager): Boolean =
        ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.wakeActions,
            alreadyDone = { pm.isScreenOn(0) },
            perform = { action ->
                when (action) {
                    ScreenPowerAttempts.WakeAction.BINDER -> {
                        if (!pm.wakeUp()) {
                            Log.w(LOG_TAG, "$TAG: wakeUp() invoke failed, polling then falling back to keys")
                        }
                    }

                    ScreenPowerAttempts.WakeAction.KEY_WAKEUP -> {
                        Log.w(LOG_TAG, "$TAG: injecting KEYCODE_WAKEUP")
                        injectKey(KeyEvent.KEYCODE_WAKEUP)
                    }

                    ScreenPowerAttempts.WakeAction.KEY_POWER -> if (!pm.isScreenOn(0)) {
                        Log.w(LOG_TAG, "$TAG: injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == ScreenPowerAttempts.WakeAction.BINDER) {
                    SCREEN_ON_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val on = pollUntil(timeout) { pm.isScreenOn(0) }
                if (!on) Log.w(LOG_TAG, "$TAG: screen still off after $action")
                on
            },
        )

    // 适配：上游为 private fun ensureScreenOff，为供特权侧接线（熄屏）改为 public，逻辑 1:1。
    fun ensureScreenOff(pm: PowerManager): Boolean =
        ScreenPowerAttempts.run(
            actions = ScreenPowerAttempts.sleepActions,
            alreadyDone = { !pm.isScreenOn(0) },
            perform = { action ->
                when (action) {
                    ScreenPowerAttempts.SleepAction.BINDER -> {
                        if (!pm.goToSleep()) {
                            Log.w(LOG_TAG, "$TAG: goToSleep() invoke failed, polling then falling back to keys")
                        }
                    }

                    ScreenPowerAttempts.SleepAction.KEY_SLEEP -> {
                        Log.w(LOG_TAG, "$TAG: injecting KEYCODE_SLEEP")
                        injectKey(KeyEvent.KEYCODE_SLEEP)
                    }

                    ScreenPowerAttempts.SleepAction.KEY_POWER -> if (pm.isScreenOn(0)) {
                        Log.w(LOG_TAG, "$TAG: injecting KEYCODE_POWER")
                        injectKey(KeyEvent.KEYCODE_POWER)
                    }
                }
            },
            pollAfter = { action ->
                val timeout = if (action == ScreenPowerAttempts.SleepAction.BINDER) {
                    SCREEN_OFF_TIMEOUT_MS
                } else {
                    KEY_WAKE_TIMEOUT_MS
                }
                val off = pollUntil(timeout) { !pm.isScreenOn(0) }
                if (!off) Log.w(LOG_TAG, "$TAG: screen still on after $action")
                off
            },
        )

    // 适配：上游经 maa/InputControlUtils.keyDown/keyUp 注入，本项目调既有 TouchInjector.injectKey（Down+Up 同通道），不移植 InputControlUtils。
    private fun injectKey(keyCode: Int) {
        val ok = try {
            TouchInjector.injectKey(keyCode)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "$TAG: injectKey keyCode=$keyCode threw ${t.message}")
            false
        }
        if (!ok) {
            Log.w(LOG_TAG, "$TAG: injectKey keyCode=$keyCode failed err=${TouchInjector.lastError?.message}")
        }
    }

}
