package com.erl.blindcast.core.server.routes

import android.content.Context
import android.util.Log
import com.erl.blindcast.blindCastApp
import com.erl.blindcast.core.priv.DesktopController
import com.erl.blindcast.data.repository.SettingsRepository
import com.erl.blindcast.data.repository.SettingsRepositoryImpl
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 设备操作路由（需鉴权）：应用上下文管理 + 设置读写。
 *
 * ## 应用上下文（A18）
 * `POST /api/apps/action` body `{"action":...,"package":...,"user":0,"displayId":N?,"target":"phone"?}`
 * - `uninstall`  → `pm uninstall --user <u> <pkg>`（特权 shell）
 * - `clearData`  → `pm clear --user <u> <pkg>`
 * - `forceStop`  → `am force-stop --user <u> <pkg>`
 * - `launch`     → 在目标屏拉起该应用 launcher activity
 * - `appInfo`    → 在目标屏打开系统「应用信息」页
 *
 * 返回 `{"ok":bool,"action":...,"package":...,"detail":"..."}`；失败 `ok:false` + `detail`。
 *
 * **目标屏解析（launch/appInfo）**：显式 `displayId` > `target:"phone"`（物理屏 0）> 桌面虚拟屏；
 * 三者都不可得（桌面未运行且未显式指定）→ **fail closed**，返回 `ok:false`，
 * 绝不隐式落到物理主屏 0（避免破坏桌面隔离、惊扰用户前台）。
 *
 * **安全**：包名先做严格校验（[PKG_RE]），再统一经 [shellQuote] 单引号包裹后拼进特权命令；
 * 非法包名 / 越界 user 一律 400，不触达 shell。
 *
 * ## 设置
 * - `GET /api/settings` → 当前设置快照（**不回传 streamToken 明文**，只回 `tokenSet`）；
 * - `POST /api/settings` → 按白名单局部更新（只写出现的字段，不动用户其它配置）。
 *   **不含夜间模式**：夜间模式是设备级系统设置，走独立端点 [DisplayApiRoute]，
 *   以防浏览器端外观（appearance）切换被静默映射成全局 `cmd uimode night` 扰动物理主屏。
 *   若请求体带 `nightMode`，返回 400 并指引到 `/api/display/night-mode`。
 *
 * 特权命令一律走 libsu `Shell`（root 优先），在 IO 线程上同步执行；连接线程上 `runBlocking`。
 */
object DeviceOpsApiRoute {

    private const val TAG = "BlindCast-DeviceOps"

    /** 应用动作允许集。 */
    private val APP_ACTIONS = setOf("uninstall", "clearData", "forceStop", "launch", "appInfo")

    /** 需要明确目标屏的动作（桌面/会话语义，必须 fail closed）。 */
    private val DISPLAY_ACTIONS = setOf("launch", "appInfo")

    /**
     * Android 包名严格校验：至少两段、每段为 `[A-Za-z0-9_]` 且非空，总长 ≤ 255。
     * 拒绝引号、空格、`;` `$` 反引号等一切 shell 元字符（配合 [shellQuote] 双重防护）。
     */
    private val PKG_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    // ------------------------------------------------------------------
    // 应用上下文
    // ------------------------------------------------------------------

    fun handleAppsAction(method: String, body: ByteArray): Pair<Int, String> {
        if (method != "POST") return 405 to err("method not allowed")
        val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return 400 to err("invalid json body")
        val action = obj.optString("action", "").trim()
        val pkg = obj.optString("package", "").trim()
        if (action !in APP_ACTIONS) return 400 to err("unknown action: $action")
        if (!isValidPackage(pkg)) return 400 to err("invalid package name")
        val user = obj.optInt("user", 0)
        if (user !in 0..999) return 400 to err("invalid user: $user")

        // 桌面/会话动作必须先定目标屏；定不出来就 fail closed，绝不隐式落 display 0。
        val displayId: Int?
        if (action in DISPLAY_ACTIONS) {
            displayId = resolveDisplayId(obj)
            if (displayId == null) {
                Log.i(TAG, "[apps] $action pkg=$pkg fail-closed: no desktop display")
                return 200 to JSONObject()
                    .put("ok", false)
                    .put("action", action)
                    .put("package", pkg)
                    .put("detail", "desktop not running; pass displayId or target=phone")
                    .toString()
            }
        } else {
            displayId = null
        }

        val (ok, detail) = runBlocking(Dispatchers.IO) {
            when (action) {
                "uninstall" -> execOk("pm uninstall --user $user ${shellQuote(pkg)}", expect = "Success")
                "clearData" -> execOk("pm clear --user $user ${shellQuote(pkg)}", expect = "Success")
                "forceStop" -> execOk("am force-stop --user $user ${shellQuote(pkg)}")
                "launch" -> launchOnDisplay(pkg, displayId!!, user)
                else -> appInfo(pkg, displayId!!, user)
            }
        }
        Log.i(TAG, "[apps] $action pkg=$pkg user=$user did=${displayId ?: -1} ok=$ok ${detail.take(160)}")
        return 200 to JSONObject()
            .put("ok", ok)
            .put("action", action)
            .put("package", pkg)
            .put("detail", detail)
            .toString()
    }

