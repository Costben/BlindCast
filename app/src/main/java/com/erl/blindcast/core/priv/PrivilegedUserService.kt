package com.erl.blindcast.core.priv

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.blackout.meow.ServiceManager as MeowServiceManager
import com.erl.blindcast.core.blackout.meow.WakeUnlockController as MeowWakeUnlockController
import com.erl.blindcast.core.blackout.meow.WakeUnlockResult as MeowWakeUnlockResult

/**
 * Shizuku UserService 通道服务端（Priv-Bridge-1 通道，Priv-Bridge-2 改道 SurfaceControl，
 * Priv-Bridge-7 加验效轮询 + 按键兜底；Priv-Bridge-9 熄屏加 KEY_POWER 最终兜底）。
 *
 * 运行身份：本类实例由 Shizuku server（或 Sui）在独立 `app_process` 中实例化，
 * 以 root（UID 0）或 shell（UID 2000，adb 启动的 Shizuku）身份运行，因此可直接调用
 * [PowerController.setDisplayPower]（Priv-Bridge-2 起直调改道后的 SurfaceControl 版：
 * SDK 28 走 getBuiltInDisplay，SDK 29+ 含 14/15 统一走 getPhysicalDisplayIds/
 * getPhysicalDisplayToken/setDisplayPowerMode，全部 android.view.SurfaceControl 反射，
 * JNI 在 libandroid_runtime，shell 身份可调；DisplayControl 为 14+ fallback。
 * Priv-Bridge-7 起直调版内含 DisplayManager 验效轮询（STATE_OFF/ON，约 2s）+
 * 按键兜底（熄屏 KEYCODE_SLEEP，点亮 KEYCODE_WAKEUP→KEYCODE_POWER，经 TouchInjector）；
 * Priv-Bridge-9 起熄屏为 binder→SLEEP→POWER 三段（SLEEP 被 OPlus ROM 忽略时终段 POWER
 * 经同通道 TouchInjector 注入，物理按键通路 ROM 拦不住，复验 OFF/DOZE/DOZE_SUSPEND 约 6s）。
 * 普通 App 进程调同样代码必吃 SecurityException，见实证诊断。
 *
 * 范式说明（遵循 Shizuku-API demo）：
 * - 直接继承 AIDL 生成的 [IPrivilegedOps.Stub]（13.x 无 `UserService` 基类，
 *   “UserService 通道”即指此类 + [PrivilegedBridge] 的 bind/unbind 封装）。
 * - 无需在 Manifest 中声明 `<service>`：Shizuku server 按 [PrivilegedBridge.userServiceArgs]
 *   中的 ComponentName 反射实例化本类。
 * - 构造器：保留无参构造（老版本 Shizuku 用）与 `@Keep (Context)` 构造（Shizuku v13
 *   优先尝试，Context 由 `createPackageContextAsUser` 创建，仅部分 API 可用——
 *   Priv-Bridge-7 起该 Context 会经 [PowerController.init] 存为验效用 DisplayManager 来源，
 *   无 Context 时 SurfaceControl 回退 DisplayManagerGlobal 反射，同样可验）。
 * - [destroy] 为 Shizuku server 保留销毁方法（transaction 16777115，aidl 侧编号
 *   16777114）：清理后 [System.exit] 结束特权进程，供解绑时杀进程用。
 *
 * R8 注意：release 启用 minify，proguard-rules.pro 中 keep 本类及两个构造器。
 */
@Keep
class PrivilegedUserService : IPrivilegedOps.Stub {

    /** 老版本 Shizuku / Sui 使用的无参构造。 */
    constructor() : super()

    /**
     * Shizuku v13 优先尝试的带参构造。
     *
     * @param context Shizuku server 侧 `createPackageContextAsUser` 创建的 Context，
     *   与普通 Application Context 语义不同（registerReceiver/getContentResolver 等不可用），
     *   Priv-Bridge-7 起经 [PowerController.init] 存为验效用 DisplayManager 来源
     *   （getSystemService(DisplayManager) 在该 Context 上可用；不可用时回退 Global 反射）。
     */
    @Keep
    constructor(context: Context) : super() {
        runCatching { PowerController.init(context) }
    }

    /**
     * Shizuku server 保留销毁方法：直接退出进程。
     * 由 [PrivilegedBridge] 在 `unbindUserService(..., remove = true)` 后经 binder 触发，
     * 或 Shizuku 版本更替时由 server 主动调用。
     */
    override fun destroy() {
        System.exit(0)
    }

