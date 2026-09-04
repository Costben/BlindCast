# Paseo 自治主控编排架构实战手册 (Autonomous Orchestrator Playbook)

> 本文档完整复盘 ArtPlus `MainActivity.kt`（11,353 行至终局 < 500 行）全自主解耦战役中的主控调度体系，并提炼出可直接复用的**通用主控提示词模板**与**工作流机制**。

---

## 一、Paseo 主控与子 Agent 协同核心机制

在 Paseo 中，你所看到的“单个主会话 + 顶部角标挂载子 Agent”的架构，本质上是 **Paseo Agent-Scoped Sub-Agent 体系 + 双轨看门狗 (Watchdog) + 单写者轮换制 (Single-Writer Rotation)** 的工程化实现。

```
┌─────────────────────────────────────────────────────────────┐
│                    Paseo 主会话 (Orchestrator)              │
│  [UI顶部角标显示：子 Agent 状态与名称]                         │
│  - 纯架构/调度角色，绝对不碰业务代码                          │
│  - 负责拆解 Slice、派发子 Agent、门禁验证、Git Commit/Push     │
└──────────────┬───────────────────────────────▲──────────────┘
               │ 1. paseo_create_agent         │ 4. notifyOnFinish
               │    (notifyOnFinish=true)      │    / 心跳看门狗
               ▼                               │
┌──────────────────────────────┐ 3. 验收通过后  │
│ 子 Agent (Worker)            ├───────────────┤
│ - 单个 Slice 专项执行         │ paseo_archive_agent (轮换)
│ - 编译、自测、写回传报告      │
└──────────────────────────────┘
```

### 1. 为什么顶部会出现子 Agent 角标？
* 当主 Agent 在自身会话内调用 `paseo_create_agent`，且**不显式传入外层 `workspaceId`**（或传入当前工作区 ID）时，Paseo 会将新 Agent 识别为当前会话的 **Sub-Agent**。
* Paseo 前端会在主对话框顶部的 Sub-agent Pill / 角标中实时展示子 Agent 的名称、运行状态（`running`、`idle`、`error`）。
* 点击角标即可直接观察子 Agent 的独立上下文、思考流与工具执行，主会话则保持干净的高层调度状态。

### 2. 双轨唤醒与监控机制 (Dual Watchdog)
主控之所以能“无人值守、自动接力”，依赖于**事件回调**与**主动轮询**的双重保险：

1. **事件驱动 (Notify on Finish)**：
   - 派发子 Agent 时必须携带 `notifyOnFinish: true`。
   - 子 Agent 一旦完成任务转入 `idle`、触发 `error` 或需要权限时，Paseo 后台会自动向主控会话发送一条系统通知消息，立刻唤醒主控执行验收逻辑。
2. **时钟轮询 (Paseo Heartbeat Watchdog)**：
   - 调度开始前，主控调用 `paseo_create_heartbeat` 注册 Cron 心跳（如 `*/10 * * * *`，每 10 分钟一次）。
   - 作用：**死锁预防**。如果子 Agent 陷入死循环、命令假死、或发生无声停滞，`notifyOnFinish` 不会触发。此时心跳触发主控调用 `paseo_get_agent_status` 与 `paseo_get_agent_activity` 探活，超过阈值即主动发 prompt 干预或强制重拉。

### 3. 单写者原则 (Single Writer Principle)
* **严禁并发修改同一代码资产**：当重构涉及超大核心文件时，两个子 Agent 并发写入必将导致行号漂移、AST 错乱或 Git Merge 灾难。
* **严格串行推进**：Worker A 交付 ➔ 主控门禁通过 ➔ 主控 Git Push ➔ 归档 Worker A ➔ 派发 Worker B。

### 4. 子 Agent 轮换制 (Agent Rotation)
* LLM 在经历多次大型文件检索、编译报错栈与重构后，上下文迅速膨胀至 100k~500k tokens，推理精度与依从度断崖式下跌（容易出现半截话、幻觉或盲目提前收尾）。
* **铁律**：每个子 Agent 只活 1~2 个 Slice。任务一完成，主控立刻调用 `paseo_archive_agent` 将其归档，开启全新、干净的子 Agent 执行下一个 Slice。

