# TARS Assistant 代码导航

> 本文说明代码位置、调用关系和修改同步点。技术契约以 `docs/DESIGN.md` 为准。

## 主链路

```text
MainActivity / 触发器
  -> TarsAccessibilityService.captureUiSnapshot()
  -> UiSummarizer（采集即摘要，节点=执行节点）
  -> AgentClient POST /agent/run（nodes + window_layers）
  -> agent.server.agent_run()
  -> agent_loop.decide_once() + 云端模型
  -> bridge schema 校验
  -> TarsAccessibilityService.execute()
  -> ActionExecutor / ShizukuGateway
  -> awaitFreshUiAfter() -> 下一轮或安全停止
```

## 目录

| 路径 | 职责 |
|---|---|
| `android/` | Kotlin 执行侧、对话 UI、设置、无障碍与 Shizuku |
| `agent/` | FastAPI 服务、模型循环、固定技能和 UI diff |
| `bridge/` | JSON Schema 与协议校验 |
| `config/` | 私有云端配置模板；真实 `cloud.yaml` 不入库 |
| `scripts/` | Agent 部署与协议烟测 |
| `docs/` | 契约、部署、验收、配置和代码导航 |

## Android 执行侧

| 文件 | 关键职责 | 修改时注意 |
|---|---|---|
| `MainActivity.kt` | 对话 UI、任务循环、轮数上限检查、终止、history、Android 日志写入 | 不在此做模型决策；轮数上限=用户设置 `maxObservationRounds` |
| `UiSummarizer.kt` | 无障碍树 → 紧凑节点（采集即摘要）；摘要节点与执行节点同源 | 摘要字段/筛选改动须同步回退实现 `agent/ui_summarizer.py` |
| `SettingsActivity.kt` | 权限入口、运行参数、日志上传、应用列表入口 | 配置规则仍归 `RuntimeSettings` |
| `LaunchableAppsActivity.kt` / `LaunchableApps.kt` | 枚举、刷新、勾选和校验 launcher 应用 | 保存前后均验证应用仍安装 |
| `TarsAccessibilityService.kt` | 多窗口采集、事件根回退、前台上下文、稳定 UI 等待、`UiSnapshot` | 摘要与执行共用同一节点列表；原始 XML 仅作本地新鲜度指纹，不发送 |
| `ActionExecutor.kt` | 动作白名单、节点匹配、输入解析、敏感确认 | 动作/敏感语义改动须同步 Python 和 schema |
| `ActionConfirmationOverlay.kt` | 前台无障碍敏感确认浮层 | 不泄露内部节点编号 |
| `AgentClient.kt` | `/health`、`/agent/run`、取消连接、日志上传、HTTP 诊断 | 校验响应 session ID |
| `AndroidLogStore.kt` | 私有 Android 诊断日志 | 仅本地写入，用户主动上传 |
| `RuntimeSettings.kt` | 默认值、校验、持久化和应用目录 | 同步 `RUNTIME_CONFIG.md` |
| `ShizukuGateway.kt`、`ShellInputUserService.kt`、`IInputService.aidl` | 受限输入/滑动回退 | 不得开放任意 shell |
| `NotificationTriggerService.kt`、`TaskScheduler.kt`、`PendingTriggerStore.kt`、`TriggerNotifier.kt` | 通知/定时任务 | 只产生待处理任务，不能直接调用 Agent |
| `VoiceIntentCapture.kt`、`FloatingVoiceOverlay.kt` | 语音录入与入口浮层 | 用户仍须检查并发送 |

## Python Agent

| 文件 | 关键职责 | 修改时注意 |
|---|---|---|
| `server.py` | HTTP 门面、请求/响应校验、日志上传、会话级 UI diff 缓存 | 优先采用请求 `nodes`；`ui_xml` 仅回退摘要；不保存原始 XML |
| `agent_loop.py` | 系统提示、模型消息、解析、敏感确认和响应规范化 | 保持 schema fail-closed |
| `ui_summarizer.py` | 回退摘要：XML 到节点、窗口层级和 prompt（测试/旧客户端） | 语义须与 Android `UiSummarizer.kt` 对齐 |
| `ui_diff.py` | 连续 UI 摘要的紧凑变化描述 | 缓存空摘要前必须确认不会覆盖有效上下文 |
| `llm_client.py` | OpenAI-compatible 请求与有界重试 | 错误不可泄露 API Key |
| `cloud_config.py` | 私有云端配置读取 | 禁止把实际配置加入 Git |
| `skill_router.py` | 固定技能路由 | `launch` 仍须在用户授权目录中 |
| `test_phase2.py` | Agent、服务、摘要和日志接口回归测试 | 改协议/安全行为时扩充测试 |

## 协议与不变量

`bridge/schemas.py` 定义协议和动作，`bridge/validate.py` 执行校验。Android 的 `Protocol.kt` 必须与之
同步。UI 筛选、类名匹配、节点排序、动作白名单和敏感标签均有跨端一致性要求。

修改路径：

1. 改协议、动作或参数边界：先改 `docs/DESIGN.md` 与 `bridge/`，再改 Android/Python。
2. 改 UI 采集或节点 ID：同步 `TarsAccessibilityService.kt`、`ActionExecutor.kt` 与
   `ui_summarizer.py`，并覆盖空树、图层与输入节点测试。
3. 改观察时序：检查前台包名、XML 有效性、稳定采样和超时收敛。
4. 改设置项：同步 `RuntimeSettings.kt`、设置 UI 与 `docs/RUNTIME_CONFIG.md`。

## 验证命令

```powershell
.\.venv\Scripts\python.exe -m pytest agent bridge -q

cd android
.\gradlew.bat :app:assembleDebug

cd ..
.\.venv\Scripts\python.exe -m bridge.validate
```

真实 AVD/设备联调、日志读取和发布流程见 `docs/AVD_TESTING.md` 与 `docs/DEPLOY.md`。
