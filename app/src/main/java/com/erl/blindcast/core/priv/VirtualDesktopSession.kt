package com.erl.blindcast.core.priv

import android.content.ComponentName
import android.content.Context
import android.graphics.Point
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Surface
import java.lang.reflect.Proxy

/**
 * Phase C 虚拟桌面会话（Vds-Session-1 · 在 **shell（uid 2000）** 子进程内持有
 * `VirtualDevice` + 其 `VirtualDisplay` 的生命周期）。
 *
 * ## 为什么单独一层
 * - 关联由 App 侧（root）经 [VirtualDeviceAssociation] 建好后传入；
 * - 设备/虚拟屏必须在 **shell 身份**进程内建（root uid0 会被 VDM 拒，实证见
 *   `outputs/probe/phase-c-probe.md` §3.1）；
 * - 采集侧（[com.erl.blindcast.core.scrcpy.PrivilegedCapture]）只负责把编码器
 *   `inputSurface` 挂上/摘下（[attachSurface]/[detachSurface]），不重复实现建屏。
 *
 * ## 进程模型（**进程内单例，不做跨进程假共享**）
 * 本 `object` 是**进程内**单例：它持有的 `VirtualDevice`/`VirtualDisplay` 只在**当前进程**有效。
 * 因此 VDM 持有进程、`MediaCodec` 创建进程、`Surface` 挂载动作**必须同进程**：
 * 生产路径由**同一个常驻 shell 身份 `app_process`**（`FusionDesktopMain`）同时承担
 * 「建设备 + 建屏」与「跑采集编码器」，surface 是进程内对象直接 `setSurface` 挂上，
 * **不经任何 IPC 传 Surface**。绝不在另一个 `RootCaptureMain`/`RootMain` 进程里
 * 直接引用本单例当共享状态（那只会拿到另一个空实例）。
 *
 * ## 生命周期（与报告口径一致）
 * - [start] 建设备 + 建虚拟屏（**surface 先挂 null**，屏已存在、应用已可启动）；
 * - 采集起 → [attachSurface]（`VirtualDisplay.setSurface`）画面才进编码器；
 *   采集停 → [detachSurface]（挂回 null），**虚拟屏与桌面应用继续存活**；
 * - [stop] 释放虚拟屏 + 关设备，桌面应用随之销毁。
 * 即：桌面模式在 = 桌面临存活；采集按需挂/摘 surface，不决定桌面生死。
 * （若某版实现改为 idle 即 [stop]，必须同步改本注释与对外报告口径，不得宣称持久。）
 *
 * ## 失败语义
 * 任一步失败记 [lastError] 并返回 false，**不静默成功**；[stop] 幂等。
 */
object VirtualDesktopSession {

    private const val TAG = "BlindCast-VDSession"

    /** 当前设备（null = 未运行）。 */
    @Volatile
    private var device: VirtualDeviceBridge.Device? = null

    /** 当前虚拟屏。 */
    @Volatile
    private var display: VirtualDeviceBridge.VirtualDisplayHandle? = null

    /** 屏幕性质参数（日志/状态用）。 */
    @Volatile
    var displayName: String = ""
        private set

    /** 最近失败文案（成功清空）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 桌面是否存活。 */
    val isRunning: Boolean get() = device != null && display != null

    /** 虚拟 displayId（无则 -1）。 */
    val displayId: Int get() = display?.displayId ?: -1

    /** 设备 id（无则 -1）。 */
    val deviceId: Int get() = device?.deviceId ?: -1

    /** 当前挂着的采集 surface 描述（诊断用；null = 未挂）。 */
    @Volatile
    private var attachedSurface: Surface? = null

    val surfaceAttached: Boolean get() = attachedSurface != null

