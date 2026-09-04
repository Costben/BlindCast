# BlindCast (隐播) MVP 最终交付验收报告 · Slice 6.3（终验）

- 分支：`feat/blindcast-mvp`（基于 Slice 6.2 commit `00f220c`）
- 构建：`./gradlew :app:assembleDebug` **SUCCESS**（JDK 17.0.20.1，`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`）
- APK：`app/build/outputs/apk/debug/BlindCast_0.1.0_100-debug.apk`（24M，aapt 确认包名 `com.erl.blindcast`，含 `assets/web/index.html` 30KB）
- 本 Slice 修复性改动仅 1 行：`app/src/main/AndroidManifest.xml` 补 `FOREGROUND_SERVICE_CONNECTED_DEVICE`（见 §3）

## 1. 验收矩阵（MVP.md 第五章 + 第二章全部铁线）

| # | 验收项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | 全量构建 `assembleDebug` 全绿 | ✅ | `BUILD SUCCESSFUL`（manifest 改后重跑 `processDebugMainManifest` + `packageDebug` 全绿） |
| 2 | APK 包名 `com.erl.blindcast` | ✅ | `aapt dump badging` → `package: name='com.erl.blindcast' versionCode='100' versionName='0.1.0'` |
| 3 | APK 含 `assets/web/index.html` | ✅ | zip 内 `assets/web/index.html` 存在（30375 bytes，单文件零 CDN） |
| 4 | 3 底栏项 Home / HomeAssistant / Settings | ✅ | `BottomBarDestination.kt` 仅 3 enum（pageIndex 0/1/2）；`MainPagerConfig.PAGE_COUNT=3`；`MainActivity HorizontalPager(3)` + About/ColorPalette/Permissions 仅为 Navigation3 push 路由 |
| 5 | 前台服务启停链 | ✅ | `BlindCastForegroundService.start()` → `startForegroundService(ACTION_START)`；`stop()` → `startService(ACTION_STOP)` → `onStartCommand` 判 `ACTION_STOP` → `stopSelf()`；`onCreate` 顺序 server→video→audio→keeper+双锁，`onDestroy` 逆序回收 + `EmergencyRecovery.notifyServiceDestroy()`；`START_STICKY` 粘性拉起 |
| 6 | `/ws/stream` 通道头 0x01/0x02 | ✅ | 后端 `KIND_VIDEO=0x01` / `KIND_AUDIO=0x02` + `hello` 自描述；前端 `KIND_VIDEO=1, KIND_AUDIO=2` 分流一致 |
| 7 | `/ws/control` 8 指令 down/move/up/key/click/text/audio/ping | ✅ | 后端 `ControlWsRoute` 全分支 + `ack` 回执；前端 pointer/wheel/keyboard/悬浮栏全覆盖（右键=Back、中键=Home、滚轮=滑动、打字=text批量、`audio` 总闸、`ping` 2s 心跳） |
| 8 | `/api/auth` + `/api/screen` + `/api/status` | ✅ | 后端路由表 `BlindCastServer.dispatch` 全注册；前端 `api/auth/status`、`api/auth/verify`、`api/status`、`api/screen{action}` 逐项命中 |
| 9 | Token 留空直通 / 设密 401 | ✅ | `TokenAuthenticator` 空串直通 + `?token=` / `Authorization: Bearer|裸Token` 双通道 + 常量时间比较；未授权 HTTP/WS 统一 401；前端 `localStorage bc_token` + `?token=` 预埋透传 |
| 10 | HA Discovery 四实体 | ✅ | `switch.blindcast_screen`（object_id `blindcast_screen`）+ `configuration_url`（device 块，`?token=` 免密秒进）+ `sensor.blindcast_battery`（%）+ `sensor.blindcast_temperature`（°C）；`buildAll` 3 包 retained+qos1，`HaStatePublisher` 全栈 connect→Discovery→Birth→订阅→上报，`HaCommandHandler` ON=熄屏/OFF=点亮双向对齐，`HaSensorReporter` 周期上报 |
| 11 | Web 单页协议对齐无残留 | ✅ | 通道头 / 指令名 / 端点逐项 grep 前后端一致，不一致清单见 §2（零未修复项） |
| 12 | Manifest 权限 / 服务注册完整 | ✅（已补 1 行） | 见 §3 |
| 13 | Application 熔断接入 | ✅ | `BlindCastApplication.onCreate` 首行 `EmergencyRecovery.install(this)`（崩溃 hook + Activity 销毁回调 + Service 显式 `notifyServiceDestroy`），只装熔断不启动喂狗 |
| 14 | 主题双端 + 无 TODO 崩溃点 | ✅ | `UiMode.Miuix/Material` + `TemplateTheme` 双分支；Home/HA/Settings 均有 Miuix+Material 双实现；全仓 `TODO|FIXME|NotImplementedError` 仅 1 处 `BaseFieldFilter.computePos()`（UI Kit 继承残留，`return 0` 非抛错，无崩溃风险） |
| 15 | 4s 喂狗保活 | ✅ | `UserActivityKeeper.INTERVAL_MS=4_000L`，`PowerManager.userActivity` 多签名反射 + 公开 API 回退 |

