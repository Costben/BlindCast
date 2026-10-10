package com.erl.blindcast.core.server.routes

import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.priv.DesktopWindowController
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject

/** Real Android launcher-shortcut bridge used by the Fusion app menu. */
object ShortcutApiRoute {
    private val PACKAGE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun handleList(method: String, rawQuery: String?): Pair<Int, String> {
        if (method != "GET") return 405 to error("method not allowed")
        val q = query(rawQuery)
        val pkg = q["package"].orEmpty()
        val user = q["user"]?.toIntOrNull() ?: 0
        if (!validPackage(pkg) || user !in 0..999) return 400 to error("invalid package or user")
        val result = runCommand("cmd shortcut get-shortcuts --user $user --flags 15 ${quote(pkg)}")
        if (!result.ok && result.output.isBlank()) return 200 to JSONObject()
            .put("ok", false).put("package", pkg).put("shortcuts", JSONArray()).put("error", "unavailable").toString()
        val rows = parse(result.output, pkg)
        return 200 to JSONObject().put("ok", true).put("package", pkg).put("user", user)
            .put("shortcuts", rows.map { it.json }.let { JSONArray(it) }).put("error", "").toString()
    }

    fun handleStart(method: String, body: ByteArray): Pair<Int, String> {
        if (method != "POST") return 405 to error("method not allowed")
        val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return 400 to error("invalid json body")
        val pkg = obj.optString("package", "").trim()
        val id = obj.optString("shortcutId", obj.optString("id", "")).trim()
        val user = obj.optInt("user", 0)
        val wid = obj.optString("sessionId", "").toIntOrNull() ?: 0
        if (!validPackage(pkg) || id.isBlank() || user !in 0..999) return 400 to error("invalid shortcut")
        val window = DesktopWindowController.list().firstOrNull { it.windowId == wid && it.state == "running" }
            ?: return 200 to result(false, pkg, id, "no-session")
        if (window.displayId <= 0) return 200 to result(false, pkg, id, "no-session")
        val shortcut = parse(runCommand("cmd shortcut get-shortcuts --user $user --flags 15 ${quote(pkg)}").output, pkg)
            .firstOrNull { it.id == id }
            ?: return 200 to result(false, pkg, id, "enoent")
        val action = shortcut.action.ifBlank { "android.intent.action.MAIN" }
        val component = shortcut.component
        if (component.isBlank()) return 200 to result(false, pkg, id, "eio")
        val cmd = buildString {
            append("am start --user ").append(user).append(" --display ").append(window.displayId)
            append(" -a ").append(quote(action))
            append(" -n ").append(quote(component))
            if (shortcut.data.isNotBlank()) append(" -d ").append(quote(shortcut.data))
        }
        val started = runCommand(cmd)
        return 200 to result(started.ok && !started.output.contains("Error: Activity not started"), pkg, id,
            if (started.ok) "" else "eio")
    }

    private data class Row(
        val id: String,
        val label: String,
        val component: String,
        val action: String,
        val data: String,
        val json: JSONObject,
    )

    private fun parse(output: String, pkg: String): List<Row> {
        val rows = ArrayList<Row>()
        output.split("ShortcutInfo {").drop(1).forEach { block ->
            val id = field(block, "id=") ?: return@forEach
            val label = field(block, "shortLabel=")?.substringBefore(", resId")?.trim().orEmpty()
            val rank = field(block, "rank=")?.toIntOrNull() ?: 0
            val comp = Regex("cmp=([^}\\s]+)").find(block)?.groupValues?.get(1).orEmpty()
            val action = Regex("act=([^\\s}]+)").find(block)?.groupValues?.get(1).orEmpty()
            val data = Regex("dat=([^\\s}]+)").find(block)?.groupValues?.get(1).orEmpty()
            val json = JSONObject().put("id", id).put("label", label).put("shortLabel", label)
                .put("longLabel", field(block, "longLabel=")?.substringBefore(", resId")?.trim().orEmpty())
                .put("rank", rank).put("enabled", !block.contains("[Disabled"))
            rows += Row(id, label, comp, action, data, json)
        }
        return rows.sortedBy { it.json.optInt("rank", 0) }
    }

    private fun field(block: String, key: String): String? =
        block.lineSequence().firstOrNull { it.trimStart().startsWith(key) }
            ?.trim().orEmpty().substringAfter(key, "").substringBefore(',').trim().ifBlank { null }

    private data class CmdResult(val ok: Boolean, val output: String)
    private fun runCommand(command: String): CmdResult = runCatching {
        val r = Shell.cmd(command).exec()
        CmdResult(r.isSuccess, (r.out + r.err).joinToString("\n"))
    }.getOrElse { CmdResult(false, it.message.orEmpty()) }

    private fun validPackage(pkg: String): Boolean = pkg.length in 3..255 && PACKAGE.matches(pkg)
    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    private fun result(ok: Boolean, pkg: String, id: String, error: String): String = JSONObject()
        .put("ok", ok).put("package", pkg).put("shortcutId", id).put("error", error).toString()
    private fun error(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull {
        val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to
            runCatching { java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8") }.getOrDefault("")
    }.toMap()
}
