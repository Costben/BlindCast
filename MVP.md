# BlindCast (隐播) - MVP 产品需求与技术架构规格书 (PRD & Tech Spec)

> **版本**：v1.0.0-MVP  
> **定位**：基于 KernelSU-Style-UI-Kit 的安卓息屏挂机工作站，支持局域网串流、网页端 scrcpy 级键鼠反向操控、Home Assistant 实体联动与 Token 安全鉴权。

---

## 一、 项目背景与核心价值

在安卓设备上进行长时间挂机（如游戏托管、自动化脚本运行、后台仿真测试）时，用户常面临两大痛点：
1. **屏幕亮着发热耗电，且容易在口袋或背包中误触**；
2. **按物理电源键息屏会导致系统挂起（Doze 模式）、渲染管道暂停、甚至游戏断线中断**。

**BlindCast (隐播)** 通过深度提取底层硬件熄屏与保活技术，让设备在**物理熄屏、彻底屏蔽触控**的状态下，内部渲染管线与 CPU **100% 满血前台运行**；同时在手机本地暴露安全的 HTTP 端口，让用户可以在任何电脑、平板或手机浏览器上，以 **低延迟 scrcpy 的形式直接监视并反向操控** 手机，并通过 **Home Assistant (HA)** 作为智能家居实体远程控制屏幕与直达监控。

---

## 二、 MVP 范围与功能矩阵 (Scope)

### 1. 核心功能点清单

| 模块 | 功能项 | 描述 | 优先级 |
| :--- | :--- | :--- | :---: |
| **息屏挂机** | 硬件级真熄屏 | 提权模式（Shizuku/Root）下，通过底层反射物理断电屏幕，触控停止上报，渲染管线不中断 | P0 |
| | 挂机防休眠保活 | 前台持有 `FLAG_KEEP_SCREEN_ON` + 4s 周期向系统服务喂狗 `userActivity()`，阻止 Doze 休眠 | P0 |
| | 全黑防误触遮罩 | 免提权降级模式：全屏纯黑 `TYPE_APPLICATION_OVERLAY`，不抢焦点，拦截消费触控 | P0 |
| **屏幕传输与控制** | 局域网服务暴露 | 嵌入式服务默认绑定 `0.0.0.0:8888`，同一局域网设备输入 IP 即可访问 | P0 |
| | H.264 硬件低延迟推流 | `MediaProjection` 捕获 + `MediaCodec` 硬件编码，通过 WebSocket 推送 NALU | P0 |
| | Web 端 WebCodecs 渲染 | 单页免安装 Web 播放器，浏览器原生硬解至 Canvas，延迟 < 50ms | P0 |
| | 完整 scrcpy 反向控制 | 网页端鼠标左键拖拽映射为触控、右键映射为返回键(Back)、电脑打字映射为输入法注入 | P0 |
| **访问鉴权** | Token 密码机制 | 支持在 App 内配置 Token：留空免密直通；设置后网页端弹出极简暗黑密码框，验证后记住 | P0 |
| **智能家居联动** | Home Assistant 自动发现 | 基于 MQTT Discovery 协议，自动向 HA 注册设备与实体，无需手动编写 YAML | P0 |
| | HA 屏幕开关实体 | 在 HA 仪表板中呈现为 `switch.blindcast_screen`，远程一键熄屏/点亮 | P0 |
| | HA 快速直达链接 | 设备携带 `configuration_url`，HA 设备卡片一键直达 Web 监控（自动带 Token 免密） | P0 |
| | 电池与温度传感器 | 向 HA 上报 `sensor.blindcast_battery` 与 `sensor.blindcast_temperature` | P1 |
| **视觉界面** | 严格 3 页面体系 | 完全遵循 `KernelSU-Style-UI-Kit` 规范：主页、Home Assistant 页面、设置页面 | P0 |

---

## 三、 界面布局与交互规划（严格遵循 KernelSU-Style-UI-Kit）

应用严格收敛为 **3 个一级页面**，使用底栏（`BottomBar` / 悬浮毛玻璃 `FloatingBottomBar`）切换，支持左右滑动（`HorizontalPager`）与横屏左侧导航轨（`SideRail`）。

### 页面 1：主页 (Home Screen)
* **Hero 状态大卡片 (KernelSU 经典样式，带右下角大半透浮印图标)**：
  * **运行中 (绿色)**：标题「挂机服务运行中」，副标题「局域网暴露: http://192.168.x.x:8888」，统计数据「帧率 60 FPS | 码率 3.5 Mbps | 在线人数 1 | Token 保护: 开启」。
  * **未启动 (灰暗色)**：标题「服务已停止」，提示点击启动挂机服务。
* **快速操作卡片组 (Miuix Card)**：
  * **⚡ 立即息屏挂机 (Button/Tile)**：下发指令彻底关闭物理屏幕，进入极致省电无感前台挂机。
  * **🔄 点亮物理屏幕 (Button/Tile)**：恢复屏幕显示（亦支持按两次物理电源键物理唤醒）。
  * **🌐 串流后台总服务 (SwitchPreference)**：一键启动/关闭录屏编码与 HTTP 监听服务。
