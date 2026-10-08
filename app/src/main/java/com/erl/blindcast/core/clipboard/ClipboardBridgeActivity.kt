package com.erl.blindcast.core.clipboard

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent

/**
 * 透明剪贴板读取桥（原版 `ReadClipboardActivity` 的等价实现）。
 *
 * ## 为什么需要前台 Activity
 * Android 10+ 后台应用 `getPrimaryClip()` 受隐私限制常返回 null；原版也是用一个
 * 透明 Activity 在前台读一次、再经 Binder 回传。本类同构：读一次 → 发布到
 * [ClipboardBridge] → **立即 `finish()`**，把前台还给用户原来的界面。
 *
 * ## 不抢前台 / 不后台轮询
 * - 只在 [com.erl.blindcast.core.server.routes.ClipboardApiRoute] 的 `GET /api/clipboard`
 *   直读失败时**按需**拉起一次；读完立刻结束（`noHistory` + 无动画 + 透明），
 *   **绝不后台定时轮询**。
 * - 首选在 `onCreate` 读（原版即如此）；若此刻尚未拿到焦点导致读空，150ms 后重试一次。
 */
class ClipboardBridgeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var published = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window?.setBackgroundDrawable(ColorDrawable(0))
        Log.i(TAG, "created")
        // 立即读一次（原版口径）；读空则 150ms 后补一次（等窗口拿到焦点）。
        if (!readOnce()) {
            handler.postDelayed({ readOnce() }, 150L)
        }
        // 兜底：最多存活 2s，绝不长期占前台。
        handler.postDelayed({ finishQuietly() }, 2_000L)
    }

    /** @return true = 已发布结果（可结束）。 */
    private fun readOnce(): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = runCatching { cm.primaryClip }.getOrNull()
        val has = clip != null && clip.itemCount > 0
        if (!has) return false
        val text = runCatching { clip!!.getItemAt(0).coerceToText(this).toString() }.getOrDefault("")
        ClipboardBridge.publish(text, true)
        published = true
        Log.i(TAG, "read ok textLen=${text.length}")
        finishQuietly()
        return true
    }

    private fun finishQuietly() {
        handler.removeCallbacksAndMessages(null)
        if (!published) ClipboardBridge.publish(null, false)
        if (!isFinishing) {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        finishQuietly()
        return false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BlindCast-ClipboardBridge"
    }
}
