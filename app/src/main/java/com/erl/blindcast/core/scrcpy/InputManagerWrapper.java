package com.erl.blindcast.core.scrcpy;

import android.os.IBinder;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 系统输入注入服务反射包装（Slice 3.3 · MVP.md 二(4) + 四(二)(3)）。
 *
 * <p>职责：经 {@code android.os.ServiceManager} 取输入服务 Binder，
 * 再反射调用系统侧注入方法，把 {@link InputEvent}（触控 / 按键）塞进系统输入队列。
 * 是网页端反向操控到达 Android 底层的最后一跳，上游统一入口为 {@code TouchInjector}。
 *
 * <p>双路径探测（“新签名优先、旧签名回退”，与 {@code UserActivityKeeper} 同风格）：
 * <ol>
 *   <li><b>新路径（首选）</b>：{@code ServiceManager.getService("input")} 取 Binder，
 *       经 {@code IInputManager$Stub.asInterface(IBinder)} 得代理，再调统一方法
 *       {@code injectInputEvent(InputEvent, int mode)}（返回 boolean）。
 *       {@code IInputManager} 按 ROM 实际位置依次探测
 *       {@code android.hardware.input.IInputManager} 与 {@code android.view.IInputManager}。</li>
 *   <li><b>旧路径（回退）</b>：{@code ServiceManager.getService("window")} 取 Binder，
 *       经 {@code android.view.IWindowManager$Stub.asInterface(IBinder)} 得代理，
 *       按事件类型分发到 {@code injectKeyEvent(KeyEvent, boolean sync)} /
 *       {@code injectPointerEvent(MotionEvent, boolean sync)}（均返回 boolean），
 *       其中 {@code sync = (mode != INJECT_INPUT_EVENT_MODE_ASYNC)}。</li>
 * </ol>
 *
 * <p>返回值归一：AOSP 实现返回 boolean；签名为 void 的实现上反射返回 null，
 * 此时无异常即视为成功返回 true；极个别返回 int 的 ROM 实现按 {@code != 0} 判成功。
 *
 * <p><b>调用点约束（Shizuku / Root 提权进程调用点预留）：</b>注入需要签名级
 * {@code INJECT_EVENTS} 权限，本类所有方法必须在提权进程内执行——
 * Shizuku UserService（system_server 上下文）或以 Root 身份启动的 app_process 进程。
 * 普通 App 进程调用将抛 {@link SecurityException}（或其
 * {@code InvocationTargetException} 包裹形态），由上游 {@code TouchInjector}
 * 捕获后记 {@code lastError} 并返回 false，永不崩溃。
 *
 * <p><b>线程约束：</b>{@code inject*} 均为同步阻塞调用（含一次 Binder 往返），
 * 禁止在主线程调用；上游请走后台线程（Slice 4.1 {@code ControlWsRoute} 内调用）。
 *
 * <p>Slice 3.3 · 纯反射无状态工具类（注入统计与手势状态由 {@code TouchInjector} 维护）。
 * 仅做能力封装，不接网络、不接 UI；鼠标右键→BACK / 中键→HOME 的实际协议映射
 * 在 Slice 4.1 {@code ControlWsRoute} 落子，本类只提供按键注入能力。
 */
public final class InputManagerWrapper {

    /** 异步注入：不等待输入派发结果（默认模式，延迟最低，反向操控首选）。 */
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    /** 注入并等待派发结果（对应系统侧 WAIT_FOR_RESULT）。 */
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    /** 注入并等待派发完成（对应系统侧 WAIT_FOR_FINISH，最慢，仅诊断用）。 */
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;

    private static final String SERVICE_INPUT = "input";
    private static final String SERVICE_WINDOW = "window";

    private static final String SERVICE_MANAGER_CLASS = "android.os.ServiceManager";
    private static final String METHOD_GET_SERVICE = "getService";
    private static final String METHOD_AS_INTERFACE = "asInterface";

    private static final String[] INPUT_MANAGER_CLASSES = {
            "android.hardware.input.IInputManager",
            "android.view.IInputManager",
    };
    private static final String WINDOW_MANAGER_CLASS = "android.view.IWindowManager";

    private static final String METHOD_INJECT_INPUT_EVENT = "injectInputEvent";
    private static final String METHOD_INJECT_KEY_EVENT = "injectKeyEvent";
    private static final String METHOD_INJECT_POINTER_EVENT = "injectPointerEvent";

    /** 新路径：统一注入。 */
    private static final int KIND_INPUT_MANAGER = 0;
    /** 旧路径：按事件类型分发到 window 服务。 */
    private static final int KIND_WINDOW_MANAGER_SPLIT = 1;

