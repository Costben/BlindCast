package com.erl.blindcast.core.priv;

// Priv-Bridge-1：特权操作 Binder 契约（跑在 Shizuku UserService 进程，特权身份）。
//
// - 服务端实现见 PrivilegedUserService（直接继承本接口的 Stub，无需 manifest <service> 声明，
//   Shizuku server 经 app_process 直接实例化；详见 Shizuku-API demo 的 UserService 范式）。
// - 本 Slice 仅接通熄屏链路 setDisplayPower；抓屏/注入改道预留后续编号（=2 起），不 breaking 现有编号。
// - destroy() 为 Shizuku server 保留销毁方法（transaction 16777115，aidl 侧写 16777114），
//   实现内做清理并 System.exit(0)，供 PrivilegedBridge 解绑时杀进程用。
interface IPrivilegedOps {

    void destroy() = 16777114; // Destroy method defined by Shizuku server.

    // 设置主显示屏电源：true = 点亮（POWER_MODE_NORMAL），false = 物理熄屏（POWER_MODE_OFF）。
    boolean setDisplayPower(boolean on) = 1;

    // TODO(priv-bridge-next): 抓屏复用预留 —— 如 ParcelFileDescriptor captureFrame(...) = 2;
    // TODO(priv-bridge-next): 注入复用预留 —— 如 boolean injectInput(...) = 3;
}
