package com.erl.blindcast.core.priv

import android.content.Context
import android.graphics.Point
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController
import com.erl.blindcast.core.scrcpy.PrivilegedCapture
import com.erl.blindcast.core.scrcpy.TouchInjector
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Shizuku UserService 通道服务端（Priv-Bridge-1 通道，Priv-Bridge-2 改道 SurfaceControl，
 * No-Lock-1 熄屏永久下线锁屏链：只许 binder 物理断电 + STATE_OFF 严格验效）。
 *
 * 运行身份：本类实例由 Shizuku server（或 Sui）在独立 `app_process` 中实例化，
 * 以 root（UID 0）或 shell（UID 2000，adb 启动的 Shizuku）身份运行，因此可直接调用
 * [PowerController.setDisplayPower]（Priv-Bridge-2 起直调改道后的 SurfaceControl 版：
 * SDK 28 走 getBuiltInDisplay，SDK 29+ 含 14/15 统一走 getPhysicalDisplayIds/
 * getPhysicalDisplayToken/setDisplayPowerMode，全部 android.view.SurfaceControl 反射，
 * JNI 在 libandroid_runtime，shell 身份可调；DisplayControl 为 14+ fallback。
 * No-Lock-1 起直调版内含 DisplayManager 验效轮询（熄屏 STATE_OFF 严格约 2s，
 * 点亮 STATE_ON 约 2s）；熄屏无按键/锁屏兜底，点亮侧保留
 * KEYCODE_WAKEUP→KEYCODE_POWER（经 TouchInjector，只点亮不制造新锁）。
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
     * No-Lock-1 顺序：先 [PowerController.tryBinderDisplayPower] binder 物理断电/点亮直试
     * （混合路由+日志不动，仅 binder→STATE 严格验效约 2s：熄屏 STATE_OFF，点亮 STATE_ON），
     * 成了直接返回 ok（无锁屏、无 AOD 真黑）；熄屏 binder 验效失败直接返 false，
     * 永不调锁屏链、不注入熄屏键；点亮 binder 验效失败则试 [PowerController.wakeByKey]
     * （WAKEUP→POWER，只点亮不制造新锁）。
     * 返回值即验效后最终结果；失败明细经 [PowerController.recordPrivResult] 记入
     * lastPrivError，App 侧经 [getLastError] 同绑定内取回
     * （用完即焚，跨绑定取不到），isBlackedOut/lastError/日志/Home状态行/Toast/路由契约全部不变
     * （App 侧 routed 入口据返回值 + 明细自行翻转）。
     *
     * @param on true = 点亮（binder NORMAL+验效→wakeByKey），false = 物理熄屏（binder OFF+验效，无兜底）。
     * @return 验效通过 true；失败返回 false（特权进程内失败多为 ROM 静默忽略，
     *   明细见 [getLastError]，调用方以返回值 + [getLastError] 为准）。
     */
    override fun setDisplayPower(on: Boolean): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        val pid = try { Process.myPid() } catch (_: Throwable) { -1 }
        val uid = try { Process.myUid() } catch (_: Throwable) { -1 }
        Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower enter on=$on pid=$pid uid=$uid (binder-first no-lock)")
        return try {
            val ok = setDisplayPowerNoLock(on)
            Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower exit on=$on ok=$ok " +
                "err=${PowerController.lastError?.toString() ?: PowerController.lastPrivError}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid setDisplayPower on=$on failed", t)
            throw t
        }
    }

    /**
     * No-Lock-1 特权熄屏/点亮（Shizuku UserService 进程内直调，同步阻塞）。
     * 先 binder 物理直试（[PowerController.tryBinderDisplayPower]，严格验效约 2s），
     * 成了直接返回 ok（无锁屏真黑）；熄屏失败直接返 false，不进锁屏链；
     * 点亮失败试 [PowerController.wakeByKey]（只点亮不制造新锁）。成功/失败均记 recordPrivResult
     * （熄屏失败文案含“物理断电未生效（本机忽略），未执行锁屏兜底”，供 getLastError 回读）。
     */
    private fun setDisplayPowerNoLock(on: Boolean): Boolean {
        val op = if (on) "restore" else "blackout"
        // 先 binder 物理直试（特权身份下成了即无锁屏真黑）。
        var binderDesc: String? = null
        var binderRead: String? = null
        try {
            val r = PowerController.tryBinderDisplayPower(on)
            if (r.verified) {
                Log.d(TAG, "[PrivilegedUserService] binder-first hit on=$on route=${r.route} read=${r.read}")
                PowerController.recordPrivResult(op, true, null)
                return true
            }
            binderDesc = PowerController.binderFirstSegmentDesc(on, r)
            binderRead = r.read
            Log.d(TAG, "[PrivilegedUserService] binder-first miss on=$on $binderDesc (No-Lock-1 no lock fallback for off)")
        } catch (t: Throwable) {
            binderDesc = "binder-root段异常：${t.message ?: t}"
            Log.e(TAG, "[PrivilegedUserService] binder-first threw on=$on $binderDesc", t)
        }
        return try {
            if (on) {
                val ok = try {
                    PowerController.wakeByKey()
                } catch (t: Throwable) {
                    Log.e(TAG, "[PrivilegedUserService] wakeByKey threw", t)
                    PowerController.recordPrivResult(op, false, "点亮失败：${binderDesc}→按键唤醒段异常：${t.message ?: t}")
                    return false
                }
                if (ok) {
                    PowerController.recordPrivResult(op, true, null)
                } else {
                    val wakeErr = runCatching { PowerController.lastError?.message }
                        .getOrNull()?.takeIf { !it.isNullOrBlank() }
                        ?: runCatching { PowerController.lastPrivError }.getOrNull()
                    val msg = "点亮失败：${binderDesc}→${wakeErr ?: "按键唤醒WAKEUP→POWER复验仍未STATE_ON"}（见特权进程logcat [PowerController]明细）"
                    PowerController.recordPrivResult(op, false, msg)
                }
                ok
            } else {
                val msg = "熄屏失败：${binderDesc}；物理断电未生效（本机忽略），未执行锁屏兜底" +
                    (if (!binderRead.isNullOrBlank()) "（当前$binderRead" +
                        "，见特权进程logcat [PowerController][BinderFirst]明细）" else "")
                PowerController.recordPrivResult(op, false, msg)
                Log.d(TAG, "[PrivilegedUserService] blackout binder-only miss, no fallback err=$msg")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] setDisplayPowerNoLock threw", t)
            PowerController.recordPrivResult(op, false, t.message ?: t.toString())
            false
        }
    }

    /**
     * 按键直调：熄屏·SLEEP 单键（跑在特权进程内，仅单发排障保留）。
     * 经 [PowerController.sleepByKey] 注入 KEYCODE_SLEEP（Down+Up，SOURCE_KEYBOARD）
     * 后验 STATE_OFF/DOZE/DOZE_SUSPEND（约 6s）。No-Lock-1 熄屏主链路不再调用；
     * 常规熄屏请走 [setDisplayPower]（binder-only 无兜底）。
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
     * 按键直调：熄屏·POWER 单键（跑在特权进程内，仅单发排障保留）。
     * 经 [PowerController.powerByKey] 注入 KEYCODE_POWER 后验
     * STATE_OFF/DOZE/DOZE_SUSPEND（约 6s）。No-Lock-1 熄屏主链路不再调用；
     * 常规熄屏请走 [setDisplayPower]（binder-only 无兜底）。
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
     * 按键直调：点亮（跑在特权进程内，主链路点亮兜底仍在用）。
     * 经 [PowerController.wakeByKey] 依次试 KEYCODE_WAKEUP、无则 KEYCODE_POWER 后验 STATE_ON
     * （只点亮不制造新锁）。
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
     * @return 失败文案（熄屏为 binder 段 +“物理断电未生效（本机忽略），未执行锁屏兜底”，
     * 点亮为 binder→WAKEUP→POWER 各记），成功/无记录时 null。
     */
    override fun getLastError(): String? {
        return try {
            PowerController.lastError?.message ?: PowerController.lastPrivError
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 特权采集启动（Stream-Priv-1 · 跑在 Shizuku UserService 常驻进程内）。
     * 直调 [PrivilegedCapture.start]（SurfaceControl.createDisplay scrcpy 路线 +
     * socket 回传 App 侧 CaptureSocketLink 服）。调用方须 daemon(true) 常驻绑定，
     * 流期间不 destroy；stop 时先 [stopCapture] 再 destroy 宿主。
     */
    override fun startCapture(width: Int, height: Int, bitrate: Int, fps: Int): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        val pid = try { Process.myPid() } catch (_: Throwable) { -1 }
        val uid = try { Process.myUid() } catch (_: Throwable) { -1 }
        Log.i(TAG, "[PrivilegedUserService] $tid startCapture enter ${width}x${height} ${bitrate}bps ${fps}fps pid=$pid uid=$uid")
        return try {
            val ok = PrivilegedCapture.start(width, height, bitrate, fps)
            Log.i(TAG, "[PrivilegedUserService] $tid startCapture exit ok=$ok " +
                "video=${PrivilegedCapture.videoRunning} audio=${PrivilegedCapture.audioRunning} " +
                "err=${PrivilegedCapture.errorMessage()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid startCapture threw", t)
            throw t
        }
    }

    /** 停特权采集并释放 display/codec（幂等，长流 stop 时调）。 */
    override fun stopCapture() {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        Log.i(TAG, "[PrivilegedUserService] $tid stopCapture enter")
        runCatching { PrivilegedCapture.stop() }
        Log.i(TAG, "[PrivilegedUserService] $tid stopCapture exit")
    }

    /** 取特权采集最近失败明细（须同一次常驻绑定内调用）。 */
    override fun getCaptureError(): String? {
        return try {
            PrivilegedCapture.errorMessage()
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 反控注入（特权进程内原子执行；调用方 ControlWsRoute 经 PrivilegedBridge 按次绑定）
    // ------------------------------------------------------------------

    @Volatile
    private var inputError: String? = null

    /** 取最近一次注入失败明细（同绑定内调用；成功时 null）。 */
    override fun getInputError(): String? = inputError

    /** 轻点：Down+Up 原子（相对真实主屏归一化坐标）。 */
    override fun injectTap(normX: Float, normY: Float): Boolean {
        val (x, y) = resolvePx(normX, normY) ?: return false
        return try {
            val ok = TouchInjector.injectTouchDownPx(x, y) && TouchInjector.injectTouchUpPx(x, y)
            if (!ok) inputError = TouchInjector.lastError?.message ?: "tap rejected by system"
            else inputError = null
            ok
        } catch (t: Throwable) {
            inputError = t.message ?: t.toString()
            Log.e(TAG, "[PrivilegedUserService] injectTap failed", t)
            false
        }
    }

    /** 拖拽：Down+N插值Move+Up 单次调用内完成（松手执行；step 间 8ms）。 */
    override fun injectDrag(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val (px0, py0) = resolvePx(x0, y0) ?: return false
        val (px1, py1) = resolvePx(x1, y1) ?: return false
        return try {
            var ok = TouchInjector.injectTouchDownPx(px0, py0)
            val steps = 10
            var i = 1
            while (ok && i <= steps) {
                val f = i.toFloat() / (steps + 1)
                ok = TouchInjector.injectTouchMovePx(px0 + (px1 - px0) * f, py0 + (py1 - py0) * f)
                if (ok) runCatching { Thread.sleep(8L) }
                i++
            }
            ok = TouchInjector.injectTouchUpPx(px1, py1) && ok
            if (!ok) inputError = TouchInjector.lastError?.message ?: "drag rejected by system"
            else inputError = null
            ok
        } catch (t: Throwable) {
            inputError = t.message ?: t.toString()
            Log.e(TAG, "[PrivilegedUserService] injectDrag failed", t)
            false
        }
    }

    /** 完整按键 Down+Up（无状态）。 */
    override fun injectKey(keyCode: Int): Boolean {
        return try {
            val ok = TouchInjector.injectKey(keyCode)
            if (!ok) inputError = TouchInjector.lastError?.message ?: "key rejected by system"
            else inputError = null
            ok
        } catch (t: Throwable) {
            inputError = t.message ?: t.toString()
            Log.e(TAG, "[PrivilegedUserService] injectKey failed", t)
            false
        }
    }

    /** 文本经虚拟键盘映射注入（无状态；CJK 等不可映射字符按既有语义跳过记错）。 */
    override fun injectText(text: String?): Boolean {
        return try {
            val ok = TouchInjector.injectText(text ?: "")
            if (!ok) inputError = TouchInjector.lastError?.message ?: "text rejected by system"
            else inputError = null
            ok
        } catch (t: Throwable) {
            inputError = t.message ?: t.toString()
            Log.e(TAG, "[PrivilegedUserService] injectText failed", t)
            false
        }
    }

    /**
     * 归一化坐标→真实主屏物理像素（特权进程内经 IWindowManager 反射解析，
     * 无需 Context；失败记 inputError 返 null）。
     */
    private fun resolvePx(normX: Float, normY: Float): Pair<Float, Float>? {
        val size = realDisplaySize()
        if (size == null) {
            if (inputError == null) inputError = "resolve display size failed (IWindowManager)"
            return null
        }
        if (!TouchInjector.configure(size.first, size.second)) {
            inputError = TouchInjector.lastError?.message ?: "configure display size failed"
            return null
        }
        val x = normX.coerceIn(0f, 1f) * size.first
        val y = normY.coerceIn(0f, 1f) * size.second
        return x to y
    }

    /** 经 `IWindowManager.getInitialDisplaySize(0)` 取真实主屏尺寸（特权身份可调）。 */
    private fun realDisplaySize(): Pair<Int, Int>? {
        return try {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/os/ServiceManager",
                    "Landroid/view/IWindowManager",
                    "Landroid/view/IWindowManager\$Stub",
                )
            }
            val sm = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder ?: return null
            val stub = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, sm) ?: return null
            val pt = Point()
            stub.javaClass.getMethod(
                "getInitialDisplaySize",
                Int::class.javaPrimitiveType,
                Point::class.java,
            ).invoke(stub, 0, pt)
            if (pt.x > 0 && pt.y > 0) pt.x to pt.y else null
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] realDisplaySize failed", t)
            null
        }
    }

    companion object {
        /** 全链路统一 TAG（与 SurfaceControl / PowerController 一致，特权进程 logcat 可见）。 */
        private const val TAG = "BlindCast"
    }
}
