package com.erl.blindcast.core.blackout;

import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * {@code android.view.SurfaceControl} 隐藏 API 反射封装（Priv-Bridge-2 · 唯一物理熄屏底层；
 * Priv-Bridge-3 加全链路日志 + 调用实效核验）。
 *
 * <p>二分支算法（MVP.md 四(二)(1) · Priv-Bridge-2 修订：14+ 熄屏改走 SurfaceControl）：
 * <ul>
 *   <li>Android 9（SDK 28）：{@code SurfaceControl.getBuiltInDisplay()} 直接取内置屏 token；</li>
 *   <li>Android 10+（SDK 29+，含 14 / 15）：{@code getPhysicalDisplayIds()} 枚举物理屏 ID，
 *       再经 {@code getPhysicalDisplayToken(long)} 取主屏（index 0）token。</li>
 * </ul>
 *
 * <p>取到 token 后统一经 {@code setDisplayPowerMode(IBinder, int)} 物理切断 / 恢复屏幕电源。
 * 本地常量 {@link #POWER_MODE_OFF} / {@link #POWER_MODE_NORMAL} 每次调用前经反射与
 * {@code android.view.SurfaceControl} 类内真实常量值比对（OPlus 变体核验），不一致时记错
 * 并采用真实值，杜绝硬编码拍脑袋。
 *
 * <p>实证结论（OPlus Android 15 真机，Shizuku daemon root 运行）：
 * {@code com.android.server.display.DisplayControl} 的 JNI 实现只存在于 system_server 的
 * {@code libandroid_servers.so}，Shizuku {@code app_process} 内是空桩——14+ 走 DisplayControl
 * 此路不通。而 {@code SurfaceControl} 的 JNI 在 {@code libandroid_runtime}（所有进程有），
 * shell 身份可调（Harbour Duck 同款路线），故 10+ 含 14 / 15 统一走本类。
 *
 * <p>Priv-Bridge-3 排障结论（特权进程 status:0 干净退出但屏幕没黑、无 Toast）：
 * 调用曾返回 true（无异常）却无视觉效果，属静默 no-op。为此本类新增：
 * 对全部物理屏逐个设模式（不只 index 0，防主屏索引漂移）、调用后尽力读回显示状态
 * （能读则读，读不到只记日志不失败）、主模式返回 false 时依次尝试经反射取到的
 * 真实备用模式值（注释见 {@link #setDefaultDisplayPowerMode}）。
 *
 * <p>隐藏 API 豁免：本类经项目既有 {@link HiddenApiBypass} 方案放行
 * {@code Landroid/view/SurfaceControl}，每次反射前调用 {@code ensureHiddenApiExempted()}。
 *
 * <p><b>调用点约束（Shizuku / Root 提权进程调用点预留）：</b>本类所有方法必须在提权进程内执行——
 * Shizuku UserService（shell UID 2000 / root UID 0 的独立 app_process）或以 Root 身份启动的
 * app_process 进程。普通 App 进程缺少签名级显示电源权限，直接调用将抛出
 * {@link SecurityException} 或反射异常。Slice 2.1 仅做底层能力封装，不启动任何线程、
 * 不触碰 UI；喂狗保活与崩溃熔断在 Slice 2.2 实现（{@code UserActivityKeeper} /
 * {@code EmergencyRecovery}，届时在销毁路径强制回调点亮）。
 *
 * <p>日志约定：统一 TAG="BlindCast"，DEBUG/TRACE 级事实记录，各点位带线程序号，
 * 不打 Token 内容（只记 null 与否）与隐私；发布版保留（量极小，排障用）。
 */
public final class SurfaceControl {

    /** 全链路统一 TAG（与 PowerController / PrivilegedBridge / HomeViewModel 一致）。 */
    private static final String TAG = "BlindCast";

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

    private static String tid() {
        Thread t = Thread.currentThread();
        return "t=" + t.getId() + "(" + t.getName() + ")";
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
        ensureHiddenApiExempted();
        Method m = surfaceControlClass().getDeclaredMethod(name, parameterTypes);
        m.setAccessible(true);
        return m;
    }

    /**
     * 经项目既有 HiddenApiBypass 方案放行 {@code Landroid/view/SurfaceControl}。
     * 提权进程与普通进程均可调用（失败只吞不抛，留给后续反射异常如实上报）。
     */
    private static void ensureHiddenApiExempted() {
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/SurfaceControl");
        } catch (Throwable ignored) {
            // 豁免失败不掩盖主异常：后续反射抛出的才是可诊断的真实原因。
        }
    }

    /**
     * 反射枚举 {@code android.view.SurfaceControl} 类内全部 {@code POWER_MODE_*} 真实常量。
     * 禁止硬编码拍脑袋：备用模式值一律以此处读到的真实值为准（含 OPlus 变体）。
     *
     * @return 有序 map（name -&gt; value）；读失败返回空 map（调用方记错，不抛）。
     */
    public static Map<String, Integer> readRealPowerModeConstants() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            Class<?> c = surfaceControlClass();
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName();
                if (!n.startsWith("POWER_MODE_")) {
                    continue;
                }
                if (f.getType() != int.class && f.getType() != Integer.TYPE) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    int v = f.getInt(null);
                    out.put(n, v);
                } catch (Throwable perField) {
                    Log.e(TAG, "[SurfaceControl] " + tid()
                            + " read constant " + n + " failed", perField);
                }
            }
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " real POWER_MODE_*=" + out);
        } catch (Throwable t) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " readRealPowerModeConstants failed", t);
        }
        return out;
    }

    /**
     * 取单个真实常量值，失败回退本地值（记错，不抛）。
     *
     * @param name 真实字段名（如 POWER_MODE_OFF）
     * @param fallback 本地硬编码值
     * @return 真实值或 fallback
     */
    static int realConstantOrFallback(String name, int fallback) {
        try {
            Field f = surfaceControlClass().getDeclaredField(name);
            f.setAccessible(true);
            int v = f.getInt(null);
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " constant " + name + " real=" + v
                    + " local=" + fallback + " match=" + (v == fallback));
            return v;
        } catch (Throwable t) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " read constant " + name + " failed, use local=" + fallback, t);
            return fallback;
        }
    }

    /**
     * Android 9 分支：取内置显示屏 token。
     *
     * @return 显示屏 Binder token（非 null）
     * @throws Exception 反射失败（含方法不存在、隐藏 API 拦截、无提权）时抛出
     */
    public static IBinder getBuiltInDisplay() throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid() + " getBuiltInDisplay enter");
        try {
            Object token = hiddenStaticMethod(METHOD_GET_BUILT_IN_DISPLAY).invoke(null);
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " getBuiltInDisplay tokenNull=" + (token == null));
            if (token == null) {
                throw new IllegalStateException("SurfaceControl.getBuiltInDisplay() returned null");
            }
            return (IBinder) token;
        } catch (Exception e) {
            Log.e(TAG, "[SurfaceControl] " + tid() + " getBuiltInDisplay failed", e);
            throw e;
        }
    }

    /**
     * Android 10+ 分支（含 14 / 15）：枚举物理显示屏 ID 数组。
     *
     * @return 物理屏 ID 数组（至少包含主屏）
     * @throws Exception 反射失败时抛出
     */
    public static long[] getPhysicalDisplayIds() throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid() + " getPhysicalDisplayIds enter");
        try {
            Object ids = hiddenStaticMethod(METHOD_GET_PHYSICAL_DISPLAY_IDS).invoke(null);
            if (ids == null) {
                Log.e(TAG, "[SurfaceControl] " + tid()
                        + " getPhysicalDisplayIds returned null");
                throw new IllegalStateException("SurfaceControl.getPhysicalDisplayIds() returned null");
            }
            long[] arr = (long[]) ids;
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " displayIds=" + Arrays.toString(arr) + " len=" + arr.length);
            return arr;
        } catch (Exception e) {
            Log.e(TAG, "[SurfaceControl] " + tid() + " getPhysicalDisplayIds failed", e);
            throw e;
        }
    }

    /**
     * Android 10+ 分支（含 14 / 15）：由物理屏 ID 取显示屏 token。
     *
     * @param physicalDisplayId {@link #getPhysicalDisplayIds()} 返回的 ID 之一
     * @return 显示屏 Binder token（非 null）
     * @throws Exception 反射失败时抛出
     */
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid()
                + " getPhysicalDisplayToken enter id=" + physicalDisplayId);
        try {
            Object token = hiddenStaticMethod(
                    METHOD_GET_PHYSICAL_DISPLAY_TOKEN, long.class)
                    .invoke(null, physicalDisplayId);
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " getPhysicalDisplayToken id=" + physicalDisplayId
                    + " tokenNull=" + (token == null));
            if (token == null) {
                throw new IllegalStateException(
                        "SurfaceControl.getPhysicalDisplayToken(" + physicalDisplayId + ") returned null");
            }
            return (IBinder) token;
        } catch (Exception e) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " getPhysicalDisplayToken id=" + physicalDisplayId + " failed", e);
            throw e;
        }
    }

    /**
     * 取默认（主）显示屏 token：优先 Android 10+ 物理屏路径（含 14 / 15），
     * 方法不存在时回退到 Android 9 内置屏路径。
     *
     * @return 主屏 Binder token（非 null）
     * @throws Exception 两条路径均失败时抛出
     */
    public static IBinder getDefaultDisplayToken() throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid() + " getDefaultDisplayToken enter");
        try {
            long[] ids = getPhysicalDisplayIds();
            if (ids.length > 0) {
                Log.d(TAG, "[SurfaceControl] " + tid()
                        + " getDefaultDisplayToken pick ids[0]=" + ids[0]);
                return getPhysicalDisplayToken(ids[0]);
            }
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " getPhysicalDisplayIds empty, fallback getBuiltInDisplay");
        } catch (NoSuchMethodException fallThrough) {
            // Android 9 设备无 getPhysicalDisplayIds，回退到 getBuiltInDisplay
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " no getPhysicalDisplayIds (SDK28?), fallback getBuiltInDisplay");
        }
        IBinder token = getBuiltInDisplay();
        Log.d(TAG, "[SurfaceControl] " + tid()
                + " getDefaultDisplayToken fallback tokenNull=" + (token == null));
        return token;
    }

    /**
     * 取全部物理屏 token（Priv-Bridge-3：逐屏设模式，防主屏索引漂移）。
     * 10+ 路径无方法时回退单内置屏；空数组记错抛异常（上游记 false）。
     *
     * @return 非空 token 列表（顺序与 displayIds 一致）
     * @throws Exception 全部失败时抛出
     */
    static List<IBinder> getAllDisplayTokens() throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid() + " getAllDisplayTokens enter");
        try {
            long[] ids = getPhysicalDisplayIds();
            if (ids.length == 0) {
                Log.e(TAG, "[SurfaceControl] " + tid()
                        + " getAllDisplayTokens ids empty");
                throw new IllegalStateException(
                        "SurfaceControl.getPhysicalDisplayIds() is empty");
            }
            List<IBinder> out = new ArrayList<>(ids.length);
            for (long id : ids) {
                try {
                    out.add(getPhysicalDisplayToken(id));
                } catch (Exception perDisplay) {
                    // 单屏 token 失败只记错，继续其余屏（至少一屏成功即有意义）。
                    Log.e(TAG, "[SurfaceControl] " + tid()
                            + " getAllDisplayTokens id=" + id + " token failed", perDisplay);
                }
            }
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " getAllDisplayTokens ids=" + Arrays.toString(ids)
                    + " okCount=" + out.size());
            if (out.isEmpty()) {
                throw new IllegalStateException("all display tokens null/failed");
            }
            return out;
        } catch (NoSuchMethodException fallThrough) {
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " getAllDisplayTokens no ids method, fallback built-in");
            List<IBinder> single = new ArrayList<>(1);
            single.add(getBuiltInDisplay());
            return single;
        }
    }

    /**
     * 对指定显示屏 token 设置电源模式。
     *
     * @param displayToken 显示屏 Binder token（见各取 token 方法；null 记错抛异常，上游返 false）
     * @param mode 熄屏 / 点亮模式值（调用方应传经反射核验后的真实值）
     * @return AOSP 实现返回 boolean；部分 ROM 为 void，此时无异常即视为成功返回 true
     * @throws Exception 反射失败或系统拒绝（无提权）时抛出
     */
    public static boolean setDisplayPowerMode(IBinder displayToken, int mode) throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid()
                + " setDisplayPowerMode enter mode=" + mode
                + " tokenNull=" + (displayToken == null));
        if (displayToken == null) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " setDisplayPowerMode token==null mode=" + mode);
            throw new IllegalArgumentException("displayToken == null");
        }
        try {
            Object result = hiddenStaticMethod(
                    METHOD_SET_DISPLAY_POWER_MODE, IBinder.class, int.class)
                    .invoke(null, displayToken, mode);
            boolean ok;
            if (result instanceof Boolean) {
                ok = (Boolean) result;
            } else {
                // 某些 ROM 上该隐藏方法签名为 void：反射调用无异常即视为成功
                ok = true;
            }
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " setDisplayPowerMode mode=" + mode
                    + " resultType=" + (result == null ? "void/null" : result.getClass().getSimpleName())
                    + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " setDisplayPowerMode mode=" + mode + " failed", e);
            throw e;
        }
    }

    /**
     * 尽力读回显示状态（能读则读，读不到返回 null，只记日志不抛）。
     * 依次尝试隐藏读方法（各 ROM 方法名/签名可能不同），命中即调：
     * getDisplayState(IBinder) / getDisplayPowerMode(IBinder) / getPowerMode(IBinder)。
     * 只记结果 toString，不记 token 内容。
     */
    static String tryReadBackDisplayState(IBinder token) {
        if (token == null) {
            Log.d(TAG, "[SurfaceControl] " + tid() + " readBack skip tokenNull");
            return null;
        }
        String[] candidates = {"getDisplayState", "getDisplayPowerMode", "getPowerMode"};
        for (String name : candidates) {
            try {
                ensureHiddenApiExempted();
                Method m;
                try {
                    m = surfaceControlClass().getDeclaredMethod(name, IBinder.class);
                } catch (NoSuchMethodException nsme) {
                    Log.d(TAG, "[SurfaceControl] " + tid()
                            + " readBack no method " + name);
                    continue;
                }
                m.setAccessible(true);
                Object state = m.invoke(null, token);
                String s = String.valueOf(state);
                Log.d(TAG, "[SurfaceControl] " + tid()
                        + " readBack via " + name + " state=" + s);
                return name + "=" + s;
            } catch (Throwable t) {
                Log.e(TAG, "[SurfaceControl] " + tid()
                        + " readBack via " + name + " failed", t);
            }
        }
        Log.d(TAG, "[SurfaceControl] " + tid() + " readBack unavailable");
        return null;
    }

    /**
     * 对主显示屏设置电源模式（取 token + 设模式一次完成，Priv-Bridge-3 实效核验版）。
     *
     * <p>流程：
     * 1. 反射核验 {@code POWER_MODE_OFF}/{@code POWER_MODE_NORMAL} 真实值
     *    （OPlus 变体一致性；不一致记错并采用真实值）；
     * 2. 枚举全部物理屏 token，逐屏设模式并记每屏 mode/ok/异常全文
     *    （不只 index 0；单屏失败继续其余屏）；
     * 3. 每屏调用后尽力读回显示状态（能读则读）；
     * 4. 若主模式全屏失败（返回 false 全灭），依次尝试经
     *    {@link #readRealPowerModeConstants} 取到的真实备用模式值——
     *    熄屏请求排除 NORMAL 类点亮值、点亮请求只试 NORMAL/ON 类值，
     *    候选排序按值升序，逐个全屏重试并记日志。
     *    备用值全部来自反射，禁止硬编码拍脑袋。
     *
     * @param mode {@link #POWER_MODE_OFF} 熄屏 / {@link #POWER_MODE_NORMAL} 点亮
     * @return 任意一屏成功即 true；全失败返回 false
     * @throws Exception 取 token 全失败等致命异常时抛出（上游返 false）
     */
    public static boolean setDefaultDisplayPowerMode(int mode) throws Exception {
        Log.d(TAG, "[SurfaceControl] " + tid()
                + " setDefaultDisplayPowerMode enter mode=" + mode);
        // 1. 常量核验：以真实值为准。
        int realOff = realConstantOrFallback("POWER_MODE_OFF", POWER_MODE_OFF);
        int realNormal = realConstantOrFallback("POWER_MODE_NORMAL", POWER_MODE_NORMAL);
        int effectiveMode = mode;
        if (mode == POWER_MODE_OFF && realOff != mode) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " OFF mismatch local=" + mode + " real=" + realOff + ", use real");
            effectiveMode = realOff;
        } else if (mode == POWER_MODE_NORMAL && realNormal != mode) {
            Log.e(TAG, "[SurfaceControl] " + tid()
                    + " NORMAL mismatch local=" + mode + " real=" + realNormal + ", use real");
            effectiveMode = realNormal;
        }
        Map<String, Integer> realMap = readRealPowerModeConstants();

        // 2. 逐屏设主模式。
        List<IBinder> tokens = getAllDisplayTokens();
        boolean anyOk = false;
        int idx = 0;
        for (IBinder token : tokens) {
            try {
                boolean ok = setDisplayPowerMode(token, effectiveMode);
                String readBack = tryReadBackDisplayState(token);
                Log.d(TAG, "[SurfaceControl] " + tid()
                        + " display[" + idx + "] mode=" + effectiveMode
                        + " ok=" + ok + " readBack=" + readBack);
                anyOk = anyOk || ok;
            } catch (Exception perDisplay) {
                Log.e(TAG, "[SurfaceControl] " + tid()
                        + " display[" + idx + "] mode=" + effectiveMode + " failed", perDisplay);
            }
            idx++;
        }
        if (anyOk) {
            Log.d(TAG, "[SurfaceControl] " + tid()
                    + " setDefaultDisplayPowerMode primary ok mode=" + effectiveMode);
            return true;
        }

        // 4. 主模式全灭：依次尝试真实备用模式值。
        // 熄屏（OFF 请求）：排除点亮类值（NORMAL 及含 ON/NORMAL 名的），其余按值升序试；
        // 点亮（NORMAL 请求）：只试点亮类值（NORMAL 及含 ON/NORMAL 名的，排除主模式自身）。
        boolean wantOff = (effectiveMode == realOff);
        List<Map.Entry<String, Integer>> fallbacks = new ArrayList<>();
        for (Map.Entry<String, Integer> e : realMap.entrySet()) {
            int v = e.getValue();
            if (v == effectiveMode) {
                continue;
            }
            String n = e.getKey().toUpperCase();
            boolean looksOn = n.contains("NORMAL") || (n.contains("ON") && !n.contains("DOZE"));
            if (wantOff && looksOn) {
                continue;
            }
            if (!wantOff && !looksOn) {
                continue;
            }
            fallbacks.add(e);
        }
        fallbacks.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        Log.e(TAG, "[SurfaceControl] " + tid()
                + " primary mode=" + effectiveMode + " all displays failed"
                + ", fallback candidates=" + fallbacks);
        for (Map.Entry<String, Integer> fb : fallbacks) {
            int fbMode = fb.getValue();
            boolean fbOk = false;
            int i = 0;
            for (IBinder token : tokens) {
                try {
                    boolean ok = setDisplayPowerMode(token, fbMode);
                    String readBack = tryReadBackDisplayState(token);
                    Log.d(TAG, "[SurfaceControl] " + tid()
                            + " fallback " + fb.getKey() + "=" + fbMode
                            + " display[" + i + "] ok=" + ok + " readBack=" + readBack);
                    fbOk = fbOk || ok;
                } catch (Exception perDisplay) {
                    Log.e(TAG, "[SurfaceControl] " + tid()
                            + " fallback " + fb.getKey() + "=" + fbMode
                            + " display[" + i + "] failed", perDisplay);
                }
                i++;
            }
            if (fbOk) {
                Log.d(TAG, "[SurfaceControl] " + tid()
                        + " fallback " + fb.getKey() + "=" + fbMode + " ok");
                return true;
            }
        }
        Log.e(TAG, "[SurfaceControl] " + tid()
                + " setDefaultDisplayPowerMode all modes failed primary=" + effectiveMode);
        return false;
    }
}