## 2. 前后端协议不一致清单

逐项 grep 核对（`ControlWsRoute.kt` ↔ `index.html`、`StreamWsRoute.kt` ↔ `index.html`、`BlindCastServer.kt` ↔ `index.html`、`HaDiscoveryPayload.kt` ↔ HA 标准）：

- 通道头：`0x01 video / 0x02 audio` ↔ `KIND_VIDEO=1 / KIND_AUDIO=2` —— 一致 ✅
- 控制指令：`down/move/up/key/click/text/audio/ping` ↔ 前端 8 种 `sendCtl` 全命中 —— 一致 ✅
- REST：`/api/auth/status`、`/api/auth/verify`、`POST /api/screen{on|action}`、`GET /api/status{blackedOut,batteryLevel,batteryTempC,charging,audioEnabled,videoRunning,audioRunning,streamClients,controlClients}` ↔ 前端调用 —— 一致 ✅
- HA：`switch.blindcast_screen` / `configuration_url` / `sensor.blindcast_battery` / `sensor.blindcast_temperature` —— 四实体齐全 ✅
- **未修复不一致：无。** 唯一发现并已修复的是 Manifest 缺权（§3），非协议问题。

## 3. 本 Slice 唯一修复（小改）

- 文件：`app/src/main/AndroidManifest.xml`（+1 行）
- 内容：补 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />`
- 原因：`targetSdk/compileSdk=37`，服务以 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`（`0x10`）前台化（aapt xmltree 已确认），Android 14+ 无此声明即 `SecurityException` 崩溃；原 manifest 仅声明 `FOREGROUND_SERVICE` / `MEDIA_PROJECTION` / `MICROPHONE`。APK 复验 badging 已含该权限，服务注册 intact。

## 4. 已知限制（需真机 / 外设，静态冒烟无法覆盖）

1. 需真机 + Shizuku/Root：`PowerController`（SurfaceControl/DisplayControl 反射）、`ScreenCaptureEngine`（VirtualDisplay + MediaCodec H.264）、`AudioCaptureEngine`（内录）、`TouchInjector`（InputManager 注入）在无提权普通进程返回 false（各 `lastError` 留痕，服务不崩，Web 静态页/状态 API 照常）。
2. 需 Shizuku UserService / Root 进程打通后重启前台服务即满血；物理熄屏断电与触控停报只能真机目视验证。
3. 延迟 `<50ms`（WebCodecs + Web Audio 硬解）与 HA broker 联调（MQTT connect→Discovery→开关→传感器）需局域网真机实测；本终验仅做静态协议对齐。
4. 端口默认 `0.0.0.0:8888`，改端口/Token 后需重启串流总服务生效（设置页已给 restart-hint）。

## 5. 交付物

- APK：`app/build/outputs/apk/debug/BlindCast_0.1.0_100-debug.apk`
- Web 控制台：`app/src/main/assets/web/index.html`（APK 内同路径）
- 本报告：`outputs/DELIVERY.md`
- 变更集：`app/src/main/AndroidManifest.xml`（+1 权限）＋ 本报告（终验只验不加功能，无其他代码改动）
