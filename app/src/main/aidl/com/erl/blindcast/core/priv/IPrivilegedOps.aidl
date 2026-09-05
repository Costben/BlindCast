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
    // Priv-Bridge-7 起服务端内含验效轮询 + 按键兜底（binder 无异常但验效失败 → KEY_SLEEP/WAKEUP/POWER），
    // Priv-Bridge-9 起熄屏为 binder→SLEEP→POWER 三段（SLEEP 仍 miss 则试 POWER，同通道复验约 6s），
    // 返回值已是验效后最终结果（true = Display 状态已达目标）。
    boolean setDisplayPower(boolean on) = 1;

    // Priv-Bridge-7：按键兜底直调（特权进程内经 TouchInjector/InputManagerWrapper 注入 + 验效）。
    // 熄屏注入 KEYCODE_SLEEP（Down+Up，SOURCE_KEYBOARD）后验 STATE_OFF/DOZE/DOZE_SUSPEND；
    // 点亮依次试 KEYCODE_WAKEUP、无则 KEYCODE_POWER 后验 STATE_ON。编号顺延，旧方法不动。
    // Priv-Bridge-9：新增 powerByKey（POWER 单键，熄屏最终兜底，对齐 MAA-Meow 三段链终段思想；
    // OPlus Android 15 真机 SLEEP 被 ROM 忽略，POWER 走物理按键通路，同通道 Down+Up 注入后验
    // OFF/DOZE/DOZE_SUSPEND 约 6s）。sleepByKey 保持 SLEEP 单键语义不动。
    boolean sleepByKey() = 2;
    boolean wakeByKey() = 3;
    boolean powerByKey() = 5;

    // Priv-Bridge-7：取特权进程侧最近一次失败明细（PowerController.lastError.message，
    // 成功时 null；App 侧 routed 失败时同 binder 内取回，用于 Home 状态行/Toast，契约不变）。
    // 与 setDisplayPower/sleepByKey/powerByKey/wakeByKey 同一次绑定内调用才有意义
    // （UserService 用完即焚，跨绑定静态量清零）。
    String getLastError() = 4;

    // Stream-Priv-1：特权采集（scrcpy 同构：SurfaceControl 直建 Display + socket 回传）。
    // 跑在 Shizuku UserService 常驻进程（daemon(true)，流期间不 destroy；stop 时 destroy 宿主）。
    // 旧编号 1..5 一律不动，新编号顺延 6..8。
    // startCapture 在特权进程内调 PrivilegedCapture.start(w,h,bitrate,fps)（内部连
    // abstract:blindcast_capture 回传 App 侧 CaptureSocketLink 服），true=编码已起
    // （首帧另由 App 侧等 socket 首帧或 3s 超时判定）；false=失败，明细经 getCaptureError 回读。
    boolean startCapture(int width, int height, int bitrate, int fps) = 6;
    // 停特权采集并释放 display/codec（幂等；长流 stop 时调，调完 App 侧再 destroy 宿主）。
    void stopCapture() = 7;
    // 取特权采集最近失败明细（PrivilegedCapture.lastError.message，成功时 null；
    // 须同一次常驻绑定内调用，用完即焚语义同 getLastError）。
    String getCaptureError() = 8;

    // 反控注入（跑在特权进程内经 TouchInjector/InputManagerWrapper 注入；App 进程无
    // INJECT_EVENTS，ControlWsRoute 一律走本通道；旧编号 1..8 不动，新编号顺延 9..13）。
    // 特权进程按次绑定用完即焚，手势状态跨绑定不保留，故触控一律单次调用内原子完成：
    // tap=Down+Up 一次，drag=Down+N插值Move+Up 一次（松手时执行，非实时跟手）。
    // 归一化坐标 0..1（相对真实主屏，特权侧自行解析物理尺寸换算）。
    boolean injectTap(float normX, float normY) = 9;
    boolean injectDrag(float x0, float y0, float x1, float y1) = 10;
    boolean injectKey(int keyCode) = 11;
    boolean injectText(String text) = 12;
    // 取最近一次注入失败明细（同绑定内调用；成功时 null）。
    String getInputError() = 13;
}
