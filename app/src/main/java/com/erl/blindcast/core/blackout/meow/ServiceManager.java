// Ported from Aliothmoon/MAA-Meow (AGPL-3.0, main 2026-09-05)，逻辑未改，仅包名/日志/按键注入适配。
// 上游原文：app/src/main/java/com/aliothmoon/maameow/third/wrappers/ServiceManager.java
// 适配：只取 getPowerManager/getWindowManager 相关（含 getService  helper），原样搬运所需方法；
// 包名 com.aliothmoon.maameow→com.erl.blindcast；不移植 Display/Input/StatusBar/Activity/Camera 分支与 FakeContext 依赖。
package com.erl.blindcast.core.blackout.meow;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class ServiceManager {

    private static final Method GET_SERVICE_METHOD;
    private static WindowManager windowManager;
    private static PowerManager powerManager;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ServiceManager() {
        /* not instantiable */
    }

    static IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = WindowManager.create();
        }
        return windowManager;
    }

    public static PowerManager getPowerManager() {
        if (powerManager == null) {
            powerManager = PowerManager.create();
        }
        return powerManager;
    }
}
