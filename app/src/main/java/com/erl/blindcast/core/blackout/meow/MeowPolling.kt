// Ported from Aliothmoon/MAA-Meow (AGPL-3.0, main 2026-09-05)，逻辑未改，仅包名/日志/按键注入适配。
// 上游原文：app/src/main/java/com/aliothmoon/maameow/remote/internal/Polling.kt
// WakeUnlockController 的轮询依赖（原样搬运，WakeUnlockController 逻辑 1:1 所需）。
package com.erl.blindcast.core.blackout.meow

import android.os.SystemClock

/** 亮屏、keyguard 这类交互 100ms 的粒度足够 */
const val DEFAULT_POLL_INTERVAL_MS = 100L

/** 提权进程里的状态变更只能靠轮询确认，唤醒/解锁/录制共用这一份 */
internal inline fun pollUntil(
    timeoutMs: Long,
    intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    cond: () -> Boolean,
): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (cond()) return true
        Thread.sleep(intervalMs)
    }
    return cond()
}
