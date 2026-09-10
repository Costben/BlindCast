package com.erl.blindcast.core.quickaction

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.UserActivityKeeper
import com.erl.blindcast.core.service.BlindCastForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 快捷操作执行器（供 App Shortcuts 与 Quick Settings Tiles 状态栏磁贴共用）。
 */
object QuickActionExecutor {

    private const val TAG = "BlindCast-QuickAction"

    /**
     * 触发快速熄屏（若已熄屏则点亮，若亮屏则物理熄屏挂机）。
     * @param forceBlackout true 表示无条件执行熄屏，不走 toggle；false 为切换。
     */
    suspend fun toggleBlackout(context: Context, forceBlackout: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val pkg = context.packageName
            val currentlyBlackedOut = PowerController.isBlackedOut
            val targetBlackout = if (forceBlackout) true else !currentlyBlackedOut

            Log.i(TAG, "toggleBlackout: current=$currentlyBlackedOut, target=$targetBlackout, force=$forceBlackout")

            val ok = if (targetBlackout) {
                val res = runCatching { PowerController.blackoutRouted(pkg) }.getOrDefault(false)
                if (res) {
                    runCatching { UserActivityKeeper.start(context.applicationContext) }
                }
                res
            } else {
                val res = runCatching { PowerController.restoreRouted(pkg) }.getOrDefault(false)
                if (res) {
                    runCatching { UserActivityKeeper.stop() }
                }
                res
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    val msg = if (targetBlackout) "⏻ 已物理熄屏挂机" else "⏻ 已点亮屏幕"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } else {
                    val err = PowerController.lastError?.message ?: "特权调用失败"
                    Toast.makeText(context, "操作失败: $err", Toast.LENGTH_SHORT).show()
                }
            }
            ok
        }

    /**
     * 切换 HTTP 服务状态（开启则停止，停止则启动）。
     */
    fun toggleHttp(context: Context): Boolean {
        val running = BlindCastForegroundService.status.value.isRunning
        return if (running) {
            BlindCastForegroundService.stop(context)
            Toast.makeText(context, "BlindCast HTTP 服务已停止", Toast.LENGTH_SHORT).show()
            false
        } else {
            BlindCastForegroundService.startHttp(context)
            Toast.makeText(context, "BlindCast HTTP 服务已启动", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /**
     * 快速开启 HTTP 端口服务。
     */
    fun startHttp(context: Context) {
        BlindCastForegroundService.startHttp(context)
        Toast.makeText(context, "BlindCast HTTP 服务已启动", Toast.LENGTH_SHORT).show()
    }
}
