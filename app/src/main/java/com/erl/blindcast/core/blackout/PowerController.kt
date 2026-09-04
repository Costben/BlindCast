package com.erl.blindcast.core.blackout

import android.os.Build
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 硬件屏幕电源统一控制器（Slice 2.1 · 物理灭屏底层唯一对外入口）。
 *
 * ## 三分支分发（MVP.md 四(二)(1)）
 * - Android 9（SDK 28）：[SurfaceControl.getBuiltInDisplay] 取 token 后设电源模式；
 * - Android 10 ~ 13（SDK 29 ~ 33）：[SurfaceControl.getPhysicalDisplayIds] /
 *   [SurfaceControl.getPhysicalDisplayToken] 取主屏 token 后设电源模式；
 * - Android 14+（SDK 34+）：[DisplayControl] 经 `SYSTEMSERVERCLASSPATH`
 *   反射系统服务侧实现后设电源模式。
 *
 * 熄屏语义：`POWER_MODE_OFF` 物理切断屏幕电源（OLED / 背光断电、触控停止上报），
 * 渲染管线与 CPU 保持满血前台运行；点亮语义：`POWER_MODE_NORMAL` 恢复屏幕电源。
 *
 * ## 调用点约束（Shizuku / Root 提权进程调用点预留）
 * [setDisplayPower] 系列方法**必须在提权进程内执行**：Shizuku UserService
 *（system_server 上下文）或以 Root 身份启动的 app_process 进程。普通 App 进程
 * 缺少签名级显示电源权限，直接调用将失败并返回 `false`（异常记录在 [lastError]）。
 * 后续 Slice 可在此处接入 Shizuku binder 透传 / Root 守护进程转调，本对象 API 保持不变。
 *
 * ## 范围声明
 * - 仅做底层能力封装：不接 UI、不启动任何线程（4s 喂狗与崩溃熔断在 Slice 2.2
 *   由 `UserActivityKeeper` / `EmergencyRecovery` 实现；Slice 2.2 的销毁兜底路径
 *   应调用 [restore] 强制点亮，杜绝屏幕变砖）。
 * - [setDisplayPower] 为同步阻塞调用（含一次 Binder往返），禁止在主线程调用；
 *   协程调用方请使用 [setDisplayPowerSuspend]。
 *
 * @property isBlackedOut 当前是否处于已熄屏状态（仅反映本控制器最近一次**成功**调用的结果）。
 * @property lastError 最近一次调用失败的异常（成功后清零，供 Slice 2.2 熔断与 6.3 诊断消费）。
 */
object PowerController {

    /** 熄屏模式（透传给 [SurfaceControl] / [DisplayControl]）。 */
    const val POWER_MODE_OFF = 0

    /** 正常点亮模式（透传给 [SurfaceControl] / [DisplayControl]）。 */
    const val POWER_MODE_NORMAL = 2

    /** 当前是否处于已熄屏状态（volatile，只在调用成功时翻转）。 */
    @Volatile
    var isBlackedOut: Boolean = false
        private set

    /** 最近一次调用失败的异常；最近一次成功后为 null。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /**
     * 本设备 SDK 是否落在任一受支持分支内（SDK >= 28 即三分支全覆盖）。
     * 项目 minSdk = 31，恒为 true；保留该开关用于未来 ROM 黑名单扩展。
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * 设置主显示屏电源（同步阻塞，含 Binder 调用，禁止主线程直调）。
     *
     * @param on true = 点亮（[POWER_MODE_NORMAL]），false = 物理熄屏（[POWER_MODE_OFF]）。
     * @return 底层调用成功返回 true；反射失败 / 系统拒绝 / 底层返回 false 时返回 false，
     *   且失败原因记录在 [lastError]，[isBlackedOut] 保持不变。
     */
    @Synchronized
    @WorkerThread
    fun setDisplayPower(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        return try {
            // 按 SDK 版本分发：14+ 走 DisplayControl（SYSTEMSERVERCLASSPATH），以下走 SurfaceControl。
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                DisplayControl.setDefaultDisplayPowerMode(mode)
            } else {
                SurfaceControl.setDefaultDisplayPowerMode(mode)
            }
            if (ok) {
                isBlackedOut = !on
                lastError = null
            }
            ok
        } catch (t: Throwable) {
            lastError = t
            false
        }
    }

    /**
     * [setDisplayPower] 的协程版本（自动切到 [Dispatchers.IO]，可在任意调度器上调用）。
     *
     * @param on true = 点亮，false = 物理熄屏。
     * @return 同 [setDisplayPower]。
     */
    suspend fun setDisplayPowerSuspend(on: Boolean): Boolean =
        withContext(Dispatchers.IO) { setDisplayPower(on) }

    /**
     * 物理熄屏快捷入口（同步阻塞，禁止主线程直调）。
     *
     * @return 同 [setDisplayPower]。
     */
    @WorkerThread
    fun blackout(): Boolean = setDisplayPower(false)

    /**
     * 点亮屏幕快捷入口（同步阻塞，禁止主线程直调）。
     *
     * Slice 2.2 约定：`EmergencyRecovery` 与 Service / Activity 销毁兜底路径
     * 必须调用本方法强制恢复亮屏。
     *
     * @return 同 [setDisplayPower]。
     */
    @WorkerThread
    fun restore(): Boolean = setDisplayPower(true)

    /** [blackout] 的协程版本。 */
    suspend fun blackoutSuspend(): Boolean = setDisplayPowerSuspend(false)

    /** [restore] 的协程版本。 */
    suspend fun restoreSuspend(): Boolean = setDisplayPowerSuspend(true)
}