    /**
     * 经 `cmd companiondevice list <user>` 找自己的关联 id（**无 libsu 依赖**，
     * 供壳子进程用）。只认 [VirtualDeviceAssociation.OWN_MAC]。
     */
    fun findOwnAssociationId(userId: Int = 0): Int = runCatching {
        val p = ProcessBuilder("sh", "-c", "cmd companiondevice list $userId")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        VirtualDeviceAssociation.parseOwnId(out)
    }.getOrDefault(-1)

    /**
     * 拉起虚拟桌面：建 [VirtualDeviceBridge.Device] + 一个虚拟屏（surface=null）。
     *
     * @param associationId 关联 id（>0），由 App 侧 root 建好。
     * @param home 虚拟屏 Home 组件（系统会在该屏拉起它）。
     * @param ime 虚拟屏输入法组件（可空）。
     * @return true = 设备与屏均已建立（[displayId] 有效）。
     */
    fun start(
        associationId: Int,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int = VirtualDeviceBridge.defaultDesktopFlags(),
        home: ComponentName? = null,
        ime: ComponentName? = null,
        surface: Surface? = null,
    ): Boolean {
        if (isRunning) {
            Log.i(TAG, "[start] already running displayId=$displayId deviceId=$deviceId, no-op")
            return true
        }
        lastError = null
        displayName = name
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        return try {
            VirtualDeviceBridge.addHiddenApiExemptions()
            val base = VirtualDeviceBridge.obtainBaseContext { step, msg -> Log.i(TAG, "[start][$step] $msg") }
                ?: throw IllegalStateException("拿不到 base Context（裸 app_process 自举失败）")
            val ctx = VirtualDeviceBridge.wrapShellContext(base, uid)
            val mgr = VirtualDeviceBridge.obtainManager(ctx)
            Log.i(TAG, "[start] uid=$uid assocId=$associationId mgr=${mgr.javaClass.name}")
            val dev = VirtualDeviceBridge.createDevice(mgr, associationId, name, home, ime)
            device = dev
            val vd = VirtualDeviceBridge.createVirtualDisplay(
                dev, name, width, height, densityDpi, surface, flags,
                homeSupported = home != null,
            )
            display = vd
            attachedSurface = surface
            Log.i(TAG, "[start] OK deviceId=${dev.deviceId} displayId=${vd.displayId} " +
                "${width}x${height} dpi=$densityDpi flags=$flags home=$home surface=${surface != null}")
            true
        } catch (t: Throwable) {
            val m = "${t.javaClass.simpleName}: ${t.message}"
            lastError = m
            Log.e(TAG, "[start] FAILED $m", t)
            runCatching { stop() }
            false
        }
    }

    /** 把采集编码器的 inputSurface 挂到虚拟屏（`VirtualDisplay.setSurface`）。 */
    fun attachSurface(surface: Surface): Boolean {
        val vd = display ?: run {
            lastError = "attachSurface: 桌面未运行"
            return false
        }
        return runCatching {
            vd.instance.javaClass.getMethod("setSurface", Surface::class.java).invoke(vd.instance, surface)
            attachedSurface = surface
            Log.i(TAG, "[attachSurface] ok displayId=${vd.displayId}")
            true
        }.getOrElse {
            val m = "${VirtualDeviceBridge.unwrap(it).javaClass.simpleName}: ${VirtualDeviceBridge.unwrap(it).message}"
            lastError = "attachSurface failed: $m"
            Log.e(TAG, "[attachSurface] FAILED $m")
            false
        }
    }

    /** 摘掉采集 surface（挂回 null），虚拟屏与桌面应用继续存活。 */
    fun detachSurface(): Boolean {        val vd = display ?: return false
        attachedSurface = null
        return runCatching {
            // 注意：Kotlin vararg 下必须显式给数组，直传 null 会被当成"整个参数数组为 null"。
            vd.instance.javaClass.getMethod("setSurface", Surface::class.java)
                .invoke(vd.instance, arrayOf<Any?>(null))
            Log.i(TAG, "[detachSurface] ok displayId=${vd.displayId}")
            true
        }.getOrElse {
            Log.w(TAG, "[detachSurface] failed: ${VirtualDeviceBridge.unwrap(it).message}")
            false
        }
    }