    /** 包名严格校验。 */
    private fun isValidPackage(pkg: String): Boolean =
        pkg.length in 3..255 && PKG_RE.matches(pkg)

    /** POSIX 单引号包裹；内部单引号按 `'\''` 转义（包名已过 [PKG_RE]，此为纵深防御）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 目标屏：显式 `displayId` ≥ 0 > `target:"phone"`（物理屏 0）> 桌面虚拟屏。
     * 桌面未运行且未显式指定 → `null`（调用方 fail closed）。
     */
    private fun resolveDisplayId(obj: JSONObject): Int? {
        val explicit = obj.optInt("displayId", -1)
        if (explicit >= 0) return explicit
        if (obj.optString("target", "").equals("phone", ignoreCase = true)) return 0
        val st = runCatching { DesktopController.status() }.getOrNull()
        return if (st != null && st.running && st.displayId > 0) st.displayId else null
    }

    private fun launchOnDisplay(pkg: String, displayId: Int, user: Int): Pair<Boolean, String> {
        val ctx = contextOrNull() ?: return false to "no context"
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return false to "no launcher activity for $pkg"
        val comp = launch.component ?: return false to "unresolved launcher component"
        val didArg = if (displayId > 0) "--display $displayId " else ""
        return execOk(
            "am start --user $user $didArg-n ${shellQuote("${comp.packageName}/${comp.className}")}",
        )
    }

    private fun appInfo(pkg: String, displayId: Int, user: Int): Pair<Boolean, String> {
        val didArg = if (displayId > 0) "--display $displayId " else ""
        return execOk(
            "am start --user $user $didArg-a android.settings.APPLICATION_DETAILS_SETTINGS " +
                "-d ${shellQuote("package:$pkg")}",
        )
    }