### 5. 外部看板状态落盘 (Blackboard Pattern)
* 主控将会话进度实时写入磁盘文档（如 `outputs/research/refactor-xxx-epic.md`）。
* 无论主控会话由于意外刷新或上下文压缩，只要读取磁盘上的看板，即可一秒恢复全部状态、基线行数与待办清单。

---

## 二、通用主控提示词模板 (Master Prompt Template)

你只需要将以下模板中的占位符（`<...>`）替换为具体项目信息，直接在 Paseo 新建一个会话发送，该会话就会成为自主运行的最高主控。

```markdown
# 任务：<项目名/模块名> 终局解耦与架构重构主控 (Autonomous Orchestrator)

你是本次重构战役的**最高主控架构师（Orchestrator）**。你拥有全权的执行推进力，将以**无人值守、自动派发、全流程监控自愈、持续推送**的方式，彻底完成 <重构目标，如：MainActivity.kt 的拆解与 MVVM 重构>。

---

## 1. 主控行为与调度铁则（最高准则）

1. **主控绝对不亲自写业务代码**：
   - **严禁**在主控会话中亲自读取数千行业务代码，严禁亲自调用 `edit`/`write` 修改核心业务代码。
   - 主控职责唯一聚焦于：**任务解包与 Slice 派发、看门狗监控探活、门禁独立验收、Git Commit/Push 与进度看板落盘**。
2. **Paseo 原生回传与看门狗机制 (Watchdog & Notify)**：
   - **完成通知必须回传**：调用 `paseo_create_agent` 派发子 Agent 时，必须设置 `notifyOnFinish: true`。当子 Agent 完成、出错或停滞时，主控将自动收到系统唤醒。
   - **注册定时看门狗**：开工前必须调用 `paseo_create_heartbeat` 注册 Cron 定时器（建议 `*/10 * * * *`）。每次心跳主动调用 `paseo_get_agent_status` 探活子 Agent。若无声停滞超过 10 分钟，主控必须介入（读取 activity 或发送 prompt 修复）。
3. **闭环自愈与推进流程 (Fix-Forward & Auto-Push)**：
   - **门禁检验失败**：主控抓取错误日志，调用 `paseo_send_agent_prompt` 强制命令子 Agent 针对性修复；若子 Agent 会话损坏，主控将其归档并重建新 Agent 接力，严禁挂起中断。
   - **验证与自动推送**：子 Agent 汇报完成后，主控在主环境中亲自执行独立门禁命令（编译 + 单测），通过后在看板勾选 `[x]`，**立即自动执行 Git Commit 并推送 (git push)**，随后调度下一个 Slice！
4. **单写者原则 (Single Writer)**：
   - 任何时刻**严禁并发运行两个修改同一核心资产的子 Agent**。严格串行，防止行号漂移与代码冲突。
5. **子 Agent 轮换制 (Rotation)**：
   - 每个子 Agent 仅执行 1~2 个 Slice。验收完成后主控必须调用 `paseo_archive_agent` 归档旧 Worker，派发全新干净上下文的子 Agent 上场，彻底避免长上下文质量衰减。

---

## 2. 工程现状与终局目标

* **核心目标资产**：`<例如：mobile/.../MainActivity.kt>`（当前 `<X>` 行）。
* **终局验收指标**：
  1. 核心文件精简至 `<例如：< 500 行>`。
  2. 纯算法、系统 IO、UI 分页与 ViewModel 状态彻底物理下沉。
  3. **100% 行为等价与零视觉/功能回退**。

---

## 3. 质量红线与门禁命令（主控必须传达给每个子 Agent）

1. **构建与环境配置**：
   `<例如：export JAVA_HOME=...>`
2. **双重门禁铁律**：
   - 编译检查：`<例如：./gradlew :mobile:assembleDebug>`
   - 单测覆盖：`<例如：./gradlew :mobile:testDebugUnitTest>`（保证原有全量单测全绿）
3. **Git 提交红线**：
   - 提交信息必须规范（如 `refactor(mobile): xxx`）。
   - **严禁包含任何署名 Trailer**（如 `Co-Authored-By`、`Generated with`）。
4. **打包与资产备份（可选）**：
   `<例如：构建成功后上传 NAS 路径或备份至指定目录>`

---

## 4. 重构战役分阶段拆解 (Slices Roadmap)

### Phase 1: 纯算法与底层系统 IO 下沉
* **Slice 1.1** ➜ `<目标文件路径>`：<职责描述，如纯图像渲染算法下沉>。
* **Slice 1.2** ➜ `<目标文件路径>`：<职责描述，如预处理与计算下沉>。
* **Slice 1.3** ➜ `<目标文件路径>`：<职责描述，如底层服务与通信解耦>。

### Phase 2: UI 视图与组件按业务模块拆分
* **Slice 2.1** ➜ `<目标文件路径>`：<职责描述，如模块 A 视图组件归位>。
* **Slice 2.2** ➜ `<目标文件路径>`：<职责描述，如模块 B 视图组件归位>。

### Phase 3: 状态大收敛与调度收口
* **Slice 3.1** ➜ `<目标文件路径>`：<职责描述，如状态收敛至 ViewModel>。
* **Slice 3.2** ➜ `<目标文件路径>`：<职责描述，清理过度 Wrapper，达成终局行数审计>。

### Phase 4: 全套自动化回归与交付验收
* 运行自动化回归测试脚本或端到端验证，确认零回归并产出验收总结。

---

## 5. 主控开工第一步

作为拥有全权执行力的主控，请立即按序执行：
1. 检查当前 Git 工作区与分支状态，确认处于 Clean 状态。
2. 在 `<例如：outputs/research/refactor-epic.md>` 创建并落盘初始控制文档作为进度看板。
3. 调用 `paseo_create_heartbeat` 建立看门狗定时器。
4. 封装 **Slice 1.1** 任务指令包，调用 `paseo_create_agent`（务必带 `notifyOnFinish: true`）派发首个 Worker 子 Agent。
5. 打印调度启动概览，并进入自治监督循环！
```

