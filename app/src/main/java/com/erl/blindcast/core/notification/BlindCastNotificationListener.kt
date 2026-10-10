package com.erl.blindcast.core.notification

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 通知镜像监听器（原版 A19 `notif-*` 的数据源）。
 *
 * 需用户在系统里授权「通知使用权」；本应用以 root 幂等执行
 * `cmd notification allow_listener <component>` 自动授权（见
 * [com.erl.blindcast.core.server.routes.NotificationApiRoute.ensureListenerGranted]）。
 *
 * 回调里把标题/正文/图标规范化后交给 [NotificationStore]；图标为按需转 PNG（96×96）。
 * 提供 [cancel] 供 `notif-cmd dismiss` 用。
 */
class BlindCastNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        NotificationStore.connected = true
        instance = this
        // 连接时把当前通知栏全量同步进快照，避免漏掉连接前的存量。
        runCatching { NotificationStore.clearActive() }
        runCatching { activeNotifications?.forEach { ingest(it) } }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
        NotificationStore.connected = false
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { ingest(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationStore.remove(sbn.key, 0)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        sbn ?: return
        NotificationStore.remove(sbn.key, reason)
    }

    private fun ingest(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val subText = ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val item = NotificationStore.Item(
            key = sbn.key,
            pkg = sbn.packageName,
            title = title,
            text = text,
            time = sbn.postTime,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            group = sbn.groupKey.orEmpty(),
            actions = emptyList(),
            subText = subText,
            userId = sbn.userId,
            clearable = sbn.isClearable,
            systemHidden = sbn.isOngoing && (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            importance = if (android.os.Build.VERSION.SDK_INT >= 24) n.channelId?.let { n.priority } ?: n.priority else n.priority,
            contentIntentTargetKind = if (n.contentIntent != null) "activity" else "unknown",
            postTime = sbn.postTime,
            updateTime = System.currentTimeMillis(),
        )
        val actions = n.actions?.mapNotNull { action ->
            action.actionIntent?.let { pending ->
                NotificationStore.Action(
                    action.title?.toString().orEmpty(),
                    pending,
                    action.remoteInputs ?: emptyArray(),
                    action.semanticAction?.toString().orEmpty(),
                    action.icon?.toString().orEmpty(),
                )
            }
        } ?: emptyList()
        NotificationStore.put(item, iconPng(n), n.contentIntent, actions)
    }

    private fun iconPng(n: Notification): ByteArray? = runCatching {
        val d = n.smallIcon?.loadDrawable(this) ?: return null
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        private const val TAG = "BlindCast-NotifListener"

        /** 已连接的监听器实例（dismiss 用）。 */
        @Volatile
        var instance: BlindCastNotificationListener? = null
            private set

        /** 取消一条通知；监听器未连接返回 false。 */
        fun cancel(key: String): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.cancelNotification(key); true }.getOrDefault(false)
        }

        /** 取消全部通知。 */
        fun cancelAll(): Boolean {
            val svc = instance ?: return false
            return runCatching { svc.cancelAllNotifications(); true }.getOrDefault(false)
        }
    }
}
