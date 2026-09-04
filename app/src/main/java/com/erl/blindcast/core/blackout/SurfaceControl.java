package com.erl.blindcast.core.blackout;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * {@code android.view.SurfaceControl} 隐藏 API 反射封装（Android 9 ~ 13 分支）。
 *
 * <p>三分支算法（见 MVP.md 四(二)(1) · 物理熄屏）：
 * <ul>
 *   <li>Android 9（SDK 28）：{@code SurfaceControl.getBuiltInDisplay()} 直接取内置屏 token；</li>
 *   <li>Android 10 ~ 13（SDK 29 ~ 33）：{@code getPhysicalDisplayIds()} 枚举物理屏 ID，
 *       再经 {@code getPhysicalDisplayToken(long)} 取主屏（index 0）token；</li>
 *   <li>Android 14+（SDK 34+）：本类不再适用，请改用 {@link DisplayControl}。</li>
 * </ul>
 *
 * <p>取到 token 后统一经 {@code setDisplayPowerMode(IBinder, int)} 物理切断 / 恢复屏幕电源：
 * {@code POWER_MODE_OFF = 0} 熄屏，{@code POWER_MODE_NORMAL = 2} 点亮。
 *
 * <p><b>调用点约束（Shizuku / Root 提权进程调用点预留）：</b>本类所有方法必须在提权进程内执行——
 * Shizuku UserService（system_server 上下文）或以 Root 身份启动的 app_process 进程。
 * 普通 App 进程缺少签名级显示电源权限且受隐藏 API 访问限制，直接调用将抛出
 * {@link SecurityException} 或反射异常。Slice 2.1 仅做底层能力封装，不启动任何线程、
 * 不触碰 UI；喂狗保活与崩溃熔断在 Slice 2.2 实现（{@code UserActivityKeeper} /
 * {@code EmergencyRecovery}，届时在销毁路径强制回调点亮）。
 *
 * <p>Slice 2.1 · 纯反射无状态工具类（屏显状态由 {@code PowerController} 维护）。
 */
public final class SurfaceControl {

    /** 熄屏：物理切断屏幕电源（OLED / 背光断电，触控停止上报）。 */
    public static final int POWER_MODE_OFF = 0;
    /** Doze 模式（保留常量，本封装未使用）。 */
    public static final int POWER_MODE_DOZE = 1;
    /** 正常点亮：恢复屏幕电源。 */
    public static final int POWER_MODE_NORMAL = 2;

    private static final String SURFACE_CONTROL_CLASS_NAME = "android.view.SurfaceControl";
    private static final String METHOD_GET_BUILT_IN_DISPLAY = "getBuiltInDisplay";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_IDS = "getPhysicalDisplayIds";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_TOKEN = "getPhysicalDisplayToken";
    private static final String METHOD_SET_DISPLAY_POWER_MODE = "setDisplayPowerMode";

    private static volatile Class<?> sSurfaceControlClass;

    private SurfaceControl() {
        // no instances
    }

    /**
     * 懒加载 {@code android.view.SurfaceControl} 类对象。
     *
     * @throws ClassNotFoundException ROM 裁剪或类名变更时抛出
     */
    static Class<?> surfaceControlClass() throws ClassNotFoundException {
        Class<?> c = sSurfaceControlClass;
        if (c == null) {
            c = Class.forName(SURFACE_CONTROL_CLASS_NAME);
            sSurfaceControlClass = c;
        }
        return c;
    }

    private static Method hiddenStaticMethod(String name, Class<?>... parameterTypes) throws Exception {
        Method m = surfaceControlClass().getDeclaredMethod(name, parameterTypes);
        m.setAccessible(true);
        return m;
    }

    /**
     * Android 9 分支：取内置显示屏 token。
     *
     * @return 显示屏 Binder token（非 null）
     * @throws Exception 反射失败（含方法不存在、隐藏 API 拦截、无提权）时抛出
     */
    public static IBinder getBuiltInDisplay() throws Exception {
        Object token = hiddenStaticMethod(METHOD_GET_BUILT_IN_DISPLAY).invoke(null);
        if (token == null) {
            throw new IllegalStateException("SurfaceControl.getBuiltInDisplay() returned null");
        }
        return (IBinder) token;
    }

    /**
     * Android 10 ~ 13 分支：枚举物理显示屏 ID 数组。
     *
     * @return 物理屏 ID 数组（至少包含主屏）
     * @throws Exception 反射失败时抛出
     */
    public static long[] getPhysicalDisplayIds() throws Exception {
        Object ids = hiddenStaticMethod(METHOD_GET_PHYSICAL_DISPLAY_IDS).invoke(null);
        if (ids == null) {
            throw new IllegalStateException("SurfaceControl.getPhysicalDisplayIds() returned null");
        }
        return (long[]) ids;
    }

    /**
     * Android 10 ~ 13 分支：由物理屏 ID 取显示屏 token。
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
                    "SurfaceControl.getPhysicalDisplayToken(" + physicalDisplayId + ") returned null");
        }
        return (IBinder) token;
    }

    /**
     * 取默认（主）显示屏 token：优先 Android 10 ~ 13 物理屏路径，
     * 方法不存在时回退到 Android 9 内置屏路径。
     *
     * @return 主屏 Binder token（非 null）
     * @throws Exception 两条路径均失败时抛出
     */
    public static IBinder getDefaultDisplayToken() throws Exception {
        try {
            long[] ids = getPhysicalDisplayIds();
            if (ids.length > 0) {
                return getPhysicalDisplayToken(ids[0]);
            }
        } catch (NoSuchMethodException fallThrough) {
            // Android 9 设备无 getPhysicalDisplayIds，回退到 getBuiltInDisplay
        }
        return getBuiltInDisplay();
    }

    /**
     * 对指定显示屏 token 设置电源模式。
     *
     * @param displayToken 显示屏 Binder token（见各取 token 方法）
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return AOSP 实现返回 boolean；部分 ROM 为 void，此时无异常即视为成功返回 true
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
        // 某些 ROM 上该隐藏方法签名为 void：反射调用无异常即视为成功
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
