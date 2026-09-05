# BlindCast Orchestrator Handoff — 压缩恢复点 (2026-09-05)

> 给新会话/compact后恢复用。读完本文件 + 下列5文件即满血：`PASEO_AUTONOMOUS_ORCHESTRATOR_PLAYBOOK.md` `PLAN.md` `MVP.md` `outputs/DELIVERY.md` `outputs/plans/blindcast-orchestration-plan.md`

## 1. 身份与铁则（初始设定MD）
- 身份：BlindCast战役最高主控 Orchestrator，分支 `feat/blindcast-mvp`。
- 铁则：主控不亲自写业务代码（只调度/验收/提交）；单写者串行；每Slice一全新Worker（`paseo_archive_agent`轮换）；派发必全权限+`notifyOnFinish:true`（opencode=`modeId:build+auto_accept`）；Worker运行期才挂`*/15`看门狗，心跳只`get_agent_status`+读信箱，完工即删；信箱`.paseo/bus/worker-message.json`（FINISHED→VERIFY_AND_DISPATCH_NEXT / BLOCKED→REQUEST_ASSISTANCE）；门禁=强制重编+`assembleDebug`双SUCCESS才commit（无trailer，排除`.paseo/`）；小改只`git add`点名文件，严禁`add --all`（有Worker在跑时）。
- 环境：`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`，门禁`./gradlew :app:assembleDebug`（疑缓存加`--rerun-tasks`），APK=`app/build/outputs/apk/debug/BlindCast_0.1.0_100-debug.apk`，包名`com.erl.blindcast`。
- 装机：`push APK /data/local/tmp/BlindCast.apk` + `pm install -r`。禁用`adb install`（无线下挂死）和`/sdcard/Download`直装（system_server读不到FUSE）。

## 2. 战况（PLAN只记到6.3收官`1f7860b`，之后22个提交是本文件的意义）
- 6.3后按序（`git log 1f7860b..HEAD`可验，共22）：6749941首页清模板残留+Hero红灰绿三态 → 8422b1b HA/设置一级行左侧图标（标准库插槽） → 6807c51底栏缩写HA（页内仍Home Assistant） → d9b2a7d权限3s轮询自愈+红卡自报缺项 → e596d19 FGS改dataSync+启动try/catch（修connectedDevice崩溃循环） → 39c8577 Shizuku桥+UserService → 73c93c9转SurfaceControl+应用内Shizuku授权 → 34ed2aa全链路BlindCast日志 → 1a8ed77 token多候选（InternalDisplayToken优先） → 78d76d4预载android_servers.so（抄MAA-Meow机制） → f3a7a58设值归SurfaceControl → b52d4bf验效+KEY_SLEEP兜底 → 1d506b3复验放宽到DOZE → 5833736加KEY_POWER三段 → 2746cda Root后端（libsu+app_process，uid0） → dd7a69a搬MAA睡眠链（meow包） → f9f84e8 binder优先 → **cd219ac删锁屏链+binder-only+删meow包（-1392行，AGPL清零）** → 5cbf67d特权采集（SurfaceControl直镜+socket回传） → 669f83d修LocalSocket超时 → 8af7209隐藏首页"特权操作结果"行（plumbing留） → **HEAD 8f2858e采集bind幂等+重试+服务解耦（HTTP先起，采集失败不拉死HTTP）**。
- 用户铁令：**永远不fallback锁屏链**。熄屏只许binder物理断电，成=无锁真黑，不成=干净报错。目前用户确认"点击熄屏正常、一切功能正常"。
- 真机（192.168.31.165，OPlus 15）：Shizuku服务端已用root拉起；shell身份binder被静默忽略，root uid0可执行；SLEEP键shell下被忽略，POWER键即锁屏（已删）；AOD=`secure.Setting_AodEnable`，MAA无AOD开关代码（grep实锤），不争论。

## 3. 当前未闭环（新会话第一件事）
- 串流：HTTP/`/api/status`通，`videoRunning:false`。stream-priv-2已装机，待用户拨"串流后台总服务"开关后用`python3 /var/folders/m0/_bt343lx0yz7r6f8fsyg0m6r0000gn/T/opencode/wsprobe.py 192.168.31.165 8888 10`看首帧。只读日志不动屏。
- 信箱最后：`worker-stream-priv-2 FINISHED→DONE`（CaptureSocketLink+ForegroundService，build SUCCESS）。
- 无运行中Worker，无心跳（按需重建）。

## 4. 恢复后回话格式
- 先`git log --oneline -3`+`git status --short`+读信箱确认本文件未过期，再报"我已恢复，HEAD=xxx，待办=xxx"。
