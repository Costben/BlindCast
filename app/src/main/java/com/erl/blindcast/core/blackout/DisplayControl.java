package com.erl.blindcast.core.blackout;

import android.os.IBinder;

import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

/**
 * Android 14+（SDK 34+）显示电源控制反射封装。
 *
 * <p>三分支算法（见 MVP.md 四(二)(1) · 物理熄屏）：Android 14 起
 * {@code android.view.SurfaceControl} 的显示电源隐藏方法不可用，改为经
 * {@code SYSTEMSERVERCLASSPATH} 类路径反射加载系统服务侧的
 * {@code com.android.server.display.DisplayControl}，再调用其
 * {@code getPhysicalDisplayIds() / getPhysicalDisplayToken(long) /
 * setDisplayPowerMode(IBinder, int)} 静态方法。电源模式语义与
 * {@link SurfaceControl} 一致：{@code POWER_MODE_OFF = 0} 熄屏，
 * {@code POWER_MODE_NORMAL = 2} 点亮。
 *
 * <p><b>调用点约束（Shizuku / Root 提权进程调用点预留）：</b>本类所有方法必须在提权进程内执行——
 * Shizuku UserService（system_server 上下文）或以 Root 身份启动的 app_process 进程。
 * 普通 App 进程内 {@code SYSTEMSERVERCLASSPATH} 环境变量通常不可见，且缺少签名级权限，
 * 调用将抛出 {@link IllegalStateException} / {@link SecurityException}。
 * Slice 2.1 仅做底层能力封装，不启动任何线程、不触碰 UI；喂狗保活与崩溃熔断在
 * Slice 2.2 实现（{@code UserActivityKeeper} / {@code EmergencyRecovery}，
 * 届时在销毁路径强制回调点亮）。
 *
 * <p>Slice 2.1 · 纯反射无状态工具类（屏显状态由 {@code PowerController} 维护）。
 * 仅允许在 {@code SDK_INT >= 34} 的设备上调用，低版本请使用 {@link SurfaceControl}。
 */
public final class DisplayControl {

    /** 熄屏：物理切断屏幕电源（OLED / 背光断电，触控停止上报）。 */
    public static final int POWER_MODE_OFF = 0;
    /** Doze 模式（保留常量，本封装未使用）。 */
    public static final int POWER_MODE_DOZE = 1;
    /** 正常点亮：恢复屏幕电源。 */
    public static final int POWER_MODE_NORMAL = 2;

    private static final String DISPLAY_CONTROL_CLASS_NAME =
            "com.android.server.display.DisplayControl";
    private static final String SYSTEM_SERVER_CLASSPATH_ENV = "SYSTEMSERVERCLASSPATH";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_IDS = "getPhysicalDisplayIds";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_TOKEN = "getPhysicalDisplayToken";
    private static final String METHOD_SET_DISPLAY_POWER_MODE = "setDisplayPowerMode";

    private static volatile Class<?> sDisplayControlClass;

    private DisplayControl() {
        // no instances
    }

    /**
     * 经 {@code SYSTEMSERVERCLASSPATH} 懒加载系统服务侧 {@code DisplayControl} 类对象。
     *
     * @throws IllegalStateException 非提权进程（环境变量不可见）时抛出
     * @throws ClassNotFoundException 类加载失败时抛出
     */
    static Class<?> displayControlClass() throws Exception {
        Class<?> c = sDisplayControlClass;
        if (c == null) {
            synchronized (DisplayControl.class) {
                c = sDisplayControlClass;
                if (c == null) {
                    String classPath = System.getenv(SYSTEM_SERVER_CLASSPATH_ENV);
                    if (classPath == null || classPath.isEmpty()) {
                        throw new IllegalStateException(
                                "SYSTEMSERVERCLASSPATH is not visible: "
                                        + "DisplayControl must be called inside a privileged process "
                                        + "(Shizuku UserService or root app_process), "
                                        + "not a plain app process");
                    }
                    ClassLoader parent = DisplayControl.class.getClassLoader();
                    PathClassLoader loader = new PathClassLoader(classPath, parent);
                    c = Class.forName(DISPLAY_CONTROL_CLASS_NAME, false, loader);
                    sDisplayControlClass = c;
                }
            }
        }
        return c;
    }

    private static Method hiddenStaticMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method m = displayControlClass().getDeclaredMethod(name, parameterTypes);
        m.setAccessible(true);
        return m;
    }

    /**
     * 枚举物理显示屏 ID 数组。
     *
     * @return 物理屏 ID 数组（至少包含主屏）
     * @throws Exception 反射失败时抛出
     */
    public static long[] getPhysicalDisplayIds() throws Exception {
        Object ids = hiddenStaticMethod(METHOD_GET_PHYSICAL_DISPLAY_IDS).invoke(null);
        if (ids == null) {
            throw new IllegalStateException("DisplayControl.getPhysicalDisplayIds() returned null");
        }
        return (long[]) ids;
    }

    /**
     * 由物理屏 ID 取显示屏 token。
     *
     * @param physicalDisplayId {@link #getPhysicalDisplayIds()} 返回的 ID 之一
     * @return 显示屏 Binder token（非 null）
     * @throws Exception 反射失败时抛出
     */
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) throws Exception {
        Object token = hiddenStaticMethod(
                METHOD_GET_PHYSICAL_DISPLAY_TOKEN, long.class)
                .invoke(null, physicalDisplayId);
        if (token == null) {
            throw new IllegalStateException(
                    "DisplayControl.getPhysicalDisplayToken(" + physicalDisplayId + ") returned null");
        }
        return (IBinder) token;
    }

    /**
     * 取默认（主）显示屏 token（物理屏数组 index 0）。
     *
     * @return 主屏 Binder token（非 null）
     * @throws Exception 反射失败或数组为空时抛出
     */
    public static IBinder getDefaultDisplayToken() throws Exception {
        long[] ids = getPhysicalDisplayIds();
        if (ids.length == 0) {
            throw new IllegalStateException("DisplayControl.getPhysicalDisplayIds() is empty");
        }
        return getPhysicalDisplayToken(ids[0]);
    }

    /**
     * 对指定显示屏 token 设置电源模式。
     *
     * @param displayToken 显示屏 Binder token（见各取 token 方法）
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return 系统服务返回 boolean；签名为 void 的实现上无异常即视为成功返回 true
     * @throws Exception 反射失败或系统拒绝（无提权）时抛出
     */
    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) throws Exception {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken == null");
        }
        Object result = hiddenStaticMethod(
                METHOD_SET_DISPLAY_POWER_MODE, IBinder.class, int.class)
                .invoke(null, displayToken, mode);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        // 服务侧签名为 void 时：反射调用无异常即视为成功
        return true;
    }

    /**
     * 对主显示屏设置电源模式（取 token + 设模式一次完成）。
     *
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return 是否成功（底层返回 false 时为 false）
     * @throws Exception 取 token 或设模式失败时抛出
     */
    public static boolean setDefaultDisplayPowerMode(int mode) throws Exception {
        return setDisplayPowerMode(getDefaultDisplayToken(), mode);
    }
}