    /**
     * 设置主显示屏电源（跑在特权进程内，直调底层）。
     *
     * Root-Cut-1 顺序：先 [PowerController.tryBinderDisplayPower] binder 物理断电/点亮直试
     * （混合路由+日志不动，仅 binder→STATE 严格验效约 2s：熄屏 STATE_OFF，点亮 STATE_ON），
     * 成了直接返回 ok（无锁屏、无 AOD 真黑）；binder 验效失败才进 meow 锁屏链兜底
     * （熄屏 lockAndSleep→ensureScreenOff，点亮 ensureScreenOn，既有不动），逻辑原样来自
     * MAA-Meow WakeUnlockController（BINDER→KEY_SLEEP→KEY_POWER + 验效，
     * 经 PowerManager binder + TouchInjector 按键；熄屏先 lockNow 上锁再 goToSleep，
     * 未 OK 则 fallback ensureScreenOff 保证物理熄屏）。
     * 返回值即验效后最终结果；失败明细经 [PowerController.recordPrivResult] 记入
     * lastPrivError（区分 binder-root 段/meow 段），App 侧经 [getLastError] 同绑定内取回
     * （用完即焚，跨绑定取不到），isBlackedOut/lastError/日志/Home状态行/Toast/路由契约全部不变
     * （App 侧 routed 入口据返回值 + 明细自行翻转）。
     *
     * @param on true = 点亮（binder NORMAL+验效→meow ensureScreenOn），false = 物理熄屏（binder OFF+验效→meow lockAndSleep→ensureScreenOff）。
     * @return 验效通过 true；失败返回 false（特权进程内失败多为 ROM 静默忽略，
     *   明细见 [getLastError]，调用方以返回值 + [getLastError] 为准）。
     */
    override fun setDisplayPower(on: Boolean): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        val pid = try { Process.myPid() } catch (_: Throwable) { -1 }
        val uid = try { Process.myUid() } catch (_: Throwable) { -1 }
        Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower enter on=$on pid=$pid uid=$uid (binder-first+meow)")
        return try {
            val ok = meowSetDisplayPower(on)
            Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower exit on=$on ok=$ok " +
                "err=${PowerController.lastError?.toString() ?: PowerController.lastPrivError}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid setDisplayPower on=$on failed", t)
            throw t
        }
    }

    /**
     * Root-Cut-1 特权熄屏/点亮（Shizuku UserService 进程内直调，同步阻塞）。
     * 先 binder 物理直试（[PowerController.tryBinderDisplayPower]，严格验效约 2s），
     * 成了直接返回 ok（无锁屏真黑）；失败才进 meow 锁屏链兜底（既有不动）：
     * 熄屏先 lockAndSleep，未 OK（含 NO_KEYGUARD 无锁屏早返）则 fallback ensureScreenOff；
     * 点亮：wakeScreen（即 ensureScreenOn）。成功/失败均记 recordPrivResult
     * （失败文案区分 binder-root 段/meow 段，供 getLastError 回读）。
     */
    private fun meowSetDisplayPower(on: Boolean): Boolean {
        val op = if (on) "restore" else "blackout"
        // 先 binder 物理直试（root 真身下 OPlus 很可能放行，成了就不用锁屏链）。
        val binderDesc: String? = try {
            val r = PowerController.tryBinderDisplayPower(on)
            if (r.verified) {
                Log.d(TAG, "[PrivilegedUserService] binder-first hit on=$on route=${r.route} read=${r.read}")
                PowerController.recordPrivResult(op, true, null)
                return true
            }
            val d = PowerController.binderFirstSegmentDesc(on, r)
            Log.d(TAG, "[PrivilegedUserService] binder-first miss on=$on $d, fallback meow")
            d
        } catch (t: Throwable) {
            val d = "binder-root段异常：${t.message ?: t}"
            Log.e(TAG, "[PrivilegedUserService] binder-first threw on=$on $d", t)
            d
        }
        return try {
            if (on) {
                val ok = try {
                    MeowWakeUnlockController.wakeScreen()
                } catch (t: Throwable) {
                    Log.e(TAG, "[PrivilegedUserService] meow ensureScreenOn threw", t)
                    PowerController.recordPrivResult(op, false, "点亮失败：${binderDesc}→meow段点亮异常：${t.message ?: t}")
                    return false
                }
                if (ok) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val msg = "点亮失败：${binderDesc}→meow段ensureScreenOn验效未通过（BINDER→WAKEUP→POWER三段均miss，见特权进程logcat [MeowWakeUnlock]明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                ok
            } else {
                val lockCode = try {
                    MeowWakeUnlockController.lockAndSleep()
                } catch (t: Throwable) {
                    Log.e(TAG, "[PrivilegedUserService] meow lockAndSleep threw", t)
                    MeowWakeUnlockResult.UNSUPPORTED
                }
                if (lockCode == MeowWakeUnlockResult.OK) {
                    PowerController.recordPrivResult(op, true, null)
                    return true
                }
                val offOk = try {
                    val pm = MeowServiceManager.getPowerManager()
                    MeowWakeUnlockController.ensureScreenOff(pm)
                } catch (t: Throwable) {
                    Log.e(TAG, "[PrivilegedUserService] meow ensureScreenOff threw", t)
                    false
                }
                if (offOk) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val msg = "熄屏失败：${binderDesc}→meow段lockAndSleep=$lockCode→ensureScreenOff验效未通过（BINDER→SLEEP→POWER三段均miss，见特权进程logcat明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                offOk
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] meowSetDisplayPower threw", t)
            PowerController.recordPrivResult(op, false, t.message ?: t.toString())
            false
        }
    }

    /**
     * 按键兜底直调：熄屏·SLEEP 单键（跑在特权进程内）。
     * 经 [PowerController.sleepByKey] 注入 KEYCODE_SLEEP（Down+Up，SOURCE_KEYBOARD）
     * 后验 STATE_OFF/DOZE/DOZE_SUSPEND（约 6s）。供 App 侧单发按键排障用，保持 SLEEP
     * 单键语义不动；常规熄屏请走 [setDisplayPower] 全链路（binder→SLEEP→POWER 三段），
     * POWER 单键请走 [powerByKey]。
     *
     * @return 验效通过 true，否则 false（明细见 [getLastError]）。
     */
    override fun sleepByKey(): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        Log.d(TAG, "[PrivilegedUserService] $tid sleepByKey enter")
        return try {
            val ok = PowerController.sleepByKey()
            Log.d(TAG, "[PrivilegedUserService] $tid sleepByKey exit ok=$ok " +
                "err=${PowerController.lastError?.toString()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid sleepByKey failed", t)
            throw t
        }
    }

    /**
     * 按键兜底直调：熄屏·POWER 单键（跑在特权进程内）。
     * Priv-Bridge-9 最终兜底单键版：经 [PowerController.powerByKey] 注入 KEYCODE_POWER
     * （Down+Up，SOURCE_KEYBOARD，与 SLEEP 同通道 TouchInjector，物理按键通路 ROM 拦不住）
     * 后验 STATE_OFF/DOZE/DOZE_SUSPEND（约 6s）。供 App 侧单发排障用
     * （SLEEP 被 OPlus ROM 忽略时验证 POWER 通道）；常规熄屏请走 [setDisplayPower] 全链路。
     *
     * @return 验效通过 true，否则 false（明细见 [getLastError]）。
     */
    override fun powerByKey(): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        Log.d(TAG, "[PrivilegedUserService] $tid powerByKey enter")
        return try {
            val ok = PowerController.powerByKey()
            Log.d(TAG, "[PrivilegedUserService] $tid powerByKey exit ok=$ok " +
                "err=${PowerController.lastError?.toString()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid powerByKey failed", t)
            throw t
        }
    }

    /**
     * 按键兜底直调：点亮（跑在特权进程内）。
     * 经 [PowerController.wakeByKey] 依次试 KEYCODE_WAKEUP、无则 KEYCODE_POWER 后验 STATE_ON。
     * Priv-Bridge-9 点亮侧不动。
     *
     * @return 验效通过 true，否则 false（明细见 [getLastError]）。
     */
    override fun wakeByKey(): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        Log.d(TAG, "[PrivilegedUserService] $tid wakeByKey enter")
        return try {
            val ok = PowerController.wakeByKey()
            Log.d(TAG, "[PrivilegedUserService] $tid wakeByKey exit ok=$ok " +
                "err=${PowerController.lastError?.toString()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid wakeByKey failed", t)
            throw t
        }
    }

    /**
     * 取特权进程侧最近一次失败明细（[PowerController.lastError.message]，成功时 null）。
     * 必须与 [setDisplayPower]/[sleepByKey]/[powerByKey]/[wakeByKey] 同一次绑定内调用（用完即焚）。
     *
     * @return 失败文案（含走到哪一步：熄屏 binder→SLEEP→POWER 三段各记，点亮 binder→WAKEUP→POWER），
     * 成功/无记录时 null。
     */
    override fun getLastError(): String? {
        return try {
            PowerController.lastError?.message ?: PowerController.lastPrivError
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** 全链路统一 TAG（与 SurfaceControl / PowerController 一致，特权进程 logcat 可见）。 */
        private const val TAG = "BlindCast"
    }
}
