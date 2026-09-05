package com.erl.blindcast.core.blackout;

// 机制参考 Aliothmoon/MAA-Meow (AGPL-3.0)：仅借鉴“SYSTEMSERVERCLASSPATH 载类 +
// Runtime.loadLibrary0 预载 android_servers.so + 14+ 混合路由”通用机制自行实现，
// 未复制其源文件与大段代码（同类手法 scrcpy/Apache-2.0 亦有实现）。

import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;

/**
 * Android 14+（SDK 34+）显示电源控制反射封装 —— Priv-Bridge-5 混合路由 fallback 分支。
 *
 * <p>前情（OPlus Android 15 真机实证，Shizuku daemon root 运行已授权）：
 * {@code com.android.server.display.DisplayControl} 的 JNI 实现只存在于 system_server 的
 * {@code libandroid_servers.so}，Shizuku {@code app_process} 里默认是空桩，直接调
 * {@code nativeGetPhysicalDisplayIds()} 会抛
 * {@code No implementation found ... is the library loaded?}。
 * Priv-Bridge-2 因此改道 {@link SurfaceControl}（JNI 在 {@code libandroid_runtime}，
 * 所有进程有，shell 身份可调）。
 *
 * <p>Priv-Bridge-5 补全（机制参考 Aliothmoon/MAA-Meow (AGPL-3.0)，自行实现）：
 * 经 {@code SYSTEMSERVERCLASSPATH} 类加载器载入系统服务侧 {@code DisplayControl} 类后，
 * 再经 {@code Runtime.loadLibrary0(Class, "android_servers")} 反射把
 * {@code libandroid_servers.so} 载入当前（特权）进程，JNI 桩即补齐，此路恢复可用。
 * 全程 try/catch 记 BlindCast 日志，失败不抛（留到方法调用时再错）。
 * 同类 native 预载手法在 scrcpy（Apache-2.0）中亦有实现，属通用 Android 技术。
 *
 * <p>混合路由（见 {@code PowerController}，Priv-Bridge-6 修订）：SDK&gt;=34 时先特征探测
 * {@code SurfaceControl} 是否有 {@code getPhysicalDisplayIds} 方法，有则走
 * {@link SurfaceControl} 全链路（取 token + 设值），无则走本类
 * {@code getPhysicalDisplayIds / getPhysicalDisplayToken} 只取 token，
 * 拿到 token 后一律调 {@link SurfaceControl#setDisplayPowerMode} 设值
 * （OPlus Android 15 真机实证：本机 {@code DisplayControl} 根本没有
 * {@code setDisplayPowerMode(IBinder,int)} 方法，MAA-Meow 同样只有取 token 两方法，
 * 设值一定走 {@code SurfaceControl}）；29~33 维持 {@link SurfaceControl}；28 及以下维持
 * {@code getBuiltInDisplay} 分支。POWER_MODE_OFF=0 / NORMAL=2 与 SurfaceControl 一致。
 *
 * <p>调用点约束：必须在提权进程内执行（Shizuku UserService / Root app_process），
 * 普通 App 进程环境变量不可见，直接抛 {@link IllegalStateException}。
 */
public final class DisplayControl {

    /** 全链路统一 TAG（与 SurfaceControl / PowerController 一致）。 */
    private static final String TAG = "BlindCast";

    /** 熄屏：物理切断屏幕电源（OLED / 背光断电，触控停止上报）。 */
    public static final int POWER_MODE_OFF = 0;
    /** Doze 模式（保留常量，本封装未使用）。 */
    public static final int POWER_MODE_DOZE = 1;
    /** 正常点亮：恢复屏幕电源。 */
    public static final int POWER_MODE_NORMAL = 2;

    private static final String DISPLAY_CONTROL_CLASS_NAME =
            "com.android.server.display.DisplayControl";
    private static final String SYSTEM_SERVER_CLASSPATH_ENV = "SYSTEMSERVERCLASSPATH";
    private static final String CLASS_LOADER_FACTORY_NAME =
            "com.android.internal.os.ClassLoaderFactory";
    private static final String CREATE_CLASS_LOADER = "createClassLoader";
    private static final String LIB_ANDROID_SERVERS = "android_servers";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_IDS = "getPhysicalDisplayIds";
    private static final String METHOD_GET_PHYSICAL_DISPLAY_TOKEN = "getPhysicalDisplayToken";

