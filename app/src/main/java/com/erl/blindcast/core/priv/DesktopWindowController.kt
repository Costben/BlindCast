package com.erl.blindcast.core.priv

import android.util.Log
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.scrcpy.CaptureLink
import com.erl.blindcast.core.scrcpy.PrivilegedCapture
import com.erl.blindcast.core.server.routes.StreamWsRoute
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 逐应用窗口控制器（Win-Ctrl-1 · App 进程）。
 *
 * ## 模型（每窗口一路独立流）
 * 一个窗口 = **一个虚拟设备 + 一张虚拟显示 + 一路 MediaCodec 编码 + 一条抽象 socket +
 * 一个 shell 身份宿主进程**。N 个窗口 = N 条完全独立的采集链：
 * - 虚拟显示之间互相隔离，一个窗口的输入不会落到另一个窗口或物理主屏；
 * - 每窗口一个 [CaptureLink]（socket `blindcast_win_<id>`），帧经
 *   [StreamWsRoute.broadcastWindowVideo] 以 `0x11 + windowId` 多路复用到 `/ws/stream`；
 * - 关窗 = touch 该窗口自己的 stop 文件 → 宿主 `PrivilegedCapture.stop()` →
 *   `VirtualDesktopSession.stop()` 释放它自己的虚拟屏与设备 → 屏上应用任务随之销毁。
 *   **绝不触碰物理主屏任务，也绝不动整屏桌面会话。**
 *
 * ## 为什么不是「一张副屏多任务 + 客户端裁剪」
 * 副屏上的应用是 fullscreen 任务，同一时刻只有一个是可见的；要「并排同时看到多个应用
 * 各自实时更新」，必须每个应用一张屏、一路流。真机实证（216 / Android 16）：同一
 * companion 关联下并发建两个虚拟设备/虚拟屏均成功，各自拿到硬编输入面。
 *
 * ## 运行文件（生产运行 IPC，落 `/data/local/tmp`）
 * - stop：`blindcast_win_<id>.stop`（**按窗口 id 固定命名，不按 PID** —— 换号不失联）；
 * - status：`blindcast_win_<id>.stop.status`（宿主写，App 侧只读）。
 * 磁盘状态文件是**唯一真源**：App 进程被替换后，[reconcile] 据它识别仍在跑的宿主。
 * 但宿主侧**没有重连**（采集侧写帧失败只记错），纳管恢复不了画面，故这类孤儿宿主一律
 * 回收，让 windowId 空出来供 UI 重新打开。
 */
object DesktopWindowController {

    private const val TAG = "BlindCast-Win"

    private const val RUN_DIR = "/data/local/tmp"
    private const val FILE_PREFIX = "blindcast_win_"
    private const val SOCKET_PREFIX = "blindcast_win_"

    const val DEFAULT_WIDTH = 720
    const val DEFAULT_HEIGHT = 1280
    const val DEFAULT_BITRATE = 4_000_000
    const val DEFAULT_FPS = 30

    /** 并发窗口上限（每窗一路硬编，超了设备扛不住）。 */
    const val MAX_WINDOWS = 4

    /** 窗口元信息（对外 JSON 用）。 */
    data class WindowInfo(
        val windowId: Int,
        val displayId: Int,
        val taskId: Int,
        val packageName: String,
        val component: String,
        val width: Int,
        val height: Int,
        val state: String,
        val error: String,
        val intentUrl: String = "",
        val kind: String = "app",
        val userId: Int = 0,
    )

    /** 单个窗口的运行态（内存侧；磁盘 status 文件为准）。 */
    class Entry(
        val windowId: Int,
        val socketName: String,
        val stopPath: String,
    ) {
        @Volatile var link: CaptureLink? = null
        @Volatile var displayId: Int = -1
        @Volatile var deviceId: Int = -1
        @Volatile var packageName: String = ""
        @Volatile var component: String = ""
        @Volatile var width: Int = DEFAULT_WIDTH
        @Volatile var height: Int = DEFAULT_HEIGHT
        @Volatile var state: String = "stopped"
        @Volatile var error: String = ""
        @Volatile var intentUrl: String = ""
        @Volatile var kind: String = "app"
        @Volatile var userId: Int = 0

        val statusPath: String get() = stopPath + ".status"

        fun toInfo(taskId: Int): WindowInfo = WindowInfo(
            windowId = windowId,
            displayId = displayId,
            taskId = taskId,
            packageName = packageName,
            component = component,
            width = width,
            height = height,
            state = state,
            error = error,
            intentUrl = intentUrl,
            kind = kind,
            userId = userId,
        )
    }

