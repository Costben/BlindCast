package com.erl.blindcast.core.blackout

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 崩溃熔断安全网（Slice 2.2 · MVP.md 二(3)"崩溃兜底" + PLAN.md Slice 2.2）。
 *
 * ## 约定
 * 任何导致进程异常退出的路径，都必须先强制 [PowerController.restore] 点亮屏幕，
 * 杜绝"物理熄屏后 App 崩溃 -> 屏幕永远无法点亮变砖"。覆盖三条兜底路径：
 * 1. 未捕获异常崩溃：链式 Hook 默认 [Thread.UncaughtExceptionHandler][Thread.getDefaultUncaughtExceptionHandler]，
 *    熔断恢复后原样委托给前任 handler（不断 Crash 弹窗 / 系统上报链路）；
 * 2. 前台 Activity 销毁：注册 [Application.ActivityLifecycleCallbacks]，`onActivityDestroyed`
 *    时若 [PowerController.isBlackedOut] 仍为熄屏态，异步强制点亮；
 * 3. 前台 Service 销毁：Slice 6.1 的 `BlindCastForegroundService.onDestroy()` 内主动调用
 *    [notifyServiceDestroy]（本对象无法静态拦截 Service 生命周期，故约定为显式调用点）。
 *
 * ## 接入点
 * - [install]：[Application.onCreate] 中调用一次即可（幂等），**只装熔断，不启动喂狗线程**，
 *   喂狗启动权留给 Slice 6.1 的服务开关（[UserActivityKeeper.start]）。
 * - [uninstall]：仅单测 / 进程级清理使用，正常运行期不要调用。
 *
 * ## 线程注意
 * - 崩溃线程内直接同步调用 [PowerController.restore]（Binder 一次往返，进程将死，无视主线程禁令）；
 * - Activity/Service 销毁路径走 daemon 线程异步恢复，避免在主线程触发 `StrictMode` / ANR。
 */
object EmergencyRecovery {

    /** 是否已安装（volatile，跨线程可见）。 */
    @Volatile
    var isInstalled: Boolean = false
        private set

    private var application: Application? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var crashHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 安装熔断安全网（幂等）。重复调用直接返回，不叠加 handler / callbacks。
     *
     * @param application 应用实例，用于注册 Activity 生命周期回调。
     */
    @Synchronized
    fun install(application: Application) {
        if (isInstalled) return
        this.application = application

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val prev = previousHandler
        val ours = Thread.UncaughtExceptionHandler { thread, throwable ->
            // 熔断：崩溃前最后一搏强制点亮。任何失败只吞不抛，绝不能掩盖原始崩溃。
            runCatching { PowerController.restore() }
            try {
                if (prev != null) {
                    prev.uncaughtException(thread, throwable)
                } else {
                    // 无前任 handler 时走默认行为：杀进程，避免"崩溃被吞"假活。
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            } catch (_: Throwable) {
                // 前任 handler 自身抛异常时兜底杀进程，防止静默僵尸进程。
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }
        crashHandler = ours
        Thread.setDefaultUncaughtExceptionHandler(ours)

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (PowerController.isBlackedOut) recoverAsync()
            }
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)

        isInstalled = true
    }

    /**
     * 卸载熔断安全网（幂等）。仅单测 / 特殊清理流程使用。
     * 仅当当前默认 handler 仍是本对象安装的 [crashHandler] 时才还原，避免误拆他人 handler。
     */
    @Synchronized
    fun uninstall() {
        if (!isInstalled) return
        runCatching {
            if (Thread.getDefaultUncaughtExceptionHandler() === crashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }
        runCatching {
            lifecycleCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        }
        application = null
        lifecycleCallbacks = null
        crashHandler = null
        previousHandler = null
        isInstalled = false
    }

    /**
     * 前台 Service / Activity `onDestroy` 显式调用点（Slice 6.1 约定）。
     * 熄屏态下异步强制点亮；已是点亮态则 no-op，避免多余 Binder 调用。
     * 永不抛异常。
     */
    fun notifyServiceDestroy() = recoverAsync()

    /** Activity 销毁路径的等价显式调用点（回调已自动覆盖，手动调用亦可）。 */
    fun notifyActivityDestroy() = recoverAsync()

    /**
     * 同步强制恢复亮屏（阻塞，含 Binder 调用）。
     * 仅供崩溃线程 / 后台线程调用，禁止在主线程直接调用（主线程请用 [notifyServiceDestroy]）。
     *
     * @return 底层调用成功 true，失败 false（原因见 [PowerController.lastError]），永不抛异常。
     */
    fun recoverNowBlocking(): Boolean = runCatching { PowerController.restore() }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** daemon 线程异步点亮，fire-and-forget，永不抛。 */
    private fun recoverAsync() {
        if (!PowerController.isBlackedOut) return
        try {
            val t = Thread({ runCatching { PowerController.restore() } }, "BlindCast-EmergencyRecovery")
            t.isDaemon = true
            t.start()
        } catch (_: Throwable) {
            // 连线程都起不来（如关机竞态）：最后尝试同线程同步恢复。
            runCatching { PowerController.restore() }
        }
    }
}
