package com.erl.blindcast.core.notification

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知镜像持有者（进程内静态）：由 [BlindCastNotificationListener] 在收到系统回调时填充，
 * 服务侧 [com.erl.blindcast.core.server.routes.NotificationApiRoute] 读快照。
 *
 * 「active」= 当前仍在通知栏的；被移除的移入「recent」环形缓冲（上限 [RECENT_MAX]），
 * 对齐原版 `notif-snapshot` 的 active/recent 分区语义。
 */
object NotificationStore {

    private const val RECENT_MAX = 64

    /** 单条通知的规范化字段。 */
    data class Item(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val time: Long,
        val ongoing: Boolean,
        val group: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("package", pkg)
            .put("title", title)
            .put("text", text)
            .put("time", time)
            .put("ongoing", ongoing)
            .put("group", group)
    }

    @Volatile
    var connected: Boolean = false

    private val active = ConcurrentHashMap<String, Item>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private val recent = ArrayDeque<Item>()

    fun put(item: Item, png: ByteArray?) {
        active[item.key] = item
        if (png != null) icons[item.key] = png else icons.remove(item.key)
    }

    fun remove(key: String) {
        active.remove(key)?.let { synchronized(recent) {
            recent.addFirst(it)
            while (recent.size > RECENT_MAX) recent.removeLast()
        } }
        icons.remove(key)
    }

    /** 清空（监听器重连时重建，避免陈旧残留）。 */
    fun clearActive() {
        active.clear()
        icons.clear()
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
