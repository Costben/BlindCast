package com.erl.blindcast.core.blackout

import android.content.Context
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.ref.WeakReference

/**
 * 4s 喂狗防休眠守护（Slice 2.2 · MVP.md 二(3) + 四(二)(1)）。
 *
 * ## 语义
 * 物理熄屏后（[PowerController.blackout]），Android 仍会按无用户活动计时进入 Doze，
 * 导致渲染管线 / CPU 被挂起。本守护以后台 daemon 线程每 [INTERVAL_MS] 向系统电源服务
 * 注入一次 `userActivity` 事件，彻底阻止 Doze 休眠，确保游戏 / 脚本满血前台运行。
 *
 * ## 喂狗路径（优先级从高到低）
 * 1. `ServiceManager.getService("power")` 取 Binder，经 `IPowerManager$Stub.asInterface`
 *    直调系统服务 `userActivity`（无需 Context，提权 / 普通进程通用）；
 * 2. 回退：`Context.getSystemService(PowerManager::class.java).userActivity()` 公开 API
 *   （需先经 [start] 传入 Context，否则跳过）。
 *
 * ## 新旧签名兼容（displayId 参数）
 * - 新签名（Android 12+ 部分 ROM）：`userActivity(long, int, int, int displayId)`；
 * - 旧签名：`userActivity(long, int, int)`。
 * 反射时按"新 -> 旧"顺序探测并缓存首个可用 [java.lang.reflect.Method]，ROM 裁剪导致
 * 双签名均缺失时回退到公开 API，全部失败则记 [lastError] 不抛崩。
 *
 * ## 线程模型
 * - [start] 幂等：重复调用不产生第二条线程；
 * - [stop] 幂等：中断守护线程并 join（至多等待 1s，不阻塞调用方过久）；
 * - 守护线程为 daemon（`isDaemon = true`），进程退出时不阻止 VM 回收；
 * - [pokeNow] 同步阻塞（含一次 Binder 往返），禁止在主线程高频直调，守护线程内调用无妨。
 *
 * ## 启动权归属
 * 默认不自启。`BlindCastApplication.onCreate()` 只装 [EmergencyRecovery] 熔断，
 * 启动 / 停止权留给 Slice 6.1 的服务开关（息屏挂机开始 -> `start()`，点亮 / 服务销毁 -> `stop()`）。
 */
object UserActivityKeeper {

    /** 喂狗周期：4s（MVP.md 二(3) 铁线）。 */
    const val INTERVAL_MS: Long = 4_000L

    /** `userActivity` 事件：OTHER（0），表示非触控的通用用户活动。 */
    private const val USER_ACTIVITY_EVENT_OTHER = 0

    /** `userActivity` 标志：0 = 允许按需唤醒，不附加 NO_CHANGE_LIGHTS 等修饰。 */
    private const val USER_ACTIVITY_FLAG_NONE = 0

    /** 默认显示屏 ID：0 = DEFAULT_DISPLAY。 */
    private const val DEFAULT_DISPLAY_ID = 0

    /** 是否正在喂狗（volatile，跨线程可见）。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 最近一次喂狗失败的异常；最近一次成功后清零，供 6.3 诊断消费。喂狗失败永不崩溃。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 最近一次喂狗成功的时间戳（[SystemClock.uptimeMillis] 基准，-1 = 尚未成功过）。 */
    @Volatile
    var lastKeepAliveTime: Long = -1L
        private set

    /** 累计喂狗成功次数。 */
    @Volatile
    var successCount: Long = 0L
        private set

    /** 累计喂狗失败次数。 */
    @Volatile
    var failureCount: Long = 0L
        private set

    private val lock = Any()
    private var worker: Thread? = null
    private var appContextRef: WeakReference<Context>? = null

    // ---- 反射缓存（命中后复用，避免每次喂狗重复 getMethod） ----
    @Volatile
    private var cachedProxy: Any? = null

    @Volatile
    private var cachedMethod: java.lang.reflect.Method? = null

    @Volatile
    private var cachedTakesDisplayId: Boolean = false

    @Volatile
    private var reflectionSealed: Boolean = false

