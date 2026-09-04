package com.erl.blindcast.core.scrcpy

import android.content.Context
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.ref.WeakReference

/**
 * 反向触控 / 按键统一注入入口（Slice 3.3 · MVP.md 二(4) + 四(二)(3)）。
 *
 * ## 链路
 * 网页端归一化坐标 / 按键码（Slice 4.1 `/ws/control` 透传 `{type: down/move/up/key}`）
 * -> 本对象归一化→物理像素换算 + 手势序列状态机（Down 采样 downTime，Move / Up 复用）
 * -> [InputControlUtils] 构造 `MotionEvent` / `KeyEvent`
 * -> [InputManagerWrapper] 反射 `injectInputEvent` 塞进系统输入队列
 * -> 前台游戏 / 脚本准确响应（物理熄屏状态下照常驱动，PLAN.md Slice 3.3 验收目标）。
 *
 * ## 鼠标按钮映射预留（约定常量，实际落子在 Slice 4.1 `ControlWsRoute`）
 * - 鼠标右键 -> [MOUSE_BUTTON_RIGHT_KEYCODE]（= `KEYCODE_BACK`，Android 返回键）；
 * - 鼠标中键 -> [MOUSE_BUTTON_MIDDLE_KEYCODE]（= `KEYCODE_HOME`，回到桌面）。
 * 本 Slice 只提供 [injectKey] 按键注入能力，不解析任何网络协议、不触碰 UI。
 *
 * ## 提权调用点预留（Shizuku / Root · 必读）
 * 注入需要签名级 `INJECT_EVENTS` 权限，所有 `inject*` 方法**必须在提权进程内执行**：
 * Shizuku UserService（system_server 上下文）或以 Root 身份启动的 `app_process` 进程。
 * 普通 App 进程调用将失败并返回 false（系统拒绝记 [lastError]），永不崩溃。
 * 后续 Slice 在此接入 Shizuku binder 透传 / Root 守护进程转调时，本对象 API 保持不变。
 *
 * ## 显示尺寸来源（优先级从高到低）
 * 1. [configure] 显式配置（Slice 4.1 按视频流 / 物理分辨率传入，推荐）；
 * 2. [init] 缓存的应用上下文 `displayMetrics`（[WeakReference]，不泄漏）；
 * 3. 均无时注入直接返回 false（原因记 [lastError]）。
 *
 * ## 手势状态机
 * - Down：若已有未结束手势（网页端丢包导致悬 finger），先在旧坐标自动补发 Up 收尾，
 *   再开始新手势（杜绝屏幕上残留“按住不放”的卡死触点）；
 * - Move / Up：无活跃手势时直接返回 false（调用方必须保证 Down→Move*→Up 顺序，
 *   Slice 4.1 `ControlWsRoute` 负责 enforcing）；
 * - [cancelTouch]：在最后已知坐标补发 Up（连接断开 / 服务销毁时解卡，Slice 6.3 可调）。
 *
 * ## 线程模型
 * - 所有 `inject*` 均为同步阻塞调用（含一次 Binder 往返 + `synchronized` 保序），
 *   禁止在主线程调用；同一时刻多线程调用按进入顺序串行注入（Down→Move→Up 不乱序）；
 * - 所有异常记 [lastError] 不抛崩；最近一次成功后 [lastError] 清零；
 *   成功 / 失败计数见 [successCount] / [failureCount]，供 6.3 诊断消费。
 *
 * ## 范围声明
 * - 仅做注入能力封装：不接网络（Slice 4.1）、不接 UI、不启动保活
 *   （保活归 `UserActivityKeeper`，熔断归 `EmergencyRecovery`）。
 */
object TouchInjector {

    // ------------------------------------------------------------------
    // Slice 4.1 映射预留：鼠标按钮 -> Android 按键（本 Slice 仅常量 + 注释）
    // ------------------------------------------------------------------

