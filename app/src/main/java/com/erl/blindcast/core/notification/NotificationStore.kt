package com.erl.blindcast.core.notification

import org.json.JSONArray
import org.json.JSONObject
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 通知镜像持有者（进程内静态）：由 [BlindCastNotificationListener] 在收到系统回调时填充，
 * 服务侧 [com.erl.blindcast.core.server.routes.NotificationApiRoute] 读快照。
 *
 * 「active」= 当前仍在通知栏的；被移除的移入「recent」环形缓冲（上限 [RECENT_MAX]），
 * 对齐原版 `notif-snapshot` 的 active/recent 分区语义。
 */
object NotificationStore {

    private const val RECENT_MAX = 64

    data class Action(
        val label: String,
        val intent: PendingIntent,
        val remoteInputs: Array<RemoteInput> = emptyArray(),
        val semanticAction: String = "",
        val iconId: String = "",
    )
    data class ActionMeta(
        val index: Int,
        val title: String,
        val semanticAction: String,
        val hasRemoteInput: Boolean,
        val intentTargetKind: String = "unknown",
        val iconId: String = "",
    ) {
        fun toJson() = JSONObject().put("index", index).put("title", title)
            .put("semanticAction", semanticAction).put("hasRemoteInput", hasRemoteInput)
            .put("intentTargetKind", intentTargetKind).put("iconId", iconId)
    }

    /** 单条通知的规范化字段。 */
    data class Item(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val time: Long,
        val ongoing: Boolean,
        val group: String,
        val actions: List<ActionMeta> = emptyList(),
        val subText: String = "",
        val userId: Int = 0,
        val clearable: Boolean = true,
        val systemHidden: Boolean = false,
        val importance: Int? = null,
        val contentIntentTargetKind: String = "unknown",
        val postTime: Long = time,
        val updateTime: Long = time,
        val removedAt: Long = 0L,
        val removalReason: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("package", pkg)
            .put("title", title)
            .put("text", text)
            .put("subText", subText)
            .put("userId", userId)
            .put("time", time)
            .put("postTime", postTime)
            .put("updateTime", updateTime)
            .put("removedAt", removedAt)
            .put("removalReason", removalReason)
            .put("ongoing", ongoing)
            .put("clearable", clearable)
            .put("systemHidden", systemHidden)
            .put("importance", importance)
            .put("contentIntentTargetKind", contentIntentTargetKind)
            .put("group", group)
            .put("actions", JSONArray().also { a -> actions.forEach { a.put(it.toJson()) } })
    }

    @Volatile
    var connected: Boolean = false

    private val active = ConcurrentHashMap<String, Item>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val contentIntents = ConcurrentHashMap<String, PendingIntent>()
    private val actionIntents = ConcurrentHashMap<String, List<Action>>()
    private val recent = ArrayDeque<Item>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.forEach { runCatching { it() } } }

    fun put(item: Item, png: ByteArray?, contentIntent: PendingIntent? = null, actions: List<Action> = emptyList()) {
        active[item.key] = item.copy(actions = actions.mapIndexed { index, action ->
            ActionMeta(index, action.label, action.semanticAction, action.remoteInputs.isNotEmpty(), iconId = action.iconId)
        })
        if (png != null) icons[item.key] = png else icons.remove(item.key)
        if (contentIntent != null) contentIntents[item.key] = contentIntent else contentIntents.remove(item.key)
        if (actions.isEmpty()) actionIntents.remove(item.key) else actionIntents[item.key] = actions
        changed()
    }

    fun remove(key: String, reason: Int = 0) {
        active.remove(key)?.let { removed -> synchronized(recent) {
            recent.addFirst(removed.copy(removedAt = System.currentTimeMillis(), removalReason = reason))
            while (recent.size > RECENT_MAX) recent.removeLast()
        } }
        icons.remove(key)
        contentIntents.remove(key)
        actionIntents.remove(key)
        changed()
    }

    /** 清空（监听器重连时重建，避免陈旧残留）。 */
    fun clearActive() {
        active.clear()
        icons.clear()
        contentIntents.clear()
        actionIntents.clear()
        changed()
    }

    fun send(context: Context, key: String, actionIndex: Int? = null, reply: String? = null, displayId: Int = 0): Boolean {
        val action = actionIndex?.let { actionIntents[key]?.getOrNull(it) }
        val pending = action?.intent ?: if (actionIndex == null) contentIntents[key] else null
        if (pending == null) return false
        return runCatching {
            val fill = if (reply != null && action != null && action.remoteInputs.isNotEmpty()) {
                Intent().also { intent ->
                    val values = Bundle().apply { putCharSequence(action.remoteInputs[0].resultKey, reply) }
                    RemoteInput.addResultsToIntent(action.remoteInputs, intent, values)
                }
            } else null
            val options = if (displayId > 0) android.app.ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(displayId)
            }.toBundle() else null
            if (options != null) pending.send(context, 0, fill, null, null, null, options)
            else pending.send(context, 0, fill)
            true
        }.getOrDefault(false)
    }

    fun snapshot(): List<Item> = active.values.sortedByDescending { it.time }

    fun recentSnapshot(): List<Item> = synchronized(recent) { recent.toList() }

    fun iconPng(key: String): ByteArray? = icons[key]

    /** `notif-snapshot` 形状：`{active:[...],recent:[...]}`。 */
    fun toJson(): JSONObject {
        val act = JSONArray()
        snapshot().forEach { act.put(it.toJson()) }
        val rec = JSONArray()
        recentSnapshot().forEach { rec.put(it.toJson()) }
        return JSONObject().put("active", act).put("recent", rec).put("count", act.length())
    }
}