* **局域网访问与二维码直连卡片 (Miuix Card)**：
  * 完整的 Web 地址展示与「📋 复制链接（已带Token）」按钮。
  * 「🌐 在本机浏览器打开」快捷入口。
  * 居中展示高清二维码，供局域网内其他设备（平板/笔记本）扫码直连。
* **挂机硬件状态监控卡片 (Miuix Card)**：
  * 实时呈现：电池电量（充电中/未充电）、电池温度（挂机防过热监控）、剩余运行内存、WiFi 链路速率。

### 页面 2：Home Assistant 页面 (Home Assistant Screen)
* **HA 连接状态 Hero 卡片 (Miuix Card)**：
  * 展示 MQTT 连接状态：「已连接 (Connected)」、「未配置」或「重连中」。
  * 自动发现状态：显示已注册的设备名称与实体 ID（`switch.blindcast_screen`）。
* **MQTT Broker 配置卡片组 (Miuix Card)**：
  * `SwitchPreference`：「启用 Home Assistant 联动」。
  * `SuperEditArrow`：「MQTT Broker 地址」（如 `192.168.1.50`）。
  * `SuperEditArrow`：「MQTT 端口」（默认 `1883`）。
  * `SuperEditArrow`：「用户名 (可选)」与「密码 (可选)」。
  * 「🔄 测试连接并重新上报 Discovery 实体」动作按钮。
* **已暴露实体清单说明 (Miuix Card)**：
  * `switch.blindcast_screen`：屏幕开关实体说明。
  * `configuration_url`：Web 直达链接说明。
  * `sensor.blindcast_battery` / `sensor.blindcast_temperature`：传感器状态说明。
* **免 MQTT 备选方案 (Miuix Card)**：
  * 提供「📋 一键复制 HA configuration.yaml REST 配置」按钮，方便非 MQTT 用户手动复制进 HA。

### 页面 3：设置页面 (Settings Screen)
* **串流画质与编码设置 (Miuix Card)**：
  * `OverlayDropdownPreference`：「画面分辨率」（推荐 720P / 1080P / 原生）。
  * `OverlayDropdownPreference`：「目标帧率」（30 FPS 挂机省流 / 60 FPS 极速流畅）。
  * `OverlayDropdownPreference`：「视频码率」（2 Mbps ~ 8 Mbps）。
* **安全与访问密码 (Miuix Card)**：
  * `SuperEditArrow`：「访问密码 (Token)」（留空则免密；输入密码后网页端需输入该 Token）。
  * `SuperEditArrow`：「HTTP 服务监听端口」（默认 `8888`）。
* **scrcpy 远程控制配置 (Miuix Card)**：
  * `SwitchPreference`：「启用网页端反向触控」（允许鼠标点击/滑动手机）。
  * `SwitchPreference`：「鼠标右键映射为返回键 (Back)」。
  * `SwitchPreference`：「电脑键盘输入直接注入手机」。
* **息屏与保活策略 (Miuix Card)**：
  * `OverlayDropdownPreference`：「息屏模式」（优先硬件物理熄屏 / 降级全黑防误触遮罩）。
  * `SwitchPreference`：「挂机常亮锁 (WakeLock + 4s 喂狗防休眠)」。
* **外观与关于 (Miuix Card)**：
  * `OverlayDropdownPreference`：「界面风格」（Miuix / Material 3）。
  * `ArrowPreference`：「主题取色与调色板」。
  * `ArrowPreference`：「关于 BlindCast」（版本号、GPL3 协议）。

---

## 四、 技术架构与底层实现细节

