package com.erl.blindcast.core.priv

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Phase C 虚拟桌面桥（Vdb-Bridge-1 · 运行时类反射，**不硬编顶层 `VirtualDevice`**）。
 *
 * ## 为什么按运行时返回类反射
 * 真机实证（`outputs/probe/raw/m2/m2_runtime_class_dump.json`）：
 * - `createVirtualDevice(int, VirtualDeviceParams)` 是 `VirtualDeviceManager` 上**唯一**的 overload；
 * - 其返回对象的运行时类是 **`android.companion.virtual.VirtualDeviceManager$VirtualDevice`**，
 *   与顶层 `android.companion.virtual.VirtualDevice` **不是同一个类**——顶层类既没有
 *   `createVirtualDisplay` 也没有 `setDisplayUiMode`。故本对象只经 `instance.javaClass`
 *   取方法，绝不 `Class.forName("...VirtualDevice")`。
 *
 * ## 运行时实测方法（46 个，摘录与本任务相关）
 * - `int getDeviceId()`
 * - `VirtualDisplay createVirtualDisplay(int w, int h, int densityDpi, Surface, int flags,
 *   Executor, VirtualDisplay.Callback)`
 * - `VirtualDisplay createVirtualDisplay(VirtualDisplayConfig, Executor, VirtualDisplay.Callback)`
 * - `void setDisplayUiMode(int displayId, int uiMode)` / `void setDisplayImePolicy(int,int)`
 * - `Context createContext()`  ← 该设备作用域的 Context（DisplayManager 只含本设备屏）
 * - `void close()`
 *
 * ## 身份
 * 必须在 **shell（uid 2000）** 身份进程内调用（真机实证：root uid 0 带
 * `com.android.shell` 关联会被拒 `SecurityException: Package name com.android.shell
 * does not belong to calling uid 0`）。关联由 [VirtualDeviceAssociation] 在 root 侧建好后，
 * 本桥在 shell 子进程内使用。
 *
 * ## 生命周期
 * [createDevice] → [createVirtualDisplay] → 使用 → [releaseVirtualDisplay] → [closeDevice]，
 * 每步写 logcat TAG `BlindCast-VDBridge`；任一步失败抛异常由调用方记录，**不静默成功**。
 */
object VirtualDeviceBridge {

    /** 专用 TAG（`BlindCast-*` 前缀）。 */
    private const val TAG = "BlindCast-VDBridge"

    const val VM_CLASS = "android.companion.virtual.VirtualDeviceManager"
    const val PARAMS_CLASS = "android.companion.virtual.VirtualDeviceParams"
    const val BUILDER_CLASS = "android.companion.virtual.VirtualDeviceParams\$Builder"

    /** VDM 归属包名（与关联一致，evidence-based）。 */
    const val SHELL_PACKAGE = "com.android.shell"

    /** `VirtualDeviceParams.LOCK_STATE_UNLOCKED`（Builder.setLockState 取值）。 */
    const val LOCK_STATE_UNLOCKED = 1