    private static volatile Class<?> sDisplayControlClass;
    private static volatile boolean sNativeLoaded;

    private DisplayControl() {
        // no instances
    }

    private static String tid() {
        Thread t = Thread.currentThread();
        return "t=" + t.getId() + "(" + t.getName() + ")";
    }

    /**
     * 用系统服务 classpath 构造类加载器：优先尝试隐藏的 ClassLoaderFactory，
     * 无此方法或反射失败则回退到既有的 PathClassLoader（原有链路保留）。
     */
    private static ClassLoader newSystemServerClassLoader(String classPath, ClassLoader parent) {
        try {
            Class<?> factory = Class.forName(CLASS_LOADER_FACTORY_NAME);
            // 7 参重载（AOSP 10~15 常见）：(dexPath, libPath, permittedPath, parent, sdk, shared, name)。
            try {
                Method m7 = factory.getDeclaredMethod(CREATE_CLASS_LOADER,
                        String.class, String.class, String.class, ClassLoader.class,
                        int.class, boolean.class, String.class);
                m7.setAccessible(true);
                Object loader = m7.invoke(null, classPath, null, null, parent,
                        Build.VERSION.SDK_INT, true, "BlindCast:SystemServer");
                if (loader instanceof ClassLoader) {
                    Log.d(TAG, "[DisplayControl] " + tid()
                            + " ClassLoaderFactory(7-arg) ok");
                    return (ClassLoader) loader;
                }
            } catch (NoSuchMethodException noM7) {
                Log.d(TAG, "[DisplayControl] " + tid()
                        + " no 7-arg createClassLoader, try 4-arg");
            }
            // 4 参变体重载：(dexPath, libPath, parent, name)。
            try {
                Method m4 = factory.getDeclaredMethod(CREATE_CLASS_LOADER,
                        String.class, String.class, ClassLoader.class, String.class);
                m4.setAccessible(true);
                Object loader = m4.invoke(null, classPath, null, parent,
                        "BlindCast:SystemServer");
                if (loader instanceof ClassLoader) {
                    Log.d(TAG, "[DisplayControl] " + tid()
                            + " ClassLoaderFactory(4-arg) ok");
                    return (ClassLoader) loader;
                }
            } catch (NoSuchMethodException noM4) {
                Log.d(TAG, "[DisplayControl] " + tid()
                        + " no 4-arg createClassLoader, fallback PathClassLoader");
            }
        } catch (Throwable t) {
            Log.e(TAG, "[DisplayControl] " + tid()
                    + " ClassLoaderFactory probe failed, fallback PathClassLoader", t);
        }
        Log.d(TAG, "[DisplayControl] " + tid() + " use PathClassLoader fallback");
        return new PathClassLoader(classPath, parent);
    }

    /**
     * 把 android_servers.so 载入当前进程（补 JNI 桩）。
     * 经 Runtime.loadLibrary0(Class, String) 反射实现；失败只记日志不抛，
     * 留到后续 native 方法调用时再错。
     */
    private static void ensureAndroidServersLoaded(Class<?> displayControlClass) {
        if (sNativeLoaded) {
            return;
        }
        synchronized (DisplayControl.class) {
            if (sNativeLoaded) {
                return;
            }
            try {
                Method loadLibrary0 = Runtime.class.getDeclaredMethod(
                        "loadLibrary0", Class.class, String.class);
                loadLibrary0.setAccessible(true);
                loadLibrary0.invoke(Runtime.getRuntime(), displayControlClass, LIB_ANDROID_SERVERS);
                sNativeLoaded = true;
                Log.d(TAG, "[DisplayControl] " + tid()
                        + " loadLibrary0 android_servers ok");
            } catch (Throwable t) {
                Log.e(TAG, "[DisplayControl] " + tid()
                        + " loadLibrary0 android_servers failed (deferred to call time)", t);
            }
        }
    }