    /** 本虚拟屏当前夜间模式（默认 false；仅由 [setNightMode] 成功时更新）。 */
    @Volatile
    var nightMode: Boolean = false
        private set

    /**
     * 设置**本虚拟屏**的夜间模式（原版 `display-night-mode` 的逐屏实现）。
     *
     * 反射 `VirtualDevice.setDisplayUiMode(displayId, uiMode)`，`uiMode` 取
     * [VirtualDeviceBridge.UI_MODE_NIGHT_YES]（32）/ [VirtualDeviceBridge.UI_MODE_NIGHT_NO]（16）——
     * 与逆向参考 `MirrorServerMain.java:4115-4185` 完全一致（`z ? 32 : 16`）。
     *
     * **只作用于本虚拟屏**，不动设备全局 `cmd uimode night`（物理主屏不受影响）。
     * 桌面未运行 / displayId 无效 / 反射失败 → false，并写 [lastError]（fail closed）。
     */
    fun setNightMode(on: Boolean): Boolean {
        val dev = device ?: run {
            lastError = "setNightMode: 桌面未运行"
            return false
        }
        val did = displayId
        if (did <= 0) {
            lastError = "setNightMode: displayId=$did 无效"
            return false
        }
        val ui = if (on) VirtualDeviceBridge.UI_MODE_NIGHT_YES else VirtualDeviceBridge.UI_MODE_NIGHT_NO
        val ok = VirtualDeviceBridge.setDisplayUiMode(dev, did, ui)
        if (ok) {
            nightMode = on
        } else {
            lastError = "setDisplayUiMode(display=$did, ui=0x${Integer.toHexString(ui)}) 失败"
        }
        return ok
    }

    /** 释放虚拟屏 + 关设备（幂等；任一步失败只记日志，不抛）。 */
    fun stop() {
        attachedSurface = null
        val vd = display
        display = null
        VirtualDeviceBridge.releaseVirtualDisplay(vd)
        val dev = device
        device = null
        VirtualDeviceBridge.closeDevice(dev)
        Log.i(TAG, "[stop] done (vdReleased=${vd != null} devClosed=${dev != null})")
    }

    /**
     * 该虚拟屏在其所属设备上的 `Display` 信息（宽高/密度/名称），诊断用。
     * 取不到返回 null。
     */
    fun displayInfo(): String? = runCatching {
        val vd = display ?: return null
        val d = vd.instance.javaClass.getMethod("getDisplay").invoke(vd.instance) as? Display ?: return null
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        val name = runCatching { d.name }.getOrNull() ?: "?"
        "displayId=${d.displayId} name=$name ${p.x}x${p.y} state=${d.state}"
    }.getOrNull()

    /** 枚举 `DisplayManager` 里的全部 displayId（验证虚拟屏是否真的注册进去）。 */
    fun listDisplayIds(ctx: Context?): List<Int> = runCatching {
        val dm = ctx?.getSystemService(Context.DISPLAY_SERVICE)
            ?: Class.forName("android.hardware.display.DisplayManager")
                .let { null }
        val dmObj = dm ?: return emptyList()
        val displays = dmObj.javaClass.getMethod("getDisplays").invoke(dmObj) as? Array<*> ?: return emptyList()
        displays.mapNotNull { d ->
            runCatching { d!!.javaClass.getMethod("getDisplayId").invoke(d) as? Int }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** 建一个"吞掉一切"的代理（虚拟屏回调 Executor 用不到，仅为满足签名）。 */
    internal fun nullProxy(type: Class<*>): Any? =
        runCatching { Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } }.getOrNull()

    /** 跑一条 shell 命令取输出（`/system/bin/sh -c`；无 libsu 依赖，供壳子进程诊断用）。 */
    fun shellOut(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.trim()
    }.getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
}
