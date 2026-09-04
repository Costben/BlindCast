package com.erl.blindcast.core.scrcpy;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 触控 / 按键事件构造工具（Slice 3.3 · MVP.md 二(4) + 四(二)(3)）。
 *
 * <p>职责：把网页端反向操控坐标与手势（Down / Move / Up 序列）组装为可注入的
 * {@link MotionEvent}，把键盘 / 虚拟按键映射组装为可注入的 {@link KeyEvent}。
 * 本类只做<b>纯内存对象构造</b>：无反射、无 Binder、无线程，任意线程调用；
 * 真正的系统注入由 {@link InputManagerWrapper} 执行，上游统一入口为
 * {@code TouchInjector}（归一化坐标→物理像素换算亦在该处完成）。
 *
 * <p>触控事件约定（与 scrcpy 服务端一致）：
 * <ul>
 *   <li>单指主触点 ID 固定为 {@link #TOUCH_POINTER_ID}（0）；</li>
 *   <li>同一手势内 {@code downTime} 必须保持不变（Down 时采样 {@link SystemClock#uptimeMillis()}，
 *       后续 Move / Up 复用），否则系统侧视为断裂手势；</li>
 *   <li>{@code source = SOURCE_TOUCHSCREEN}，{@code displayId = DEFAULT_DISPLAY}（主屏；
 *       {@code setDisplayId} 为隐藏 API，经反射 best-effort 设置，缺失时保持默认主屏）；</li>
 *   <li>工具类型 {@code TOOL_TYPE_FINGER}，压力 / 触面取 1（电容屏常规值）。</li>
 * </ul>
 *
 * <p>按键事件约定：
 * <ul>
 *   <li>{@code source = SOURCE_KEYBOARD}，{@code deviceId = VIRTUAL_KEYBOARD}，
 *       {@code flags = FLAG_FROM_SYSTEM}（标记为系统注入，与物理键盘区分）；</li>
 *   <li>一次“按键”恒为 Down + Up 事件对（见 {@link #obtainKeyPress(int)}），
 *       调用方注入失败时自行决定是否补发 Up，本类不持有状态。</li>
 * </ul>
 *
 * <p>Slice 3.3 · 纯构造无状态工具类。参数非法抛 {@link IllegalArgumentException}；
 * 构造出的事件用完后调用方负责 {@code recycle()}（{@code TouchInjector} 已处理）。
 */
public final class InputControlUtils {

    /** 单指主触点 ID（网页左键拖拽映射的固定触点）。 */
    public static final int TOUCH_POINTER_ID = 0;

    /** 默认目标显示屏：主屏（Display.DEFAULT_DISPLAY）。 */
    public static final int DEFAULT_DISPLAY_ID = 0;

    /** 无修饰键（metaState）。 */
    public static final int META_NONE = 0;

    /**
     * {@code MotionEvent.setDisplayId(int)} 反射缓存（该方法是隐藏 API，
     * 编译期 SDK stub 不可见，故反射调用；缺失时保持事件默认 displayId，
     * 其默认值即主屏，无需处理）。
     */
    private static volatile Method sSetDisplayId;

    private InputControlUtils() {
        // no instances
    }

    // ------------------------------------------------------------------
    // 单指触控（Down / Move / Up 主路径）
    // ------------------------------------------------------------------

    /**
     * 构造单指触控事件（主路径：网页左键手势过来统一走这里）。
     *
     * @param action {@link MotionEvent#ACTION_DOWN} / {@link MotionEvent#ACTION_MOVE} /
     *               {@link MotionEvent#ACTION_UP} 之一
     * @param downTime 手势 Down 时刻（{@link SystemClock#uptimeMillis()} 基准；
     *                 Move / Up 必须复用所属 Down 的值）
     * @param eventTime 本事件时刻（同上基准，通常取当前 uptime）
     * @param x 物理像素 X（已由 {@code TouchInjector} 把归一化坐标换算完毕）
     * @param y 物理像素 Y
     * @return 新构造的 {@link MotionEvent}（调用方负责 recycle）
     * @throws IllegalArgumentException action 非法时抛出
     */
    public static MotionEvent obtainTouchEvent(int action, long downTime, long eventTime, float x, float y) {
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            throw new IllegalArgumentException("unsupported single-touch action: " + action);
        }
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = TOUCH_POINTER_ID;
        pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
        props[0] = pp;
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = 1f;
        pc.size = 1f;
        coords[0] = pc;
        MotionEvent event = MotionEvent.obtain(
                downTime, eventTime, action, 1, props, coords,
                META_NONE, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        setDisplayIdBestEffort(event, DEFAULT_DISPLAY_ID);
        return event;
    }

    /**
     * 构造触控按下事件。
     *
     * @param downTime 本次手势 Down 时刻（= eventTime，同基准）
     * @param eventTime 同 downTime
     * @param x 物理像素 X
     * @param y 物理像素 Y
     * @return 新构造的 ACTION_DOWN 事件
     */
    public static MotionEvent obtainTouchDown(long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(MotionEvent.ACTION_DOWN, downTime, eventTime, x, y);
    }

    /**
     * 构造触控移动事件（必须复用所属 Down 的 {@code downTime}）。
     */
    public static MotionEvent obtainTouchMove(long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(MotionEvent.ACTION_MOVE, downTime, eventTime, x, y);
    }

    /**
     * 构造触控抬起事件（必须复用所属 Down 的 {@code downTime}）。
     */
    public static MotionEvent obtainTouchUp(long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(MotionEvent.ACTION_UP, downTime, eventTime, x, y);
    }

    /**
     * 构造触控取消事件（手势异常中断 / 连接断开时松开残留触点用）。
     */
    public static MotionEvent obtainTouchCancel(long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(MotionEvent.ACTION_CANCEL, downTime, eventTime, x, y);
    }

    // ------------------------------------------------------------------
    // 多指触控（POINTER_DOWN / POINTER_UP 预留，供后续 Slice 扩展双指缩放）
    // ------------------------------------------------------------------

    /**
     * 构造多指触控事件。
     *
     * @param action 基础动作（ACTION_DOWN / ACTION_POINTER_DOWN /
     *               ACTION_MOVE / ACTION_POINTER_UP / ACTION_UP / ACTION_CANCEL）
     * @param actionIndex 动作触点在数组中的下标（POINTER_DOWN / POINTER_UP 时有效，
     *                    其余动作传 0；将按 {@code ACTION_POINTER_INDEX_SHIFT} 编入 action）
     * @param downTime 首指 Down 时刻（同基准）
     * @param eventTime 本事件时刻
     * @param xs 各触点物理像素 X（非空，长度 = 触点数，各个元素一一对应）
     * @param ys 各触点物理像素 Y（非空，与 xs 等长）
     * @return 新构造的 {@link MotionEvent}（调用方负责 recycle）
     * @throws IllegalArgumentException 数组非法 / 长度不一致 / 触点数越界 /
     *                                  actionIndex 越界时抛出
     */
    public static MotionEvent obtainMultiTouchEvent(
            int action, int actionIndex, long downTime, long eventTime, float[] xs, float[] ys) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) {
            throw new IllegalArgumentException(
                    "xs/ys must be non-empty and of equal length");
        }
        int pointerCount = xs.length;
        int base = action & MotionEvent.ACTION_MASK;
        boolean isPointerAction = base == MotionEvent.ACTION_POINTER_DOWN
                || base == MotionEvent.ACTION_POINTER_UP;
        if (isPointerAction) {
            if (actionIndex < 0 || actionIndex >= pointerCount) {
                throw new IllegalArgumentException("actionIndex out of bounds: " + actionIndex);
            }
            action = base | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
            pp.id = i;
            pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = pp;
            MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
            pc.x = xs[i];
            pc.y = ys[i];
            pc.pressure = 1f;
            pc.size = 1f;
            coords[i] = pc;
        }
        MotionEvent event = MotionEvent.obtain(
                downTime, eventTime, action, pointerCount, props, coords,
                META_NONE, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        setDisplayIdBestEffort(event, DEFAULT_DISPLAY_ID);
        return event;
    }

    /**
     * best-effort 设置事件目标显示屏。
     *
     * <p>{@code MotionEvent.setDisplayId(int)} 是隐藏 API（编译期 SDK stub 不可见），
     * 此处反射调用并缓存 {@link Method}；ROM 缺失该方法时静默跳过——
     * 事件默认 displayId 即主屏（0），行为依然正确。
     */
    private static void setDisplayIdBestEffort(MotionEvent event, int displayId) {
        try {
            Method m = sSetDisplayId;
            if (m == null) {
                m = MotionEvent.class.getMethod("setDisplayId", int.class);
                m.setAccessible(true);
                sSetDisplayId = m;
            }
            m.invoke(event, displayId);
        } catch (Throwable ignored) {
            // 隐藏方法缺失：保持默认 displayId（主屏），不记错不抛异常。
        }
    }

    // ------------------------------------------------------------------
    // 按键（虚拟按键 / 键盘映射）
    // ------------------------------------------------------------------

    /**
     * 构造按键事件（完整参数）。
     *
     * @param action {@link KeyEvent#ACTION_DOWN} / {@link KeyEvent#ACTION_UP} 之一
     * @param keyCode Android 按键码（如 {@link KeyEvent#KEYCODE_BACK}）
     * @param downTime Down 时刻（uptime 基准；Up 事件复用所属 Down 的值）
     * @param eventTime 本事件时刻
     * @return 新构造的 {@link KeyEvent}
     * @throws IllegalArgumentException action 非法时抛出
     */
    public static KeyEvent obtainKeyEvent(int action, int keyCode, long downTime, long eventTime) {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            throw new IllegalArgumentException("unsupported key action: " + action);
        }
        return new KeyEvent(
                downTime, eventTime, action, keyCode,
                0 /* repeatCount */, META_NONE,
                0 /* deviceId: 0 = 虚拟注入设备（非物理键盘） */,
                0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);
    }

    /**
     * 构造按键事件（时刻自动取当前 uptime；Down 与 Up 需成对调用时请用
     * {@link #obtainKeyPress(int)} 以共享 downTime）。
     *
     * @param action ACTION_DOWN / ACTION_UP 之一
     * @param keyCode Android 按键码
     * @return 新构造的 {@link KeyEvent}
     */
    public static KeyEvent obtainKeyEvent(int action, int keyCode) {
        long now = SystemClock.uptimeMillis();
        return obtainKeyEvent(action, keyCode, now, now);
    }

    /**
     * 构造一次完整“按键”（Down + Up 事件对，共享 downTime）。
     * 典型用途：鼠标右键→BACK、鼠标中键→HOME、键盘单键注入
     * （实际按钮映射在 Slice 4.1 {@code ControlWsRoute} 落子，本方法只产事件）。
     *
     * @param keyCode Android 按键码
     * @return 长度为 2 的数组：[0] = Down，[1] = Up
     */
    public static KeyEvent[] obtainKeyPress(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = obtainKeyEvent(KeyEvent.ACTION_DOWN, keyCode, downTime, downTime);
        long upTime = SystemClock.uptimeMillis();
        KeyEvent up = obtainKeyEvent(KeyEvent.ACTION_UP, keyCode, downTime, upTime);
        return new KeyEvent[]{down, up};
    }
}
