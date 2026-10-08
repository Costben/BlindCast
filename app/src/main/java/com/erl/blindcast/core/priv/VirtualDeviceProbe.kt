package com.erl.blindcast.core.priv

import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Phase C 最小探针（§4.7 · 只做「反射拿 VirtualDeviceManager → 建 VirtualDeviceParams →
 * createVirtualDevice → 立即 close」，逐步记录成功/失败，不建屏、不编码、不改设备状态）。
 *
 * ## 证据来源（照现有 findings 实现，不猜）
 * - `andromeld-desktop-migration-plan.md` §4.1/§4.3：`VirtualDeviceManager` /
 *   `VirtualDeviceParams` / `$Builder` 全不在公开 SDK，须 root/shell 进程反射；
 *   隐藏字段 `mService` + `(IVirtualDeviceManager, Context)` 构造器取 manager。
 * - 镜像 display 桥 `work/jadx-out/sources/p177r0/C2524f0.java:279-334`（取 manager、枚举
 *   `createVirtualDevice`、`setDisplayUiMode` getMethod→getDeclaredMethod 降级、`close`）。
 * - `VirtualDeviceParams` 构造 `p177r0/C2512c3.java:88-160`（setName/setLockState/
 *   setDevicePolicy/setHomeComponent/setInputMethodComponent/build）。
 * - 应用身份 / attribution / context 三件套 `p177r0/C2497Z2.java`（ContextWrapper 覆盖
 *   `getAttributionSource()`/`getOpPackageName()`/`getPackageName()` = `com.android.shell`，
 *   AttributionSource.Builder(uid).setPackageName(...)）+ `p177r0/C2502a3.java`
 *   （`getApplicationContext()` 自返回）。
 * - 关联解析 `C2512c3.m4792c/m4793d`（`cmd companiondevice list <userId>` /
 *   `dumpsys companiondevice`，包名 `com.android.shell`）。
 *
 * ## 运行身份
 * - root（UID 0）：[RootMain] `vdProbe` 子操作，独立 `app_process`；
 * - shell（UID 2000）：Shizuku UserService 进程（[PrivilegedUserService.probeVirtualDevice]）。
 *
 * 全程 `runCatching`/try-catch 包住，任一步失败只记报文并继续后续步骤，绝不抛；
 * 每步同步写 logcat TAG `BlindCast-VDProbe`，完整报文由调用方落结果文件。
 */
object VirtualDeviceProbe {

    /** 探针专用 TAG（任务要求 `BlindCast-*` 前缀，便于 logcat 过滤）。 */
    private const val TAG = "BlindCast-VDProbe"

    private const val VM_CLASS = "android.companion.virtual.VirtualDeviceManager"
    private const val PARAMS_CLASS = "android.companion.virtual.VirtualDeviceParams"
    private const val BUILDER_CLASS = "android.companion.virtual.VirtualDeviceParams\$Builder"
    private const val DEVICE_CLASS = "android.companion.virtual.VirtualDevice"

    /** 运行时返回的实际设备类（真机实证 `createVirtualDevice` 返回此类，非顶层 VirtualDevice）。 */
    private const val DEVICE_RUNTIME_CLASS = "android.companion.virtual.VirtualDeviceManager\$VirtualDevice"

    /** AndroMeld 实证的 shell 身份包名（`C2497Z2`/`C2512c3.m4792c` 均用此常量）。 */
    const val SHELL_PACKAGE = "com.android.shell"

    /** 本应用包名（Home/IME 组件探测用；编译期常量，勿硬编码字符串）。 */
    private val APP_PACKAGE: String = com.erl.blindcast.BuildConfig.APPLICATION_ID

    /**
     * 伪装成 shell 身份的 Context（照 `C2497Z2` + `C2502a3`）。
     *
     * VDM 服务端从 `AttributionSource.getPackageName()` 取包名做关联归属校验，
     * 并 `enforceCallingUid()` 比对调用进程真实 uid，故 attribution 的 uid 用进程真实 uid
     * （不照抄 AndroMeld 硬编的 2000，避免与实际身份不符被拒后误判）。
     */
    private class ProbeContext(base: Context, val pkgName: String, val selfUid: Int) :
        ContextWrapper(base) {

        val attribution: AttributionSource? = runCatching {
            val builder = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(selfUid)
            builder.javaClass.getMethod("setPackageName", String::class.java)
                .invoke(builder, pkgName)
            builder.javaClass.getMethod("build").invoke(builder) as? AttributionSource
        }.getOrNull()

        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            attribution ?: super.getAttributionSource()
        override fun getOpPackageName(): String = pkgName
        override fun getPackageName(): String = pkgName
    }

