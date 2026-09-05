package com.erl.blindcast.core.blackout

import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.erl.blindcast.core.priv.PrivilegedBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 硬件屏幕电源统一控制器（Slice 2.1 · 物理灭屏底层唯一对外入口；Priv-Bridge-2 改道 SurfaceControl；
 * Priv-Bridge-5 加 14+ 混合路由）。
 *
 * ## 混合路由分发（MVP.md 四(二)(1) · Priv-Bridge-5 修订，机制参考 Aliothmoon/MAA-Meow (AGPL-3.0)）
 * - Android 9 及以下（SDK 28 及以下）：[SurfaceControl.getBuiltInDisplay] 取 token 后设电源模式；
 * - Android 10~13（SDK 29~33）：[SurfaceControl.getPhysicalDisplayIds] /
 *   [SurfaceControl.getPhysicalDisplayToken] 取主屏 token 后设电源模式。
 *   全部经 `android.view.SurfaceControl` 反射（JNI 在 libandroid_runtime，所有进程有，
 *   shell 身份可调；隐藏 API 经项目既有 HiddenApiBypass 放行）。
 * - Android 14+（SDK 34+）：特征探测（不只判 SDK）——若 [SurfaceControl.hasGetPhysicalDisplayIds]
 *   为 true 走 SurfaceControl 路径（取 token + 设值全走 SurfaceControl），否则走
 *   DisplayControl 取 token + SurfaceControl 设值混合路径
 *  （token 经 [DisplayControl.getPhysicalDisplayIds]/[DisplayControl.getPhysicalDisplayToken]，
 *   经 SYSTEMSERVERCLASSPATH 载类后 `Runtime.loadLibrary0(..., "android_servers")` 预载 JNI，
 *   机制参考 MAA-Meow 自行实现；设值一律经 `SurfaceControl.setDisplayPowerMode(token, mode)`，
 *   Priv-Bridge-6 修正：OPlus Android 15 真机实证 DisplayControl 根本无
 *   setDisplayPowerMode(IBinder,int) 方法，MAA-Meow 亦只有取 token 两方法）。
 *   POWER_MODE_OFF=0 / NORMAL=2 两条路线一致。
 *
 * 熄屏语义：`POWER_MODE_OFF` 物理切断屏幕电源（OLED / 背光断电、触控停止上报），
 * 渲染管线与 CPU 保持满血前台运行；点亮语义：`POWER_MODE_NORMAL` 恢复屏幕电源。
 *
 * ## 提权三路径（Priv-Bridge-1 · 调用方必读）
 * - **Shizuku 已授权（主路径）**：App 进程调用 [setDisplayPowerRouted] /
 *   [blackoutRouted] / [restoreRouted]，经 [PrivilegedBridge.withPrivileged] 把调用
 *   送进 [com.erl.blindcast.core.priv.PrivilegedUserService]（特权身份）执行，
 *   成功后翻转 [isBlackedOut] 并清零 [lastError]。
 * - **未授权（报错引导）**：Shizuku 未运行 / 未授权时 routed 入口不抛异常，而是把引导文案
 *   （[PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE] / `SHIZUKU_NOT_RUNNING_MESSAGE`）
 *   记入 [lastError] 并返回 false，调用方（如 `HomeViewModel.blackoutNow`）据此弹 Toast。
 * - **Root 直跑（后续 Slice，注释预留）**：直接以 root 身份执行时调直调版 [setDisplayPower] /
 *   [blackout] / [restore] 即可；routed 版在 Root 宿主内同样可用（Shizuku/Sui 的 binder
 *   在 Root 宿主下亦存活），互不冲突。
 *   // TODO(priv-bridge-next): Root 宿主直跑入口（su 下 app_process 拉起同一 UserService 类）。
 *
 * ## 直调 vs 路由
 * - 直调版（[setDisplayPower] / [blackout] / [restore] / suspend 版）**必须在提权进程内执行**：
 *   Shizuku UserService（shell / root 身份的独立 app_process）或以 Root 身份启动的 app_process 进程。
 *   普通 App 进程直接调用将失败并返回 `false`（异常记录在 [lastError]）。
 *   特权进程侧（[com.erl.blindcast.core.priv.PrivilegedUserService]）调的正是直调版。
 * - App 进程（含 UI / Service / EmergencyRecovery 所在进程）一律走 routed 版。
 * - blackoutRouted/restoreRouted 逻辑不变（仍经 UserService），PrivilegedUserService 内直调改道后的 SurfaceControl 版。
 *
 * ## 范围声明
 * - 仅做底层能力封装：不接 UI、不启动任何线程（4s 喂狗与崩溃熔断在 Slice 2.2
 *   由 `UserActivityKeeper` / `EmergencyRecovery` 实现；Slice 2.2 的销毁兜底路径
 *   应调用 [restore] 强制点亮，杜绝屏幕变砖）。
 * - [setDisplayPower] 为同步阻塞调用（含一次 Binder往返），禁止在主线程调用；
 *   协程调用方请使用 [setDisplayPowerSuspend]（特权进程内）或 [setDisplayPowerRouted]
 *  （App 进程内，含一次跨进程 UserService 绑定）。
 *
 * @property isBlackedOut 当前是否处于已熄屏状态（仅反映本控制器最近一次**成功**调用的结果）。
 * @property lastError 最近一次调用失败的异常（成功后清零，供 Slice 2.2 熔断与 6.3 诊断消费）。
 */