    private static final Object LOCK = new Object();
    private static volatile Object sProxy;
    private static volatile Method sUnifiedInject;
    private static volatile Method sWindowInjectKey;
    private static volatile Method sWindowInjectPointer;
    private static volatile int sKind = KIND_INPUT_MANAGER;
    /** 极端裁剪 ROM（ServiceManager / Stub 类本身缺失）时封存探测，避免每次注入重复反射。 */
    private static volatile boolean sSealed = false;

    private InputManagerWrapper() {
        // no instances
    }

    /**
     * 注入输入事件（主入口，同步阻塞，禁止主线程直调）。
     *
     * @param event 待注入事件（经 {@link InputControlUtils} 构造的触控 / 按键事件，非 null）
     * @param mode 注入模式（{@link #INJECT_INPUT_EVENT_MODE_ASYNC} 等三档之一）
     * @return 系统侧返回 true（或 void 签名下无异常）即 true
     * @throws Exception 反射失败 / 系统拒绝（无提权）/ 参数非法时抛出，
     *                   由上游 {@code TouchInjector} 捕获记错
     */
    public static boolean injectInputEvent(InputEvent event, int mode) throws Exception {
        if (event == null) {
            throw new IllegalArgumentException("event == null");
        }
        ResolvedTarget target = resolveTarget();
        if (target.kind == KIND_INPUT_MANAGER) {
            Object result = target.unifiedInject.invoke(target.proxy, event, mode);
            return coerceBoolean(result);
        }
        // 旧路径：按键与触控分发到 window 服务的两个方法。
        boolean sync = mode != INJECT_INPUT_EVENT_MODE_ASYNC;
        if (event instanceof KeyEvent) {
            if (target.windowInjectKey == null) {
                throw new NoSuchMethodException("IWindowManager.injectKeyEvent(KeyEvent, boolean) not found");
            }
            Object result = target.windowInjectKey.invoke(target.proxy, event, sync);
            return coerceBoolean(result);
        }
        if (event instanceof MotionEvent) {
            if (target.windowInjectPointer == null) {
                throw new NoSuchMethodException(
                        "IWindowManager.injectPointerEvent(MotionEvent, boolean) not found");
            }
            Object result = target.windowInjectPointer.invoke(target.proxy, event, sync);
            return coerceBoolean(result);
        }
        throw new IllegalArgumentException("unsupported InputEvent: " + event.getClass().getName());
    }

