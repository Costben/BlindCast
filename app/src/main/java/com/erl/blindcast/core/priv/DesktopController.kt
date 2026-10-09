package com.erl.blindcast.core.priv

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase C 虚拟桌面控制器（App 侧，Vdm-Controller-1）。
 *
 * ## 角色划分（**不做跨进程假共享**）
 * - 本对象在 **App 进程**：负责 root 建/清关联、拉起/停掉常驻宿主、读宿主状态文件、转发路由；
 * - `VirtualDevice` / 虚拟屏 / 编码器全部活在 **`su 2000` 派生的 [com.erl.blindcast.core.scrcpy.FusionDesktopMain] 常驻进程**内，
 *   App 侧**只读它写的状态文件**，不引用任何特权侧单例；
 * - 采集仍走既有 socket（[com.erl.blindcast.core.scrcpy.PrivilegedCapture.SOCKET_NAME]），
 *   App 侧 [com.erl.blindcast.core.scrcpy.CaptureSocketLink] 服不变，协议不变。
 *
 * ## 状态迁移串行化（Vdm-Serialize-1 · 真机缺陷修复）
 * `on` / `off` / `home` 以及服务侧空闲回收都会调本对象，真机上出现过
 * 「回收的 off 与用户 on 并发 → 抽象名仍被占 → 下一次 on 直接 EADDRINUSE」。
 * 故三者统一过 [stateLock]：同一时刻只允许一次桌面状态迁移，避免半途交叉。
 *
 * ## 生命周期（与对外报告口径一致）
 * - [on]：`VirtualDeviceAssociation.ensure(OWN_MAC)` → 起宿主 → 轮询状态文件拿到 `displayId`；
 * - [off]：touch stopFile → 宿主自行拆屏关设备并退出 → 清**桌面自己那条**关联（[VirtualDeviceAssociation.OWN_MAC]）；
 * - 桌面存活的唯一定义 = 宿主进程在跑且状态文件为 `state=running`。
 *   **宿主停则桌面与应用一并销毁**（不做 idle 后仍保活的宣称）。
 * - 关联只认 [VirtualDeviceAssociation.OWN_MAC]；探针那条（`PROBE_MAC`）与本对象无关，
 *   本对象既不创建也不删除它（Vdm-Assoc-Own-1）。
 *
 * 运行文件（`stopFile`/`statusFile`）属**生产运行 IPC**，落 `/data/local/tmp`（同既有 stop 文件约定）。
 */
object DesktopController {

    private const val TAG = "BlindCast-Desktop"

    private const val RUN_DIR = "/data/local/tmp"
    private const val PREFIX = "blindcast_desktop_"

    /** 虚拟屏参数（与真机实证一致：720x1280 dpi320）。 */
    const val DEFAULT_WIDTH = 720
    const val DEFAULT_HEIGHT = 1280
    const val DEFAULT_DENSITY = 320
    const val DEFAULT_BITRATE = 4_000_000
    const val DEFAULT_FPS = 30

    /**
     * 桌面状态迁移串行锁。
     *
     * `on/off/home` 全程阻塞持锁（内部是 `Thread.sleep` + libsu 同步 Shell），
     * 这正是我们要的语义：任何时刻只有一个迁移在跑，第二个调用者排队而不是交叉。
     */
    private val stateLock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> serialized(block: () -> T): T {
        stateLock.lock()
        try {
            return block()
        } finally {
            stateLock.unlock()
        }
    }

    /**
     * 本次会话的 stop 文件。**按固定名命名，不按 PID**：
     * 旧实现按 PID 命名，App 进程被替换后新会话读不到旧状态文件、旧宿主也收不到 stop 信号，
     * 于是残留宿主一直持有 VDM 设备与虚拟屏（真机实证：泄漏 displayId 219 / 280，
     * 并把 `am start --display` 落到死屏上）。固定名让「换号不失联」，
     * 再配合 [verifyHostAlive] 处理「文件还在但宿主已死」的陈旧态。
     */
    private fun stopFile(): String = "$RUN_DIR/${PREFIX}host.stop"

