# TARS Assistant RepoMap

> 面向开发者与编码 Agent 的仓库导航。架构与安全契约以 `docs/DESIGN.md` 为准；本文件只回答“代码在哪、由谁调用、修改时要同步什么”。

## 1. 主链路

```text
MainActivity / 触发器
  -> TarsAccessibilityService.currentUiXml()
  -> AgentClient POST 127.0.0.1:8080/agent/run
  -> agent.server.agent_run()
  -> agent.agent_loop.decide_once()
  -> agent.ui_summarizer (动作节点 + 结构事实)
  -> 云端 LLM
  -> bridge schema 校验
  -> TarsAccessibilityService.execute()
  -> ActionExecutor / ShizukuGateway
  -> awaitFreshUiAfter() -> 下一轮或安全停止
```

## 2. 目录与入口

| 路径 | 职责 | 主要入口/符号 |
|---|---|---|
| `android/` | Android 感知、触发、执行与用户确认 | `MainActivity`、`TarsAccessibilityService` |
| `agent/` | Termux FastAPI 服务、UI 摘要、模型决策 | `server.main`、`server.agent_run`、`agent_loop.decide_once` |
| `bridge/` | 请求、响应、动作的 JSON Schema 及校验 | `schemas.SCHEMAS`、`validate.validate` |
| `config/` | 云端模型私有配置模板 | `cloud.yaml.example`；真实 `cloud.yaml` 不入库 |
| `scripts/` | 本机协议烟测 | `smoke_agent.py` |
| `examples/` | 协议示例 JSON | `task_request.json`、`agent_response.json` |
| `docs/` | 契约、部署、验收和项目导航 | `DESIGN.md`、本文件 |

## 3. Android 执行侧

| 文件 | 关键符号 | 调用关系与修改边界 |
|---|---|---|
| `MainActivity.kt` | 任务循环、`MAX_OBSERVATION_ROUNDS` | 收集 XML/前台/历史，调用 Agent，执行响应，等待新 UI；不在这里做 UI 摘要或模型决策。 |
| `TarsAccessibilityService.kt` | `currentUiXml`、`collectVisibleWindowRoots`、`collectVisibleNodes`、`awaitFreshUiAfter` | 无障碍事件、窗口树采集、XML 序列化、动作后新鲜度判定。`collectVisibleNodes` 的动作节点排序必须和 Python 摘要一致。 |
| `ActionExecutor.kt` | `execute`、`findNode`、`findEditableNode` | 动作白名单、敏感操作确认、摘要 ID -> 活节点映射。修改交互筛选/排序时必须同步 `agent/ui_summarizer.py`。 |
| `AgentClient.kt` | `run`、`request` | 只允许直连 `127.0.0.1:8080`；校验 response 的 session ID。 |
| `Protocol.kt` | `TaskRequest`、`AgentAction`、`AgentResponse` | Android 协议模型；字段修改需同步 `bridge/schemas.py` 和设计契约。 |
| `ShizukuGateway.kt` | `typeText`、`swipe` | Shizuku 仅作受限文本输入和滑动回退，不采集 UI、不执行任意 shell。 |
| `ShellInputUserService.kt` / `IInputService.aidl` | Shizuku UserService | 为 `ShizukuGateway` 提供进程外输入能力。 |
| `NotificationTriggerService.kt` | `onNotificationPosted` | 将通知保存为待确认任务，从不直接调用 Agent。 |
| `TaskScheduler.kt` | `scheduleIn`、`ScheduledTaskReceiver` | 定时任务转为待确认任务。 |
| `PendingTriggerStore.kt` / `TriggerNotifier.kt` | 持久化与通知 | 触发内容的本地保存和用户提示。 |
| `VoiceIntentCapture.kt` / `FloatingVoiceOverlay.kt` | 语音入口 | 语音仅填入或提交任务意图；不承载 Agent 决策。 |

### Android UI 事实链路

`AccessibilityNodeInfo` 经 `UiTreeXml.serializeWindows()` 变为 XML。XML 保留 `text`、`content-desc`、`resource-id`、`class`、`package`、交互状态与 `bounds`，并以 `<window layer="N">` 表示窗口层级。

`collectVisibleNodes()` 是执行侧动作 ID 的事实来源。它必须保留 Python 摘要会保留的交互节点，包括零尺寸但可交互的节点；零尺寸节点不参与遮挡矩形计算。

## 4. Python Agent 侧