object PowerController {

    /** 全链路统一 TAG（与 SurfaceControl / PrivilegedBridge / HomeViewModel 一致）。 */
    private const val TAG = "BlindCast"

    /** 熄屏模式（透传给 [SurfaceControl]）。 */
    const val POWER_MODE_OFF = 0

    /** 正常点亮模式（透传给 [SurfaceControl]）。 */
    const val POWER_MODE_NORMAL = 2

    /** 当前是否处于已熄屏状态（volatile，只在调用成功时翻转）。 */
    @Volatile
    var isBlackedOut: Boolean = false
        private set

    /** 最近一次调用失败的异常；最近一次成功后为 null。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    // Priv-Bridge-3：最近一次特权操作持久可见结果（成功时间 / 失败文案，供 Home 快捷操作卡展示）。
    /** 最近一次操作：blackout / restore（null = 尚未执行）。 */
    @Volatile
    var lastPrivOp: String? = null
        private set

    /** 最近一次操作是否成功（null = 尚未执行）。 */
    @Volatile
    var lastPrivSuccess: Boolean? = null
        private set

    /** 最近一次操作时间戳 ms（0 = 尚未执行）。 */
    @Volatile
    var lastPrivAtMs: Long = 0L
        private set

    /** 最近一次操作失败文案（成功时为 null，只记 message 不记隐私）。 */
    @Volatile
    var lastPrivError: String? = null
        private set

    private fun tid(): String {
        val t = Thread.currentThread()
        return "t=${t.id}(${t.name})"
    }

    /**
     * 记录一次特权操作结果（直调与路由入口均调；成功记时间，失败记文案）。
     *
     * @param op blackout / restore。
     * @param ok 是否成功。
     * @param errMsg 失败文案（成功传 null）。
     */
    @Synchronized
    fun recordPrivResult(op: String, ok: Boolean, errMsg: String?) {
        lastPrivOp = op
        lastPrivSuccess = ok
        lastPrivAtMs = System.currentTimeMillis()
        lastPrivError = if (ok) null else errMsg?.take(200)
    }