    /**
     * 鼠标右键映射目标：Android 返回键。
     * 实际协议解析与分发在 Slice 4.1 `ControlWsRoute` 落子：
     * 收到 `{type: "key", keycode: 4}`（MVP.md 四(二)(3)）即调 [injectKey]。
     */
    const val MOUSE_BUTTON_RIGHT_KEYCODE: Int = KeyEvent.KEYCODE_BACK

    /**
     * 鼠标中键映射目标：Android Home 键。
     * 实际协议解析与分发在 Slice 4.1 `ControlWsRoute` 落子。
     */
    const val MOUSE_BUTTON_MIDDLE_KEYCODE: Int = KeyEvent.KEYCODE_HOME

    /** 默认目标显示屏：主屏（透传给 [InputControlUtils]）。 */
    const val DEFAULT_DISPLAY_ID: Int = InputControlUtils.DEFAULT_DISPLAY_ID

    /** 未配置显示尺寸哨兵（[displayWidth] / [displayHeight] 初始值）。 */
    const val UNSET_DISPLAY_SIZE: Int = -1

    /** 是否有活跃触控手势（volatile，跨线程可见；状态翻转只发生在注入成功时）。 */
    @Volatile
    var isTouching: Boolean = false
        private set

    /**
     * 最近一次失败的异常；最近一次成功注入后清零，供 Slice 6.3 诊断消费。
     * 失败永不崩溃。
     */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 当前显示宽（物理像素；-1 = 未配置，见 [configure] / [init]）。 */
    @Volatile
    var displayWidth: Int = UNSET_DISPLAY_SIZE
        private set

    /** 当前显示高（物理像素；-1 = 未配置）。 */
    @Volatile
    var displayHeight: Int = UNSET_DISPLAY_SIZE
        private set

    /** 累计注入成功次数（触控 + 按键 + 文本字符事件总数）。 */
    @Volatile
    var successCount: Long = 0L
        private set

    /** 累计注入失败次数。 */
    @Volatile
    var failureCount: Long = 0L
        private set

    private val lock = Any()
    private var appContextRef: WeakReference<Context>? = null