    private val entries = ConcurrentHashMap<Int, Entry>()

    private val stateLock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> serialized(block: () -> T): T {
        stateLock.lock()
        try {
            return block()
        } finally {
            stateLock.unlock()
        }
    }

    /** 是否有窗口在跑（[DesktopController] 据此决定能不能摘 companion 关联 / 清扫宿主）。 */
    fun isActive(): Boolean = entries.values.any { it.state == "running" }

    /** 当前窗口数。 */
    val count: Int get() = entries.size

    /** 某窗口的虚拟 displayId（无则 -1）——控制协议按 windowId 路由输入用。 */
    fun displayIdOf(windowId: Int): Int = entries[windowId]?.displayId ?: -1

    /** 某窗口的虚拟屏尺寸（无则 null）——输入注入的归一化换算基准。 */
    fun sizeOf(windowId: Int): Pair<Int, Int>? =
        entries[windowId]?.let { it.width to it.height }

    fun setNightMode(windowId: Int, on: Boolean): DesktopController.NightResult = serialized {
        val entry = entries[windowId]?.takeIf { it.state == "running" && it.displayId > 0 }
            ?: return@serialized DesktopController.NightResult(false, null, -1, "窗口未运行")
        DesktopController.applyNightMode(entry.stopPath, entry.displayId, on)
    }

    /**
     * 请求该窗口立刻产一个 IDR（Request-Sync-1）。
     *
     * App 侧 touch `blindcast_win_<id>.stop.sync` → 宿主 250ms 内消费 → 编码器
     * `setParameters({"request-sync":0})`。用于：新客户端接入、WS 背压进入
     * 「丢到下一个 IDR」态、以及客户端解码出错后的重同步。宿主不在时是空操作。
     *
     * 文件名必须与宿主监视的一致：`FusionDesktopMain` 取启动参数里的 stop 路径
     * （`$RUN_DIR/$FILE_PREFIX<id>.stop`）再拼 `.sync`，即 `.stop.sync`。
     */
    fun requestSync(windowId: Int) {
        if (windowId <= 0) return
        runCatching { Shell.cmd("touch $RUN_DIR/$FILE_PREFIX$windowId.stop.sync").exec() }
    }

    // ------------------------------------------------------------------
    // 对外操作
    // ------------------------------------------------------------------

    /**
     * 列出窗口（先与磁盘 status 对账）。
     *
     * 对账与快照读取都在 [stateLock] 内完成，与 [open]/[close] 串行；`taskIdOf` 的
     * shell 调用挪到锁外做，避免持锁期间跑外部命令。
     */
    fun list(): List<WindowInfo> {
        val snapshot = serialized {
            reconcileLocked()
            entries.values.sortedBy { it.windowId }.toList()
        }
        // 批量取各窗任务 id：单次抓取，避免逐窗 shell/dumpsys（前端 1.5s 轮询下的主要延迟来源）。
        val taskIds = DesktopTaskController.firstTaskIdsFor(snapshot.map { it.displayId })
        return snapshot.map { it.toInfo(taskIds[it.displayId] ?: -1) }
    }

    /**
     * 打开一个窗口：该应用独占一张虚拟显示 + 一路编码 + 一条 socket。
     *
     * @param packageName 目标应用包名
     * @param component   显式 `pkg/Activity`（空则用包名的 launcher activity）
     */
    suspend fun open(
        packageName: String,
        component: String = "",
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        reuseWindowId: Int = -1,
        intentUrl: String = "",
        kind: String = "app",
        userId: Int = 0,
    ): WindowInfo = withContext(Dispatchers.IO) { serialized { openLocked(packageName, component, width, height, reuseWindowId, intentUrl, kind, userId) } }