    /**
     * 启动 4s 喂狗守护线程（幂等）。
     *
     * @param context 可选应用上下文，用于反射全失败时的公开 API 回退；
     *   传入 Activity/Service 均可，内部只持 [WeakReference] 且取 applicationContext，不泄漏。
     */
    @Synchronized
    fun start(context: Context? = null) {
        if (context != null) {
            appContextRef = WeakReference(context.applicationContext ?: context)
        }
        synchronized(lock) {
            if (isRunning && worker?.isAlive == true) return
            isRunning = true
            val t = Thread(::loop, "BlindCast-UserActivityKeeper")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    /**
     * 停止喂狗守护线程（幂等）。
     * 中断 + 至多 join 1s；线程内部对 [InterruptedException] 视为正常退出信号，不记 [lastError]。
     */
    @Synchronized
    fun stop() {
        synchronized(lock) {
            isRunning = false
            worker?.interrupt()
        }
        try {
            worker?.join(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) { worker = null }
        }
    }

    /**
     * 立即喂狗一次（同步阻塞，含 Binder 调用）。
     *
     * @return 喂狗成功 true；任何失败返回 false 且原因记 [lastError]，永不抛异常。
     */
    fun pokeNow(): Boolean {
        return try {
            val ok = pokeViaPowerService() || pokeViaPublicApi()
            if (ok) {
                lastError = null
                lastKeepAliveTime = SystemClock.uptimeMillis()
                successCount++
            } else if (lastError == null) {
                lastError = IllegalStateException("UserActivityKeeper: no available userActivity path")
                failureCount++
            } else {
                failureCount++
            }
            ok
        } catch (t: Throwable) {
            lastError = t
            failureCount++
            false
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun loop() {
        // 先行喂一次，让"息屏 -> 保活"之间无 4s 空窗。
        pokeNow()
        while (isRunning && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            if (!isRunning) break
            pokeNow()
        }
    }

    /**
     * 路径 1：ServiceManager / IPowerManager 反射直调系统服务。
     * 成功返回 true；反射不可用 / 系统拒绝返回 false（原因已记 [lastError]）。
     */
    private fun pokeViaPowerService(): Boolean {
        return try {
            ensureHiddenApiExempted()
            val (proxy, method, takesDisplayId) = resolvePowerServiceMethod()
                ?: return false
            val now = SystemClock.uptimeMillis()
            if (takesDisplayId) {
                method.invoke(proxy, now, USER_ACTIVITY_EVENT_OTHER, USER_ACTIVITY_FLAG_NONE, DEFAULT_DISPLAY_ID)
            } else {
                method.invoke(proxy, now, USER_ACTIVITY_EVENT_OTHER, USER_ACTIVITY_FLAG_NONE)
            }
            true
        } catch (t: Throwable) {
            // InvocationTargetException 包裹的才是系统侧真实拒绝，拆一层便于诊断。
            val root = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            lastError = root
            // 该签名本次调用失败可能是 ROM 行为差异：放开缓存让下次重新探测另一签名。
            invalidateCache()
            false
        }
    }

    /**
     * 路径 2（回退）：PowerManager 反射调用，需 [start] 时传入过 Context。
     * 无 Context 时直接返回 false（不记错，把诊断位留给路径 1）。
     *
     * 说明：compileSdk 37 已将 `PowerManager.userActivity` 移出公开 SDK 面
     * （`javap android.os.PowerManager` 无此方法），编译期不可直调，
     * 故本路径亦为全反射：优先 3 参 `(long, int, int)`，回退公开时代遗留的
     * 双参 `(long, boolean)`（`noChangeLights = false`）。
     */
    private fun pokeViaPublicApi(): Boolean {
        val ctx = appContextRef?.get() ?: return false
        return try {
            val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
            val now = SystemClock.uptimeMillis()
            val via3 = runCatching {
                val m = pm.javaClass.getMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                m.isAccessible = true
                m.invoke(pm, now, USER_ACTIVITY_EVENT_OTHER, USER_ACTIVITY_FLAG_NONE)
                true
            }.getOrNull()
            if (via3 == true) return true
            runCatching {
                val m = pm.javaClass.getMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    java.lang.Boolean.TYPE,
                )
                m.isAccessible = true
                m.invoke(pm, now, false)
                true
            }.getOrNull() ?: return false
        } catch (t: Throwable) {
            lastError = t
            false
        }
    }

    private data class ResolvedMethod(
        val proxy: Any,
        val method: java.lang.reflect.Method,
        val takesDisplayId: Boolean,
    )

    @Synchronized
    private fun resolvePowerServiceMethod(): ResolvedMethod? {
        cachedProxy?.let { proxy ->
            cachedMethod?.let { m -> return ResolvedMethod(proxy, m, cachedTakesDisplayId) }
        }
        if (reflectionSealed) return null
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, Context.POWER_SERVICE) as? IBinder
                ?: run {
                    lastError = IllegalStateException("ServiceManager.getService(\"power\") returned null")
                    reflectionSealed = true
                    return null
                }
            val stubClass = Class.forName("android.os.IPowerManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, binder)
                ?: run {
                    lastError = IllegalStateException("IPowerManager.Stub.asInterface returned null")
                    reflectionSealed = true
                    return null
                }
            val ifaceClass = Class.forName("android.os.IPowerManager")
            // 新签名优先（含 displayId），旧签名回退。
            val probed = runCatching {
                ifaceClass.getMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ) to true
            }.getOrNull() ?: runCatching {
                ifaceClass.getMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ) to false
            }.getOrNull()
            if (probed == null) {
                lastError = NoSuchMethodException("IPowerManager.userActivity(long,int,int[,int]) not found")
                reflectionSealed = true
                return null
            }
            val (method, takesDisplayId) = probed
            method.isAccessible = true
            cachedProxy = proxy
            cachedMethod = method
            cachedTakesDisplayId = takesDisplayId
            return ResolvedMethod(proxy, method, takesDisplayId)
        } catch (t: Throwable) {
            lastError = t
            // ServiceManager / Stub 类本身缺失（极端裁剪 ROM）才封存；普通调用失败留待下次重试。
            if (t is ClassNotFoundException || t is NoSuchMethodException) reflectionSealed = true
            return null
        }
    }

    @Synchronized
    private fun invalidateCache() {
        cachedProxy = null
        cachedMethod = null
    }

    private fun ensureHiddenApiExempted() {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/os/IPowerManager",
                "Landroid/os/IPowerManager\$Stub",
                "Landroid/os/ServiceManager",
            )
        }
    }

    /** 仅供单测：清空反射缓存与封存位，强制下次喂狗重新探测签名。 */
    @VisibleForTesting
    fun resetReflectionCacheForTest() {
        synchronized(lock) {
            cachedProxy = null
            cachedMethod = null
            reflectionSealed = false
        }
    }
}