    // ---- 活跃手势状态（仅 lock 内读写） ----
    private var gestureDownTime: Long = 0L
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    /** 本设备 SDK 是否满足注入前置（minSdk 31，恒为 true，保留供 ROM 黑名单扩展）。 */
    val isSupported: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    /**
     * 预存应用上下文，供显示尺寸回退使用（内部只持 [WeakReference]，不泄漏）。
     * 调过含 Context 的方法或 [configure] 后可省略此调用。
     */
    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext ?: context)
    }

    /**
     * 显式配置目标显示尺寸（物理像素，Slice 4.1 按实际分辨率传入时调）。
     *
     * @param width 显示宽（1..7680）
     * @param height 显示高（1..7680）
     * @return 参数合法 true；非法返回 false 且原因记 [lastError]（旧配置保持不变）。
     */
    fun configure(width: Int, height: Int): Boolean {
        if (width !in 1..7680 || height !in 1..7680) {
            recordFailure(IllegalArgumentException("TouchInjector: invalid display size ${width}x${height}"))
            return false
        }
        displayWidth = width
        displayHeight = height
        return true
    }

    // ------------------------------------------------------------------
    // 触控注入（归一化坐标入口：网页端 Canvas 比例直传）
    // ------------------------------------------------------------------

    /**
     * 注入触控按下（同步阻塞，禁止主线程直调）。
     *
     * @param normX 归一化 X（0..1，越界自动钳制，兼容 Web 浮点舍入）。
     * @param normY 归一化 Y（同上）。
     * @return 注入成功 true；无尺寸 / 无提权 / 反射失败返回 false（原因记 [lastError]）。
     */
    @WorkerThread
    fun injectTouchDown(normX: Float, normY: Float): Boolean {
        synchronized(lock) {
            val (x, y) = toPixelsOrRecord(normX, normY) ?: return false
            // 悬 finger 自愈：旧手势未收尾时先补 Up，防止屏幕残留卡死触点。
            if (isTouching) {
                injectLocked(InputControlUtils.obtainTouchUp(gestureDownTime, now(), lastX, lastY))
            }
            val downTime = now()
            val ok = injectLocked(InputControlUtils.obtainTouchDown(downTime, downTime, x, y))
            if (ok) {
                gestureDownTime = downTime
                lastX = x
                lastY = y
                isTouching = true
            }
            return ok
        }
    }

    /**
     * 注入触控移动（同步阻塞，禁止主线程直调；必须有活跃 Down 手势）。
     *
     * @param normX 归一化 X（0..1，越界自动钳制）。
     * @param normY 归一化 Y（同上）。
     * @return 同 [injectTouchDown]；无活跃手势时返回 false。
     */
    @WorkerThread
    fun injectTouchMove(normX: Float, normY: Float): Boolean {
        synchronized(lock) {
            if (!isTouching) {
                recordFailure(IllegalStateException("TouchInjector: move without active down"))
                return false
            }
            val (x, y) = toPixelsOrRecord(normX, normY) ?: return false
            val ok = injectLocked(InputControlUtils.obtainTouchMove(gestureDownTime, now(), x, y))
            if (ok) {
                lastX = x
                lastY = y
            } else {
                // Move 失败即视为手势断裂：状态复位，调用方需重新 Down。
                isTouching = false
            }
            return ok
        }
    }

    /**
     * 注入触控抬起（同步阻塞，禁止主线程直调；必须有活跃 Down 手势）。
     *
     * @param normX 归一化 X（0..1，越界自动钳制）。
     * @param normY 归一化 Y（同上）。
     * @return 同 [injectTouchDown]；成功后手势状态复位。
     */
    @WorkerThread
    fun injectTouchUp(normX: Float, normY: Float): Boolean {
        synchronized(lock) {
            if (!isTouching) {
                recordFailure(IllegalStateException("TouchInjector: up without active down"))
                return false
            }
            val (x, y) = toPixelsOrRecord(normX, normY) ?: return false
            val ok = injectLocked(InputControlUtils.obtainTouchUp(gestureDownTime, now(), x, y))
            isTouching = false
            return ok
        }
    }

    // ------------------------------------------------------------------
    // 触控注入（物理像素入口：调用方已自行换算时用）
    // ------------------------------------------------------------------

    /** [injectTouchDown] 的物理像素版本（跳过归一化换算，其余语义一致）。 */
    @WorkerThread
    fun injectTouchDownPx(x: Float, y: Float): Boolean {
        synchronized(lock) {
            if (isTouching) {
                injectLocked(InputControlUtils.obtainTouchUp(gestureDownTime, now(), lastX, lastY))
            }
            val downTime = now()
            val ok = injectLocked(InputControlUtils.obtainTouchDown(downTime, downTime, x, y))
            if (ok) {
                gestureDownTime = downTime
                lastX = x
                lastY = y
                isTouching = true
            }
            return ok
        }
    }

    /** [injectTouchMove] 的物理像素版本。 */
    @WorkerThread
    fun injectTouchMovePx(x: Float, y: Float): Boolean {
        synchronized(lock) {
            if (!isTouching) {
                recordFailure(IllegalStateException("TouchInjector: move without active down"))
                return false
            }
            val ok = injectLocked(InputControlUtils.obtainTouchMove(gestureDownTime, now(), x, y))
            if (ok) {
                lastX = x
                lastY = y
            } else {
                isTouching = false
            }
            return ok
        }
    }

    /** [injectTouchUp] 的物理像素版本。 */
    @WorkerThread
    fun injectTouchUpPx(x: Float, y: Float): Boolean {
        synchronized(lock) {
            if (!isTouching) {
                recordFailure(IllegalStateException("TouchInjector: up without active down"))
                return false
            }
            val ok = injectLocked(InputControlUtils.obtainTouchUp(gestureDownTime, now(), x, y))
            isTouching = false
            return ok
        }
    }

    /**
     * 取消当前手势（在最后已知坐标补发 Up；无活跃手势时直接返回 true）。
     * 供连接断开 / 服务销毁时解卡调用（Slice 4.1 / 6.3 可调）。
     *
     * @return 同 [injectTouchDown]。
     */
    @WorkerThread
    fun cancelTouch(): Boolean {
        synchronized(lock) {
            if (!isTouching) return true
            val ok = injectLocked(InputControlUtils.obtainTouchCancel(gestureDownTime, now(), lastX, lastY))
            isTouching = false
            return ok
        }
    }

    // ------------------------------------------------------------------
    // 按键 / 文本注入
    // ------------------------------------------------------------------

    /**
     * 注入单次按键动作（Down 或 Up 半程，供长按等扩展场景用）。
     *
     * @param action [KeyEvent.ACTION_DOWN] / [KeyEvent.ACTION_UP] 之一。
     * @param keyCode Android 按键码（如右键预留的 [MOUSE_BUTTON_RIGHT_KEYCODE]）。
     * @return 注入成功 true，否则 false（原因记 [lastError]）。
     */
    @WorkerThread
    fun injectKeyAction(action: Int, keyCode: Int): Boolean {
        synchronized(lock) {
            val event = try {
                InputControlUtils.obtainKeyEvent(action, keyCode)
            } catch (t: Throwable) {
                recordFailure(t)
                return false
            }
            return injectLocked(event)
        }
    }

    /**
     * 注入一次完整按键（Down + Up 事件对；两半程任一失败即整体 false）。
     *
     * @param keyCode Android 按键码。
     * @return 两半程均成功 true，否则 false。
     */
    @WorkerThread
    fun injectKey(keyCode: Int): Boolean {
        synchronized(lock) {
            val pair = try {
                InputControlUtils.obtainKeyPress(keyCode)
            } catch (t: Throwable) {
                recordFailure(t)
                return false
            }
            var ok = true
            for (event in pair) {
                ok = injectLocked(event) && ok
            }
            return ok
        }
    }

    /**
     * 注入文本（键盘打字映射回退：经虚拟键盘字符映射逐字拆为按键事件注入）。
     *
     * - 可映射字符（含大小写 / 数字 / 常用符号，含自动 Shift 组合）逐事件注入；
     * - 映射表不支持的字符（如无输入法时的 CJK）按个跳过并计数；
     * - 空串视为无操作成功（返回 true）。
     *
     * @param text 待注入文本（非 null）。
     * @return 全部字符可映射且注入成功 true；有跳过或任一注入失败 false
     *  （跳过数字可查 log，原因记 [lastError]）。
     */
    @WorkerThread
    fun injectText(text: String): Boolean {
        synchronized(lock) {
            if (text.isEmpty()) return true
            ensureHiddenApiExempted()
            val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            // 整串优先（大小写 Shift 组合由映射表一次产出，事件顺序天然正确）。
            val whole = runCatching { keyMap.getEvents(text.toCharArray()) }.getOrNull()
            if (whole != null) {
                return injectKeyEvents(whole, text.length, text.length)
            }
            // 整串不可映射（含 CJK 混排）：逐字尽力注入，可映射的先生效。
            var mapped = 0
            var allOk = true
            for (ch in text) {
                val perChar = runCatching { keyMap.getEvents(charArrayOf(ch)) }.getOrNull()
                if (perChar == null) continue
                mapped++
                allOk = injectKeyEvents(perChar, 1, 1) && allOk
            }
            if (mapped < text.length) {
                recordFailure(
                    IllegalStateException(
                        "TouchInjector: ${text.length - mapped}/${text.length} chars unmappable " +
                            "by VIRTUAL_KEYBOARD (IME path required, out of Slice 3.3 scope)",
                    ),
                )
                return false
            }
            return allOk
        }
    }

    /** 仅供单测：复位手势状态、计数与显示配置（不碰反射缓存）。 */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            isTouching = false
            lastError = null
            displayWidth = UNSET_DISPLAY_SIZE
            displayHeight = UNSET_DISPLAY_SIZE
            successCount = 0L
            failureCount = 0L
            gestureDownTime = 0L
            lastX = 0f
            lastY = 0f
            appContextRef = null
        }
        InputManagerWrapper.resetCacheForTest()
    }

    // ------------------------------------------------------------------
    // 内部实现（调用方已持 lock）
    // ------------------------------------------------------------------

    private fun now(): Long = SystemClock.uptimeMillis()

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    /**
     * 归一化坐标→物理像素换算。尺寸按 configure > displayMetrics 顺序解析；
     * 无可用尺寸时记错并返回 null。
     */
    private fun toPixelsOrRecord(normX: Float, normY: Float): Pair<Float, Float>? {
        var w = displayWidth
        var h = displayHeight
        if (w <= 0 || h <= 0) {
            val metrics = appContextRef?.get()?.resources?.displayMetrics
            if (metrics != null && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                w = metrics.widthPixels
                h = metrics.heightPixels
                displayWidth = w
                displayHeight = h
            }
        }
        if (w <= 0 || h <= 0) {
            recordFailure(
                IllegalStateException(
                    "TouchInjector: unknown display size, call configure(w, h) or init(context) first",
                ),
            )
            return null
        }
        return (clamp01(normX) * w) to (clamp01(normY) * h)
    }

    /**
     * 单事件注入 + recycle + 记账。成功清 [lastError]（沿用引擎层“成功清零”语义）。
     * 调用方已持 [lock]。
     */
    private fun injectLocked(event: MotionEvent): Boolean {
        ensureHiddenApiExempted()
        return try {
            val ok = InputManagerWrapper.injectInputEventAsync(event)
            if (ok) {
                lastError = null
                successCount++
            } else {
                recordFailure(IllegalStateException("TouchInjector: system rejected MotionEvent"))
            }
            ok
        } catch (t: Throwable) {
            recordFailure(unwrap(t))
            false
        } finally {
            runCatching { event.recycle() }
        }
    }

    private fun injectLocked(event: KeyEvent): Boolean {
        ensureHiddenApiExempted()
        return try {
            val ok = InputManagerWrapper.injectInputEventAsync(event)
            if (ok) {
                lastError = null
                successCount++
            } else {
                recordFailure(IllegalStateException("TouchInjector: system rejected KeyEvent"))
            }
            ok
        } catch (t: Throwable) {
            recordFailure(unwrap(t))
            false
        }
    }

    /** 文本事件组批量注入（调用方已持 [lock]）。 */
    private fun injectKeyEvents(events: Array<KeyEvent>, mapped: Int, total: Int): Boolean {
        var allOk = true
        for (event in events) {
            allOk = injectLocked(event) && allOk
        }
        if (mapped < total) {
            recordFailure(
                IllegalStateException(
                    "TouchInjector: ${total - mapped}/${total} chars unmappable " +
                        "by VIRTUAL_KEYBOARD (IME path required, out of Slice 3.3 scope)",
                ),
            )
            return false
        }
        return allOk
    }

    private fun unwrap(t: Throwable): Throwable =
        (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t

    private fun recordFailure(t: Throwable) {
        lastError = t
        failureCount++
    }

    private fun ensureHiddenApiExempted() {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/os/ServiceManager",
                "Landroid/hardware/input/IInputManager",
                "Landroid/hardware/input/IInputManager\$Stub",
                "Landroid/view/IInputManager",
                "Landroid/view/IInputManager\$Stub",
                "Landroid/view/IWindowManager",
                "Landroid/view/IWindowManager\$Stub",
            )
        }
    }
}
