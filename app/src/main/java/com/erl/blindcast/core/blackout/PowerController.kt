package com.erl.blindcast.core.blackout

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.WorkerThread
import com.erl.blindcast.core.priv.PrivilegedBridge
import com.erl.blindcast.core.priv.RootExecutor
import com.erl.blindcast.core.scrcpy.TouchInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 硬件屏幕电源统一控制器（Slice 2.1 · 物理灭屏底层唯一对外入口；Priv-Bridge-2 改道 SurfaceControl；
 * Priv-Bridge-5 加 14+ 混合路由；Priv-Bridge-7 加验效轮询；
 * No-Lock-1 永久下线锁屏链：熄屏只许 binder 物理断电，无按键/锁屏兜底）。
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
 * ## 验效轮询（Priv-Bridge-7 · OPlus Android 15 真机实证；
 * ## No-Lock-1 熄屏永久下线锁屏链：只许 binder 物理断电 + SF 级严格验效）
 * - 真机 trace：`setDisplayPowerMode(mode=0)` 为 void 签名，“ok=true”只代表没抛异常，
 *   OPlus SurfaceFlinger 静默忽略（10s 后 mScreenState 仍 ON）。故 binder 调完后必须验效。
 * - 验效（Power-Fix-2 修订 · Pixel SDK 36 真机实证）：binder OFF 到达 SF 即断电
 *   （`dumpsys SurfaceFlinger` 示 powerMode=Off/isPoweredOn=0，scrcpy 同构），
 *   但 DisplayManager 侧恒报 ON（DPC 未跟进，screencap 取帧缓冲亦恒亮）。
 *   故熄屏验 SF 级 powerMode=Off **或** DM 级 STATE_OFF（或即真黑，约 2s，
 *   每次双路读回均记日志；物理断电就该 SF-Off），点亮验 DM STATE_ON 不变。
 *   特权进程无 Context 时 DM 路回退 DisplayManagerGlobal 反射，
 *   SF 路经 dumpsys 直读（uid 0 / shell 可 dump），同样可验。
 * - 熄屏无兜底（No-Lock-1 铁令）：binder 验效失败直接返 false，不注入
 *   KEYCODE_SLEEP/KEYCODE_POWER，不调 lockNow/lockAndSleep/ensureScreenOff，
 *   失败文案含“物理断电未生效（本机忽略），未执行锁屏兜底”，进
 *   lastError/Home 状态行/Toast，契约不变（仍经 recordPrivResult/lastPrivSummary）。
 * - 点亮侧保留兜底：在特权进程内经既有 [TouchInjector] 依次试 KEYCODE_WAKEUP、
 *   无则 KEYCODE_POWER（均 Down+Up，SOURCE_KEYBOARD，只点亮不制造新锁）再验 STATE_ON。
 *   AIDL 见 sleepByKey/powerByKey/wakeByKey（编号顺延；sleepByKey/powerByKey 为单发排障保留，
 *   熄屏主链路不再调用；wakeByKey 为点亮兜底仍在用）。
 * - 点亮侧验效保持 STATE_ON 不动。
 *
 * 熄屏语义：`POWER_MODE_OFF` 物理切断屏幕电源（OLED / 背光断电、触控停止上报），
 * 渲染管线与 CPU 保持满血前台运行；点亮语义：`POWER_MODE_NORMAL` 恢复屏幕电源。
 *
 * ## 提权三路径（Priv-Bridge-1 · 调用方必读；Root-Backend-1 修订为 Root→Shizuku 两段路由；
 * ## No-Lock-1 熄屏无按键兜底，点亮侧保留 WAKEUP→POWER）
 * - **Root 可用（首选）**：App 进程调用 [setDisplayPowerRouted] /
 *   [blackoutRouted] / [restoreRouted] 时先经 [RootExecutor.isRootAvailable] 判定，
 *   可用则经 [RootExecutor.runAsRootDisplayPower] 以 uid 0 真 root 身份单次 `app_process`
 *   拉起 [com.erl.blindcast.core.priv.RootMain] 直调 [setDisplayPower]（非 routed 版），
 *   成功后翻转 [isBlackedOut] 并清零 [lastError]，不再走 Shizuku。
 * - **Shizuku 已授权（次选，既有不动）**：Root 不可用/失败时回退既有通道，
 *   经 [PrivilegedBridge.withPrivileged] 把调用送进
 *   [com.erl.blindcast.core.priv.PrivilegedUserService]（特权身份）执行，
 *   成功后翻转 [isBlackedOut] 并清零 [lastError]。
 * - **未授权（报错引导）**：Root 不可用且 Shizuku 未运行 / 未授权时 routed 入口不抛异常，
 *   而是把 Root 段（无 su / 未授权引导去 KernelSU 点允许）+ Shizuku 引导文案
 *   （[PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE] / `SHIZUKU_NOT_RUNNING_MESSAGE`）
 *   记入 [lastError] 并返回 false，调用方（如 `HomeViewModel.blackoutNow`）据此弹 Toast。
 * - **按键兜底（仅点亮侧保留）**：特权进程内 [setDisplayPower] 熄屏为 binder→验效
 *   无兜底（No-Lock-1），点亮为 binder→WAKEUP→POWER 保持不变。
 *
 * ## 直调 vs 路由
 * - 直调版（[setDisplayPower] / [blackout] / [restore] / suspend 版）**必须在提权进程内执行**：
 *   Shizuku UserService（shell / root 身份的独立 app_process）或以 Root 身份启动的 app_process 进程。
 *   普通 App 进程直接调用将失败并返回 `false`（异常记录在 [lastError]）。
 *   特权进程侧（[com.erl.blindcast.core.priv.PrivilegedUserService]）调的正是直调版。
 * - App 进程（含 UI / Service / EmergencyRecovery 所在进程）一律走 routed 版。
 * - blackoutRouted/restoreRouted 两段路由（Root-Backend-1 + No-Lock-1）：Root 可用→RootExecutor 单次
 *   app_process；否则 Shizuku 既有 UserService 通道；熄屏特权侧 binder-only 无兜底，
 *   点亮侧 binder→WAKEUP→POWER 保留。
 *   PrivilegedUserService 内直调改道后的 SurfaceControl 版。
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

    // ------------------------------------------------------------------
    // Priv-Bridge-7：验效 + 按键兜底支撑（Context 与轮询参数）。
    // ------------------------------------------------------------------

    /** 验效轮询总时长约 2s（与 SurfaceControl.VERIFY_TIMEOUT_MS 一致）。 */
    private const val VERIFY_TIMEOUT_MS = 2000L

    /** 验效轮询间隔 250ms（8 次 ≈ 2s，每次读回均记日志）。 */
    private const val VERIFY_INTERVAL_MS = 250L

    /**
     * 按键兜底复验窗口约 6s（Priv-Bridge-8 · OPlus Android 15 真机实证：
     * KEY_SLEEP 注入 injOk=true 后屏幕入睡过渡要 1~3s，2s 窗口误判失败，
     * 数分钟后 mScreenState 稳定在 DOZE_SUSPEND 且进程存活。
     * 250ms 间隔不变，约 24 轮，每次读回均记日志。
     * 成功集放宽为 STATE_OFF/DOZE/DOZE_SUSPEND 任一；
     * binder 主路验效保持 STATE_OFF 严格判定不变，点亮侧保持 STATE_ON 不变）。
     */
    private const val KEY_FALLBACK_VERIFY_TIMEOUT_MS = 6000L

    /** 验效用 Context（特权进程由 PrivilegedUserService 构造时 init；App 进程可不调，回退 Global 反射）。 */
    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    /**
     * 存验效用 Context（幂等，持 applicationContext 弱引用，不泄漏）。
     * 特权进程侧由 PrivilegedUserService(Context) 构造调；App 进程不调亦可验
     * （SurfaceControl.readDisplayState 回退 DisplayManagerGlobal 反射，无需 Context）。
     */
    fun init(context: Context) {
        try {
            appContextRef = WeakReference(context.applicationContext ?: context)
        } catch (_: Throwable) {
            // 存引用失败不掩盖主流程：验效时按 ctx=null 走 Global 回退。
        }
    }

    private fun appContext(): Context? {
        return try {
            appContextRef?.get()
        } catch (_: Throwable) {
            null
        }
    }

    private fun tid(): String {
        val t = Thread.currentThread()
        return "t=${t.id}(${t.name})"
    }

    /**
     * 单次读回主屏状态日志串（验效轮询的基本单元之外，组装失败文案用）。
     *
     * @return 如“state=1(OFF)”/“state=2(ON)”/“unavailable”。
     */
    private fun readDisplayForLog(): String {
        return try {
            SurfaceControl.readDisplayStateForLog(appContext())
        } catch (t: Throwable) {
            "readFailed:${t.message}"
        }
    }

    /**
     * 轮询验效（binder 调完后必须调；点亮侧与 binder 主路熄屏验效同样调本方法）。
     * binder 主路熄屏验 STATE_OFF 严格判定（物理断电就该是 OFF），点亮验 STATE_ON，不动。
     *
     * @param expectOff true = 熄屏验 STATE_OFF，false = 点亮验 STATE_ON。
     * @return 超时前命中期望状态 true，否则 false（含读回不可用）。
     */
    private fun pollDisplayState(expectOff: Boolean): Boolean {
        return try {
            SurfaceControl.pollDisplayState(appContext(), expectOff, VERIFY_TIMEOUT_MS, VERIFY_INTERVAL_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "[PowerController] ${tid()} pollDisplayState threw expectOff=$expectOff", t)
            false
        }
    }

    /**
     * 熄屏验效（Power-Fix-2 · binder OFF 调完后必须调本方法，点亮侧不动）。
     * SDK 34+ 真机实证：SF 级断电不向 DisplayManager 传播（DM 恒报 ON，
     * screencap 取帧缓冲亦恒亮），故熄屏成功判据为 SF 级 powerMode=Off
     * **或** DM 级 STATE_OFF（或即真黑，兼容老 ROM；scrcpy turn-screen-off 同构）。
     * 单轮内先读 SF 再读 DM，每次读回均记日志；窗口约 2s 不变。
     *
     * @return 超时前任一判据命中 true，否则 false（含双路不可用）。
     */
    private fun pollDisplayOffState(): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + VERIFY_TIMEOUT_MS
        var attempt = 0
        while (true) {
            attempt++
            val sfOff: Boolean? = try {
                SurfaceControl.readSFPowerOff()
            } catch (t: Throwable) {
                Log.e(TAG, "[PowerController] ${tid()} pollOffState attempt=$attempt sf threw", t)
                null
            }
            val dmState: Int? = try {
                SurfaceControl.readDisplayState(appContext())
            } catch (t: Throwable) {
                Log.e(TAG, "[PowerController] ${tid()} pollOffState attempt=$attempt dm threw", t)
                null
            }
            val sfLog = when (sfOff) {
                true -> "powerMode=Off"
                false -> "powerMode!=Off"
                null -> "unavailable"
            }
            val dmLog = if (dmState == null) "unavailable"
                else "state=$dmState(${SurfaceControl.displayStateName(dmState)})"
            Log.d(TAG, "[PowerController] ${tid()} pollOffState attempt=$attempt " +
                "expect=SF-Off||DM-OFF sf=$sfLog dm=$dmLog")
            if (sfOff == true || dmState == android.view.Display.STATE_OFF) {
                Log.d(TAG, "[PowerController] ${tid()} pollOffState HIT attempt=$attempt " +
                    "sf=$sfLog dm=$dmLog")
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= deadline) break
            try {
                Thread.sleep(minOf(VERIFY_INTERVAL_MS, deadline - now).coerceAtLeast(50L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Log.e(TAG, "[PowerController] ${tid()} pollOffState MISS attempts=$attempt")
        return false
    }

    /**
     * 熄屏单次读回日志串（SF 级 + DM 级双判据，供失败文案组装）。
     *
     * @return 如“sf=powerMode=Off dm=state=2(ON)”。
     */
    private fun readOffStateForLog(): String {
        val sf = try {
            SurfaceControl.readSFPowerForLog()
        } catch (t: Throwable) {
            "sf=readFailed:${t.message}"
        }
        return "$sf ${readDisplayForLog().replace("state=", "dm=state=")}"
    }

    /**
     * 按键兜底复验（Priv-Bridge-8 · 仅熄屏按键后用）。
     * 窗口约 6s（250ms 间隔不变，多轮），成功集放宽为
     * STATE_OFF/DOZE/DOZE_SUSPEND 任一（Display 读回已映射好三值，每次读值均记日志）。
     * 成功后调用方照常启动 UserActivityKeeper（userActivity 不唤醒熟睡设备，
     * 只延缓无活动计时，doze 下稳定）。
     *
     * @return 超时前命中任一熄屏态 true，否则 false（含读回不可用）。
     */
    private fun pollDisplayStateOffOrDoze(): Boolean {
        return try {
            SurfaceControl.pollDisplayStateOffOrDoze(
                appContext(), KEY_FALLBACK_VERIFY_TIMEOUT_MS, VERIFY_INTERVAL_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "[PowerController] ${tid()} pollOffOrDoze threw", t)
            false
        }
    }

    /**
     * Root-Cut-1：binder 物理断电/点亮直试结果（仅数据，不改状态）。
     *
     * @property verified binder 无异常且返回 true 且严格验效通过（熄屏 STATE_OFF / 点亮 STATE_ON，约 2s）。
     * @property route 本次混合路由（SurfaceControl / DisplayControl，与 [setDisplayPower] 同逻辑）。
     * @property binderOk binder 调用返回值（异常时 false）。
     * @property binderErrMsg binder 异常文案（无异常时 null）。
     * @property read 验效后单次读回串（如 state=1(OFF)，供失败文案组装）。
     * @property mode 本次透传 mode（熄屏 0 / 点亮 2）。
     */
    data class BinderFirstResult(
        val verified: Boolean,
        val route: String,
        val binderOk: Boolean,
        val binderErrMsg: String?,
        val read: String,
        val mode: Int,
    )

    /**
     * Root-Cut-1：binder 物理断电/点亮直试（仅 binder + 严格验效约 2s，无按键兜底，不改状态）。
     *
     * 混合路由与日志与 [setDisplayPower] 的 binder 段同逻辑（SDK>=34 特征探测，
     * DisplayControl 只取 token、设值一律 SurfaceControl；其余走 SurfaceControl；
     * 每次读回均记日志），仅截取“调 binder→严格验效”两步：
     * 熄屏验 STATE_OFF 严格判定（物理断电就该是 OFF），点亮验 STATE_ON，窗口约 2s。
     * 成功（binder 无异常且 true 且验效通过）调用方直接返回 ok（无锁屏、无 AOD 真黑）；
     * 熄屏失败调用方直接返 false，不进任何锁屏/按键兜底；点亮失败调用方试 wakeByKey
     *（本方法不翻转 [isBlackedOut]、不写 [lastError]、
     * 不调 [recordPrivResult]，最终成败由调用方一次记入，契约不变）。
     *
     * 必须在提权进程内执行（Root app_process / Shizuku UserService），普通 App 进程调必败。
     *
     * @param on true = 点亮（POWER_MODE_NORMAL），false = 物理熄屏（POWER_MODE_OFF）。
     * @return binder 直试结果（含路由/读回，供调用方组装“binder-root 段”文案）。
     */
    @Synchronized
    @WorkerThread
    fun tryBinderDisplayPower(on: Boolean): BinderFirstResult {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val expectOff = !on
        val expectName = if (expectOff) "STATE_OFF" else "STATE_ON"
        Log.d(TAG, "[PowerController][BinderFirst] ${tid()} enter on=$on mode=$mode")
        return try {
            val sdk = Build.VERSION.SDK_INT
            val hasIds = try {
                SurfaceControl.hasGetPhysicalDisplayIds()
            } catch (_: Throwable) {
                false
            }
            val useDisplayControl = sdk >= 34 && !hasIds
            val route = if (useDisplayControl) "DisplayControl" else "SurfaceControl"
            Log.d(TAG, "[PowerController][BinderFirst] ${tid()} hybrid sdk=$sdk hasIds=$hasIds " +
                "route=$route mode=$mode")
            var binderOk = false
            var binderErrMsg: String? = null
            try {
                binderOk = if (useDisplayControl) {
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
                Log.d(TAG, "[PowerController][BinderFirst] ${tid()} binder done on=$on mode=$mode " +
                    "route=$route binderOk=$binderOk")
            } catch (t: Throwable) {
                binderErrMsg = t.message ?: t.toString()
                binderOk = false
                Log.e(TAG, "[PowerController][BinderFirst] ${tid()} binder threw on=$on mode=$mode route=$route", t)
            }
            val verifiedPoll = if (expectOff) pollDisplayOffState() else pollDisplayState(expectOff = false)
            val read = if (expectOff) readOffStateForLog() else readDisplayForLog()
            Log.d(TAG, "[PowerController][BinderFirst] ${tid()} verify on=$on expect=$expectName " +
                "binderOk=$binderOk binderErr=$binderErrMsg verified=$verifiedPoll read=$read")
            val verified = binderErrMsg == null && binderOk && verifiedPoll
            Log.d(TAG, "[PowerController][BinderFirst] ${tid()} exit on=$on verified=$verified read=$read")
            BinderFirstResult(
                verified = verified,
                route = route,
                binderOk = binderOk,
                binderErrMsg = binderErrMsg,
                read = read,
                mode = mode,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "[PowerController][BinderFirst] ${tid()} on=$on threw", t)
            BinderFirstResult(
                verified = false,
                route = "unknown",
                binderOk = false,
                binderErrMsg = t.message ?: t.toString(),
                read = readDisplayForLog(),
                mode = mode,
            )
        }
    }

    /**
     * No-Lock-1：组装“binder-root 段”失败文案（熄屏直接拼“未执行锁屏兜底”，点亮供调用方拼 wake 段）。
     *
     * @param on true = 点亮，false = 熄屏（决定期望态措辞 STATE_ON / STATE_OFF）。
     * @param r [tryBinderDisplayPower] 返回结果。
     * @return 如“binder-root段：binder已调无异常但验效失败（route=… mode=0，轮询约2s仍未STATE_OFF，state=…）”。
     */
    fun binderFirstSegmentDesc(on: Boolean, r: BinderFirstResult): String {
        val expectName = if (!on) "STATE_OFF" else "STATE_ON"
        return when {
            r.binderErrMsg != null ->
                "binder-root段：设值异常（route=${r.route} mode=${r.mode}）：${r.binderErrMsg}；" +
                    "验效约2s仍未$expectName（当前${r.read}）"
            !r.binderOk ->
                "binder-root段：设值失败：底层返回false（mode=${r.mode} route=${r.route}）；" +
                    "验效约2s仍未$expectName（当前${r.read}）"
            else ->
                "binder-root段：binder已调无异常但验效失败（route=${r.route} mode=${r.mode}，" +
                    "轮询约2s仍未$expectName，${r.read}）"
        }
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
     * 设置主显示屏电源（同步阻塞，含 Binder 调用 + 验效轮询，禁止主线程直调）。
     *
     * 直调版：**必须在提权进程内执行**（Shizuku UserService / Root app_process）。
     * App 进程请用 [setDisplayPowerRouted]；特权进程侧（PrivilegedUserService）调本方法。
     *
     * No-Lock-1 全链路（OPlus Android 15 真机实证：void 签名 ok=true 只代表无异常，
     * SurfaceFlinger 可静默忽略，故必须验效；熄屏永不 fallback 锁屏链）：
     * 1. binder：既有混合路由调 setDisplayPowerMode（无异常记 binderOk）；
     * 2. 验效：[pollDisplayOffState]（熄屏：SF 级 powerMode=Off 或 DM 级 STATE_OFF，
     *    约 2s，每次双路读回记日志；物理断电就该 SF-Off）/
     *    [pollDisplayState]（点亮验 STATE_ON，约 2s）；
     * 3. 熄屏无兜底：验效失败直接返 false，不注入 KEYCODE_SLEEP/KEYCODE_POWER，
     *    不调 lockNow/lockAndSleep/ensureScreenOff，失败文案含
     *    “物理断电未生效（本机忽略），未执行锁屏兜底”；
     *    点亮侧验效失败则在特权进程内经 [TouchInjector] 依次试 KEYCODE_WAKEUP→
     *    KEYCODE_POWER（均 Down+Up，SOURCE_KEYBOARD，只点亮不制造新锁）再验效。
     *    点亮侧复验仍走严格 STATE_ON（约 2s），不动。
     * 仅验效通过才算成功并翻转 [isBlackedOut]；熄屏失败文案为 binder 段 +
     * “物理断电未生效（本机忽略），未执行锁屏兜底”，点亮失败文案写清
     * binder→WAKEUP→POWER 各段，进 [lastError]/状态行/Toast，契约不变。
     *
     * @param on true = 点亮（[POWER_MODE_NORMAL]），false = 物理熄屏（[POWER_MODE_OFF]）。
     * @return 验效通过 true；反射失败 / 系统拒绝 / 验效失败返回 false，
     *   且失败原因记录在 [lastError]，[isBlackedOut] 保持不变。
     */
    @Synchronized
    @WorkerThread
    fun setDisplayPower(on: Boolean): Boolean {
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        val op = if (on) "restore" else "blackout"
        val expectOff = !on
        val expectName = if (expectOff) "STATE_OFF" else "STATE_ON"
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
            var binderOk = false
            var binderErr: Throwable? = null
            try {
                binderOk = if (useDisplayControl) {
                    // Priv-Bridge-6：DisplayControl 只取 token，设值一律走 SurfaceControl。
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
                Log.d(TAG, "[PowerController] ${tid()} binder done on=$on mode=$mode " +
                    "route=$route binderOk=$binderOk")
            } catch (t: Throwable) {
                binderErr = t
                binderOk = false
                Log.e(TAG, "[PowerController] ${tid()} binder threw on=$on mode=$mode route=$route", t)
            }
            // 验效：binder 调完后必须 poll（OPlus 静默忽略即在此现形；
            // Power-Fix-2：熄屏走 SF 级或判据，点亮走 DM STATE_ON 不变）。
            val firstVerified = if (expectOff) pollDisplayOffState() else pollDisplayState(expectOff = false)
            val firstRead = if (expectOff) readOffStateForLog() else readDisplayForLog()
            Log.d(TAG, "[PowerController] ${tid()} firstVerify on=$on expect=$expectName " +
                "binderOk=$binderOk binderErr=${binderErr?.message} " +
                "verified=$firstVerified read=$firstRead")
            if (binderErr == null && binderOk && firstVerified) {
                isBlackedOut = !on
                lastError = null
                recordPrivResult(op, true, null)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=$on mode=$mode " +
                    "route=$route ok=true via=binder+verify blackedOut=$isBlackedOut")
                return true
            }
            // No-Lock-1：熄屏无兜底（永不 fallback 锁屏链：不注 SLEEP/POWER，不调 lockNow）。
            val binderDesc = when {
                binderErr != null -> "binder抛异常（${binderErr.message}）"
                !binderOk -> "binder返回false"
                else -> "binder已调无异常但验效失败"
            }
            Log.d(TAG, "[PowerController] ${tid()} $binderDesc route=$route mode=$mode " +
                "firstRead=$firstRead, no fallback (No-Lock-1)")
            if (!on) {
                val msg = if (binderErr == null && binderOk) {
                    "binder已调无异常但验效失败（route=$route mode=$mode，轮询约2s仍未STATE_OFF，$firstRead）；" +
                        "物理断电未生效（本机忽略），未执行锁屏兜底；" +
                        "当前$firstRead，见特权进程logcat [SurfaceControl]/[PowerController]明细"
                } else if (binderErr != null) {
                    "设值异常（route=$route mode=$mode）：${binderErr.message}；" +
                        "物理断电未生效（本机忽略），未执行锁屏兜底；" +
                        "当前$firstRead，见特权进程logcat [DisplayControl]/[SurfaceControl]明细"
                } else {
                    "设值失败：底层返回false（mode=$mode route=$route）；" +
                        "物理断电未生效（本机忽略），未执行锁屏兜底；" +
                        "当前$firstRead，见特权进程logcat [DisplayControl]/[SurfaceControl]明细"
                }
                lastError = IllegalStateException(msg)
                recordPrivResult(op, false, msg)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=false ok=false " +
                    "blackedOut=$isBlackedOut err=$msg")
                return false
            } else {
                // 点亮侧依次试 KEYCODE_WAKEUP、无则 KEYCODE_POWER。
                var injWakeOk = false
                var injWakeErr: String? = null
                try {
                    injWakeOk = TouchInjector.injectKey(KeyEvent.KEYCODE_WAKEUP)
                    injWakeErr = TouchInjector.lastError?.message
                } catch (t: Throwable) {
                    injWakeErr = t.message ?: t.toString()
                    Log.e(TAG, "[PowerController] ${tid()} keyFallback WAKEUP inject threw", t)
                }
                Log.d(TAG, "[PowerController] ${tid()} keyFallback WAKEUP " +
                    "injOk=$injWakeOk err=${injWakeErr ?: "none"}")
                var verifiedAfterWake = pollDisplayState(expectOff = false)
                var readAfterWake = readDisplayForLog()
                Log.d(TAG, "[PowerController] ${tid()} keyFallback WAKEUP " +
                    "verified=$verifiedAfterWake read=$readAfterWake")
                if (verifiedAfterWake) {
                    isBlackedOut = false
                    lastError = null
                    recordPrivResult(op, true, null)
                    Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=true ok=true " +
                        "via=key(WAKEUP) blackedOut=false")
                    return true
                }
                var injPowerOk = false
                var injPowerErr: String? = null
                try {
                    injPowerOk = TouchInjector.injectKey(KeyEvent.KEYCODE_POWER)
                    injPowerErr = TouchInjector.lastError?.message
                } catch (t: Throwable) {
                    injPowerErr = t.message ?: t.toString()
                    Log.e(TAG, "[PowerController] ${tid()} keyFallback POWER inject threw", t)
                }
                Log.d(TAG, "[PowerController] ${tid()} keyFallback POWER " +
                    "injOk=$injPowerOk err=${injPowerErr ?: "none"}")
                val verifiedAfterPower = pollDisplayState(expectOff = false)
                val readAfterPower = readDisplayForLog()
                Log.d(TAG, "[PowerController] ${tid()} keyFallback POWER " +
                    "verified=$verifiedAfterPower read=$readAfterPower")
                if (verifiedAfterPower) {
                    isBlackedOut = false
                    lastError = null
                    recordPrivResult(op, true, null)
                    Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=true ok=true " +
                        "via=key(POWER) blackedOut=false")
                    return true
                }
                val keyDesc = "已试按键KEY_WAKEUP注入(injOk=$injWakeOk" +
                    (if (injWakeErr != null) ",err=$injWakeErr" else "") +
                    ")复验仍未STATE_ON($readAfterWake)→再试KEY_POWER注入(injOk=$injPowerOk" +
                    (if (injPowerErr != null) ",err=$injPowerErr" else "") +
                    ")复验仍未STATE_ON($readAfterPower)"
                val msg = if (binderErr == null && binderOk) {
                    "binder已调无异常但验效失败（route=$route mode=$mode，轮询约2s仍未STATE_ON，$firstRead）→$keyDesc；" +
                        "当前$readAfterPower，ROM可能静默忽略SurfaceFlinger调用，" +
                        "见特权进程logcat [SurfaceControl]/[PowerController]明细"
                } else if (binderErr != null) {
                    "设值异常（route=$route mode=$mode）：${binderErr.message}；$keyDesc；" +
                        "当前$readAfterPower，见特权进程logcat [DisplayControl]/[SurfaceControl]明细"
                } else {
                    "设值失败：底层返回false（mode=$mode route=$route）；$keyDesc；" +
                        "当前$readAfterPower，见特权进程logcat [DisplayControl]/[SurfaceControl]明细"
                }
                lastError = IllegalStateException(msg)
                recordPrivResult(op, false, msg)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPower on=true ok=false " +
                    "blackedOut=$isBlackedOut err=$msg")
                return false
            }
        } catch (t: Throwable) {
            val op = if (on) "restore" else "blackout"
            lastError = t
            recordPrivResult(op, false, t.message ?: t.toString())
            Log.e(TAG, "[PowerController] ${tid()} setDisplayPower on=$on mode=$mode failed", t)
            return false
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
     * No-Lock-1：binder-only + STATE_OFF 严格验效，失败直接 false，不进锁屏链。
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
    // 按键直调（必须在提权进程内执行；复用 TouchInjector）。
    // No-Lock-1：熄屏主链路不再调用 sleepByKey/powerByKey；两者仅单发排障保留。
    // 点亮 wakeByKey 为主链路点亮兜底仍在用（只点亮不制造新锁）。
    // ------------------------------------------------------------------

    /**
     * 按键熄屏直调·SLEEP 单键（同步阻塞，禁止主线程直调；必须在提权进程内执行）。
     *
     * No-Lock-1：熄屏主链路（[setDisplayPower]/[blackoutRouted]）不再调用本方法；
     * 本方法仅单发排障保留。经 [TouchInjector.injectKey] 注入 KEYCODE_SLEEP
     *（Down+Up，SOURCE_KEYBOARD）后轮询验 STATE_OFF/DOZE/DOZE_SUSPEND 任一
     *（窗口约 6s，每次读回记日志）。
     * 仅验效通过才算成功并翻转 [isBlackedOut]；供 AIDL sleepByKey 单发排障用。
     *
     * @return 验效通过 true，否则 false（明细进 [lastError]）。
     */
    @Synchronized
    @WorkerThread
    fun sleepByKey(): Boolean {
        Log.d(TAG, "[PowerController] ${tid()} sleepByKey enter")
        return try {
            var injOk: Boolean
            var injErr: String?
            try {
                injOk = TouchInjector.injectKey(KeyEvent.KEYCODE_SLEEP)
                injErr = TouchInjector.lastError?.message
            } catch (t: Throwable) {
                injOk = false
                injErr = t.message ?: t.toString()
                Log.e(TAG, "[PowerController] ${tid()} sleepByKey inject threw", t)
            }
            Log.d(TAG, "[PowerController] ${tid()} sleepByKey injOk=$injOk err=${injErr ?: "none"}")
            val verified = pollDisplayStateOffOrDoze()
            val read = readDisplayForLog()
            Log.d(TAG, "[PowerController] ${tid()} sleepByKey verified=$verified read=$read " +
                "expect=OFF/DOZE/DOZE_SUSPEND")
            if (verified) {
                isBlackedOut = true
                lastError = null
                recordPrivResult("blackout", true, null)
                Log.d(TAG, "[PowerController] ${tid()} sleepByKey ok=true read=$read " +
                    "keeper=callerStarts(userActivity doze-safe)")
            } else {
                val msg = "按键熄屏失败：KEY_SLEEP注入(injOk=$injOk" +
                    (if (injErr != null) ",err=$injErr" else "") +
                    ")后复验仍未STATE_OFF/DOZE/DOZE_SUSPEND（轮询约6s，当前$read），" +
                    "见特权进程logcat [SurfaceControl]/[PowerController]明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} sleepByKey exit verified=$verified " +
                "blackedOut=$isBlackedOut err=${lastError?.message}")
            verified
        } catch (t: Throwable) {
            lastError = t
            recordPrivResult("blackout", false, t.message ?: t.toString())
            Log.e(TAG, "[PowerController] ${tid()} sleepByKey failed", t)
            false
        }
    }

    /**
     * 按键熄屏直调·POWER 单键（同步阻塞，禁止主线程直调；必须在提权进程内执行）。
     *
     * No-Lock-1：熄屏主链路不再调用本方法；本方法仅单发排障保留。
     * 经 [TouchInjector.injectKey] 注入 KEYCODE_POWER（Down+Up，SOURCE_KEYBOARD）
     * 后轮询验 STATE_OFF/DOZE/DOZE_SUSPEND 任一（窗口约 6s，每次读回记日志）。
     * 仅验效通过才算成功并翻转 [isBlackedOut]；供 AIDL powerByKey 单发排障用。
     *
     * @return 验效通过 true，否则 false（明细进 [lastError]）。
     */
    @Synchronized
    @WorkerThread
    fun powerByKey(): Boolean {
        Log.d(TAG, "[PowerController] ${tid()} powerByKey enter")
        return try {
            var injOk: Boolean
            var injErr: String?
            try {
                injOk = TouchInjector.injectKey(KeyEvent.KEYCODE_POWER)
                injErr = TouchInjector.lastError?.message
            } catch (t: Throwable) {
                injOk = false
                injErr = t.message ?: t.toString()
                Log.e(TAG, "[PowerController] ${tid()} powerByKey inject threw", t)
            }
            Log.d(TAG, "[PowerController] ${tid()} powerByKey injOk=$injOk err=${injErr ?: "none"}")
            val verified = pollDisplayStateOffOrDoze()
            val read = readDisplayForLog()
            Log.d(TAG, "[PowerController] ${tid()} powerByKey verified=$verified read=$read " +
                "expect=OFF/DOZE/DOZE_SUSPEND")
            if (verified) {
                isBlackedOut = true
                lastError = null
                recordPrivResult("blackout", true, null)
                Log.d(TAG, "[PowerController] ${tid()} powerByKey ok=true read=$read " +
                    "keeper=callerStarts(userActivity doze-safe)")
            } else {
                val msg = "按键熄屏失败：KEY_POWER注入(injOk=$injOk" +
                    (if (injErr != null) ",err=$injErr" else "") +
                    ")后复验仍未STATE_OFF/DOZE/DOZE_SUSPEND（轮询约6s，当前$read），" +
                    "见特权进程logcat [SurfaceControl]/[PowerController]明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} powerByKey exit verified=$verified " +
                "blackedOut=$isBlackedOut err=${lastError?.message}")
            verified
        } catch (t: Throwable) {
            lastError = t
            recordPrivResult("blackout", false, t.message ?: t.toString())
            Log.e(TAG, "[PowerController] ${tid()} powerByKey failed", t)
            false
        }
    }

    /**
     * 按键点亮直调（同步阻塞，禁止主线程直调；必须在提权进程内执行）。
     *
     * 依次试 KEYCODE_WAKEUP、无则 KEYCODE_POWER（均 Down+Up，SOURCE_KEYBOARD），
     * 每次注入后轮询验 STATE_ON（约 2s，每次读回记日志）。任一验效通过即成功。
     *
     * @return 验效通过 true，否则 false（明细进 [lastError]）。
     */
    @Synchronized
    @WorkerThread
    fun wakeByKey(): Boolean {
        Log.d(TAG, "[PowerController] ${tid()} wakeByKey enter")
        return try {
            var injWakeOk = false
            var injWakeErr: String? = null
            try {
                injWakeOk = TouchInjector.injectKey(KeyEvent.KEYCODE_WAKEUP)
                injWakeErr = TouchInjector.lastError?.message
            } catch (t: Throwable) {
                injWakeErr = t.message ?: t.toString()
                Log.e(TAG, "[PowerController] ${tid()} wakeByKey WAKEUP inject threw", t)
            }
            Log.d(TAG, "[PowerController] ${tid()} wakeByKey WAKEUP " +
                "injOk=$injWakeOk err=${injWakeErr ?: "none"}")
            var verified = pollDisplayState(expectOff = false)
            var read = readDisplayForLog()
            Log.d(TAG, "[PowerController] ${tid()} wakeByKey after WAKEUP " +
                "verified=$verified read=$read")
            if (verified) {
                isBlackedOut = false
                lastError = null
                recordPrivResult("restore", true, null)
                Log.d(TAG, "[PowerController] ${tid()} wakeByKey exit via=WAKEUP blackedOut=false")
                return true
            }
            var injPowerOk = false
            var injPowerErr: String? = null
            try {
                injPowerOk = TouchInjector.injectKey(KeyEvent.KEYCODE_POWER)
                injPowerErr = TouchInjector.lastError?.message
            } catch (t: Throwable) {
                injPowerErr = t.message ?: t.toString()
                Log.e(TAG, "[PowerController] ${tid()} wakeByKey POWER inject threw", t)
            }
            Log.d(TAG, "[PowerController] ${tid()} wakeByKey POWER " +
                "injOk=$injPowerOk err=${injPowerErr ?: "none"}")
            verified = pollDisplayState(expectOff = false)
            read = readDisplayForLog()
            Log.d(TAG, "[PowerController] ${tid()} wakeByKey after POWER " +
                "verified=$verified read=$read")
            if (verified) {
                isBlackedOut = false
                lastError = null
                recordPrivResult("restore", true, null)
            } else {
                val msg = "按键点亮失败：已试KEY_WAKEUP注入(injOk=$injWakeOk" +
                    (if (injWakeErr != null) ",err=$injWakeErr" else "") +
                    ")→再试KEY_POWER注入(injOk=$injPowerOk" +
                    (if (injPowerErr != null) ",err=$injPowerErr" else "") +
                    ")复验仍未STATE_ON（轮询约2s，当前$read），" +
                    "见特权进程logcat [SurfaceControl]/[PowerController]明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("restore", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} wakeByKey exit verified=$verified " +
                "blackedOut=$isBlackedOut err=${lastError?.message}")
            verified
        } catch (t: Throwable) {
            lastError = t
            recordPrivResult("restore", false, t.message ?: t.toString())
            Log.e(TAG, "[PowerController] ${tid()} wakeByKey failed", t)
            false
        }
    }

    /** [sleepByKey] 的协程版本（特权进程内，自动切 IO）。 */
    suspend fun sleepByKeySuspend(): Boolean =
        withContext(Dispatchers.IO) { sleepByKey() }

    /** [powerByKey] 的协程版本（特权进程内，自动切 IO）。 */
    suspend fun powerByKeySuspend(): Boolean =
        withContext(Dispatchers.IO) { powerByKey() }

    /** [wakeByKey] 的协程版本（特权进程内，自动切 IO）。 */
    suspend fun wakeByKeySuspend(): Boolean =
        withContext(Dispatchers.IO) { wakeByKey() }

    // ------------------------------------------------------------------
    // Priv-Bridge-1：App 进程提权路由入口（Shizuku 已授权 → UserService 通道；
    // 未授权 → 记 lastError 并返回 false，不抛异常，调用方据此弹 Toast）。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Root-Backend-1 + No-Lock-1：Root→Shizuku两段路由支撑（熄屏特权侧 binder-only 无兜底）。
    // ------------------------------------------------------------------

    /**
     * 解析调用方 APK 路径（拼 `CLASSPATH=` 用，勿硬编码）。
     *
     * 优先级：[appContextRef]（特权进程 init 存的；App 进程若调过 init 同样可用）→
     * `ActivityThread.currentApplication()` 反射（App 进程免 init 即可取，不新增权限）→ null（取不到则跳过 Root 段）。
     *
     * @param packageName 调用方包名（`context.packageName`，防 applicationIdSuffix 变体时回退自身）。
     * @return `applicationInfo.sourceDir`，取不到返回 null。
     */
    private fun resolveApkPath(packageName: String): String? {
        runCatching {
            appContextRef?.get()?.let { ctx ->
                runCatching {
                    ctx.packageManager.getApplicationInfo(packageName, 0).sourceDir
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                runCatching { ctx.applicationInfo.sourceDir }
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context
                ?: return null
            runCatching {
                app.packageManager.getApplicationInfo(packageName, 0).sourceDir
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { app.applicationInfo.sourceDir }
                    .getOrNull()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 试 Root 段（单次 `app_process`，失败不抛，只记文案供最终失败组合）。
     *
     * @return first = Root 段是否成功（true 则调用方直接成功返回）；second = Root 段文案
     *  （成功时 null；跳过/失败时为追加进最终失败文案的 Root 段，如无 su/未授权引导去 KernelSU）。
     */
    private suspend fun tryRootSegment(packageName: String, on: Boolean): Pair<Boolean, String?> {
        val rootAvailable: Boolean = try {
            RootExecutor.isRootAvailable()
        } catch (_: Throwable) {
            false
        }
        Log.d(TAG, "[PowerController] ${tid()} tryRootSegment on=$on available=$rootAvailable")
        if (!rootAvailable) {
            return false to "Root段不可用（无su/未授权，去KernelSU管理器点允许BlindCast）"
        }
        val apkPath = try {
            resolveApkPath(packageName)
        } catch (_: Throwable) {
            null
        }
        if (apkPath.isNullOrBlank()) {
            Log.d(TAG, "[PowerController] ${tid()} tryRootSegment skip no apkPath pkg=$packageName")
            return false to "Root段跳过（取APK路径失败）"
        }
        val rootOk: Boolean = try {
            RootExecutor.runAsRootDisplayPower(packageName, apkPath, on)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "[PowerController] ${tid()} tryRootSegment run threw", t)
            false
        }
        Log.d(TAG, "[PowerController] ${tid()} tryRootSegment done on=$on ok=$rootOk")
        return if (rootOk) {
            true to null
        } else {
            false to "Root段已试失败（见logcat [RootExecutor]/[RootMain]明细）"
        }
    }

    /** 组合最终失败文案（Root 段 + Shizuku/特权段，任一为空则取另一段）。 */
    private fun combineRootError(rootNote: String?, shizukuPart: String?): String {
        val root = rootNote?.takeIf { it.isNotBlank() }
        val shizuku = shizukuPart?.takeIf { it.isNotBlank() }
        return when {
            root != null && shizuku != null -> "$root；$shizuku"
            root != null -> root
            shizuku != null -> shizuku
            else -> "特权执行失败（未知原因）"
        }
    }

    /**
     * 设置主显示屏电源（App 进程入口，协程，可在任意调度器上调用）。
     *
     * 路由逻辑（Root-Backend-1 两段 + No-Lock-1：Root→Shizuku，熄屏无按键兜底）：
     * 1. Root 可用→[RootExecutor.runAsRootDisplayPower] 单次 `app_process` 直调
     *    [setDisplayPower]（uid 0 真 root，成功直接返回）；
     * 2. 否则 [PrivilegedBridge.isPrivilegedGranted] 为 true → 经 UserService
     *    通道在特权进程内执行（熄屏 binder→STATE_OFF 严格验效无兜底，
     *    点亮 binder→WAKEUP→POWER 保留，见 [setDisplayPower]）。
     * 失败文案追加 Root 段（无 su / 未授权引导去 KernelSU 点允许），进
     * [lastError]/状态行/Toast，契约不变。
     * Priv-Bridge-7：Shizuku 段失败明细经 [PrivilegedBridge.setDisplayPowerDetailed] 同绑定内取回
     * （熄屏为 binder 段 +“物理断电未生效（本机忽略），未执行锁屏兜底”，点亮为 binder→WAKEUP→POWER 各记）。
     *
     * @param packageName 调用方包名（`context.packageName`，用于定位 UserService 组件与解析 APK 路径）。
     * @param on true = 点亮，false = 物理熄屏。
     * @return 特权执行验效通过 true；Root/Shizuku 均失败 / 未授权 / 绑定失败 / 验效失败时 false。
     */
    suspend fun setDisplayPowerRouted(packageName: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            // Root 段（首选；跳过/失败则记文案并回退 Shizuku，既有 Shizuku 逻辑不动）。
            val rootRes: Pair<Boolean, String?> = try {
                tryRootSegment(packageName, on)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "[PowerController] ${tid()} setDisplayPowerRouted root segment threw", t)
                false to "Root段异常（${t.message ?: t}）"
            }
            if (rootRes.first) {
                isBlackedOut = !on
                lastError = null
                recordPrivResult(if (on) "restore" else "blackout", true, null)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted on=$on ok=true via=root")
                return@withContext true
            }
            val rootNote = rootRes.second
            val running = PrivilegedBridge.isShizukuRunning()
            val granted = PrivilegedBridge.isPrivilegedGranted()
            Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted enter on=$on " +
                "rootNote=$rootNote running=$running granted=$granted")
            if (!granted) {
                val shizukuMsg = if (running) {
                    PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE
                } else {
                    PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE
                }
                val msg = combineRootError(rootNote, shizukuMsg)
                lastError = if (running) {
                    SecurityException(msg)
                } else {
                    IllegalStateException(msg)
                }
                recordPrivResult(if (on) "restore" else "blackout", false, msg)
                Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted auth denied " +
                    "on=$on running=$running granted=$granted err=$msg")
                return@withContext false
            }
            val detailed: Pair<Boolean, String?> = try {
                PrivilegedBridge.setDisplayPowerDetailed(packageName, on)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val base = t.message ?: t.toString()
                val msg = combineRootError(rootNote, "Shizuku段绑定失败：$base")
                lastError = IllegalStateException(msg, t)
                recordPrivResult(if (on) "restore" else "blackout", false, msg)
                Log.e(TAG, "[PowerController] ${tid()} setDisplayPowerRouted on=$on bridge failed", t)
                return@withContext false
            }
            val ok = detailed.first
            if (ok) {
                isBlackedOut = !on
                lastError = null
                recordPrivResult(if (on) "restore" else "blackout", true, null)
            } else {
                val privErr = detailed.second?.takeIf { it.isNotBlank() }
                    ?: "特权进程执行失败（返回 false），请查看特权进程 logcat [SurfaceControl] 逐屏明细"
                val msg = combineRootError(rootNote, "Shizuku段失败：$privErr")
                lastError = IllegalStateException(msg)
                recordPrivResult(if (on) "restore" else "blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} setDisplayPowerRouted on=$on ok=$ok " +
                "via=shizuku blackedOut=$isBlackedOut err=${lastError?.message}")
            ok
        }

    /**
     * 物理熄屏（App 进程入口，[setDisplayPowerRouted] 特化，`on = false`；
     * No-Lock-1：只许 binder 物理断电 + STATE_OFF 严格验效，失败直接返 false，
     * 不注入 SLEEP/POWER，不调锁屏链，失败文案含“物理断电未生效（本机忽略），未执行锁屏兜底”）。
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
     * 点亮屏幕（App 进程入口，[setDisplayPowerRouted] 特化，`on = true`；
     * binder NORMAL + WAKEUP→POWER 唤醒键保留，只点亮不制造新锁）。
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

    /**
     * 按键熄屏·SLEEP 单键（App 进程入口，[sleepByKey] 的路由版，经 UserService 通道）。
     * No-Lock-1：常规熄屏请走 [blackoutRouted]（binder-only 无兜底）；本入口仅单发按键排障保留，
     * 熄屏主链路不再调用。
     *
     * @param packageName 调用方包名。
     * @return 验效通过 true，否则 false（明细进 [lastError]，含特权侧回传）。
     */
    suspend fun sleepByKeyRouted(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "[PowerController] ${tid()} sleepByKeyRouted enter")
            if (!PrivilegedBridge.isPrivilegedGranted()) {
                val running = PrivilegedBridge.isShizukuRunning()
                lastError = if (running) {
                    SecurityException(PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE)
                } else {
                    IllegalStateException(PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE)
                }
                val msg = lastError?.message
                recordPrivResult("blackout", false, msg)
                return@withContext false
            }
            val ok: Boolean
            val privErr: String?
            try {
                val res = PrivilegedBridge.withPrivileged(packageName) { ops ->
                    val r = ops.sleepByKey()
                    val e = if (!r) runCatching { ops.lastError }.getOrNull() else null
                    r to e
                }
                ok = res.first
                privErr = res.second
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                recordPrivResult("blackout", false, t.message ?: t.toString())
                Log.e(TAG, "[PowerController] ${tid()} sleepByKeyRouted bridge failed", t)
                return@withContext false
            }
            if (ok) {
                isBlackedOut = true
                lastError = null
                recordPrivResult("blackout", true, null)
            } else {
                val msg = privErr?.takeIf { it.isNotBlank() }
                    ?: "特权进程按键熄屏失败（返回 false），请查看特权进程 logcat 明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} sleepByKeyRouted exit ok=$ok " +
                "err=${lastError?.message}")
            ok
        }

    /**
     * 按键熄屏·POWER 单键（App 进程入口，[powerByKey] 的路由版，经 UserService 通道）。
     * No-Lock-1：常规熄屏请走 [blackoutRouted]（binder-only 无兜底）；本入口仅单发排障保留。
     *
     * @param packageName 调用方包名。
     * @return 验效通过 true，否则 false（明细进 [lastError]，含特权侧回传）。
     */
    suspend fun powerByKeyRouted(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "[PowerController] ${tid()} powerByKeyRouted enter")
            if (!PrivilegedBridge.isPrivilegedGranted()) {
                val running = PrivilegedBridge.isShizukuRunning()
                lastError = if (running) {
                    SecurityException(PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE)
                } else {
                    IllegalStateException(PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE)
                }
                val msg = lastError?.message
                recordPrivResult("blackout", false, msg)
                return@withContext false
            }
            val ok: Boolean
            val privErr: String?
            try {
                val res = PrivilegedBridge.withPrivileged(packageName) { ops ->
                    val r = ops.powerByKey()
                    val e = if (!r) runCatching { ops.lastError }.getOrNull() else null
                    r to e
                }
                ok = res.first
                privErr = res.second
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                recordPrivResult("blackout", false, t.message ?: t.toString())
                Log.e(TAG, "[PowerController] ${tid()} powerByKeyRouted bridge failed", t)
                return@withContext false
            }
            if (ok) {
                isBlackedOut = true
                lastError = null
                recordPrivResult("blackout", true, null)
            } else {
                val msg = privErr?.takeIf { it.isNotBlank() }
                    ?: "特权进程按键熄屏失败（返回 false），请查看特权进程 logcat 明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("blackout", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} powerByKeyRouted exit ok=$ok " +
                "err=${lastError?.message}")
            ok
        }

    /**
     * 按键点亮（App 进程入口，[wakeByKey] 的路由版，经 UserService 通道）。
     *
     * @param packageName 调用方包名。
     * @return 验效通过 true，否则 false（明细进 [lastError]，含特权侧回传）。
     */
    suspend fun wakeByKeyRouted(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "[PowerController] ${tid()} wakeByKeyRouted enter")
            if (!PrivilegedBridge.isPrivilegedGranted()) {
                val running = PrivilegedBridge.isShizukuRunning()
                lastError = if (running) {
                    SecurityException(PrivilegedBridge.REQUIRE_SHIZUKU_MESSAGE)
                } else {
                    IllegalStateException(PrivilegedBridge.SHIZUKU_NOT_RUNNING_MESSAGE)
                }
                val msg = lastError?.message
                recordPrivResult("restore", false, msg)
                return@withContext false
            }
            val ok: Boolean
            val privErr: String?
            try {
                val res = PrivilegedBridge.withPrivileged(packageName) { ops ->
                    val r = ops.wakeByKey()
                    val e = if (!r) runCatching { ops.lastError }.getOrNull() else null
                    r to e
                }
                ok = res.first
                privErr = res.second
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                recordPrivResult("restore", false, t.message ?: t.toString())
                Log.e(TAG, "[PowerController] ${tid()} wakeByKeyRouted bridge failed", t)
                return@withContext false
            }
            if (ok) {
                isBlackedOut = false
                lastError = null
                recordPrivResult("restore", true, null)
            } else {
                val msg = privErr?.takeIf { it.isNotBlank() }
                    ?: "特权进程按键点亮失败（返回 false），请查看特权进程 logcat 明细"
                lastError = IllegalStateException(msg)
                recordPrivResult("restore", false, msg)
            }
            Log.d(TAG, "[PowerController] ${tid()} wakeByKeyRouted exit ok=$ok " +
                "err=${lastError?.message}")
            ok
        }
}
