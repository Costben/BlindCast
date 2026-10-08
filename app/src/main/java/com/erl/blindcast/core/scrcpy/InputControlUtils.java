package com.erl.blindcast.core.scrcpy;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 触控 / 按键事件构造工具（Slice 3.3 · MVP.md 二(4) + 四(二)(3)；Phase C 加 displayId 路由）。
 *
 * <p>职责：把网页端反向操控坐标与手势（Down / Move / Up 序列）组装为可注入的
 * {@link MotionEvent}，把键盘 / 虚拟按键映射组装为可注入的 {@link KeyEvent}。
 * 除 {@code setDisplayId}（隐藏 API，反射 best-effort）外不做任何系统调用：
 * 真正的系统注入由 {@link InputManagerWrapper} 执行，上游统一入口为
 * {@code TouchInjector}（归一化坐标→物理像素换算亦在该处完成）。
 *
 * <p>触控事件约定（与 scrcpy 服务端一致）：
 * <ul>
 *   <li>单指主触点 ID 固定为 {@link #TOUCH_POINTER_ID}（0）；</li>
 *   <li>同一手势内 {@code downTime} 必须保持不变（Down 时采样 {@link SystemClock#uptimeMillis()}，
 *       后续 Move / Up 复用），否则系统侧视为断裂手势；</li>
 *   <li>{@code source = SOURCE_TOUCHSCREEN}；目标显示屏由 {@code displayId} 参数显式给出
 *       （缺省 {@link #DEFAULT_DISPLAY_ID} 主屏）。{@code MotionEvent.setDisplayId} /
 *       {@code KeyEvent.setDisplayId} 均为隐藏 API，经反射 best-effort 设置，
 *       缺失时保持默认主屏并打一次日志（不抛不崩）；</li>
 *   <li>工具类型 {@code TOOL_TYPE_FINGER}，压力 / 触面取 1（电容屏常规值）。</li>
 * </ul>
 *
 * <p>Phase C（Fusion 独立虚拟桌面）显示路由：所有构造入口都提供带 {@code displayId} 的重载，
 * 旧签名一律转发 {@link #DEFAULT_DISPLAY_ID}，调用方零改动即保持主屏行为。
 *
 * <p>按键事件约定：
 * <ul>
 *   <li>{@code source = SOURCE_KEYBOARD}，{@code deviceId = 0}（虚拟注入设备），
 *       {@code flags = FLAG_FROM_SYSTEM}（标记为系统注入，与物理键盘区分）；</li>
 *   <li>一次“按键”恒为 Down + Up 事件对（见 {@link #obtainKeyPress(int)}），
 *       调用方注入失败时自行决定是否补发 Up，本类不持有状态。</li>
 * </ul>
 *
 * <p>参数非法抛 {@link IllegalArgumentException}；构造出的事件用完后调用方负责
 * {@code recycle()}（{@code TouchInjector} 已处理）。
 */
public final class InputControlUtils {

    private static final String TAG = "BlindCast-InputCtrl";

    /** 单指主触点 ID（网页左键拖拽映射的固定触点）。 */
    public static final int TOUCH_POINTER_ID = 0;

    /** 默认目标显示屏：主屏（Display.DEFAULT_DISPLAY）。 */
    public static final int DEFAULT_DISPLAY_ID = 0;

    /** 无修饰键（metaState）。 */
    public static final int META_NONE = 0;

    /**
     * {@code InputEvent.setDisplayId(int)} 与 {@code InputEvent.getDisplayId()} 反射缓存。
     * 在 AOSP 中，这两个抽象方法直接定义在 {@link InputEvent} 基类上，
     * 子类 {@link MotionEvent} 与 {@link KeyEvent} 统一继承实现。
     */
    private static volatile Method sInputEventSetDisplayId;
    private static volatile Method sInputEventGetDisplayId;

    /** 隐藏方法在主屏反射失败时只记一次日志（避免刷屏）。 */
    private static volatile boolean sDisplayIdWarnLogged;

    private InputControlUtils() {
        // no instances
    }

    // ------------------------------------------------------------------
    // 单指触控（Down / Move / Up 主路径）
    // ------------------------------------------------------------------

    /**
     * 构造单指触控事件（主路径：网页左键手势过来统一走这里；目标主屏）。
     *
     * @see #obtainTouchEvent(int, long, long, float, float, int)
     */
    public static MotionEvent obtainTouchEvent(int action, long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(action, downTime, eventTime, x, y, DEFAULT_DISPLAY_ID);
    }

    /**
     * 构造单指触控事件（带目标显示屏，Phase C 桌面路由用）。
     *
     * @param action {@link MotionEvent#ACTION_DOWN} / {@link MotionEvent#ACTION_MOVE} /
     *               {@link MotionEvent#ACTION_UP} / {@link MotionEvent#ACTION_CANCEL} 之一
     * @param downTime 手势 Down 时刻（{@link SystemClock#uptimeMillis()} 基准；
     *                 Move / Up 必须复用所属 Down 的值）
     * @param eventTime 本事件时刻（同上基准，通常取当前 uptime）
     * @param x 物理像素 X（已由 {@code TouchInjector} 把归一化坐标换算完毕）
     * @param y 物理像素 Y
     * @param displayId 目标显示屏（0 = 主屏；&lt;0 视为非法）
     * @return 新构造的 {@link MotionEvent}（调用方负责 recycle）
     * @throws IllegalArgumentException action 非法 / displayId 为负时抛出
     */
    public static MotionEvent obtainTouchEvent(
            int action, long downTime, long eventTime, float x, float y, int displayId) {
        if (action != MotionEvent.ACTION_DOWN
                && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            throw new IllegalArgumentException("unsupported single-touch action: " + action);
        }
        requireDisplayId(displayId);
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
        setDisplayIdStrict(event, displayId);
        return event;
    }

    /**
     * 构造触控按下事件（主屏）。
     *
     * @see #obtainTouchDown(long, long, float, float, int)
     */
    public static MotionEvent obtainTouchDown(long downTime, long eventTime, float x, float y) {
        return obtainTouchDown(downTime, eventTime, x, y, DEFAULT_DISPLAY_ID);
    }

    /** 构造触控按下事件（指定显示屏）。 */
    public static MotionEvent obtainTouchDown(
            long downTime, long eventTime, float x, float y, int displayId) {
        return obtainTouchEvent(MotionEvent.ACTION_DOWN, downTime, eventTime, x, y, displayId);
    }

    /**
     * 构造触控移动事件（必须复用所属 Down 的 {@code downTime}；主屏）。
     */
    public static MotionEvent obtainTouchMove(long downTime, long eventTime, float x, float y) {
        return obtainTouchMove(downTime, eventTime, x, y, DEFAULT_DISPLAY_ID);
    }

    /** 构造触控移动事件（指定显示屏）。 */
    public static MotionEvent obtainTouchMove(
            long downTime, long eventTime, float x, float y, int displayId) {
        return obtainTouchEvent(MotionEvent.ACTION_MOVE, downTime, eventTime, x, y, displayId);
    }

    /**
     * 构造触控抬起事件（必须复用所属 Down 的 {@code downTime}；主屏）。
     */
    public static MotionEvent obtainTouchUp(long downTime, long eventTime, float x, float y) {
        return obtainTouchUp(downTime, eventTime, x, y, DEFAULT_DISPLAY_ID);
    }

    /** 构造触控抬起事件（指定显示屏）。 */
    public static MotionEvent obtainTouchUp(
            long downTime, long eventTime, float x, float y, int displayId) {
        return obtainTouchEvent(MotionEvent.ACTION_UP, downTime, eventTime, x, y, displayId);
    }

    /**
     * 构造触控取消事件（手势异常中断 / 连接断开时松开残留触点用；主屏）。
     */
    public static MotionEvent obtainTouchCancel(long downTime, long eventTime, float x, float y) {
        return obtainTouchCancel(downTime, eventTime, x, y, DEFAULT_DISPLAY_ID);
    }

    /** 构造触控取消事件（指定显示屏）。 */
    public static MotionEvent obtainTouchCancel(
            long downTime, long eventTime, float x, float y, int displayId) {
        return obtainTouchEvent(MotionEvent.ACTION_CANCEL, downTime, eventTime, x, y, displayId);
    }

    // ------------------------------------------------------------------
    // 多指触控（POINTER_DOWN / POINTER_UP 预留，供后续 Slice 扩展双指缩放）
    // ------------------------------------------------------------------

    /**
     * 构造多指触控事件（主屏）。
     *
     * @see #obtainMultiTouchEvent(int, int, long, long, float[], float[], int)
     */
    public static MotionEvent obtainMultiTouchEvent(
            int action, int actionIndex, long downTime, long eventTime, float[] xs, float[] ys) {
        return obtainMultiTouchEvent(
                action, actionIndex, downTime, eventTime, xs, ys, DEFAULT_DISPLAY_ID);
    }

    /**
     * 构造多指触控事件（指定显示屏）。
     *
     * @param action 基础动作（ACTION_DOWN / ACTION_POINTER_DOWN /
     *               ACTION_MOVE / ACTION_POINTER_UP / ACTION_UP / ACTION_CANCEL）
     * @param actionIndex 动作触点在数组中的下标（POINTER_DOWN / POINTER_UP 时有效，
     *                    其余动作传 0；将按 {@code ACTION_POINTER_INDEX_SHIFT} 编入 action）
     * @param downTime 首指 Down 时刻（同基准）
     * @param eventTime 本事件时刻
     * @param xs 各触点物理像素 X（非空，长度 = 触点数，各个元素一一对应）
     * @param ys 各触点物理像素 Y（非空，与 xs 等长）
     * @param displayId 目标显示屏（0 = 主屏）
     * @return 新构造的 {@link MotionEvent}（调用方负责 recycle）
     * @throws IllegalArgumentException 数组非法 / 长度不一致 / 触点数越界 /
     *                                  actionIndex 越界 / displayId 为负时抛出
     */
    public static MotionEvent obtainMultiTouchEvent(
            int action, int actionIndex, long downTime, long eventTime,
            float[] xs, float[] ys, int displayId) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length) {
            throw new IllegalArgumentException(
                    "xs/ys must be non-empty and of equal length");
        }
        requireDisplayId(displayId);
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
        setDisplayIdStrict(event, displayId);
        return event;
    }

    /** displayId 合法性（负数不是合法 Display 标识）。 */
    private static void requireDisplayId(int displayId) {
        if (displayId < 0) {
            throw new IllegalArgumentException("invalid displayId: " + displayId);
        }
    }

    /**
     * 为 InputEvent 严格设置目标 displayId 并验效（Phase C 核心防御）。
     *
     * <p>安全契约：
     * <ol>
     *   <li>无论是主屏 0 还是副屏 >0，绝不跳过 setter，杜绝 KeyEvent 默认 -1 乱入当前 focused 屏；</li>
     *   <li>副屏（displayId &gt; 0）如果 setter 缺失、执行失败，或核对不匹配，<b>坚决抛出异常阻断</b>，
     *       绝对阻止副屏事件 fallback 到物理主屏 0；</li>
     *   <li>优先在 {@link InputEvent} 抽象基类统一反射 {@code setDisplayId(int)} 与 {@code getDisplayId()}，
     *       缺失时回退实例自身方法，保证 MotionEvent 与 KeyEvent 行为严密一致。</li>
     * </ol>
     *
     * @param event 待设置事件（非 null）
     * @param displayId 目标显示屏（>= 0）
     * @throws IllegalStateException 当副屏设置失败或校验不匹配时抛出，阻止事件误发
     */
    public static void setDisplayIdStrict(InputEvent event, int displayId) {
        if (event == null) {
            throw new IllegalArgumentException("event == null");
        }
        requireDisplayId(displayId);

        Throwable setFailure = null;
        try {
            Method setter = sInputEventSetDisplayId;
            if (setter == null) {
                try {
                    setter = InputEvent.class.getMethod("setDisplayId", int.class);
                } catch (NoSuchMethodException ignored) {
                    setter = event.getClass().getMethod("setDisplayId", int.class);
                }
                setter.setAccessible(true);
                sInputEventSetDisplayId = setter;
            }
            setter.invoke(event, displayId);
        } catch (Throwable t) {
            setFailure = t instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) t).getTargetException()
                    : t;
        }

        // 验证实际生效的 displayId
        Integer actualDisplayId = null;
        try {
            Method getter = sInputEventGetDisplayId;
            if (getter == null) {
                try {
                    getter = InputEvent.class.getMethod("getDisplayId");
                } catch (NoSuchMethodException ignored) {
                    getter = event.getClass().getMethod("getDisplayId");
                }
                getter.setAccessible(true);
                sInputEventGetDisplayId = getter;
            }
            Object res = getter.invoke(event);
            if (res instanceof Integer) {
                actualDisplayId = (Integer) res;
            }
        } catch (Throwable ignored) {
            // getDisplayId 隐藏方法未暴露时记录，后续依 setFailure 判定
        }

        // 严格拦截条件：
        // 1. 若成功读出 actualDisplayId 且其与目标 displayId 不符，立即阻断！
        if (actualDisplayId != null && actualDisplayId != displayId) {
            throw new IllegalStateException("InputControlUtils: displayId verification failed! expected "
                    + displayId + ", but got " + actualDisplayId + ", blocking injection to prevent cross-screen spill");
        }

        // 2. 若 setter 执行失败：
        if (setFailure != null) {
            if (displayId != DEFAULT_DISPLAY_ID) {
                // 副屏事件无法绑定指定 displayId，坚决阻断，严防打到物理主屏！
                throw new IllegalStateException("InputControlUtils: failed to set displayId " + displayId
                        + " on " + event.getClass().getSimpleName()
                        + ", blocking injection to protect physical screen 0", setFailure);
            } else {
                // 主屏事件（0）：若读取到的 displayId < 0（如 INVALID_DISPLAY），同样阻断
                if (actualDisplayId != null && actualDisplayId < 0) {
                    throw new IllegalStateException("InputControlUtils: main display event has invalid displayId: "
                            + actualDisplayId, setFailure);
                }
                if (!sDisplayIdWarnLogged) {
                    sDisplayIdWarnLogged = true;
                    Log.w(TAG, "[InputControlUtils] setDisplayId on main display failed: " + setFailure);
                }
            }
        }
    }

    /**
     * 对外部已创建的 KeyEvent（如 KeyCharacterMap 产出的按键事件）补设目标 displayId。
     */
    public static void applyKeyDisplayId(KeyEvent event, int displayId) {
        if (event == null) {
            throw new IllegalArgumentException("event == null");
        }
        setDisplayIdStrict(event, displayId);
    }

    /** 仅供单测：清空 setDisplayId 反射缓存与日志门闩。 */
    static void resetDisplayIdCacheForTest() {
        sInputEventSetDisplayId = null;
        sInputEventGetDisplayId = null;
        sDisplayIdWarnLogged = false;
    }

    // ------------------------------------------------------------------
    // 按键（虚拟按键 / 键盘映射）
    // ------------------------------------------------------------------

    /**
     * 构造按键事件（完整参数；主屏）。
     *
     * @see #obtainKeyEvent(int, int, long, long, int)
     */
    public static KeyEvent obtainKeyEvent(int action, int keyCode, long downTime, long eventTime) {
        return obtainKeyEvent(action, keyCode, downTime, eventTime, DEFAULT_DISPLAY_ID);
    }

    /**
     * 构造按键事件（完整参数 + 目标显示屏，Phase C 桌面按键路由用）。
     *
     * @param action {@link KeyEvent#ACTION_DOWN} / {@link KeyEvent#ACTION_UP} 之一
     * @param keyCode Android 按键码（如 {@link KeyEvent#KEYCODE_BACK}）
     * @param downTime Down 时刻（uptime 基准；Up 事件复用所属 Down 的值）
     * @param eventTime 本事件时刻
     * @param displayId 目标显示屏（0 = 主屏）
     * @return 新构造的 {@link KeyEvent}
     * @throws IllegalArgumentException action 非法 / displayId 为负时抛出
     */
    public static KeyEvent obtainKeyEvent(
            int action, int keyCode, long downTime, long eventTime, int displayId) {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            throw new IllegalArgumentException("unsupported key action: " + action);
        }
        requireDisplayId(displayId);
        KeyEvent event = new KeyEvent(
                downTime, eventTime, action, keyCode,
                0 /* repeatCount */, META_NONE,
                0 /* deviceId: 0 = 虚拟注入设备（非物理键盘） */,
                0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);
        setDisplayIdStrict(event, displayId);
        return event;
    }

    /**
     * 构造按键事件（时刻自动取当前 uptime；主屏）。
     *
     * @see #obtainKeyEvent(int, int, int)
     */
    public static KeyEvent obtainKeyEvent(int action, int keyCode) {
        return obtainKeyEvent(action, keyCode, DEFAULT_DISPLAY_ID);
    }

    /**
     * 构造按键事件（时刻自动取当前 uptime；指定显示屏）。
     * Down 与 Up 需成对调用时请用 {@link #obtainKeyPress(int, int)} 以共享 downTime。
     *
     * @param action ACTION_DOWN / ACTION_UP 之一
     * @param keyCode Android 按键码
     * @param displayId 目标显示屏（0 = 主屏）
     * @return 新构造的 {@link KeyEvent}
     */
    public static KeyEvent obtainKeyEvent(int action, int keyCode, int displayId) {
        long now = SystemClock.uptimeMillis();
        return obtainKeyEvent(action, keyCode, now, now, displayId);
    }

    /**
     * 构造一次完整“按键”（Down + Up 事件对，共享 downTime；主屏）。
     *
     * @see #obtainKeyPress(int, int)
     */
    public static KeyEvent[] obtainKeyPress(int keyCode) {
        return obtainKeyPress(keyCode, DEFAULT_DISPLAY_ID);
    }

    /**
     * 构造一次完整“按键”（Down + Up 事件对，共享 downTime；指定显示屏）。
     * 典型用途：鼠标右键→BACK、鼠标中键→HOME、键盘单键注入
     * （实际按钮映射在 Slice 4.1 {@code ControlWsRoute} 落子，本方法只产事件）。
     *
     * @param keyCode Android 按键码
     * @param displayId 目标显示屏（0 = 主屏）
     * @return 长度为 2 的数组：[0] = Down，[1] = Up
     */
    public static KeyEvent[] obtainKeyPress(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = obtainKeyEvent(KeyEvent.ACTION_DOWN, keyCode, downTime, downTime, displayId);
        long upTime = SystemClock.uptimeMillis();
        KeyEvent up = obtainKeyEvent(KeyEvent.ACTION_UP, keyCode, downTime, upTime, displayId);
        return new KeyEvent[]{down, up};
    }
}
