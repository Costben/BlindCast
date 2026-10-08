package com.erl.blindcast.core.server.routes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.clipboard.ClipboardBridge
import com.erl.blindcast.core.clipboard.ClipboardBridgeActivity
import com.topjohnwu.superuser.Shell
import org.json.JSONObject

/**
 * 剪贴板路由（需鉴权）：原版 A12（chan3 type1 设备→浏览器 / type2 浏览器→设备）的 REST 落地。
 *
 * - `GET  /api/clipboard` → `{ok, text, available, via}`：读设备主剪贴板文本。
 * - `POST /api/clipboard {"text":...}` → `{ok}`：写设备主剪贴板（`ClipData.newPlainText`）。
 *
 * ## 读取：直读 + 前台桥兜底（真实读取，非空壳）
 * Android 10+ 后台应用 `getPrimaryClip()` 受隐私限制常返回 null。故：
 * 1. **快路径**：应用恰在前台时直读；
 * 2. **慢路径**：直读为空则按需拉起 [ClipboardBridgeActivity]（透明、`noHistory`、无动画）
 *    在前台读一次并发布结果，读完**立即结束**把前台还给用户；本路由在 ≤1.8s 窗口内取结果。
 *    `via` 字段标明本次来源（`direct` / `activity`）。
 * 绝不后台定时轮询、绝不长期占据前台。
 *
 * ## 写入
 * 写入不受后台限制，直接 `setPrimaryClip`。
 */
object ClipboardApiRoute {

    private const val TAG = "BlindCast-Clipboard"

    /** 前台桥读取的等待窗口（ms）。 */
    private const val BRIDGE_WAIT_MS = 1_800L

    /** 悬浮窗授权是否已尝试（幂等，进程内一次性）。 */
    @Volatile
    private var overlayGranted = false

    fun handle(method: String, body: ByteArray): Pair<Int, String> = when (method) {
        "GET" -> read()
        "POST" -> write(body)
        else -> 405 to err("method not allowed")
    }

    private fun read(): Pair<Int, String> {
        val ctx = ctx() ?: return 500 to err("no context")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return 500 to err("no clipboard service")

        // 快路径：直读（应用前台时可用）。
        val direct = runCatching { cm.primaryClip }.getOrNull()
        if (direct != null && direct.itemCount > 0) {
            val text = runCatching { direct.getItemAt(0).coerceToText(ctx).toString() }.getOrDefault("")
            Log.i(TAG, "[clipboard] read via=direct textLen=${text.length}")
            return 200 to result(text, true, "direct")
        }

        // 慢路径：拉起透明前台桥读一次。
        ensureOverlayGrant()
        val start = System.currentTimeMillis()
        launchBridge(ctx)
        var waited = 0L
        while (waited < BRIDGE_WAIT_MS) {
            Thread.sleep(60L)
            waited += 60L
            if (ClipboardBridge.lastAt > start) {
                val avail = ClipboardBridge.lastAvailable
                val text = ClipboardBridge.lastText ?: ""
                Log.i(TAG, "[clipboard] read via=activity available=$avail textLen=${text.length} after=${waited}ms")
                return 200 to result(text, avail, "activity")
            }
        }
        Log.w(TAG, "[clipboard] read timeout after=${waited}ms")
        return 200 to result("", false, "activity-timeout")
    }

    /**
     * 确保本应用持有 `SYSTEM_ALERT_WINDOW`（悬浮窗）授权——它是「后台启动透明 Activity」
     * 的 BAL 豁免条件（真机实测：未授权时 `START ... (BAL_BLOCK)`，透明桥起不来 → 读剪贴板超时；
     * 授权后 `via:activity` 180ms 内读回）。用 root 幂等授予，只作用于本应用自身，可逆。
     */
    private fun ensureOverlayGrant() {
        if (overlayGranted) return
        overlayGranted = true
        val pkg = ctx()?.packageName ?: return
        runCatching { Shell.cmd("appops set $pkg SYSTEM_ALERT_WINDOW allow").exec() }
            .onFailure { Log.w(TAG, "[clipboard] overlay grant failed: ${it.message}") }
    }

    private fun launchBridge(ctx: Context) {        runCatching {
            val i = Intent(ctx, ClipboardBridgeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            ctx.startActivity(i)
        }.onFailure { Log.w(TAG, "[clipboard] launch bridge failed: ${it.message}") }
    }

    private fun write(body: ByteArray): Pair<Int, String> {
        val ctx = ctx() ?: return 500 to err("no context")
        val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return 400 to err("invalid json body")
        val text = obj.optString("text", "")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return 500 to err("no clipboard service")
        val ok = runCatching {
            cm.setPrimaryClip(ClipData.newPlainText("BlindCast", text))
            true
        }.getOrDefault(false)
        Log.i(TAG, "[clipboard] write ok=$ok textLen=${text.length}")
        return 200 to JSONObject().put("ok", ok).toString()
    }

    private fun result(text: String, available: Boolean, via: String): String = JSONObject()
        .put("ok", true)
        .put("text", text)
        .put("available", available)
        .put("via", via)
        .toString()

    private fun ctx(): Context? = runCatching { blindCastApp.applicationContext }.getOrNull()

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
