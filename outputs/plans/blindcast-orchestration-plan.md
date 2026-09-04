---
task: blindcast-mvp
status: not-started
worktree: null
branch: feat/blindcast-mvp
pr: null
created: 2026-09-05
updated: 2026-09-05
---

# BlindCast (隐播) 自治主控执行计划与编排看板 (Orchestration Plan)

> **项目定位**：基于 `KernelSU-Style-UI-Kit` 构建的安卓息屏挂机工作站。支持硬件级物理熄屏、4s 喂狗保活防休眠、局域网 0.0.0.0 暴露串流、Web 端 scrcpy 级键鼠反向触控、Home Assistant 实体联动与 Token 密码安全鉴权。  
> **编排机制**：遵循 `PASEO_AUTONOMOUS_ORCHESTRATOR_PLAYBOOK.md` 规范。主控专职调度、单写者严格串行、子 Agent 轮换、全权限无阻注入、15 分钟防爆舱心跳、子 Agent 自判 Block 文件信箱反向指令闭环。

---

## 一、 Requirements (immutable 铁线需求)

本块定义后全局冻结，作为所有子 Agent 与主控验收的唯一不可动摇准绳：

1. **运行环境与提权**：
   - 强依赖 Shizuku (ADB) 或 Root 环境。无提权授权时首页展示引导卡片，不妥协低效的辅助功能或非提权降级。
2. **屏幕与音频采集**：
   - 采用提权原生 VirtualDisplay 抓屏，**彻底免除 Android 系统录屏确认弹窗**，无状态栏录屏隐私红点。
   - 画面采用硬件 `MediaCodec` 极速编码为 H.264 NALU。
   - 捕获系统内部音频，并在 App 与 Web 端均提供**「音频传输开关」**，支持自由启停音乐传输以兼顾省流。
3. **硬件熄屏与保活防休眠**：
   - 通过反射 `SurfaceControl.setDisplayPowerMode(token, POWER_MODE_OFF)` 与 Android 14+ `DisplayControl` 物理切断屏幕电源（OLED/背光断电、触控停止上报）。
   - 前台窗口持有 `FLAG_KEEP_SCREEN_ON` + 守护线程每 4 秒向系统服务喂狗 `PowerManager.userActivity()`，彻底阻止 Android 进入 Doze 低功耗休眠，确保游戏/脚本满血前台运行。
   - **多维唤醒与崩溃兜底**：支持双击物理电源键、Web 端点击点亮、Home Assistant 开关打开；且在 App 崩溃或服务销毁时自动强制调用 `setDisplayPower(true)`，杜绝屏幕变砖风险。
4. **反向操控 (scrcpy)**：
   - Web 端鼠标左键拖拽映射为系统多点触控（`MotionEvent`）。
   - **鼠标右键直接映射为 Android 返回键 (Back)**；鼠标中键映射为 Home 键；键盘打字映射为输入法文本/按键注入。
5. **网络暴露与鉴权**：
   - 嵌入式服务默认绑定 `0.0.0.0:8888` 局域网暴露。
   - 支持设置访问 Token（留空免密直通；设置密码后网页端弹出暗黑毛玻璃 Token 输入框，校验后本地记住）。HA 设备链接预埋 `?token=xxx` 免密秒进。
6. **Home Assistant 智能家居联动**：
   - 基于标准 MQTT Discovery 协议，自动上报注册：① 屏幕电源开关 `switch.blindcast_screen`；② 直达链接 `configuration_url`；③ 电池电量传感器 `sensor.blindcast_battery`；④ 电池温度传感器 `sensor.blindcast_temperature`。
7. **Web 端控制台**：
   - 单文件内嵌于 `app/src/main/assets/web/`，基于 **WebCodecs (VideoDecoder)** + **Web Audio API** 硬件低延迟渲染（延迟 < 50ms）。
   - 悬浮半透明快捷控制栏：虚拟按键 (Back/Home/Recents)、音量调节、息屏/亮屏切换、音频静音切换、全屏、实时电量与延迟显示。
8. **界面与视觉规范**：
   - 严格收敛为 **3 个一级页面**：① 主页 (Home)、② Home Assistant 页、③ 设置页 (Settings)。
   - 100% 遵循 `KernelSU-Style-UI-Kit` 规范（Miuix 质感卡片、FloatingBottomBar、Navigation3、Miuix/Material 3 主题切换）。

---

## 二、 主控调度与通信协议 (Orchestrator Protocol)