    /**
     * 跑一遍最小探针，返回多行文本报文（每行 `[step] detail`）。
     *
     * @param context 特权进程可用的 Context（可为 null，探针会自举 system context）。
     * @param label 身份标签（"root"/"shizuku"），仅入报文/日志。
     */
    fun run(context: Context?, label: String): String {
        val sb = StringBuilder()
        fun rec(step: String, msg: String) {
            val line = "[$step] $msg"
            sb.append(line).append('\n')
            Log.i(TAG, "[$label] $line")
        }

        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        val pid = runCatching { Process.myPid() }.getOrDefault(-1)
        rec(
            "env",
            "label=$label pid=$pid uid=$uid sdk=${Build.VERSION.SDK_INT} rel=${Build.VERSION.RELEASE} " +
                "ctxIn=${context?.javaClass?.name ?: "null"}",
        )

        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/companion/virtual",
                "Landroid/content/AttributionSource",
                "Landroid/content/Context",
                "Landroid/app/ContextImpl",
                "Landroid/app/ActivityThread",
                "Landroid/app/Application",
                "Landroid/app/ConfigurationController",
                "Landroid/app/AppGlobals",
                "Landroid/os/Looper",
            )
        }

        // 1) 取 base Context：传入优先；裸 app_process 前几路全空，靠自举 ActivityThread
        //    （照 AbstractC2522e3.m4801B/m4830b/m4845i0 —— AndroMeld 正是靠这个才拿到 Context）。
        val systemContext: Context? = context?.takeIf { it !is ProbeContext } ?: tryObtainContext(::rec)
        rec("ctx.base", systemContext?.let { it.javaClass.name } ?: "null")
        if (systemContext == null) {
            rec("abort", "no Context available in this process; cannot continue")
            return sb.toString()
        }

        // 1b) 照 C2497Z2.m4779a：systemContext.createPackageContext("com.android.shell", 2)
        //     （2 = CONTEXT_IGNORE_SECURITY），失败回退 systemContext。
        val shellBase: Context = runCatching {
            systemContext.createPackageContext(SHELL_PACKAGE, 2)
        }.getOrElse { t ->
            rec("ctx.shellPackage", "EXC ${t.javaClass.name}: ${t.message} -> fallback system context")
            systemContext
        }
        rec("ctx.shellPackage", shellBase.javaClass.name)

        // 2) 应用身份 / attribution / context 三件套（照 C2497Z2 + C2502a3）。
        val probeCtx = runCatching { ProbeContext(shellBase, SHELL_PACKAGE, uid) }
            .getOrElse { t ->
                rec("ctx.wrap", "EXC ${t.javaClass.name}: ${t.message}")
                null
            }
        if (probeCtx == null) {
            rec("abort", "context wrapper construction failed")
            return sb.toString()
        }
        rec(
            "ctx.wrap",
            "pkg=${probeCtx.packageName} opPkg=${probeCtx.opPackageName} " +
                "attribution=${describeAttribution(probeCtx.attribution)}",
        )

        // 3) 反射取 VirtualDeviceManager（隐藏 mService 字段 + (mService, Context) 构造器）。
        val mgrClass = runCatching { Class.forName(VM_CLASS) }
            .getOrElse { t ->
                rec("vdm.class", "EXC ${t.javaClass.name}: ${t.message}")
                return sb.toString()
            }
        rec("vdm.class", mgrClass.name)

        val svc = runCatching {
            Context::class.java.getMethod("getSystemService", Class::class.java)
                .invoke(probeCtx, mgrClass)
        }.getOrElse { t ->
            rec("vdm.getSystemService", "EXC ${t.javaClass.name}: ${t.message}")
            null
        }
        rec("vdm.getSystemService", svc?.javaClass?.name ?: "null")
        if (svc == null) {
            rec("abort", "VirtualDeviceManager service unavailable from getSystemService")
            return sb.toString()
        }

        val mServiceField = runCatching { mgrClass.getDeclaredField("mService") }
            .getOrElse { t ->
                rec("vdm.mService", "EXC ${t.javaClass.name}: ${t.message}")
                null
            }
        if (mServiceField == null) {
            rec("abort", "mService field unavailable")
            return sb.toString()
        }
        mServiceField.isAccessible = true
        val serviceObj = runCatching { mServiceField.get(svc) }.getOrNull()
        rec(
            "vdm.mService",
            "field=${mServiceField.type.name} value=${serviceObj?.javaClass?.name ?: "null"}",
        )

        val mgr = runCatching {
            val ctor = mgrClass.getConstructor(mServiceField.type, Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(serviceObj, probeCtx)
        }.getOrElse { t ->
            rec("vdm.new", "EXC ${t.javaClass.name}: ${t.message}")
            null
        }
        rec("vdm.new", mgr?.let { "${it.javaClass.name} ok" } ?: "null")
        if (mgr == null) {
            rec("abort", "manager construction failed")
            return sb.toString()
        }

        // 4) 枚举 createVirtualDevice 全部 overload（不假设只有 (int, Params)）。
        val cdvOverloads = LinkedHashMap<String, Method>()
        runCatching { mgrClass.declaredMethods }.getOrDefault(emptyArray())
            .forEach { if (it.name == "createVirtualDevice") cdvOverloads[it.toGenericString()] = it }
        runCatching { mgrClass.methods }.getOrDefault(emptyArray())
            .forEach { if (it.name == "createVirtualDevice") cdvOverloads[it.toGenericString()] = it }
        cdvOverloads.keys.forEach { rec("cdv.sig", it) }
        if (cdvOverloads.isEmpty()) rec("cdv.sig", "none declared on ${mgrClass.name}")

        // 5) 建 VirtualDeviceParams.Builder，逐方法探测（签名 + 参数 + 成功/错误）。
        val paramsBuilder = runCatching {
            val b = Class.forName(BUILDER_CLASS).getConstructor().newInstance()
            rec("params.Builder", "ok ${b.javaClass.name}")
            b
        }.getOrElse { t ->
            rec("params.Builder", "EXC ${t.javaClass.name}: ${t.message}")
            null
        }
        if (paramsBuilder == null) {
            rec("abort", "VirtualDeviceParams.Builder unavailable")
            return sb.toString()
        }

        val policyTypeClipboard = staticInt(PARAMS_CLASS, "POLICY_TYPE_CLIPBOARD", -1)
        val devicePolicyCustom = staticInt(PARAMS_CLASS, "DEVICE_POLICY_CUSTOM", -1)
        rec(
            "params.consts",
            "POLICY_TYPE_CLIPBOARD=$policyTypeClipboard DEVICE_POLICY_CUSTOM=$devicePolicyCustom " +
                "POLICY_TYPE_CAMERA=${staticInt(PARAMS_CLASS, "POLICY_TYPE_CAMERA", -1)} " +
                "POLICY_TYPE_AUDIO=${staticInt(PARAMS_CLASS, "POLICY_TYPE_AUDIO", -1)}",
        )

        // builder 方法调用后可能返回新实例（照 AndroMeld m4790a 的链式处理）。
        var builder: Any = paramsBuilder
        // paramTypes 必须显式给出：Java 反射要求 `int` 用 Integer.TYPE，盒装 Integer 匹配不到。
        fun probeBuilder(name: String, paramTypes: Array<Class<*>>, args: Array<Any?>, argDesc: String) {
            val target = builder
            val cls = target.javaClass
            val resolved = runCatching<Method> {
                runCatching { cls.getMethod(name, *paramTypes) }.getOrElse { cls.getDeclaredMethod(name, *paramTypes) }
            }.getOrElse { t ->
                rec(
                    "params.$name",
                    "MISSING ${t.javaClass.simpleName}: ${t.message} types=${paramTypes.joinToString { it.name }} args=($argDesc)",
                )
                return
            }
            val declared = runCatching { cls.getDeclaredMethod(name, *paramTypes) }.isSuccess
            runCatching { resolved.isAccessible = true }
            runCatching { resolved.invoke(target, *args) }
                .onSuccess { ret ->
                    if (ret != null && ret !== target) builder = ret
                    rec(
                        "params.$name",
                        "OK sig=${resolved.toGenericString()} declared=$declared args=($argDesc) ret=${ret?.javaClass?.name ?: "null"}",
                    )
                }
                .onFailure { t ->
                    val cause = unwrap(t)
                    rec(
                        "params.$name",
                        "ERR ${cause.javaClass.name}: ${cause.message} " +
                            "sig=${resolved.toGenericString()} args=($argDesc)",
                    )
                }
        }

        val intT = Integer.TYPE
        probeBuilder("setName", arrayOf(String::class.java), arrayOf<Any?>("BlindCast"), "String=BlindCast")
        probeBuilder("setLockState", arrayOf(intT), arrayOf<Any?>(1), "int=1(LOCK_STATE_UNLOCKED)")
        probeBuilder(
            "setHomeComponent",
            arrayOf(ComponentName::class.java),
            arrayOf<Any?>(ComponentName(APP_PACKAGE, "$APP_PACKAGE.FusionHomeActivity")),
            "ComponentName=$APP_PACKAGE/.FusionHomeActivity",
        )
        probeBuilder(
            "setInputMethodComponent",
            arrayOf(ComponentName::class.java),
            arrayOf<Any?>(ComponentName(APP_PACKAGE, "$APP_PACKAGE.FusionInputMethodService")),
            "ComponentName=$APP_PACKAGE/.FusionInputMethodService",
        )
        if (policyTypeClipboard >= 0 && devicePolicyCustom >= 0) {
            probeBuilder(
                "setDevicePolicy",
                arrayOf(intT, intT),
                arrayOf<Any?>(policyTypeClipboard, devicePolicyCustom),
                "int=$policyTypeClipboard(POLICY_TYPE_CLIPBOARD),int=$devicePolicyCustom(DEVICE_POLICY_CUSTOM)",
            )
        } else {
            rec("params.setDevicePolicy", "SKIP 常量缺失 POLICY_TYPE_CLIPBOARD/DEVICE_POLICY_CUSTOM")
        }
        // setDisplayUiMode 在 Android 14/15 是 VirtualDevice 上的方法（C2524f0.java:322-331），
        // 同时探 Builder（跨版本漂移）与两个 VirtualDevice 类名。
        probeBuilder("setDisplayUiMode", arrayOf(intT, intT), arrayOf<Any?>(0, 0x11), "int=0(displayId),int=0x11(UI_MODE_NIGHT_NO)")
        probeNamedOnClass(DEVICE_CLASS, "setDisplayUiMode", ::rec)
        probeNamedOnClass("android.companion.virtual.VirtualDeviceManager\$VirtualDevice", "setDisplayUiMode", ::rec)

        // 6) build()
        val params = runCatching {
            builder.javaClass.getMethod("build").invoke(builder)
        }.getOrElse { t ->
            val cause = unwrap(t)
            rec("params.build", "ERR ${cause.javaClass.name}: ${cause.message}")
            null
        }
        rec("params.build", params?.javaClass?.name ?: "null")

        // 7) 关联解析（照 C2512c3.m4792c/m4793d：cmd companiondevice list + dumpsys）。
        val userId = if (uid >= 0) uid / 100000 else 0
        val listOut = runCatching { shell("cmd companiondevice list $userId") }.getOrElse { "EXC ${it.message}" }
        rec("assoc.list", listOut.replace("\n", " | ").take(600))
        val dumpOut = runCatching { shell("dumpsys companiondevice | head -25") }.getOrElse { "EXC ${it.message}" }
        rec("assoc.dumpsys", dumpOut.replace("\n", " | ").take(600))
        val assocId = parseAssociationId(listOut, SHELL_PACKAGE)
        rec("assoc.resolved", "userId=$userId pkg=$SHELL_PACKAGE associationId=$assocId")

        if (params == null) {
            rec("abort", "params build failed; skip createVirtualDevice")
            return sb.toString()
        }

        // 8) VirtualDevice 类方法清单（为 §4.4 编码器接入留证据，不实例化）。
        //    注意：顶层 `android.companion.virtual.VirtualDevice` 与运行时返回的
        //    `VirtualDeviceManager$VirtualDevice` **不是同一个类**（真机实证：顶层类没有
        //    createVirtualDisplay/setDisplayUiMode）——故底下 8b 必须按运行时类再枚举一次。
        runCatching {
            Class.forName(DEVICE_CLASS).methods.map { it.name }.distinct().sorted()
        }.onSuccess { rec("vd.methods", it.joinToString(",")) }
            .onFailure { rec("vd.methods", "EXC ${it.javaClass.name}: ${it.message}") }

        // 8b) 运行时返回类的**全部**声明方法（含隐藏 createVirtualDisplay 各 overload）。
        dumpAllMethods(DEVICE_RUNTIME_CLASS, "vdc.", ::rec)
        dumpAllMethods(VM_CLASS, "vdmcls.", ::rec)
        dumpAllMethods("android.hardware.display.VirtualDisplay", "vdisp.", ::rec)

        // 9) 逐个 overload 尝试 createVirtualDevice（无关联时以实际异常为据，不硬编成功）。
        val callAssocId = if (assocId >= 0) assocId else 0
        for ((sig, m) in cdvOverloads) {
            runCatching { m.isAccessible = true }
            val pts = m.parameterTypes
            val args = arrayOfNulls<Any?>(pts.size)
            var buildable = true
            pts.forEachIndexed { i, pt ->
                when {
                    pt == Int::class.javaPrimitiveType -> args[i] = callAssocId
                    pt.name == PARAMS_CLASS -> args[i] = params
                    else -> buildable = false
                }
            }
            if (!buildable) {
                rec("cdv.skip", "unsupported params ${pts.joinToString { it.name }} sig=$sig")
                continue
            }
            val argDesc = pts.mapIndexed { i, pt -> "$pt=${args[i]}" }.joinToString(",")
            runCatching { m.invoke(mgr, *args) }
                .onSuccess { dev ->
                    if (dev == null) {
                        rec("cdv.call", "OK but null sig=$sig assocId=$callAssocId args=($argDesc)")
                    } else {
                        rec("cdv.call", "OK dev=${dev.javaClass.name} sig=$sig assocId=$callAssocId")
                        val deviceId = runCatching {
                            dev.javaClass.getMethod("getDeviceId").invoke(dev)
                        }.getOrElse { t -> "ERR ${unwrap(t).javaClass.simpleName}: ${unwrap(t).message}" }
                        rec("cdv.deviceId", "$deviceId")
                        runCatching { dev.javaClass.getMethod("close").invoke(dev) }
                            .onSuccess { rec("cdv.close", "OK sig=$sig") }
                            .onFailure { t -> rec("cdv.close", "ERR ${unwrap(t).javaClass.name}: ${unwrap(t).message}") }
                    }
                }
                .onFailure { t ->
                    val cause = unwrap(t)
                    rec(
                        "cdv.call",
                        "ERR ${cause.javaClass.name}: ${cause.message} " +
                            "sig=$sig assocId=$callAssocId args=($argDesc)",
                    )
                }
        }

        return sb.toString()
    }

    /** 枚举一个类的全部方法（declared + inherited）签名，逐行记报文（每行截断防爆）。 */
    private fun dumpAllMethods(className: String, prefix: String, rec: (String, String) -> Unit) {
        runCatching {
            val cls = Class.forName(className)
            val all = (cls.declaredMethods.toList() + cls.methods.toList())
                .distinctBy { it.toGenericString() }
                .sortedBy { it.toGenericString() }
            rec("${prefix}count", "${all.size} methods on ${cls.name}")
            all.forEach { rec(prefix + it.name, it.toGenericString().take(240)) }
        }.onFailure { rec("${prefix}class", "EXC ${it.javaClass.name}: ${it.message}") }
    }

    /** 在给定类上按名探测一个方法（只报签名/可见性，不调用；供 VirtualDevice 侧 setDisplayUiMode）。 */
    private fun probeNamedOnClass(className: String, methodName: String, rec: (String, String) -> Unit) {
        runCatching {
            val cls = Class.forName(className)
            val all = (cls.declaredMethods.toList() + cls.methods.toList())
                .filter { it.name == methodName }
                .distinctBy { it.toGenericString() }
            if (all.isEmpty()) rec("cls.$methodName", "no $methodName on $className")
            all.forEach {
                rec(
                    "cls.$methodName",
                    "sig=${it.toGenericString()} declared=${cls.declaredMethods.contains(it)} " +
                        "public=${Modifier.isPublic(it.modifiers)}",
                )
            }
        }.onFailure { rec("cls.$methodName", "EXC ${it.javaClass.name}: ${it.message}") }
    }

    /** 解析 `cmd companiondevice list` 输出里的关联 ID（pkg 匹配；无则 -1）。 */
    private fun parseAssociationId(listOut: String, pkg: String): Int {
        return runCatching {
            listOut.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && it.contains("|") && it.contains(pkg) }
                ?.substringBefore("|")
                ?.trim()
                ?.toIntOrNull()
                ?: -1
        }.getOrDefault(-1)
    }

    /** 读隐藏静态 int 常量（`VirtualDeviceParams.POLICY_TYPE_*` 等）。 */
    private fun staticInt(className: String, fieldName: String, fallback: Int): Int =
        runCatching { Class.forName(className).getField(fieldName).getInt(null) }.getOrDefault(fallback)

    /** 裸 app_process 取 Context（前 4 路 + AndroMeld 式 ActivityThread 自举，逐步记日志）。 */
    private fun tryObtainContext(rec: (String, String) -> Unit): Context? {
        runCatching {
            val m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            m.isAccessible = true
            (m.invoke(null) as? Context)?.let {
                rec("ctx.currentApplication", it.javaClass.name)
                return it
            }
            rec("ctx.currentApplication", "null")
        }.onFailure { rec("ctx.currentApplication", "EXC ${it.javaClass.simpleName}: ${it.message}") }

        runCatching {
            val m = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread")
            m.isAccessible = true
            val thread = m.invoke(null)
            if (thread != null) {
                runCatching {
                    val g = thread.javaClass.getDeclaredMethod("getApplication")
                    g.isAccessible = true
                    (g.invoke(thread) as? Context)?.let {
                        rec("ctx.getApplication", it.javaClass.name)
                        return it
                    }
                }
            } else rec("ctx.currentActivityThread", "null")
        }.onFailure { rec("ctx.currentActivityThread", "EXC ${it.javaClass.simpleName}: ${it.message}") }

        runCatching {
            val m = Class.forName("android.app.AppGlobals").getDeclaredMethod("getInitialApplication")
            m.isAccessible = true
            (m.invoke(null) as? Context)?.let {
                rec("ctx.AppGlobals", it.javaClass.name)
                return it
            }
            rec("ctx.AppGlobals", "null")
        }.onFailure { rec("ctx.AppGlobals", "EXC ${it.javaClass.simpleName}: ${it.message}") }

        runCatching {
            val m = Class.forName("android.app.ActivityThread").getDeclaredMethod("systemMain")
            m.isAccessible = true
            val thread = m.invoke(null)
            if (thread != null) {
                listOf("getSystemContext", "getApplication").forEach { name ->
                    runCatching {
                        val g = thread.javaClass.getDeclaredMethod(name)
                        g.isAccessible = true
                        (g.invoke(thread) as? Context)?.let {
                            rec("ctx.systemMain.$name", it.javaClass.name)
                            return it
                        }
                    }
                }
            } else rec("ctx.systemMain", "null")
        }.onFailure { rec("ctx.systemMain", "EXC ${unwrap(it).javaClass.name}: ${unwrap(it).message}") }

        // 5) 裸 app_process 自举 ActivityThread（照 AbstractC2522e3.m4801B + m4830b + m4845i0；
        //    AndroMeld 正是靠这一步才拿到 system Context —— 前 4 路在 bare app_process 全空）。
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

            val initAppField = at.getDeclaredField("mInitialApplication").apply { isAccessible = true }
            if (initAppField.get(thread) == null) {
                val getSys = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                val sysCtx = runCatching { getSys.invoke(thread) as? Context }.getOrNull()
                if (sysCtx != null) {
                    val appCls = Class.forName("android.app.Application")
                    val app = appCls.getDeclaredConstructor().newInstance()
                    appCls.getDeclaredMethod("attach", Context::class.java)
                        .apply { isAccessible = true }.invoke(app, sysCtx)
                    initAppField.set(thread, app)
                    rec("ctx.bootstrap.initialApplication", "ok")
                } else {
                    rec("ctx.bootstrap.initialApplication", "systemContext=null, skipped")
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
            }.onFailure { rec("ctx.bootstrap.configController", "EXC ${unwrap(it).javaClass.simpleName}: ${unwrap(it).message}") }

            val sys = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                .invoke(thread) as? Context
            if (sys != null) {
                rec("ctx.bootstrap.getSystemContext", sys.javaClass.name)
                return sys
            }
            rec("ctx.bootstrap.getSystemContext", "null")
        }.onFailure { rec("ctx.bootstrap", "EXC ${unwrap(it).javaClass.name}: ${unwrap(it).message}") }

        return null
    }

    private fun describeAttribution(a: AttributionSource?): String =
        if (a == null) "null"
        else "pkg=${runCatching { a.packageName }.getOrNull()} uid=${runCatching { a.uid }.getOrDefault(-1)}"

    /** 解包 InvocationTargetException 取真实 cause（照 C2524f0/C2512c3 的 unwrap 习惯）。 */
    private fun unwrap(t: Throwable): Throwable {
        var cur = t
        while (cur is InvocationTargetException && cur.targetException != null) {
            cur = cur.targetException
        }
        return cur
    }

    /** 执行 shell 命令取输出（root/shell 身份下探测关联用）。 */
    private fun shell(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out.trim()
    }
}