    private fun openLocked(
        packageName: String,
        component: String,
        width: Int,
        height: Int,
        reuseWindowId: Int,
        intentUrl: String,
        kind: String,
        userId: Int,
    ): WindowInfo {
        val pkg = packageName.trim()
        val url = intentUrl.trim()
        val normalizedKind = kind.trim().ifBlank { "app" }
        if (normalizedKind !in setOf("app", "widget-picker", "widget-config")) return failure(reuseWindowId, "unsupported window kind")
        if (pkg.isNotBlank() && !Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(pkg)) return failure(reuseWindowId, "invalid packageName")
        if (pkg.isBlank() && url.isBlank() && normalizedKind == "app") return failure(reuseWindowId, "packageName 为空")
        if (entries.size >= MAX_WINDOWS && !entries.containsKey(reuseWindowId)) {
            return failure(reuseWindowId, "窗口数已达上限 $MAX_WINDOWS")
        }
        // 先与磁盘对账：回收已死、以及仍在跑但 socket 已随上个进程失联的孤儿宿主。
        reconcile()
        val reuse = reuseWindowId in 1..255
        val wid = if (reuse) reuseWindowId else freeId()
            ?: return failure(-1, "无可用 windowId")
        // 关键修复（孤儿共享 socket 几何错配）：固定命名下 windowId 会复用，
        // 若该 id 上还挂着一个**旧宿主**（App 换进程 / 上次 open 半途失败留下的），
        // 它会与新宿主抢同一条 `blindcast_win_<id>` socket，且它持有的旧几何会把新窗口带偏。
        // 故建新链前先按固定名把该 id 的孤儿宿主杀干净（只碰这一个 id，不动别的窗口）。
        reapOrphanHost(wid)
        val socket = "$SOCKET_PREFIX$wid"
        val stop = "$RUN_DIR/$FILE_PREFIX$wid.stop"
        val entry = Entry(wid, socket, stop)
        entry.packageName = pkg
        entry.component = component
        entry.intentUrl = url
        entry.kind = normalizedKind
        entry.userId = userId.coerceIn(0, 999)
        entry.width = width
        entry.height = height

        // 关联（与整屏桌面共用同一条 MAC；探针那条独立）。
        val assoc = runCatching { VirtualDeviceAssociation.ensure(VirtualDeviceAssociation.OWN_MAC) }
            .getOrDefault(-1)
        if (assoc <= 0) return failure(wid, "自管理关联建立失败（见 logcat BlindCast-VDAssoc）")

        // 宿主 CLASSPATH 必须是**本应用自己的** APK（宿主跑的是本应用的 app_process 入口）。
        val apk = runCatching { PowerController.resolveApkPath(pkg()) }.getOrNull().orEmpty()
        if (apk.isBlank()) return failure(wid, "取本应用 APK 路径失败")

        // 1) 先建 socket 服，宿主随后 connect 上来（顺序不能反）。
        val link = CaptureLink(
            socketName = socket,
            onVideo = { payload, isKey, pts -> StreamWsRoute.broadcastWindowVideo(wid, payload, isKey, pts) },
        )
        val linkOk = runCatching { link.start(width, height, DEFAULT_BITRATE, DEFAULT_FPS) }.getOrDefault(false)
        if (!linkOk) {
            return failure(wid, "搬运服启动失败：${link.errorMessage() ?: "unknown"}")
        }

        // 2) 拉宿主（shell 身份）。
        runCatching { Shell.cmd("rm -f $stop $stop.status").exec() }
        val comp = component.ifBlank { if (url.isBlank()) resolveLauncherComponent(pkg) else "" }
        if (comp.isBlank() && url.isBlank() && normalizedKind == "app") {
            runCatching { link.stop() }
            return failure(wid, "无法解析 $pkg 的启动组件（包名不存在或没有 launcher activity）")
        }
        val encodedUrl = if (url.isBlank()) "-" else android.util.Base64.encodeToString(url.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val encodedComponent = if (comp.isBlank()) "-" else android.util.Base64.encodeToString(comp.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val inner = "CLASSPATH=$apk app_process /system/bin " +
            "com.erl.blindcast.core.scrcpy.FusionDesktopMain window " +
            "$width $height ${DEFAULT_BITRATE} ${DEFAULT_FPS} $assoc $stop $socket $encodedComponent $wid $encodedUrl $normalizedKind ${entry.userId}"
        val cmd = "su 2000 -c '$inner' >/dev/null 2>&1 &"
        runCatching { Shell.cmd(cmd).exec() }
        Log.i(TAG, "[open] wid=$wid pkg=$pkg comp=$comp ${width}x${height} socket=$socket")

        // 3) 等状态文件（宿主就绪会写 state=running）。
        //    真机教训：旧实现只盲等 20s。若宿主根本没起来（root 被拒 / app_process 崩 /
        //    同名孤儿抢 socket），状态文件永远不出现，用户要干等满 20s 才见失败。
        //    这里加宿主存活判定：状态文件迟迟不出现且进程连续 4s 不在 → 立刻失败。
        var waited = 0
        var deadFor = 0
        while (waited < 20_000) {
            Thread.sleep(300L)
            waited += 300
            val m = readStatusFile(entry)
            when (m["state"]) {
                "running" -> {
                    entry.displayId = m["displayId"]?.toIntOrNull() ?: -1
                    entry.deviceId = m["deviceId"]?.toIntOrNull() ?: -1
                    entry.width = m["width"]?.toIntOrNull() ?: width
                    entry.height = m["height"]?.toIntOrNull() ?: height
                    entry.state = "running"
                    entry.error = ""
                    entry.link = link
                    entries[wid] = entry
                    Log.i(TAG, "[open] wid=$wid running displayId=${entry.displayId} deviceId=${entry.deviceId} after=${waited}ms")
                    return entry.toInfo(taskIdOf(entry.displayId))
                }
                "failed" -> {
                    val err = m["error"] ?: "宿主启动失败"
                    Log.e(TAG, "[open] wid=$wid host failed: $err")
                    runCatching { link.stop() }
                    runCatching { Shell.cmd("rm -f $stop $stop.status $stop.sync").exec() }
                    return failure(wid, err)
                }
                else -> {
                    deadFor = if (hostAlive(wid)) 0 else deadFor + 300
                    if (deadFor >= 4_000) {
                        Log.e(TAG, "[open] wid=$wid host not alive after ${waited}ms (status=${m["state"]})")
                        runCatching { link.stop() }
                        runCatching { Shell.cmd("rm -f $stop $stop.status $stop.sync").exec() }
                        return failure(wid, "宿主进程未存活（root 被拒或 app_process 崩溃，见 logcat BlindCast）")
                    }
                }
            }
        }
        runCatching { Shell.cmd("rm -f $stop $stop.status $stop.sync").exec() }
        runCatching { link.stop() }
        Log.e(TAG, "[open] wid=$wid timeout after ${waited}ms")
        return failure(wid, "宿主 ${waited}ms 内未就绪（见 logcat BlindCast/[FusionDesktopMain]）")
    }

    /**
     * 关闭窗口：touch 自己的 stop 文件 → 宿主释放虚拟屏/设备/编码器并退出 → 停搬运服。
     * 该窗口的虚拟显示与屏上应用任务一并销毁；**物理主屏与整屏桌面不受影响**。
     */
    suspend fun close(windowId: Int): Boolean =
        withContext(Dispatchers.IO) { serialized { closeLocked(windowId) } }

    private fun closeLocked(windowId: Int): Boolean {
        val entry = entries[windowId]
        if (entry == null) {
            // 内存里没有但磁盘上可能有（App 进程被换过）：照样按固定名清一遍。
            val stop = "$RUN_DIR/$FILE_PREFIX$windowId.stop"
            runCatching { Shell.cmd("touch $stop").exec() }
            Thread.sleep(1_200L)
            runCatching { Shell.cmd("rm -f $stop $stop.status $stop.sync").exec() }
            // 与正常分支一致：失效该窗口的关键帧缓存，否则新会话接入会补发已关窗口的旧 IDR，
            // 前端找不到该 wid 时会凭空造出幽灵窗口。
            StreamWsRoute.forgetWindow(windowId)
            return true
        }
        runCatching { Shell.cmd("touch ${entry.stopPath}").exec() }
        var waited = 0
        while (waited < 12_000) {
            Thread.sleep(300L)
            waited += 300
            val m = readStatusFile(entry)
            if (m.isEmpty() || m["state"] == "stopped") break
        }
        // 宿主没自己退就强杀它（只杀这一条 window 宿主，不动别的窗口与整屏桌面）。
        val alive = runCatching {
            Shell.cmd("pgrep -f 'FusionDesktopMain window .* $windowId\$' | wc -l").exec()
        }.getOrNull()?.out?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        if (alive > 0) {
            Log.w(TAG, "[close] wid=$windowId host still alive after ${waited}ms, killing")
            runCatching { Shell.cmd("pkill -f 'FusionDesktopMain window .* $windowId\$'; true").exec() }
            Thread.sleep(600L)
        }
        runCatching { entry.link?.stop() }
        runCatching { Shell.cmd("rm -f ${entry.stopPath} ${entry.statusPath} ${entry.stopPath}.sync").exec() }
        entries.remove(windowId)
        StreamWsRoute.forgetWindow(windowId)
        Log.i(TAG, "[close] wid=$windowId done after=${waited}ms killed=${alive > 0}")
        return true
    }

    /** 改尺寸：同一 windowId 重建虚拟屏与编码器（源几何随窗口变）。 */
    suspend fun resize(windowId: Int, width: Int, height: Int): WindowInfo =
        withContext(Dispatchers.IO) {
            serialized {
                val pkg = entries[windowId]?.packageName.orEmpty()
                val comp = entries[windowId]?.component.orEmpty()
                val old = entries[windowId]
                if (old == null || (pkg.isBlank() && old.intentUrl.isBlank() && old.kind == "app")) return@serialized failure(windowId, "窗口不存在或缺少启动目标")
                val url = old.intentUrl
                val kind = old.kind
                val user = old.userId
                closeLocked(windowId)
                openLocked(pkg, comp, width, height, windowId, url, kind, user)
            }
        }

    /** 停掉全部窗口（服务停止 / 应用退出时用）。 */
    suspend fun closeAll(): Int = withContext(Dispatchers.IO) {
        serialized {
            val ids = entries.keys.toList()
            var n = 0
            for (id in ids) if (closeLocked(id)) n++
            // 磁盘上可能还有本进程不知道的宿主（App 被换过）：按固定名全量清。
            runCatching { Shell.cmd("touch $RUN_DIR/${FILE_PREFIX}*.stop").exec() }
            Thread.sleep(1_200L)
            runCatching { Shell.cmd("pkill -f 'FusionDesktopMain window'; true").exec() }
            runCatching { Shell.cmd("rm -f $RUN_DIR/${FILE_PREFIX}*.stop $RUN_DIR/${FILE_PREFIX}*.stop.status $RUN_DIR/${FILE_PREFIX}*.stop.sync").exec() }
            entries.clear()
            n
        }
    }

    /**
     * 与磁盘对账：磁盘 status 是唯一真源（全程持 [stateLock]，不与 [open]/[close] 交错）。
     *
     * - 磁盘上 `state=running` 且宿主存活，但内存里没有它 → **回收**（见下）；
     * - 内存里有而磁盘上没有/不是 running → 回收（停服、清文件、摘记录）。
     *
     * 关于「App 进程被替换后旧宿主失联」的孤儿：宿主侧**没有重连**——采集侧
     * [PrivilegedCapture] 写帧失败只记 `lastError`，不会重连新 socket。因此「纳管」
     * 只能恢复元信息，恢复不了画面。为避免留一个 `state=running` 却无画面的幽灵窗口
     * （还白占一个 windowId 与一路 4Mbps 编码器），这类孤儿宿主一律回收，
     * 让 windowId 空出来供 UI 重新打开。
     */
    fun reconcile() = serialized { reconcileLocked() }

    private fun reconcileLocked() {
        val live = runCatching {
            Shell.cmd("ls -1 $RUN_DIR/${FILE_PREFIX}*.stop.status 2>/dev/null").exec()
        }.getOrNull()?.out?.map { it.trim() }?.filter { it.startsWith("/") } ?: emptyList()
        // 一次读出所有存活窗口宿主的 windowId（argv 末位）。逐窗 pgrep 是 O(N) 次 shell spawn，
        // 在 1.5s 轮询下会堆积成十几秒延迟；这里把对账的进程存活判定降为常数次 shell。
        val alive = runCatching {
            Shell.cmd("for p in \$(pgrep -f 'FusionDesktopMain window'); do tr '\\0' ' ' < /proc/\$p/cmdline; echo; done").exec()
        }.getOrNull()?.out?.mapNotNull { line ->
            line.trim().split(' ').lastOrNull()?.trim()?.toIntOrNull()
        }?.filter { it in 1..255 }?.toSet() ?: emptySet()
        val liveIds = mutableSetOf<Int>()
        for (path in live) {
            val wid = Regex("""${FILE_PREFIX}(\d+)\.stop\.status""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val m = readStatusText(path)
            // 状态文件是唯一真源，但「文件说 running」不等于「宿主还活着」：
            // App 换进程 / 宿主崩溃后文件会残留。故加进程存活判定，死了就当陈旧清掉，不纳管。
            val stopPath = path.removeSuffix(".status")
            if (m["state"] != "running" || wid !in alive) {
                runCatching { Shell.cmd("rm -f $path $stopPath $stopPath.sync").exec() }
                continue
            }
            liveIds += wid
            val cur = entries[wid]
            if (cur == null) {
                // 宿主还活着但内存里没有它：它在上一个 App 进程里连着的那条 socket 已随进程
                // 消失，而宿主不会重连 → 画面永远不会恢复。故不纳管，直接按孤儿宿主回收。
                Log.i(TAG, "[reconcile] wid=$wid host alive but its socket is gone (host has no reconnect), reap")
                reapOrphanHost(wid)
                StreamWsRoute.forgetWindow(wid)
            } else {
                cur.displayId = m["displayId"]?.toIntOrNull() ?: cur.displayId
                cur.state = "running"
            }
        }
        for ((wid, e) in entries) {
            if (wid !in liveIds) {
                Log.i(TAG, "[reconcile] reap wid=$wid (no live status file)")
                runCatching { e.link?.stop() }
                entries.remove(wid)
                StreamWsRoute.forgetWindow(wid)
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun pkg(): String = com.erl.blindcast.BuildConfig.APPLICATION_ID

    /** 解析包名的 launcher 组件（`cmd package resolve-activity --brief`，输出形如 `pkg/.MainActivity`）。 */
    private fun resolveLauncherComponent(packageName: String): String {
        val out = runCatching {
            Shell.cmd("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName").exec()
        }.getOrNull() ?: return ""
        val line = (out.out + out.err).map { it.trim() }.lastOrNull { it.contains('/') } ?: return ""
        return line.substringBefore(' ').trim()
    }

    private fun freeId(): Int? {
        for (i in 1..255) if (!entries.containsKey(i)) return i
        return null
    }

    /** 某 windowId 的宿主进程是否存活（只认 `FusionDesktopMain window … <wid>`，不认 desktop 宿主）。 */
    private fun hostAlive(wid: Int): Boolean = runCatching {
        Shell.cmd("pgrep -f 'FusionDesktopMain window .* $wid\$' | wc -l").exec()
    }.getOrNull()?.out?.firstOrNull()?.trim()?.toIntOrNull()?.let { it > 0 } ?: false

    /**
     * 杀干净某 windowId 的**孤儿**宿主：touch 它自己的 stop 文件让它自退，
     * 3s 没退就 pkill 这一条，最后清掉它的运行文件。只作用于这一个 id，
     * 绝不波及别的窗口或整屏桌面。
     */
    private fun reapOrphanHost(wid: Int) {
        val stop = "$RUN_DIR/$FILE_PREFIX$wid.stop"
        val status = "$stop.status"
        val sync = "$stop.sync"
        if (!hostAlive(wid)) {
            // 进程不在：只清文件即可（旧几何/旧 socket 名不得残留）。
            runCatching { Shell.cmd("rm -f $stop $status $sync").exec() }
            return
        }
        runCatching { Shell.cmd("touch $stop").exec() }
        var waited = 0
        while (waited < 3_000 && hostAlive(wid)) {
            Thread.sleep(200L)
            waited += 200
        }
        if (hostAlive(wid)) {
            Log.w(TAG, "[reap] wid=$wid orphan host alive after ${waited}ms, killing")
            runCatching { Shell.cmd("pkill -f 'FusionDesktopMain window .* $wid\$'; true").exec() }
            Thread.sleep(400L)
        }
        runCatching { Shell.cmd("rm -f $stop $status $sync").exec() }
        Log.i(TAG, "[reap] wid=$wid orphan host reaped after=${waited}ms")
    }

    private fun failure(wid: Int, err: String): WindowInfo =
        WindowInfo(wid, -1, -1, "", "", DEFAULT_WIDTH, DEFAULT_HEIGHT, "failed", err)

    private fun readStatusFile(e: Entry): Map<String, String> = readStatusText(e.statusPath)

    private fun readStatusText(path: String): Map<String, String> {
        val text = runCatching {
            val f = File(path)
            if (f.exists()) f.readText() else ""
        }.getOrDefault("")
        if (text.isBlank()) return emptyMap()
        return text.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }
            .toMap()
    }

    /** 该虚拟屏上的第一个任务 id（列表展示用；拿不到返回 -1，不阻塞）。 */
    private fun taskIdOf(displayId: Int): Int {
        if (displayId <= 0) return -1
        return runCatching {
            DesktopTaskController.listTasks(displayId).firstOrNull()?.taskId ?: -1
        }.getOrDefault(-1)
    }
}