### 1. 代码目录架构规划
```text
com.erl.blindcast
├── ui/                                 # 界面表现层 (严格基于 KernelSU-Style-UI-Kit)
│   ├── MainActivity.kt                 # 单 Activity，承载 3 页 HorizontalPager + 浮动底栏
│   ├── navigation3/                    # Navigation3 路由
│   ├── component/bottombar/            # 3 项底栏 (Home, HomeAssistant, Settings)
│   ├── screen/
│   │   ├── home/                       # 主页 (HomeMiuix, HomeMaterial, HomeViewModel)
│   │   ├── homeassistant/              # HA 页 (HomeAssistantMiuix, HomeAssistantViewModel)
│   │   └── settings/                   # 设置页 (SettingsMiuix, SettingsViewModel)
│   └── theme/                          # Miuix / Material 3 动态取色引擎
│
├── core/
│   ├── blackout/                       # 【息屏与保活核心 (提取自 MAA-Meow，纯功能无UI)】
│   │   ├── PowerController.kt          # 硬件屏幕电源控制 (SurfaceControl / DisplayControl)
│   │   ├── UserActivityKeeper.kt       # 4s 周期向 PowerManager 喂狗，阻止系统 Doze
│   │   └── BlackoutOverlayManager.kt   # 免提权备选：WindowManager 全屏纯黑防误触遮罩
│   │
│   ├── scrcpy/                         # 【屏幕采集与反向输入注入】
│   │   ├── ScreenCaptureEngine.kt      # MediaProjection 捕获 + MediaCodec 硬件 H.264 编码
│   │   ├── InputManagerWrapper.java    # 提取自 MAA-Meow: InputManager 反射包装
│   │   └── TouchInjector.kt            # 网页鼠标/触摸事件转换为 MotionEvent/KeyEvent 注入系统
│   │
│   ├── server/                         # 【嵌入式 HTTP & WebSocket 服务器】
│   │   ├── BlindCastServer.kt          # 协程服务引擎，绑定 0.0.0.0:8888
│   │   ├── routes/
│   │   │   ├── WebStaticRoutes.kt      # 静态文件路由，输出 assets/web 单页播放器
│   │   │   ├── StreamWsRoute.kt        # WebSocket /ws/stream: 极速推送 H.264 视频帧
│   │   │   ├── ControlWsRoute.kt       # WebSocket /ws/control: 双向反向键鼠指令
│   │   │   ├── AuthRoute.kt            # REST /api/auth: Token 状态检查与校验
│   │   │   └── DeviceApiRoute.kt       # REST /api/screen (开关屏幕), /api/status (电量温度)
│   │   └── auth/TokenAuthenticator.kt  # Token 鉴权拦截器
│   │
│   ├── ha/                             # 【Home Assistant 自动发现与状态同步】
│   │   ├── HaMqttClient.kt             # 轻量 MQTT 客户端与断线自动重连
│   │   ├── HaDiscoveryPayload.kt       # MQTT Discovery 格式封装 (switch / sensor / url)
│   │   └── HaStatePublisher.kt         # 屏幕状态与传感器数据定时同步
│   │
│   └── service/
│       └── BlindCastForegroundService.kt # 常驻前台保活服务 (持有 WakeLock + WifiLock)
│
└── assets/web/                         # 【内嵌 Web 端监控控制台】
    ├── index.html                      # 单文件极客暗黑风响应式控制台
    ├── player.js                       # WebCodecs 硬件加速低延迟解码渲染引擎 (<50ms)
    └── control.js                      # 鼠标左键拖拽、右键返回、滚轮滑动与打字监听
```

### 2. 关键核心算法与机制

#### (1) 物理熄屏与防休眠（MAA-Meow 核心思想）
* **SurfaceControl 灭屏**：
  * Android 9: `SurfaceControl.getBuiltInDisplay()` -> `setDisplayPowerMode(token, POWER_MODE_OFF)`
  * Android 10~13: `SurfaceControl.getPhysicalDisplayIds()` -> `setDisplayPowerMode(token, POWER_MODE_OFF)`
  * Android 14+: 通过 `SYSTEMSERVERCLASSPATH` 反射加载 `com.android.server.display.DisplayControl`。
* **系统防休眠**：
  * 前台窗口添加 `FLAG_KEEP_SCREEN_ON`。
  * 守护线程每 4 秒调用一次 `ServiceManager.getPowerManager().userActivity(displayId)` 喂狗，彻底消除系统休眠导致渲染挂起的问题。

#### (2) Web 端极速 WebCodecs 播放器
* 网页接收 WebSocket 二进制流（NAL Unit，首包 SPS/PPS 探测初始化 `VideoDecoder`）。
* 解码出的 `VideoFrame` 直接绘制在 `<canvas>`，全流程零内存拷贝，延迟稳定在 30ms ~ 60ms。

#### (3) 网页反向操作映射
* 鼠标左键按下 -> 计算 Canvas 真实物理坐标比例 -> WebSocket 发送 `{type: "down", x, y}` -> `TouchInjector` 构建 `MotionEvent` 调用 `InputManager.injectInputEvent()`。
* 鼠标右键 -> 拦截浏览器上下文菜单，直接发送 `{type: "key", keycode: 4}`（Android 返回键）。

#### (4) Token 鉴权
* 网页打开时先探活 `/api/auth/status`。
* 若要求密码且未传入有效 Token：前端展示毛玻璃密码对话框。
* 校验通过后存入 `localStorage`，并在后续建立 WebSocket 时带上 `?token=xxx`。
* HA 设备卡片点击「访问设备」时，链接预埋 `?token=<配置的Token>`，实现免密一键查看。

---

## 五、 质量验收标准 (Definition of Done)

1. **构建门禁**：
   * `./gradlew :app:assembleDebug` 100% 编译成功无报错。
2. **核心业务验收**：
   * 启动服务后，同一局域网 PC 输入 `http://<手机IP>:8888` 能秒级看到手机画面，延迟感知低于 80ms。
   * 点击网页画面，手机前台界面能准确响应点击与滑动；点击鼠标右键能正确执行 Android 返回。
   * 点击「息屏挂机」，手机物理屏幕完全断电黑屏，而 PC 网页端画面持续流畅刷新，手机底层任务未中断。
   * 设置 Token 密码后，未授权浏览器访问提示输入密码；配置 MQTT 后，Home Assistant 自动弹出开关实体并能正常控制屏幕。
3. **视觉与规范**：
   * 严格呈现 3 个一级页面，Miuix 质感卡片与主题切换完好，无外部无用依赖与 UI 污染。
