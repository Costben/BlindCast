package com.erl.blindcast.core.widget

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 已绑定 widget 的持久化表（`filesDir/widgets.json`）。
 *
 * ## 为什么需要它
 * `AppWidgetHost.allocateAppWidgetId()` 产生的 id 由系统侧持久化（跨重启有效），
 * 但「本应用绑了哪些 id、分别绑的哪个 provider」需自行记录：
 * provider 被卸载后 `AppWidgetManager.getAppWidgetInfo(id)` 返回 null，
 * 届时只能靠本表回溯组件名与展示名（对应渲染侧的 stale 态）。
 *
 * ## 存储
 * - 路径 `filesDir/widgets.json`；
 * - 格式 `{"widgets":[{"id":N,"provider":"pkg/.Cls","label":"...","createdAt":N}]}`；
 * - 写入为临时文件 + 原子改名（同 [com.erl.blindcast.core.priv.PrivilegedBridge] 落盘范式）。
 *
 * ## 线程模型
 * - 全部方法 `synchronized(lock)` 串行；读一次后缓存在内存，写即落盘；
 * - 永不抛异常：IO 失败只记日志，内存态照常生效（丢的是持久化而非运行时）。
 */
object WidgetStore {

    private const val TAG = "BlindCast-Widget"
    private const val FILE_NAME = "widgets.json"

    /** 一条绑定记录。[label] 仅作展示兜底（provider 卸载后仍能显示名字）。 */
    data class Record(
        val id: Int,
        val provider: String,
        val label: String,
        val createdAt: Long,
    )

    private val lock = Any()
    private var records = mutableListOf<Record>()
    private var loaded = false
    private var appContext: Context? = null

    /** 预存上下文并首次载入（幂等，任意线程）。 */
    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext ?: context
            if (!loaded) {
                records = load()
                loaded = true
            }
        }
    }

    fun all(): List<Record> = synchronized(lock) { records.toList() }

    fun find(id: Int): Record? = synchronized(lock) { records.firstOrNull { it.id == id } }

    fun contains(id: Int): Boolean = synchronized(lock) { records.any { it.id == id } }

    fun add(record: Record) {
        synchronized(lock) {
            if (records.any { it.id == record.id }) return
            records.add(record)
            save()
        }
    }

    fun remove(id: Int): Boolean = synchronized(lock) {
        val removed = records.removeAll { it.id == id }
        if (removed) save()
        removed
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    private fun load(): MutableList<Record> {
        val f = file() ?: return mutableListOf()
        if (!f.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("widgets") ?: JSONArray()
            val out = mutableListOf<Record>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optInt("id", -1)
                val provider = o.optString("provider", "")
                if (id < 0 || provider.isEmpty()) continue
                out.add(
                    Record(
                        id = id,
                        provider = provider,
                        label = o.optString("label", ""),
                        createdAt = o.optLong("createdAt", 0L),
                    ),
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "WidgetStore load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun save() {
        val f = file() ?: return
        runCatching {
            val arr = JSONArray()
            for (r in records) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("provider", r.provider)
                        .put("label", r.label)
                        .put("createdAt", r.createdAt),
                )
            }
            val text = JSONObject().put("widgets", arr).toString()
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "WidgetStore save failed: ${it.message}") }
    }
}
