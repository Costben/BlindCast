package com.erl.blindcast.core.input

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A virtual-device IME: commit Unicode through the editor, bound to the active virtual editor. */
class FusionInputMethodService : InputMethodService() {
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        synchronized(active) {
            if (attribute != null && attribute.inputType != 0) active.add(this) else active.remove(this)
        }
        Log.i(TAG, "editor started display=${editorDisplayId()} package=${attribute?.packageName}")
    }

    override fun onFinishInput() {
        synchronized(active) { active.remove(this) }
        super.onFinishInput()
    }

    override fun onDestroy() {
        synchronized(active) { active.remove(this) }
        super.onDestroy()
    }

    // Attach a zero-height IME window so WindowManager supplies the editor's
    // display context. A detached decor view otherwise incorrectly reports #0.
    override fun onEvaluateInputViewShown(): Boolean = true
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val height = window?.window?.decorView?.height ?: 0
        outInsets.contentTopInsets = height
        outInsets.visibleTopInsets = height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }

    private fun editorDisplayId(): Int {
        val attached = window?.window?.decorView?.takeIf { it.isAttachedToWindow }?.display?.displayId
        if (attached != null && attached > 0) return attached
        // With a hardware keyboard Android doesn't show an IME window. Its
        // detached decor belongs to #0 even though its input connection is on
        // the virtual display. Query WindowManager's actual IME display instead.
        return runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/view/WindowManagerGlobal;", "Landroid/view/IWindowManager;")
            val global = Class.forName("android.view.WindowManagerGlobal")
            val wm = global.getMethod("getWindowManagerService").invoke(null)
            wm.javaClass.getMethod("getImeDisplayId").invoke(wm) as Int
        }.getOrElse { -1 }
    }

    companion object {
        private const val TAG = "BlindCast-IME"
        private val active = mutableSetOf<FusionInputMethodService>()
        private val main = Handler(Looper.getMainLooper())

        /** Match the target display on the main thread; never send text to a different editor. */
        fun commit(text: String, displayId: Int): Pair<Boolean, String?> {
            if (displayId <= 0) return false to "virtual editor required"
            val done = CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference(false to "no focused editor on display $displayId")
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val action = Runnable {
                try {
                    if (!cancelled.get()) {
                        val ime = synchronized(active) { active.lastOrNull { it.editorDisplayId() == displayId } }
                        val connection = ime?.currentInputConnection
                        if (connection != null) {
                            val ok = connection.commitText(text, 1)
                            result.set(ok to if (ok) "" else "editor rejected text")
                        } else {
                            val displays = synchronized(active) { active.map { it.editorDisplayId() } }
                            result.set(false to "no focused editor on display $displayId (active=$displays)")
                        }
                    }
                } finally { done.countDown() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run() else main.post(action)
            if (!done.await(1500, TimeUnit.MILLISECONDS)) {
                cancelled.set(true)
                main.removeCallbacks(action)
                return false to "editor commit timed out"
            }
            val (ok, error) = result.get()
            return ok to error.takeIf { it.isNotEmpty() }
        }
    }
}