    private fun statusFile(): String = stopFile() + ".status"

    /**
     * 请求整屏桌面源立刻产一个 IDR（Request-Sync-1）。
     *
     * App 侧 touch `blindcast_desktop_host.stop.sync` → 宿主 250ms 内消费 →
     * `setParameters({"request-sync":0})`。用于新客户端接入与背压丢帧后的重同步。
     * 桌面未运行时是空操作（宿主不在，没人消费那个文件，[sweepStaleHosts] 会清掉）。
     */
    fun requestSync() {
        runCatching { Shell.cmd("touch ${stopFile()}.sync").exec() }
    }

    /** 逐屏夜间模式结果。 */
    data class NightResult(val ok: Boolean, val on: Boolean?, val displayId: Int, val error: String)

    /**
     * 设置**桌面虚拟屏**的夜间模式（原版 `display-night-mode` 的逐屏实现，Display-Api）。
     *
     * 写 `<stopFile>.uimode`（`yes`/`no`）→ 宿主 250ms 内消费并反射
     * `VirtualDevice.setDisplayUiMode(displayId, 32|16)` → 回写 `<stopFile>.uimode.state`。
     * **只作用于本虚拟屏**，不动设备全局 `cmd uimode night`。
     *
     * 桌面未运行 → `ok=false`（fail closed，绝不隐式改全局）；宿主 5s 内无回执 → 超时失败。
     */
    fun setNightMode(on: Boolean): NightResult = serialized {
        val st = status()
        if (!st.running) return@serialized NightResult(false, null, -1, "桌面未运行")
        applyNightMode(stopFile(), st.displayId, on)
    }

    /** Caller holds its own display lifecycle lock until the host acknowledges. */
    internal fun applyNightMode(stop: String, displayId: Int, on: Boolean): NightResult {
        val cmdFile = "$stop.uimode"
        val stateFile = "$stop.uimode.state"
        runCatching { Shell.cmd("rm -f $stateFile").exec() }
        runCatching { Shell.cmd("echo ${if (on) "yes" else "no"} > $cmdFile").exec() }
        var waited = 0
        while (waited < 5_000) {
            Thread.sleep(150L)
            waited += 150
            val txt = runCatching { Shell.cmd("cat $stateFile 2>/dev/null").exec() }
                .getOrNull()?.out?.joinToString("\n").orEmpty()
            if (txt.contains("ok=")) {
                val ok = txt.contains("ok=1")
                Log.i(TAG, "[setNightMode] on=$on ok=$ok displayId=$displayId after=${waited}ms")
                return NightResult(ok, if (ok) on else null, displayId, if (ok) "" else "setDisplayUiMode 失败")
            }
        }
        Log.w(TAG, "[setNightMode] timeout on=$on displayId=$displayId")
        return NightResult(false, null, displayId, "宿主 5s 内未回写 uimode 结果")
    }

    /** 状态快照（对应 `GET /api/desktop`）。 */
    data class Status(
        val running: Boolean,
        val displayId: Int,
        val deviceId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val source: String,
        val error: String,
        val assocId: Int,
    ) {
        val mode: String get() = if (running) "desktop" else "mirror"
    }