### 1. 通信信箱文件 (IPC Mailbox)
通信总线位于：`.paseo/bus/worker-message.json`。
* **子 Agent 完工报文 (`status: FINISHED`)**：
  ```json
  {
    "sender": "worker-slice-X.Y",
    "timestamp": "ISO-TIME",
    "status": "FINISHED",
    "instruction": "VERIFY_AND_DISPATCH_NEXT",
    "slice": "X.Y",
    "next_slice": "X.Z",
    "result": {
      "build": "PASS",
      "changed_files": ["..."],
      "summary": "..."
    }
  }
  ```
* **子 Agent 自判 Block 报文 (`status: BLOCKED`)**：
  遇到无法自愈的编译/依赖死结时，子 Agent 严禁假死挂起，必须写回报文并正常结束：
  ```json
  {
    "sender": "worker-slice-X.Y",
    "timestamp": "ISO-TIME",
    "status": "BLOCKED",
    "instruction": "REQUEST_ASSISTANCE",
    "slice": "X.Y",
    "blocker": {
      "reason": "...",
      "failed_command": "./gradlew :app:assembleDebug",
      "error_log": "...",
      "tried_solutions": ["..."]
    }
  }
  ```

### 2. 全权限无阻派发 (Full Permissions)
主控调用 `paseo_create_agent` 时，强制携带最高权限设置：
* Claude: `settings: { modeId: "bypassPermissions" }`
* Codex: `settings: { modeId: "full-access" }`
* OpenCode: `settings: { modeId: "build", features: { "auto_accept": true } }`
* 统一携带：`notifyOnFinish: true`。

### 3. 15 分钟轻量看门狗 (Heartbeat Anti-Bleed)
* 仅在 Worker 运行期间保持 `paseo_create_heartbeat(cron: "*/15 * * * *")`。
* 探活**严禁**调用 `paseo_get_agent_activity`！仅执行 `paseo_get_agent_status` + 读取信箱文件。单次心跳 tokens 消耗 < 100。
* Worker 验收归档后，立即调用 `paseo_delete_heartbeat` 销毁心跳。

### 4. 单写者串行与轮换 (Rotation)
* 同一时间绝对只有一个子 Agent 在修改代码。
* 每个子 Agent 只执行 1 个 Slice，门禁通过后主控立即 `paseo_archive_agent`，开全新干净会话接力下一 Slice。

---

## 三、 执行阶段与切片清单 (Phases & Slices Roadmap)

### Phase 1: 基础设施搭建与 3 页面 Miuix 骨架收敛
- [x] **Slice 1.1** · implement · 克隆引入 KernelSU-Style-UI-Kit 并完成项目身份迁移
  - **范围**：导入 UI Kit，修改 `settings.gradle.kts` 为 `BlindCast`，源码迁移至 `com.erl.blindcast`，清理多余文档与测试代码。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：成功构建出包名为 `com.erl.blindcast` 的初始空 APK。

- [x] **Slice 1.2** · implement · 改造 BottomBar 导航与 3 大页面 Miuix 骨架
  - **范围**：修改 `BottomBarDestination` 为 `Home`, `HomeAssistant`, `Settings` 3 项；调整 `MainActivity.kt` 承载 `HorizontalPager(pageCount = 3)`；实现 3 个页面的基本 Miuix TopAppBar 与卡片容器。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：启动应用后可通过 Miuix 底部悬浮栏与手势平滑切换 3 个页面。

---

### Phase 2: 硬件息屏与 4s 喂狗保活引擎 (提取自 MAA-Meow)
- [ ] **Slice 2.1** · implement · SurfaceControl 物理灭屏底层跨版本反射
  - **范围**：在 `core/blackout/` 下构建 `SurfaceControl.java` 与 Android 14+ `DisplayControl.java`，封装 `PowerController.kt` 实现 `setDisplayPower(on: Boolean)`。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：通过提权进程能直接物理切断物理屏幕背光电源与恢复。

- [ ] **Slice 2.2** · implement · 4s 喂狗防休眠线程与崩溃熔断安全网
  - **范围**：编写 `UserActivityKeeper.kt`，实现后台 4s 周期向 `PowerManager.userActivity()` 喂狗；编写 Activity/Service 销毁与未捕获异常时的 `EmergencyRecovery` 钩子，强制恢复亮屏。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：息屏后系统坚决不进入 Doze 睡眠，App 退出时自动强制点亮屏幕。

---

### Phase 3: scrcpy 屏幕采集、音频捕获与触控注入
- [ ] **Slice 3.1** · implement · 提权 VirtualDisplay 捕获与 MediaCodec H.264 硬件编码
  - **范围**：在提权服务中调用 `DisplayManager.createVirtualDisplay`，将 Surface 接入 `MediaCodec` H.264 (AVC Baseline, 低延迟配置)，对外产出 NALU 字节包。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：免录屏系统弹窗，原生硬件编码持续吐出视频帧。