    /** 执行特权 shell；[expect] 非空时要求输出包含该子串，否则按失败处理。 */
    private fun execOk(cmd: String, expect: String? = null): Pair<Boolean, String> {
        val res = runCatching { Shell.cmd(cmd).exec() }.getOrElse { t ->
            return false to "shell threw: ${t.javaClass.simpleName}: ${t.message}"
        }
        val out = (res.out + res.err).joinToString("\n").trim()
        val ok = if (expect != null) out.contains(expect) else res.isSuccess
        return ok to out.ifBlank { if (res.isSuccess) "ok" else "exit=${res.code}" }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private val BOOL_KEYS = mapOf(
        "audioEnabled" to "audioEnabled",
        "scrcpyTouchEnabled" to "scrcpyTouchEnabled",
        "scrcpyRightBackEnabled" to "scrcpyRightBackEnabled",
        "scrcpyKeyboardEnabled" to "scrcpyKeyboardEnabled",
        "keepAliveEnabled" to "keepAliveEnabled",
        "bootStartEnabled" to "bootStartEnabled",
        "haEnabled" to "haEnabled",
    )
    private val INT_KEYS = mapOf(
        "videoFps" to "videoFps",
        "videoBitrateMbps" to "videoBitrateMbps",
        "serverPort" to "serverPort",
        "haBrokerPort" to "haBrokerPort",
    )
    private val STR_KEYS = mapOf(
        "videoResolution" to "videoResolution",
        "blackoutMode" to "blackoutMode",
        "haBrokerHost" to "haBrokerHost",
    )

    fun handleSettings(method: String, body: ByteArray): Pair<Int, String> {
        val repo = runCatching { SettingsRepositoryImpl() }.getOrNull()
            ?: return 500 to err("no settings repository")
        return when (method) {
            "GET" -> 200 to settingsJson(repo)
            "POST" -> {
                val obj = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                    ?: return 400 to err("invalid json body")
                // 夜间模式是设备级系统设置，必须走独立端点；此处拒绝以防静默扰动物理主屏。
                if (obj.has("nightMode")) {
                    return 400 to err(
                        "nightMode is a device-wide system setting; use POST /api/display/night-mode",
                    )
                }
                applySettings(repo, obj)
                200 to settingsJson(repo)
            }
            else -> 405 to err("method not allowed")
        }
    }

    private fun applySettings(repo: SettingsRepository, obj: JSONObject) {
        for ((jsonKey, prop) in BOOL_KEYS) {
            if (obj.has(jsonKey)) runCatching { setBool(repo, prop, obj.optBoolean(jsonKey)) }
        }
        for ((jsonKey, prop) in INT_KEYS) {
            if (obj.has(jsonKey)) runCatching { setInt(repo, prop, obj.optInt(jsonKey)) }
        }
        for ((jsonKey, prop) in STR_KEYS) {
            if (obj.has(jsonKey)) runCatching { setStr(repo, prop, obj.optString(jsonKey)) }
        }
    }

    private fun setBool(repo: SettingsRepository, prop: String, v: Boolean) {
        when (prop) {
            "audioEnabled" -> repo.audioEnabled = v
            "scrcpyTouchEnabled" -> repo.scrcpyTouchEnabled = v
            "scrcpyRightBackEnabled" -> repo.scrcpyRightBackEnabled = v
            "scrcpyKeyboardEnabled" -> repo.scrcpyKeyboardEnabled = v
            "keepAliveEnabled" -> repo.keepAliveEnabled = v
            "bootStartEnabled" -> repo.bootStartEnabled = v
            "haEnabled" -> repo.haEnabled = v
        }
    }

    private fun setInt(repo: SettingsRepository, prop: String, v: Int) {
        when (prop) {
            "videoFps" -> repo.videoFps = v
            "videoBitrateMbps" -> repo.videoBitrateMbps = v
            "serverPort" -> repo.serverPort = v
            "haBrokerPort" -> repo.haBrokerPort = v
        }
    }

    private fun setStr(repo: SettingsRepository, prop: String, v: String) {
        when (prop) {
            "videoResolution" -> repo.videoResolution = v
            "blackoutMode" -> repo.blackoutMode = v
            "haBrokerHost" -> repo.haBrokerHost = v
        }
    }

    private fun settingsJson(repo: SettingsRepository): String = JSONObject()
        .put("ok", true)
        .put("videoResolution", runCatching { repo.videoResolution }.getOrDefault(""))
        .put("videoFps", runCatching { repo.videoFps }.getOrDefault(0))
        .put("videoBitrateMbps", runCatching { repo.videoBitrateMbps }.getOrDefault(0))
        .put("audioEnabled", runCatching { repo.audioEnabled }.getOrDefault(false))
        .put("scrcpyTouchEnabled", runCatching { repo.scrcpyTouchEnabled }.getOrDefault(false))
        .put("scrcpyRightBackEnabled", runCatching { repo.scrcpyRightBackEnabled }.getOrDefault(false))
        .put("scrcpyKeyboardEnabled", runCatching { repo.scrcpyKeyboardEnabled }.getOrDefault(false))
        .put("blackoutMode", runCatching { repo.blackoutMode }.getOrDefault(""))
        .put("keepAliveEnabled", runCatching { repo.keepAliveEnabled }.getOrDefault(false))
        .put("bootStartEnabled", runCatching { repo.bootStartEnabled }.getOrDefault(false))
        .put("serverPort", runCatching { repo.serverPort }.getOrDefault(8888))
        .put("haEnabled", runCatching { repo.haEnabled }.getOrDefault(false))
        .put("haBrokerHost", runCatching { repo.haBrokerHost }.getOrDefault(""))
        .put("haBrokerPort", runCatching { repo.haBrokerPort }.getOrDefault(0))
        // 安全：绝不回传 token 明文，只回是否已设置。
        .put("tokenSet", runCatching { repo.streamToken }.getOrDefault("").isNotEmpty())
        // 夜间模式只读展示（设备全局；写入走 /api/display/night-mode）。
        .put("nightMode", runCatching { DisplayApiRoute.readGlobalNightMode() }.getOrDefault(false))
        .toString()

    // ------------------------------------------------------------------

    private fun contextOrNull(): Context? = runCatching { blindCastApp.applicationContext }.getOrNull()

    private fun err(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()
}