    /**
     * 经 {@code SYSTEMSERVERCLASSPATH} 懒加载系统服务侧 {@code DisplayControl} 类对象，
     * 载类成功后立即预载 {@code android_servers.so}（静态初始化补全）。
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
                    ClassLoader loader = newSystemServerClassLoader(classPath, parent);
                    c = Class.forName(DISPLAY_CONTROL_CLASS_NAME, false, loader);
                    sDisplayControlClass = c;
                    Log.d(TAG, "[DisplayControl] " + tid()
                            + " load class ok " + DISPLAY_CONTROL_CLASS_NAME);
                    ensureAndroidServersLoaded(c);
                }
            }
        }
        if (c != null) {
            ensureAndroidServersLoaded(c);
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
        Log.d(TAG, "[DisplayControl] " + tid() + " getPhysicalDisplayIds enter");
        try {
            Object ids = hiddenStaticMethod(METHOD_GET_PHYSICAL_DISPLAY_IDS).invoke(null);
            if (ids == null) {
                throw new IllegalStateException("DisplayControl.getPhysicalDisplayIds() returned null");
            }
            long[] arr = (long[]) ids;
            Log.d(TAG, "[DisplayControl] " + tid()
                    + " getPhysicalDisplayIds len=" + arr.length);
            return arr;
        } catch (Exception e) {
            Log.e(TAG, "[DisplayControl] " + tid() + " getPhysicalDisplayIds failed", e);
            throw e;
        }
    }

    /**
     * 由物理屏 ID 取显示屏 token。
     *
     * @param physicalDisplayId {@link #getPhysicalDisplayIds()} 返回的 ID 之一
     * @return 显示屏 Binder token（非 null）
     * @throws Exception 反射失败时抛出
     */
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) throws Exception {
        Log.d(TAG, "[DisplayControl] " + tid()
                + " getPhysicalDisplayToken enter id=" + physicalDisplayId);
        try {
            Object token = hiddenStaticMethod(
                    METHOD_GET_PHYSICAL_DISPLAY_TOKEN, long.class)
                    .invoke(null, physicalDisplayId);
            Log.d(TAG, "[DisplayControl] " + tid()
                    + " getPhysicalDisplayToken id=" + physicalDisplayId
                    + " tokenNull=" + (token == null));
            if (token == null) {
                throw new IllegalStateException(
                        "DisplayControl.getPhysicalDisplayToken(" + physicalDisplayId + ") returned null");
            }
            return (IBinder) token;
        } catch (Exception e) {
            Log.e(TAG, "[DisplayControl] " + tid()
                    + " getPhysicalDisplayToken id=" + physicalDisplayId + " failed", e);
            throw e;
        }
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
     * <p>Priv-Bridge-6 语义修正（OPlus Android 15 真机实证）：
     * 本机 {@code com.android.server.display.DisplayControl} 根本没有
     * {@code setDisplayPowerMode(IBinder,int)} 方法（{@code NoSuchMethodException}），
     * MAA-Meow 的 DisplayControl 同样只有取 token 两方法——设值一定走
     * {@link SurfaceControl#setDisplayPowerMode}。本方法仅为防他处引用保留，
     * 内部直接转调 SurfaceControl 版，不再反射本类。
     *
     * @param displayToken 显示屏 Binder token（见各取 token 方法）
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return 系统服务返回 boolean；签名为 void 的实现上无异常即视为成功返回 true
     * @throws Exception 反射失败或系统拒绝（无提权）时抛出
     */
    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) throws Exception {
        Log.d(TAG, "[DisplayControl] " + tid()
                + " setDisplayPowerMode enter mode=" + mode
                + " tokenNull=" + (displayToken == null)
                + " (delegate SurfaceControl, Priv-Bridge-6)");
        // 本类无此方法：直接转调 SurfaceControl 设值（取 token 失败与设值失败由被调方日志区分）。
        return SurfaceControl.setDisplayPowerMode(displayToken, mode);
    }

    /**
     * 对主显示屏设置电源模式（取 token + 设模式一次完成）。
     *
     * <p>Priv-Bridge-6：token 经本类 {@link #getDefaultDisplayToken()} 获取，
     * 设值一律经 {@link SurfaceControl#setDisplayPowerMode}。
     *
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return 是否成功（底层返回 false 时为 false）
     * @throws Exception 取 token 或设模式失败时抛出
     */
    public static boolean setDefaultDisplayPowerMode(int mode) throws Exception {
        Log.d(TAG, "[DisplayControl] " + tid()
                + " setDefaultDisplayPowerMode enter mode=" + mode
                + " (token=DisplayControl, set=SurfaceControl)");
        IBinder token = getDefaultDisplayToken();
        boolean ok = SurfaceControl.setDisplayPowerMode(token, mode);
        Log.d(TAG, "[DisplayControl] " + tid()
                + " setDefaultDisplayPowerMode mode=" + mode + " ok=" + ok);
        return ok;
    }
}