    /**
     * 异步注入便捷入口（同步阻塞语义同 {@link #injectInputEvent}，模式固定为 ASYNC）。
     *
     * @param event 待注入事件（非 null）
     * @return 同 {@link #injectInputEvent}
     * @throws Exception 同 {@link #injectInputEvent}
     */
    public static boolean injectInputEventAsync(InputEvent event) throws Exception {
        return injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /**
     * 非抛探活：当前进程是否能解析到任一可用注入路径。
     * 仅探测反射可用性，不实际注入事件；返回 false 不代表无提权，只代表路径缺失。
     *
     * @return 任一路径解析成功 true，否则 false（永不抛异常）
     */
    public static boolean isAvailable() {
        try {
            resolveTarget();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 仅供单测：清空反射缓存与封存位，强制下次注入重新探测路径。 */
    public static void resetCacheForTest() {
        synchronized (LOCK) {
            sProxy = null;
            sUnifiedInject = null;
            sWindowInjectKey = null;
            sWindowInjectPointer = null;
            sKind = KIND_INPUT_MANAGER;
            sSealed = false;
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static final class ResolvedTarget {
        final Object proxy;
        final int kind;
        final Method unifiedInject;
        final Method windowInjectKey;
        final Method windowInjectPointer;

        ResolvedTarget(
                Object proxy,
                int kind,
                Method unifiedInject,
                Method windowInjectKey,
                Method windowInjectPointer) {
            this.proxy = proxy;
            this.kind = kind;
            this.unifiedInject = unifiedInject;
            this.windowInjectKey = windowInjectKey;
            this.windowInjectPointer = windowInjectPointer;
        }
    }

    private static ResolvedTarget resolveTarget() throws Exception {
        Object proxy = sProxy;
        if (proxy != null) {
            if (sKind == KIND_INPUT_MANAGER && sUnifiedInject != null) {
                return new ResolvedTarget(proxy, sKind, sUnifiedInject, null, null);
            }
            if (sKind == KIND_WINDOW_MANAGER_SPLIT
                    && (sWindowInjectKey != null || sWindowInjectPointer != null)) {
                return new ResolvedTarget(
                        proxy, sKind, null, sWindowInjectKey, sWindowInjectPointer);
            }
        }
        synchronized (LOCK) {
            proxy = sProxy;
            if (proxy != null) {
                if (sKind == KIND_INPUT_MANAGER && sUnifiedInject != null) {
                    return new ResolvedTarget(proxy, sKind, sUnifiedInject, null, null);
                }
                if (sKind == KIND_WINDOW_MANAGER_SPLIT
                        && (sWindowInjectKey != null || sWindowInjectPointer != null)) {
                    return new ResolvedTarget(
                            proxy, sKind, null, sWindowInjectKey, sWindowInjectPointer);
                }
            }
            if (sSealed) {
                throw new IllegalStateException(
                        "InputManagerWrapper: injection path sealed (no usable input/window service)");
            }
            // 路径 1（新）：input 服务 + IInputManager.injectInputEvent(InputEvent, int)。
            ResolvedTarget fresh = tryResolveInputManager();
            if (fresh == null) {
                // 路径 2（旧）：window 服务 + IWindowManager.injectKeyEvent/injectPointerEvent。
                fresh = tryResolveWindowManager();
            }
            if (fresh == null) {
                throw new IllegalStateException(
                        "InputManagerWrapper: neither IInputManager.injectInputEvent "
                                + "nor IWindowManager.injectKeyEvent/injectPointerEvent is available");
            }
            sProxy = fresh.proxy;
            sUnifiedInject = fresh.unifiedInject;
            sWindowInjectKey = fresh.windowInjectKey;
            sWindowInjectPointer = fresh.windowInjectPointer;
            sKind = fresh.kind;
            return fresh;
        }
    }

    private static ResolvedTarget tryResolveInputManager() throws Exception {
        IBinder binder = getServiceBinder(SERVICE_INPUT);
        if (binder == null) {
            return null;
        }
        for (String className : INPUT_MANAGER_CLASSES) {
            Class<?> iface;
            try {
                iface = Class.forName(className);
            } catch (ClassNotFoundException nextCandidate) {
                continue;
            }
            Method inject;
            try {
                inject = iface.getMethod(METHOD_INJECT_INPUT_EVENT, InputEvent.class, int.class);
            } catch (NoSuchMethodException noUnifiedSignature) {
                continue;
            }
            Object proxy = asInterface(className, binder);
            if (proxy == null) {
                continue;
            }
            inject.setAccessible(true);
            return new ResolvedTarget(proxy, KIND_INPUT_MANAGER, inject, null, null);
        }
        return null;
    }

    private static ResolvedTarget tryResolveWindowManager() throws Exception {
        IBinder binder = getServiceBinder(SERVICE_WINDOW);
        if (binder == null) {
            return null;
        }
        Class<?> iface;
        try {
            iface = Class.forName(WINDOW_MANAGER_CLASS);
        } catch (ClassNotFoundException e) {
            sealIfFrameworkMissing(e);
            return null;
        }
        Method injectKey = null;
        Method injectPointer = null;
        try {
            injectKey = iface.getMethod(METHOD_INJECT_KEY_EVENT, KeyEvent.class, boolean.class);
            injectKey.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // 按键与触控方法独立探测：缺其一仍保留另一能力。
        }
        try {
            injectPointer = iface.getMethod(
                    METHOD_INJECT_POINTER_EVENT, MotionEvent.class, boolean.class);
            injectPointer.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
            // 同上。
        }
        if (injectKey == null && injectPointer == null) {
            return null;
        }
        Object proxy = asInterface(WINDOW_MANAGER_CLASS, binder);
        if (proxy == null) {
            return null;
        }
        return new ResolvedTarget(
                proxy, KIND_WINDOW_MANAGER_SPLIT, null, injectKey, injectPointer);
    }

    private static IBinder getServiceBinder(String name) throws Exception {
        Class<?> smClass;
        try {
            smClass = Class.forName(SERVICE_MANAGER_CLASS);
        } catch (ClassNotFoundException e) {
            sealIfFrameworkMissing(e);
            throw e;
        }
        Method getService = smClass.getMethod(METHOD_GET_SERVICE, String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, name);
        return (IBinder) binder;
    }

    private static Object asInterface(String interfaceClassName, IBinder binder) throws Exception {
        Class<?> stub;
        try {
            stub = Class.forName(interfaceClassName + "$Stub");
        } catch (ClassNotFoundException e) {
            sealIfFrameworkMissing(e);
            throw e;
        }
        Method asInterface = stub.getMethod(METHOD_AS_INTERFACE, IBinder.class);
        asInterface.setAccessible(true);
        return asInterface.invoke(null, binder);
    }

    private static boolean coerceBoolean(Object result) {
        if (result == null) {
            // void 签名：无异常即视为成功。
            return true;
        }
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result instanceof Number) {
            return ((Number) result).intValue() != 0;
        }
        return true;
    }

    private static void sealIfFrameworkMissing(ClassNotFoundException e) {
        // 框架类本身缺失（极端裁剪 ROM）才封存；普通调用失败留待下次重试。
        sSealed = true;
    }
}