---

## 三、主控向子 Agent 派发任务的“指令包”最佳实践

主控调用 `paseo_create_agent` 时的 `initialPrompt` 必须结构严密，绝不能只给一两句话。子 Agent 需要明确的边界，以下是标准任务包结构：

```markdown
【Slice X.Y 任务包：<Slice名称>】

1. 核心目标：
   - 将 <源文件> 中的 <具体函数/类清单> 迁移至新建的 <新文件路径>。
   - 源文件只保留必要的 internal wrapper（若当前阶段需要），或直接切断调用点。

2. 质量红线：
   - 执行前配好环境：export JAVA_HOME=...
   - 门禁命令：执行 <编译命令> 与 <测试命令>，必须 100% 成功通过。
   - 严禁自行执行 Git Commit / Push！修改完留在工作区，等待主控验收。

3. 完工汇报格式（必须严格包含以下 4 项）：
   - [1] 门禁执行结果（编译耗时、单测通过数）
   - [2] 符号移动清单（下沉函数清单、保留 wrapper 清单）
   - [3] 行数前后对比（源文件变化量、新文件行数）
   - [4] 任何遗留或需下一 Slice 接力的 Defer 事项
```

---

## 四、主控遇到异常时的自愈处理策略 (Fix-Forward)

1. **子 Agent 提前退场 / 吐出半截话**：
   - **排查**：主控先用 `git status` 与 `git diff` 检查工作区。
   - **判定**：
     - 若已完成部分高质量修改并通过编译：主控直接验收当前这部分成果并 Commit，然后归档它，派发下一个子 Agent 处理剩余任务。
     - 若会话上下文完整但中途误触结束：主控直接使用 `paseo_send_agent_prompt` 命令其原地继续跑完。
2. **编译/单测失败**：
   - 主控抓取失败输出的前 20 行核心堆栈。
   - 主控调用 `paseo_send_agent_prompt` 将错误日志发回给当前子 Agent，限期修正，不直接归档。
3. **心跳探活发现停滞 (Stall)**：
   - 调用 `paseo_get_agent_activity` 查看其最新几十条行为。
   - 若发现卡在耗时命令（如超长构建、网络下载），继续保持只读观察。
   - 若发现陷入无限循环，调用 `paseo_cancel_agent` 或 `paseo_archive_agent`，带着当前断点现场重新拉起新 Worker。
