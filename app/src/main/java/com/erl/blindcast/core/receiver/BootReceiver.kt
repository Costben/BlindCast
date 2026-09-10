package com.erl.blindcast.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.erl.blindcast.core.service.BlindCastForegroundService

/**
 * 开机与安装更新后自启动接收器。
 *
 * 监听系统开机完成（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）及应用更新（MY_PACKAGE_REPLACED）广播，
 * 当用户在设置中开启“开机自启动”（默认开启）时，按上一次持久化的期望运行态自动拉起常驻前台服务，
 * 避免手机重启或电量耗尽开机后必须手动点开 App。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BlindCast-BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received broadcast action: $action")

        val targetContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            !context.isDeviceProtectedStorage
        ) {
            runCatching { context.createDeviceProtectedStorageContext() }.getOrDefault(context)
        } else {
            context
        }

        val prefs = runCatching {
            targetContext.getSharedPreferences(
                BlindCastForegroundService.PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }.getOrNull()

        if (prefs == null) {
            Log.w(TAG, "SharedPreferences unavailable during action=$action (possibly device locked), skipping")
            return
        }

        // 1. 检查开机自启动总开关（默认开启）
        val bootStartEnabled = prefs.getBoolean(
            BlindCastForegroundService.KEY_BOOT_START_ENABLED,
            true
        )
        if (!bootStartEnabled) {
            Log.i(TAG, "Boot auto-start is disabled in settings. Skipping.")
            return
        }

        // 2. 获取上一次保存的服务期望态（只有上次明确开启过服务才恢复，未开启过绝不打扰用户）
        val streamWant = prefs.getBoolean(BlindCastForegroundService.KEY_STREAM_ENABLED, false)
        val httpWant = prefs.getBoolean(BlindCastForegroundService.KEY_HTTP_ENABLED, false)

        if (!streamWant && !httpWant) {
            Log.i(TAG, "Neither streaming nor HTTP service was active previously. Keeping idle.")
            return
        }

        Log.i(TAG, "Boot auto-start triggered: streamWant=$streamWant, httpWant=$httpWant")

        try {
            if (streamWant) {
                Log.i(TAG, "Restoring streaming foreground service...")
                BlindCastForegroundService.startStreaming(targetContext)
            } else if (httpWant) {
                Log.i(TAG, "Restoring HTTP foreground service...")
                BlindCastForegroundService.startHttp(targetContext)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to auto-start BlindCast service on boot", t)
        }
    }
}