    /**
     * 最近一次特权操作摘要（Home 快捷操作卡持久行展示用）。
     *
     * @return null = 尚未执行；否则如“上次熄屏成功 09-05 14:22:10”/“上次熄屏失败：xxx”。
     */
    fun lastPrivSummary(): String? {
        val op = lastPrivOp ?: return null
        val ok = lastPrivSuccess ?: return null
        val opName = if (op == "blackout") "熄屏" else "点亮"
        val time = try {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastPrivAtMs))
        } catch (_: Throwable) {
            lastPrivAtMs.toString()
        }
        return if (ok) {
            "上次${opName}成功 $time"
        } else {
            "上次${opName}失败：${lastPrivError ?: "未知错误"}"
        }
    }

    /**
     * 本设备 SDK 是否落在受支持分支内（SDK >= 28 即二分支全覆盖）。
     * 项目 minSdk = 31，恒为 true；保留该开关用于未来 ROM 黑名单扩展。
     */
    val isSupported: Boolean
        get() = true

    /**
     * 设置主显示屏电源（同步阻塞，含 Binder 调用，禁止主线程直调）。
     *
     * 直调版：**必须在提权进程内执行**（Shizuku UserService / Root app_process）。
     * App 进程请用 [setDisplayPowerRouted]；特权进程侧（PrivilegedUserService）调本方法。
     *
     * @param on true = 点亮（[POWER_MODE_NORMAL]），false = 物理熄屏（[POWER_MODE_OFF]）。
     * @return 底层调用成功返回 true；反射失败 / 系统拒绝 / 底层返回 false 时返回 false，
     *   且失败原因记录在 [lastError]，[isBlackedOut] 保持不变。
     */
    @Synchronized
    @WorkerThread
    fun setDisplayPower(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val op = if (on) "restore" else "blackout"
        Log.d(TAG, "[PowerController] ${tid()} setDisplayPower enter on=$on mode=$mode")
        return try {
            // Priv-Bridge-5 混合路由（特征探测，不只判 SDK）：
            // SDK>=34 且 SurfaceControl 无 getPhysicalDisplayIds 方法 → DisplayControl 取 token
            // + SurfaceControl 设值混合路径（SYSTEMSERVERCLASSPATH 载类 + loadLibrary0 预载
            // android_servers.so 取 token，设值一律 SurfaceControl.setDisplayPowerMode，
            // Priv-Bridge-6 修正：DisplayControl 无 set 方法，机制参考 MAA-Meow）；
            // 29~33 维持 SurfaceControl；28 及以下维持 SurfaceControl 内 getBuiltInDisplay 分支。
            val sdk = Build.VERSION.SDK_INT
            val hasIds = try {
                SurfaceControl.hasGetPhysicalDisplayIds()
            } catch (_: Throwable) {
                false
            }
            val useDisplayControl = sdk >= 34 && !hasIds
            val route = if (useDisplayControl) "DisplayControl" else "SurfaceControl"
            Log.d(TAG, "[PowerController] ${tid()} hybrid sdk=$sdk hasIds=$hasIds " +
                "route=$route mode=$mode")
            val ok = if (useDisplayControl) {
                // Priv-Bridge-6：DisplayControl 只取 token，设值一律走 SurfaceControl。
                // 失败文案区分“取 token 失败”与“设值失败”（上游 Toast/状态行照常消费 message）。
                val token = try {
                    DisplayControl.getDefaultDisplayToken()
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "取 token 失败（route=DisplayControl mode=$mode）: ${t.message ?: t}", t)
                }
                try {
                    SurfaceControl.setDisplayPowerMode(token, mode)
                } catch (t: Throwable) {
                    throw IllegalStateException(
                        "设值失败（route=DisplayControl取token+SurfaceControl设值 mode=$mode）: ${t.message ?: t}", t)
                }
            } else {
                SurfaceControl.setDefaultDisplayPowerMode(mode)
            }
            if (ok) {
                isBlackedOut = !on
                lastError = null
                recordPrivResult(op, true, null)
            } else {
                val msg = if (useDisplayControl) {
                    "设值失败：底层返回 false（mode=$mode route=DisplayControl取token+SurfaceControl设值），见特权进程 logcat [DisplayControl]/[SurfaceControl] 明细"
                } else {
                    "底层返回 false（mode=$mode），见特权进程 logcat [SurfaceControl] 逐屏明细"
                }
                lastError = IllegalStateException(msg)
                recordPrivResult(op, false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=$on mode=$mode route=$route " +
                "ok=$ok blackedOut=$isBlackedOut")
            ok
        } catch (t: Throwable) {
            lastError = t
            recordPrivResult(op, false, t.message ?: t.toString())
            Log.e(TAG, "[PowerController] ${tid()} setDisplayPower on=$on mode=$mode failed", t)
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

    // ------------------------------------------------------------------
    // Priv-Bridge-1：App 进程提权路由入口（Shizuku 已授权 → UserService 通道；
    // 未授权 → 记 lastError 并返回 false，不抛异常，调用方据此弹 Toast）。
    // ------------------------------------------------------------------

    /**
     * 设置主显示屏电源（App 进程入口，协程，可在任意调度器上调用）。
     *
     * 路由逻辑：[PrivilegedBridge.isPrivilegedGranted] 为 true → 经 UserService
     * 通道在特权进程内执行；否则把引导文案记入 [lastError] 并返回 false。
     * 特权进程内返回 false（ROM 差异等）同样记入 [lastError]（跨进程异常原文不可见，
     * 此处记通用原因，细节看特权进程 logcat）。
     *
     * @param packageName 调用方包名（`context.packageName`，用于定位 UserService 组件）。
     * @param on true = 点亮，false = 物理熄屏。
     * @return 特权执行成功返回 true；未授权 / 绑定失败 / 底层返回 false 时返回 false。
     */
    suspend fun setDisplayPowerRouted(packageName: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val running = PrivilegedBridge.isShizukuRunning()
            val granted = PrivilegedBridge.isPrivilegedGranted()
            Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted enter on=$on " +
                "running=$running granted=$granted")
            if (!granted) {
                lastError = if (running) {
                    SecurityException(PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE)
                } else {
                    IllegalStateException(PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE)
                }
                val msg = lastError?.message
                recordPrivResult(if (on) "restore" else "blackout", false, msg)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted auth denied " +
                    "on=$on running=$running granted=$granted err=$msg")
                return@withContext false
            }
            val ok = try {
                PrivilegedBridge.setDisplayPower(packageName, on)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                recordPrivResult(if (on) "restore" else "blackout", false, t.message ?: t.toString())
                Log.e(TAG, "[PowerController] ${tid()} setDisplayPowerRouted on=$on bridge failed", t)
                return@withContext false
            }
            if (ok) {
                isBlackedOut = !on
                lastError = null
                recordPrivResult(if (on) "restore" else "blackout", true, null)
            } else {
                val msg = "特权进程执行失败（返回 false），请查看特权进程 logcat [SurfaceControl] 逐屏明细"
                lastError = IllegalStateException(msg)
                recordPrivResult(if (on) "restore" else "blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted on=$on ok=$ok " +
                "blackedOut=$isBlackedOut err=${lastError?.message}")
            ok
        }

    /**
     * 物理熄屏（App 进程入口，[setDisplayPowerRouted] 特化，`on = false`）。
     *
     * @param packageName 调用方包名。
     * @return 同 [setDisplayPowerRouted]。
     */
    suspend fun blackoutRouted(packageName: String): Boolean {
        Log.d(TAG, "[PowerController] ${tid()} blackoutRouted enter")
        val ok = setDisplayPowerRouted(packageName, false)
        Log.d(TAG, "[PowerController] ${tid()} blackoutRouted exit ok=$ok " +
            "err=${lastError?.message} summary=${lastPrivSummary()}")
        return ok
    }

    /**
     * 点亮屏幕（App 进程入口，[setDisplayPowerRouted] 特化，`on = true`）。
     *
     * @param packageName 调用方包名。
     * @return 同 [setDisplayPowerRouted]。
     */
    suspend fun restoreRouted(packageName: String): Boolean {
        Log.d(TAG, "[PowerController] ${tid()} restoreRouted enter")
        val ok = setDisplayPowerRouted(packageName, true)
        Log.d(TAG, "[PowerController] ${tid()} restoreRouted exit ok=$ok " +
            "err=${lastError?.message} summary=${lastPrivSummary()}")
        return ok
    }
}
