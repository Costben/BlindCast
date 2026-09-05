package com.erl.blindcast.core.priv

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import com.erl.blindcast.core.blackout.PowerController

/**
 * Shizuku UserService 通道服务端（Priv-Bridge-1 通道，Priv-Bridge-2 改道 SurfaceControl）。
 *
 * 运行身份：本类实例由 Shizuku server（或 Sui）在独立 `app_process` 中实例化，
 * 以 root（UID 0）或 shell（UID 2000，adb 启动的 Shizuku）身份运行，因此可直接调用
 * [PowerController.setDisplayPower]（Priv-Bridge-2 起直调改道后的 SurfaceControl 版：
 * SDK 28 走 getBuiltInDisplay，SDK 29+ 含 14/15 统一走 getPhysicalDisplayIds/
 * getPhysicalDisplayToken/setDisplayPowerMode，全部 android.view.SurfaceControl 反射，
 * JNI 在 libandroid_runtime，shell 身份可调；DisplayControl 已废弃不用）。
 * 普通 App 进程调同样代码必吃 SecurityException，见实证诊断。
 *
 * 范式说明（遵循 Shizuku-API demo）：
 * - 直接继承 AIDL 生成的 [IPrivilegedOps.Stub]（13.x 无 `UserService` 基类，
 *   “UserService 通道”即指此类 + [PrivilegedBridge] 的 bind/unbind 封装）。
 * - 无需在 Manifest 中声明 `<service>`：Shizuku server 按 [PrivilegedBridge.userServiceArgs]
 *   中的 ComponentName 反射实例化本类。
 * - 构造器：保留无参构造（老版本 Shizuku 用）与 `@Keep (Context)` 构造（Shizuku v13
 *   优先尝试，Context 由 `createPackageContextAsUser` 创建，仅部分 API 可用——
 *   本实现不依赖该 Context，仅存引用备查）。
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
     *   此处不使用，仅保留签名。
     */
    @Keep
    constructor(context: Context) : super()

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
     * @param on true = 点亮（POWER_MODE_NORMAL），false = 物理熄屏（POWER_MODE_OFF）。
     * @return 底层调用成功返回 true；失败返回 false（特权进程内失败多为 ROM 差异，
     *   异常同样吞入 [PowerController.lastError]，但跨进程不可见——调用方以返回值为准）。
     */
    override fun setDisplayPower(on: Boolean): Boolean {
        val tid = "t=${Thread.currentThread().id}(${Thread.currentThread().name})"
        val pid = try { Process.myPid() } catch (_: Throwable) { -1 }
        val uid = try { Process.myUid() } catch (_: Throwable) { -1 }
        Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower enter on=$on pid=$pid uid=$uid")
        return try {
            val ok = PowerController.setDisplayPower(on)
            Log.d(TAG, "[PrivilegedUserService] $tid setDisplayPower exit on=$on ok=$ok " +
                "err=${PowerController.lastError?.toString()}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "[PrivilegedUserService] $tid setDisplayPower on=$on failed", t)
            throw t
        }
    }

    companion object {
        /** 全链路统一 TAG（与 SurfaceControl / PowerController 一致，特权进程 logcat 可见）。 */
        private const val TAG = "BlindCast"
    }
}