| 文件 | 关键符号 | 调用关系与修改边界 |
|---|---|---|
| `server.py` | `main`、`configure_runtime`、`agent_run` | FastAPI HTTP 门面、请求 XML 校验、固定技能优先路由、决策调用和日志。`--log-file` 同时输出后台日志文件。 |
| `agent_loop.py` | `SYSTEM_PROMPT`、`_build_user_message`、`decide_once` | 将任务、动作节点、结构事实和历史交给模型；解析 JSON，规范化已知节点 ID，标记敏感点击。这里不定义具体 App 组件的业务语义。 |
| `ui_summarizer.py` | `summarize_xml`、`to_llm_prompt`、`to_llm_context` | XML -> 有动作 ID 的紧凑节点列表；附带有界的原始结构事实。动作列表用于执行，结构事实仅供模型理解，不生成动作 ID。 |
| `llm_client.py` | `LLMClient`、`MockLLM`、`extract_json` | OpenAI-compatible 请求、有界重试、测试 mock。 |
| `cloud_config.py` | `load_cloud_config` | 读取私有云端配置。 |
| `skill_router.py` | `route_fixed_skill` | 固定技能短路；返回值同样必须符合协议。 |
| `test_phase2.py` | Agent/摘要/服务测试 | 主要 Python 回归测试文件。 |

### 模型输入组成

`decide_once()` 每轮组成：

```text
用户意图
当前前台应用包名 / 窗口类名
当前屏幕节点（可执行 ID 列表）
当前屏幕结构事实（窗口、路径、resource-id、class、package、bounds、状态）
前面的动作/观察
```

结构事实只报告无障碍树中已有的属性。它不把组件标记为“遮挡物”“建议卡”或“必须关闭”，由模型自行判断当前组件应选择、关闭或忽略。

## 5. 协议与安全

| 文件 | 关键符号 | 不变量 |
|---|---|---|
| `bridge/schemas.py` | `ACTION_TYPES`、`SCHEMAS` | 协议 `1.0`；所有 action 必须匹配参数约束。 |
| `bridge/validate.py` | `validate`、`validate_action` | 所有模型返回在执行前 fail-closed 校验。 |
| `bridge/test_validate.py` | Schema 回归测试 | 协议字段或 action 修改时必须扩充。 |

敏感语义的确认由 Python 的 `_enforce_sensitive_confirmation` 和 Android 的 `ActionExecutor.requiresConfirmation` 双重执行。`发送/删除/支付` 等操作不得仅相信模型的 `requires_confirmation` 字段。

## 6. 高频修改路线

| 目标 | 优先修改位置 | 必须同步检查 |
|---|---|---|
| 改 UI 采集内容 | `TarsAccessibilityService.kt` | XML 有效性、动作节点 ID 对齐、`ui_summarizer.py` 测试。 |
| 改模型所见 UI 信息 | `ui_summarizer.py`、`agent_loop.py` | 不改变动作 ID 含义；控制 token/字符预算；补 `test_phase2.py`。 |
| 改动作能力 | `bridge/schemas.py`、`Protocol.kt`、`ActionExecutor.kt` | `DESIGN.md`、两端校验、敏感确认与白名单。 |
| 改观察/时序 | `TarsAccessibilityService.awaitFreshUiAfter`、`MainActivity.kt` | 跨应用、IME、空 XML、超时的 AVD 日志证据。 |
| 改 Shizuku 能力 | `IInputService.aidl`、`ShellInputUserService.kt`、`ShizukuGateway.kt` | 参数边界、授权失败路径、不得开放任意 shell。 |
| 改云端配置/模型 | `cloud_config.py`、`config/cloud.yaml.example` | 私有 `cloud.yaml` 不入 Git；mock 测试仍应离线可运行。 |

## 7. 验证命令

```powershell
# Python 单测（使用项目虚拟环境）
.\.venv\Scripts\python.exe -m pytest agent\ bridge\

# Android 构建（在 android 目录）
cd android
.\gradlew.bat :app:assembleDebug

# 协议烟测
.\.venv\Scripts\python.exe -m bridge.validate
```

真实设备/AVD 联调记录、后台日志读取方式和安全测试边界见 `docs/AVD_TESTING.md` 与 `docs/DEPLOY.md`。

## 8. 修改前检查清单

1. 是否改变 Android <-> Agent 契约？若是，先更新 `docs/DESIGN.md` 与 `bridge/`。
2. 是否改变摘要动作 ID？若是，确认 `collectVisibleNodes()` 与 `summarize_xml()` 的过滤、排序和截断一致。
3. 是否新增可执行高权限能力？若是，定义参数白名单、失败路径和确认策略。
4. 是否会改变模型所见数据？记录 token/字符上限，避免把私有配置或原始敏感 UI 写入日志。
5. 是否修改项目文件？完成对应测试、Git 提交和 AVD 验证（涉及 Android 行为时）。