- [ ] **Slice 3.2** · implement · 系统底层音频抓取与独立传输控制开关
  - **范围**：构建系统音频抓取管道与编码；实现全局音频传输开关配置，允许用户随时切换是否传输声音。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：音频数据按需编码输出，关闭开关时零额外音频网络消耗。

- [ ] **Slice 3.3** · implement · InputManager 键鼠与多点触控底层注入
  - **范围**：移植 `InputManager.java` 与 `InputControlUtils.java`；实现网页端坐标与手势转换（Down/Move/Up 注入），鼠标右键映射为返回键 (Back)，键盘打字映射为按键注入。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：远端注入能在屏幕物理关闭状态下准确驱动底层前台游戏响应。

---

### Phase 4: 局域网服务 (0.0.0.0) 与低延迟 Web 播放器
- [ ] **Slice 4.1** · implement · 嵌入式 HTTP/WebSocket 服务与 Token 鉴权中间件
  - **范围**：构建监听 `0.0.0.0:8888` 的轻量异步服务；实现 `/ws/stream` 视频音频推流、`/ws/control` 键鼠双向通信、`/api/auth` Token 密码校验、`/api/screen` 远程开关屏幕 REST 接口。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：局域网设备可通过 HTTP/WS 建立稳定鉴权连接。

- [ ] **Slice 4.2** · implement · assets/web 单页极客控制台与全功能悬浮栏
  - **范围**：编写单文件 Web 前端；基于 WebCodecs (`VideoDecoder`) + Web Audio API 实现硬解 Canvas 渲染；实现全功能半透明悬浮控制栏（虚拟按键、音量、息屏/亮屏、静音、全屏、Token 输入框）。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：现代暗黑风界面，局域网连接后延迟 < 50ms，悬浮栏操作精准响应。

---

### Phase 5: Home Assistant MQTT 自动发现与状态同步
- [ ] **Slice 5.1** · implement · MQTT Discovery 自动发现报文与轻量客户端
  - **范围**：实现轻量 MQTT 客户端与断线重连；向 HA 发布 Discovery 配置包，注册 `switch.blindcast_screen`、`configuration_url`、`sensor.blindcast_battery`、`sensor.blindcast_temperature`。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：HA 自动弹出新设备 BlindCast，并列出开关实体与 Web 访问链接。

- [ ] **Slice 5.2** · implement · HA 双向开关联动与传感器实时上报
  - **范围**：订阅 HA 开关控制 Topic，接收指令联动 `PowerController` 熄屏/亮屏；定时同步手机电量与温度状态至 HA。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：在 HA 仪表板点击开关能真实熄灭/点亮手机屏幕，且状态实时双向对齐。

---

### Phase 6: Miuix 界面全要素打通与整体验收
- [ ] **Slice 6.1** · implement · HomeScreen 业务全要素绑定与二维码
  - **范围**：将状态大卡片与后台服务真实状态绑定；实现「立即息屏挂机」与「点亮屏幕」动作；生成包含 Token 的局域网直连二维码与复制按钮。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：主页数据实时刷新，扫码/复制链接一键直达 Web 监控。

- [ ] **Slice 6.2** · implement · HomeAssistantScreen 与 SettingsScreen 全量配置绑定
  - **范围**：完善 HA 页面 MQTT 配置保存与测试连接；完善设置页画质分辨率、音频开关、Token 密码设置与 Miuix 主题切换。
  - **门禁**：`./gradlew :app:assembleDebug` 成功通过。
  - **验收目标**：所有偏好设置持久化并即时生效。

- [ ] **Slice 6.3** · verify · 全量构建、端到端冒烟测试与最终交付
  - **范围**：运行 `./gradlew :app:assembleDebug`；端到端校验熄屏挂机、Web 监控操控、HA 开关联动完整闭环；生成交付归档产物。
  - **门禁**：编译全绿，产出 APK。
  - **验收目标**：所有 Phase 勾选完成，交付物就绪。

---

## 四、 过程记录 (Notes / Audit Trail)

- 2026-09-05: 需求与技术规格完成 Relentless 逐分支推演锁定，正式创建本执行看板。
- 2026-09-05: 通信总线目录 `.paseo/bus/` 初始化完毕，等待主控派发 Slice 1.1。
- 2026-09-05: Slice 1.1 验收通过（assembleDebug SUCCESS，包名 com.erl.blindcast），已提交，派发 Slice 1.2。
- 2026-09-05: Slice 1.2 验收通过（底栏收敛3项 + HorizontalPager(3)双向同步，assembleDebug SUCCESS），已提交，派发 Slice 2.1。
