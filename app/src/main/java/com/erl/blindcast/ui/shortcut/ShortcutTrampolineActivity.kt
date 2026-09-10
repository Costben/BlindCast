package com.erl.blindcast.ui.shortcut

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.erl.blindcast.core.quickaction.QuickActionExecutor
import kotlinx.coroutines.launch

/**
 * 桌面 App Shortcuts 点击中转 Activity。
 *
 * 采用完全透明、不进最近任务（excludeFromRecents）、无历史记录（noHistory）设计，
 * 触发操作后立即 finish()，避免弹出笨重的应用主页面。
 */
class ShortcutTrampolineActivity : ComponentActivity() {

    companion object {
        const val ACTION_SHORTCUT_BLACKOUT = "com.erl.blindcast.action.SHORTCUT_BLACKOUT"
        const val ACTION_SHORTCUT_HTTP = "com.erl.blindcast.action.SHORTCUT_HTTP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableActivityTransitions()

        val action = intent?.action
        when (action) {
            ACTION_SHORTCUT_BLACKOUT -> {
                lifecycleScope.launch {
                    QuickActionExecutor.toggleBlackout(this@ShortcutTrampolineActivity, forceBlackout = true)
                    finish()
                    disableActivityTransitions()
                }
            }
            ACTION_SHORTCUT_HTTP -> {
                QuickActionExecutor.startHttp(this)
                finish()
                disableActivityTransitions()
            }
            else -> {
                finish()
                disableActivityTransitions()
            }
        }
    }

    private fun disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