    /** `POLICY_TYPE_CLIPBOARD` / `DEVICE_POLICY_CUSTOM`（真机实证常量值）。 */
    const val POLICY_TYPE_CLIPBOARD = 4
    const val DEVICE_POLICY_CUSTOM = 1

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`（只显示本设备内容）。 */
    const val FLAG_OWN_CONTENT_ONLY = 8

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC`。 */
    const val FLAG_PUBLIC = 1

    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_*` 运行时真值（**反射取，绝不硬编猜值**）。 */
    private val dmFlags: Map<String, Int> by lazy {
        runCatching {
            Class.forName("android.hardware.display.DisplayManager").fields
                .filter { it.name.startsWith("VIRTUAL_DISPLAY_FLAG_") && it.type == Integer.TYPE }
                .associate { it.name to it.getInt(null) }
        }.onFailure { Log.w(TAG, "[dmFlags] reflect failed: ${it.message}") }
            .getOrDefault(emptyMap())
    }

    /** 取某个 `VIRTUAL_DISPLAY_FLAG_*` 的运行时值（未知返回 -1）。 */
    fun flagOf(name: String): Int = dmFlags[name] ?: -1

    /** 按名取并集（未知项按 0 计，不污染结果）。 */
    fun flagsOf(vararg names: String): Int =
        names.fold(0) { acc, n -> acc or flagOf(n).coerceAtLeast(0) }

    /** 全部 `VIRTUAL_DISPLAY_FLAG_*` 真值（诊断/验收打印用，逐行 `name=value`）。 */
    fun dumpFlags(): String =
        dmFlags.entries.sortedBy { it.value }.joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { "(DisplayManager flags 反射为空)" }

    /** `Set` 默认桌面显示旗标组合（`OWN_CONTENT_ONLY | TRUSTED`），运行时取值。 */
    fun defaultDesktopFlags(): Int = flagsOf("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", "VIRTUAL_DISPLAY_FLAG_TRUSTED")

    /**
     * `DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED`（1 shl 12 = 4096）。
     *
     * 真机实证（`DisplayManagerService` 原话）：
     * `Display created with home support but lacks VIRTUAL_DISPLAY_FLAG_TRUSTED,
     * ignoring the home support request.` —— 想让系统在虚拟屏拉起 Home，
     * `setHomeSupported(true)` **必须**同时带上本 flag，否则该项被静默忽略。
     */
    const val FLAG_TRUSTED_FALLBACK = 4096

    /** 已建虚拟设备句柄。 */
    class Device(
        val instance: Any,
        val cls: Class<*>,
        val deviceId: Int,
        val displayName: String,
    ) {
        /** 本设备作用域 Context（可能为 null，取不到不影响建屏）。 */
        val scopedContext: Context? by lazy {
            runCatching { cls.getMethod("createContext").invoke(instance) as? Context }
                .onFailure { Log.w(TAG, "[Device] createContext failed: ${it.message}") }
                .getOrNull()
        }
    }

    /** 已建虚拟屏句柄。 */
    class VirtualDisplayHandle(
        val instance: Any,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    // ------------------------------------------------------------------
    // 反射家底
    // ------------------------------------------------------------------

    /** 放开隐藏 API 豁免（`Landroid/companion/virtual` 等；多次调用幂等）。 */
    fun addHiddenApiExemptions() {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/companion/virtual",
                "Landroid/hardware/display/VirtualDisplay",
                "Landroid/hardware/display/VirtualDisplayConfig",
                "Landroid/hardware/display/DisplayManager",
                "Landroid/content/AttributionSource",
                "Landroid/content/Context",
                "Landroid/app/ContextImpl",
                "Landroid/app/ActivityThread",
                "Landroid/app/Application",
                "Landroid/app/ConfigurationController",
                "Landroid/app/AppGlobals",
                "Landroid/os/Looper",
                // Dtc-Ipc-1：副屏任务查询/恢复要反射 ATMS。真机实证 targetSdk 37 下
                // `ActivityTaskManager.getService()` 被 hiddenapi 拒绝（logcat：
                //   hiddenapi: Accessing hidden method ...getService()... denied），
                // 故这三条必须豁免，否则 listTasks 恒空、switchTask 恒“任务不存在”。
                "Landroid/app/ActivityTaskManager",
                "Landroid/app/ActivityManager",
                "Landroid/app/IActivityTaskManager",
                // Dtc-Ipc-1-c：副屏任务归属反查要读 `TaskInfo.displayId`。真机实证该字段同样被
                // hiddenapi 拒绝（denied）→ 反射兜底值 0 会把副屏 Task 误判成「在物理屏 0」，
                // switchTask 于是错误拒绝（实测 task 14071 明明在 232 却报「在 0」）。
                "Landroid/app/TaskInfo",
            )
        }.onFailure { Log.w(TAG, "[exemptions] addHiddenApiExemptions failed: ${it.message}") }
    }

    /** 伪装成 shell 身份的 Context（VDM 归属校验读 `getAttributionSource().getPackageName()`）。 */
    private class ShellContext(base: Context, val pkgName: String, val selfUid: Int) :
        ContextWrapper(base) {
        val attribution: AttributionSource? = runCatching {
            val b = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(selfUid)
            b.javaClass.getMethod("setPackageName", String::class.java).invoke(b, pkgName)
            b.javaClass.getMethod("build").invoke(b) as? AttributionSource
        }.getOrNull()

        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            attribution ?: super.getAttributionSource()
        override fun getOpPackageName(): String = pkgName
        override fun getPackageName(): String = pkgName
    }

    /**
     * 取 base Context（裸 `app_process` 内前几路全空，靠自举 `ActivityThread`）。
     * 供 [RootCaptureMain] / [VirtualDeviceProbe] 同款裸进程复用。
     *
     * @param log 逐路日志回调（step, msg）。
     */
    fun obtainBaseContext(log: (String, String) -> Unit): Context? {
        runCatching {
            val m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            m.isAccessible = true
            (m.invoke(null) as? Context)?.let { log("ctx.currentApplication", it.javaClass.name); return it }
        }.onFailure { log("ctx.currentApplication", "EXC ${it.javaClass.simpleName}: ${it.message}") }

        runCatching {
            if (Looper.myLooper() == null) Looper.prepare()
            if (Looper.getMainLooper() == null) {
                Looper::class.java.getDeclaredField("sMainLooper").apply { isAccessible = true }
                    .set(null, Looper.myLooper())
            }
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            val initApp = at.getDeclaredField("mInitialApplication").apply { isAccessible = true }
            if (initApp.get(thread) == null) {
                val sysCtx = runCatching {
                    at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as? Context
                }.getOrNull()
                if (sysCtx != null) {
                    val appCls = Class.forName("android.app.Application")
                    val app = appCls.getDeclaredConstructor().newInstance()
                    appCls.getDeclaredMethod("attach", Context::class.java)
                        .apply { isAccessible = true }.invoke(app, sysCtx)
                    initApp.set(thread, app)
                }
            }
            runCatching {
                val ccField = at.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                if (ccField.get(thread) == null) {
                    val ccCls = Class.forName("android.app.ConfigurationController")
                    val internalCls = Class.forName("android.app.ActivityThreadInternal")
                    ccField.set(thread, ccCls.getDeclaredConstructor(internalCls)
                        .apply { isAccessible = true }.newInstance(thread))
                }
            }
            val sys = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(thread) as? Context
            if (sys != null) log("ctx.bootstrap", sys.javaClass.name)
            return sys
        }.onFailure { log("ctx.bootstrap", "EXC ${unwrap(it).javaClass.name}: ${unwrap(it).message}") }

        log("ctx.base", "null")
        return null
    }

    /** 把 base Context 包成 shell 身份（attribution 用进程真实 uid，不硬编 2000）。 */
    fun wrapShellContext(base: Context, uid: Int = runCatching { Process.myUid() }.getOrDefault(2000)): Context =
        ShellContext(base, SHELL_PACKAGE, uid)

    /**
     * 反射取 `VirtualDeviceManager` 实例（隐藏 `mService` 字段 + `(IVirtualDeviceManager, Context)` 构造器）。
     * @return manager 实例；失败抛异常。
     */
    fun obtainManager(ctx: Context): Any {
        val mgrClass = Class.forName(VM_CLASS)
        val svc = Context::class.java.getMethod("getSystemService", Class::class.java).invoke(ctx, mgrClass)
            ?: throw IllegalStateException("getSystemService(${mgrClass.name}) returned null")
        val mService = mgrClass.getDeclaredField("mService").apply { isAccessible = true }
        val serviceObj = mService.get(svc)
            ?: throw IllegalStateException("mService field null")
        val ctor = mgrClass.getConstructor(mService.type, Context::class.java).apply { isAccessible = true }
        return ctor.newInstance(serviceObj, ctx)
    }

    // ------------------------------------------------------------------
    // 设备生命周期
    // ------------------------------------------------------------------

    /**
     * 建 `VirtualDeviceParams`（Builder 逐项 set，失败即抛 —— 不静默降级）。
     *
     * @param home 虚拟屏 Home 组件（`setHomeComponent`；null 则跳过）。
     * @param ime 虚拟屏输入法组件（`setInputMethodComponent`；null 则跳过）。
     * @param clipboardPolicy 剪贴板策略值（null 则不下发）。
     */
    private fun buildParams(
        name: String,
        home: ComponentName?,
        ime: ComponentName?,
        clipboardPolicy: Int?,
        lockState: Int,
    ): Any {
        var builder: Any = Class.forName(BUILDER_CLASS).getConstructor().newInstance()
        fun set(method: String, types: Array<Class<*>>, vararg args: Any?) {
            val m = builder.javaClass.getMethod(method, *types)
            val ret = m.invoke(builder, *args)
            if (ret != null) builder = ret
        }
        set("setName", arrayOf(String::class.java), name)
        set("setLockState", arrayOf(Integer.TYPE), lockState)
        home?.let { set("setHomeComponent", arrayOf(ComponentName::class.java), it) }
        ime?.let { set("setInputMethodComponent", arrayOf(ComponentName::class.java), it) }
        clipboardPolicy?.let {
            set("setDevicePolicy", arrayOf(Integer.TYPE, Integer.TYPE), POLICY_TYPE_CLIPBOARD, it)
        }
        return builder.javaClass.getMethod("build").invoke(builder)
    }

    /**
     * 建虚拟设备。
     *
     * @param associationId [VirtualDeviceAssociation.ensure] 返回的关联 id（>0）。
     * @param clipboardPolicy 剪贴板策略；**默认 null（不下发）**。
     *  真机实证：一旦下发 `setDevicePolicy(POLICY_TYPE_CLIPBOARD, DEVICE_POLICY_CUSTOM)`，
     *  建虚拟屏即被拒 `SecurityException: All displays must be trusted for devices with
     *  custom clipboard policy.`（自定义剪贴板策略要求"受信显示"，而 VDM 建的屏不是）。
     *  故默认不下发；确需剪贴板策略时应先确认显示可信链。
     * @return [Device]（含 deviceId）；失败抛异常。
     */
    fun createDevice(
        manager: Any,
        associationId: Int,
        name: String,
        home: ComponentName?,
        ime: ComponentName?,
        clipboardPolicy: Int? = null,
        lockState: Int = LOCK_STATE_UNLOCKED,
    ): Device {
        if (associationId <= 0) throw IllegalArgumentException("associationId=$associationId 非法（须 >0）")
        val params = buildParams(name, home, ime, clipboardPolicy, lockState)
        // 唯一 overload：(int associationId, VirtualDeviceParams)；不枚举不猜。
        val m: Method = manager.javaClass.methods.firstOrNull {
            it.name == "createVirtualDevice" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: throw NoSuchMethodException("createVirtualDevice(int, VirtualDeviceParams) 不存在")
        val device = runCatching { m.invoke(manager, associationId, params) }
            .getOrElse { throw unwrap(it) }
            ?: throw IllegalStateException("createVirtualDevice 返回 null")
        val cls = device.javaClass
        val deviceId = runCatching { cls.getMethod("getDeviceId").invoke(device) as? Int }
            .getOrNull() ?: -1
        Log.i(TAG, "[createDevice] ok class=${cls.name} deviceId=$deviceId assocId=$associationId " +
            "name=$name home=$home ime=$ime")
        return Device(device, cls, deviceId, name)
    }

    /**
     * 在虚拟设备上建虚拟屏。
     *
     * ## 为什么走 `VirtualDisplayConfig` overload（照 findings 原版）
     * 逆向参考 `p177r0/C2524f0.java:340` 用的是
     * `VirtualDevice.createVirtualDisplay(VirtualDisplayConfig, Executor, Callback)`，
     * 且配置里多做两件事（我们的第一版漏掉，故 Home 不被拉起、屏一直 OFF）：
     * - `p177r0/C2524f0.java:158-172` 的 `m4880a(...)`：
     *   `Builder(name,w,h,dpi).setFlags(f).setSurface(s)` +
     *   **SDK≥36 `setIgnoreActivitySizeRestrictions(true)`** +
     *   **`setHomeSupported(true)`**（反射，缺则记日志不炸）。
     *
     * @param surface 输出 Surface（编码器 inputSurface）；null 时屏无输出，实测会停在 OFF。
     * @param homeSupported 是否允许该屏承载 Home（对应 `setHomeSupported`）。
     */
    fun createVirtualDisplay(
        device: Device,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
        flags: Int = defaultDesktopFlags(),
        homeSupported: Boolean = true,
    ): VirtualDisplayHandle {
        val cls = device.cls
        val errors = StringBuilder()

        val cfgOverload = cls.methods.firstOrNull {
            it.name == "createVirtualDisplay" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0].name == "android.hardware.display.VirtualDisplayConfig"
        }
        if (cfgOverload != null) {
            runCatching {
                val b = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
                    .getConstructor(String::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE)
                    .newInstance(name, width, height, densityDpi)
                b.javaClass.getMethod("setFlags", Integer.TYPE).invoke(b, flags)
                b.javaClass.getMethod("setSurface", Surface::class.java).invoke(b, surface)
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    runCatching {
                        b.javaClass.getMethod("setIgnoreActivitySizeRestrictions", Boolean::class.javaPrimitiveType)
                            .invoke(b, true)
                    }.onFailure { Log.w(TAG, "[createVirtualDisplay] setIgnoreActivitySizeRestrictions 不可用: ${it.message}") }
                }
                if (homeSupported) {
                    runCatching {
                        // 关键：不加这一句，系统不会在该屏拉起 Home（findings 同款反射）。
                        b.javaClass.getMethod("setHomeSupported", Boolean::class.javaPrimitiveType).invoke(b, true)
                    }.onFailure { Log.w(TAG, "[createVirtualDisplay] setHomeSupported 不可用: ${it.message}") }
                }
                val cfg = b.javaClass.getMethod("build").invoke(b)
                val cbType = cfgOverload.parameterTypes[2]
                val cb = nullProxy(cbType)
                cfgOverload.invoke(device.instance, cfg, null, cb)
            }.onSuccess { return handleOf(it, name, width, height, densityDpi, "VirtualDisplayConfig(home=$homeSupported)") }
                .onFailure { errors.append("cfg:").append(unwrap(it).javaClass.simpleName)
                    .append(':').append(unwrap(it).message).append("; ") }
        } else {
            errors.append("cfg:NoSuchMethod; ")
        }

        val direct = Executor { it.run() }
        val sevenArg = cls.methods.firstOrNull {
            it.name == "createVirtualDisplay" && it.parameterTypes.size == 7 &&
                it.parameterTypes[0] == Integer.TYPE && it.parameterTypes[3] == Surface::class.java
        }
        if (sevenArg != null) {
            val cbType = sevenArg.parameterTypes[6]
            runCatching { sevenArg.invoke(device.instance, width, height, densityDpi, surface, flags, direct, nullProxy(cbType)) }
                .onSuccess { return handleOf(it, name, width, height, densityDpi, "7-arg int") }
                .onFailure { errors.append("7arg:").append(unwrap(it).javaClass.simpleName)
                    .append(':').append(unwrap(it).message) }
        } else {
            errors.append("7arg:NoSuchMethod")
        }

        throw IllegalStateException("createVirtualDisplay 全部 overload 失败 [$errors]")
    }

    private fun handleOf(vd: Any?, name: String, w: Int, h: Int, dpi: Int, route: String): VirtualDisplayHandle {
        if (vd == null) throw IllegalStateException("createVirtualDisplay($route) 返回 null")
        val displayId = displayIdOf(vd)
        Log.i(TAG, "[createVirtualDisplay] ok route=$route name=$name ${w}x${h} dpi=$dpi displayId=$displayId")
        return VirtualDisplayHandle(vd, displayId, w, h, dpi)
    }

    /** 取虚拟屏的 displayId（`VirtualDisplay.getDisplay().getDisplayId()`；失败 -1）。 */
    fun displayIdOf(vd: Any): Int = runCatching {
        val display = vd.javaClass.getMethod("getDisplay").invoke(vd)
        (display.javaClass.getMethod("getDisplayId").invoke(display) as? Int) ?: -1
    }.getOrDefault(-1)

    /** 释放虚拟屏（`VirtualDisplay.release()`，幂等不抛）。 */
    fun releaseVirtualDisplay(h: VirtualDisplayHandle?) {
        h ?: return
        runCatching { h.instance.javaClass.getMethod("release").invoke(h.instance) }
            .onSuccess { Log.i(TAG, "[releaseVirtualDisplay] ok displayId=${h.displayId}") }
            .onFailure { Log.w(TAG, "[releaseVirtualDisplay] failed displayId=${h.displayId}: ${unwrap(it).message}") }
    }

    /** 关闭虚拟设备（`close()`，幂等不抛）。 */
    fun closeDevice(d: Device?) {
        d ?: return
        runCatching { d.cls.getMethod("close").invoke(d.instance) }
            .onSuccess { Log.i(TAG, "[closeDevice] ok deviceId=${d.deviceId}") }
            .onFailure { Log.w(TAG, "[closeDevice] failed deviceId=${d.deviceId}: ${unwrap(it).message}") }
    }

    /** 设虚拟屏 UI 模式（`setDisplayUiMode(displayId, uiMode)`，Android 14+ 在 VirtualDevice 上）。 */
    fun setDisplayUiMode(d: Device, displayId: Int, uiMode: Int): Boolean = runCatching {
        d.cls.getMethod("setDisplayUiMode", Integer.TYPE, Integer.TYPE)
            .invoke(d.instance, displayId, uiMode)
        Log.i(TAG, "[setDisplayUiMode] ok deviceId=${d.deviceId} displayId=$displayId uiMode=$uiMode")
        true
    }.getOrElse {
        Log.w(TAG, "[setDisplayUiMode] failed: ${unwrap(it).message}")
        false
    }

    /** 吞掉一切、恒返回 null 的动态代理（虚拟屏回调只为满足方法签名）。 */
    private fun nullProxy(type: Class<*>): Any? =
        runCatching { Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } }.getOrNull()

    /** 解包 `InvocationTargetException` 取真实 cause。 */
    fun unwrap(t: Throwable): Throwable {
        var cur = t
        while (cur is InvocationTargetException && cur.targetException != null) cur = cur.targetException
        return cur
    }
}