    /** 读宿主写的状态文件（不存在 = 未运行）。 */
    private fun readStatusFile(): Map<String, String> {
        val text = runCatching {
            val f = File(statusFile())
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

    /** 当前状态（纯读，不产生副作用；但会校验宿主存活，陈旧状态文件自动作废）。 */
    fun status(): Status {
        val m = readStatusFile()
        val state = m["state"] ?: "stopped"
        var running = state == "running"
        if (running && !verifyHostAlive()) {
            // 状态文件还在但宿主已死（App 被替换/宿主崩溃）：判为未运行并清掉陈旧文件，
            // 否则「桌面 active」会把物理镜像采集一并 skip，画面彻底没有（真机实证）。
            Log.w(TAG, "[status] stale status file (host dead), clearing ${statusFile()}")
            runCatching { Shell.cmd("rm -f ${stopFile()} ${statusFile()} ${stopFile()}.sync").exec() }
            running = false
        }
        return Status(
            running = running,
            displayId = m["displayId"]?.toIntOrNull() ?: -1,
            deviceId = m["deviceId"]?.toIntOrNull() ?: -1,
            width = m["width"]?.toIntOrNull() ?: DEFAULT_WIDTH,
            height = m["height"]?.toIntOrNull() ?: DEFAULT_HEIGHT,
            densityDpi = m["densityDpi"]?.toIntOrNull() ?: DEFAULT_DENSITY,
            source = if (running) (m["source"] ?: "virtualDisplay") else "mirror",
            error = m["error"] ?: "",
            assocId = m["assocId"]?.toIntOrNull() ?: -1,
        )
    }

    /**
     * 宿主存活判定（`pgrep -f "FusionDesktopMain desktop"`，1s 缓存）。
     *
     * 只认**整屏桌面**宿主：逐窗口宿主是 `FusionDesktopMain window …`，两者必须分开判，
     * 否则窗口在跑会把已死的桌面会话判成活着。
     */
    private fun verifyHostAlive(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedAliveAt
        if (cached > 0 && now - cached < 1_000L) return cachedAlive
        val n = runCatching {
            Shell.cmd("pgrep -f 'FusionDesktopMain desktop' | wc -l").exec()
        }.getOrNull()?.out?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        cachedAlive = n > 0
        cachedAliveAt = now
        return cachedAlive
    }

    @Volatile private var cachedAlive: Boolean = false
    @Volatile private var cachedAliveAt: Long = 0L

    /** 失败状态工厂（口径统一，避免每处手拼）。 */
    private fun failure(error: String): Status =
        Status(false, -1, -1, DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_DENSITY, "mirror", error, -1)

    /**
     * 打开虚拟桌面：root 建关联 → 拉常驻宿主（`su 2000` 派生）→ 轮询状态文件拿 displayId。
     *
     * @return 成功时的 [Status]（running=true）；失败返回 running=false + error 文案
     *  （**绝不静默成功**，App 侧据此回落物理镜像）。
     */
    suspend fun on(packageName: String, apkPath: String): Status =
        withContext(Dispatchers.IO) { serialized { onLocked(apkPath) } }

    private fun onLocked(apkPath: String): Status {
        val cur = status()
        if (cur.running) {
            Log.i(TAG, "[on] already running displayId=${cur.displayId}, no-op")
            return cur
        }
        if (apkPath.isBlank()) {
            return failure("取 APK 路径失败")
        }
        // Stale-Host-1：先清扫上一代残留宿主再建新的。
        // 真机实证（216）：App 进程被替换（重装/崩溃被杀）时，先前派生的 FusionDesktopMain
        // 子进程仍在跑并持有它的 VDM 设备与虚拟屏 → displayId 219 泄漏；随后 shell 侧
        // `am start` 会把应用落到那张死屏上，物理主屏证据被污染。
        // 顺序：touch 所有历史 stop 文件（礼貌停，让旧宿主走 [PrivilegedCapture.stop] 自行
        // closeVirtualDevice）→ 等它退 → 仍在的才强杀 → 清 stop 文件。
        runCatching { sweepStaleHosts() }
        val assoc = runCatching { VirtualDeviceAssociation.ensure(VirtualDeviceAssociation.OWN_MAC) }
            .getOrDefault(-1)
        if (assoc <= 0) {
            return failure("自管理关联建立失败（见 logcat BlindCast-VDAssoc）")
        }
        val stop = stopFile()
        runCatching { Shell.cmd("rm -f $stop ${stop}.status $stop.sync ${stop}.uimode ${stop}.uimode.state").exec() }
        val inner = "CLASSPATH=$apkPath app_process /system/bin " +
            "com.erl.blindcast.core.scrcpy.FusionDesktopMain desktop " +
            "${DEFAULT_WIDTH} ${DEFAULT_HEIGHT} ${DEFAULT_BITRATE} ${DEFAULT_FPS} $assoc $stop " +
            "${com.erl.blindcast.core.scrcpy.PrivilegedCapture.SOCKET_NAME}"
        // 后台派生：`&` 让 root shell 立刻返回，宿主继续以 uid 2000 常驻。
        val cmd = "su 2000 -c '$inner' >/dev/null 2>&1 &"
        val launched = runCatching { Shell.cmd(cmd).exec() }
        Log.i(TAG, "[on] launched host assoc=$assoc exit=${launched.getOrNull()?.code} " +
            "out=${launched.getOrNull()?.out?.take(3)} err=${launched.getOrNull()?.err?.take(3)}")
        // 新宿主刚拉起：立刻让存活判定重新取样，别用旧的 false 缓存把状态判死。
        cachedAlive = false
        cachedAliveAt = 0L
        // 轮询状态文件（宿主先建屏再拉起 Home，约 3-6s）。
        var waited = 0
        while (waited < 20_000) {
            Thread.sleep(400L)
            waited += 400
            val m = readStatusFile()
            when (m["state"]) {
                "running" -> {
                    val st = status()
                    Log.i(TAG, "[on] running displayId=${st.displayId} deviceId=${st.deviceId} " +
                        "after=${waited}ms")
                    return st.copy(assocId = assoc)
                }
                "failed" -> {
                    val err = m["error"] ?: "宿主启动失败"
                    Log.e(TAG, "[on] host reported failure: $err")
                    runCatching { VirtualDeviceAssociation.release(VirtualDeviceAssociation.OWN_MAC) }
                    return failure(err)
                }
            }
        }
        // 超时：清自己的关联并回落（不静默成功）。
        runCatching { Shell.cmd("rm -f $stop").exec() }
        runCatching { VirtualDeviceAssociation.release(VirtualDeviceAssociation.OWN_MAC) }
        Log.e(TAG, "[on] timeout waiting for host status after ${waited}ms")
        return failure("宿主 ${waited}ms 内未就绪（见 logcat BlindCast/[FusionDesktopMain]）")
    }

    /**
     * Stale-Host-1：清扫上一代残留的桌面子进程。
     *
     * 同进程重启后 [stopFile] 带的是**新** PID，旧宿主的 stop 文件永远不会被 touch，
     * 于是它一直持有 VDM 设备（真机实证泄漏出 displayId 219）。这里对历史 stop 文件
     * 全量 touch 一次触发其正常退出路径，残留的才强杀；最后清掉所有历史 stop 文件。
     */
    private fun sweepStaleHosts() {
        val pattern = "$RUN_DIR/${PREFIX}*.stop"
        runCatching {
            Shell.cmd("for f in $pattern; do [ -e \"\$f\" ] && touch \"\$f\"; done; true").exec()
        }
        Thread.sleep(1_200L)
        // 只杀**整屏桌面**宿主：逐窗口宿主是 `FusionDesktopMain window …`，
        // 它们有自己的 stop 文件与生命周期（DesktopWindowController），
        // 这里一刀切会连用户正开着的窗口一起拆掉。
        val left = runCatching {
            Shell.cmd("pgrep -f 'FusionDesktopMain desktop' | wc -l").exec()
        }.getOrNull()?.out?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        if (left > 0) {
            Log.w(TAG, "[on] stale desktop hosts left=$left, terminating")
            runCatching {
                Shell.cmd("pkill -f 'FusionDesktopMain desktop'; true").exec()
            }
            Thread.sleep(800L)
        }
        runCatching { Shell.cmd("rm -f $pattern; true").exec() }
        runCatching { Shell.cmd("rm -f $RUN_DIR/${PREFIX}*.stop.sync; true").exec() }
        runCatching { Shell.cmd("rm -f $RUN_DIR/${PREFIX}*.stop.uimode $RUN_DIR/${PREFIX}*.stop.uimode.state; true").exec() }
        Log.i(TAG, "[on] stale host sweep done (left=$left, killed=${left > 0})")
    }

    /**
     * 关闭虚拟桌面：touch stopFile → 宿主自拆 → 清桌面自己那条关联。
     *
     * 只负责桌面资源；**搬运服（socket 监听）由
     * [com.erl.blindcast.core.service.BlindCastForegroundService.stopDesktopBlocking] 收**，
     * 由调用方（路由）在结束后调用，保证「切回物理镜像」时抽象名已释放。
     */
    suspend fun off(packageName: String): Status =
        withContext(Dispatchers.IO) { serialized { offLocked() } }

    private fun offLocked(): Status {
        val stop = stopFile()
        runCatching { Shell.cmd("touch $stop").exec() }
        Log.i(TAG, "[off] stop file touched $stop")
        var waited = 0
        while (waited < 15_000) {
            Thread.sleep(400L)
            waited += 400
            if (!status().running) break
        }
        // 逐窗口流与整屏桌面共用同一条 companion 关联：窗口还开着就**不能**摘关联，
        // 否则正在跑的窗口宿主会当场失去 VDM 关联（其虚拟屏随即不可用）。
        if (com.erl.blindcast.core.priv.DesktopWindowController.isActive()) {
            Log.i(TAG, "[off] windows still active, keep association")
        } else {
            runCatching { VirtualDeviceAssociation.release(VirtualDeviceAssociation.OWN_MAC) }
        }
        runCatching { Shell.cmd("rm -f $stop ${stop}.status $stop.sync ${stop}.uimode ${stop}.uimode.state").exec() }
        cachedAlive = false
        cachedAliveAt = 0L
        Log.i(TAG, "[off] done after=${waited}ms stillRunning=${status().running}")
        return Status(false, -1, -1, DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_DENSITY, "mirror", "", -1)
    }

    /**
     * 把 `FusionHomeActivity` 拉到副屏前台（不改桌面存亡）。
     *
     * @return 成功时 running 状态；桌面未运行时返回 error。
     */
    suspend fun home(packageName: String, apkPath: String): Status =
        withContext(Dispatchers.IO) { serialized { homeLocked(packageName) } }

    private fun homeLocked(packageName: String): Status {
        val st = status()
        if (!st.running) {
            return st.copy(error = "桌面未运行，无法回主页")
        }
        val out = runCatching {
            Shell.cmd("am start -W --display ${st.displayId} -n $packageName/.FusionHomeActivity").exec()
        }.getOrNull()
        val text = (out?.out.orEmpty() + out?.err.orEmpty()).joinToString(" ").trim()
        Log.i(TAG, "[home] displayId=${st.displayId} result=${text.take(200)}")
        return if (text.contains("Status: ok")) st else st.copy(error = "home 启动未确认：${text.take(160)}")
    }

    /**
     * 读桌面虚拟屏最近一次夜间模式状态（宿主回写的 `<stopFile>.uimode.state`）。
     * 无记录返回 null。
     */
    fun nightModeState(): Boolean? {
        val txt = runCatching { Shell.cmd("cat ${stopFile()}.uimode.state 2>/dev/null").exec() }
            .getOrNull()?.out?.joinToString("\n").orEmpty()
        // A failed or missing acknowledgment does not establish the actual mode.
        if (!txt.contains("on=") || !txt.contains("ok=1")) return null
        return txt.contains("on=1")
    }

    /** 一句话状态（日志/控制台用）。 */
    fun stateLine(): String {        val s = status()
        return "mode=${s.mode} displayId=${s.displayId} deviceId=${s.deviceId} " +
            "size=${s.width}x${s.height} source=${s.source} error=${s.error}"
    }
}
