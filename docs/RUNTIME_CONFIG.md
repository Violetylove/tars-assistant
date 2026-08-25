# 运行时可配置参数登记 — TARS Assistant

> 本文件是**运行时配置的登记表**：列出影响 TARS 运行时行为的可变参数，标明每个参数的
> 当前默认值、代码归属、取值范围/校验边界，以及是否计划开放给用户配置。
>
> 用途：为二期"用户运行配置"（见 `docs/PROJECT_PLAN.md` §二期候选）提供统一的登记依据；
> 任何新增/调整运行时参数应先在此登记，再实现代码，避免参数散落、难以审计。
>
> 分类约定：
> - **A 类 — 计划用户可配置**：设置界面可开放，但所有值必须过范围与格式校验，且支持恢复安全默认值。
> - **B 类 — 安全白名单**：为安全边界而恒定，**不开放**给用户配置（改变即破坏协议/防线）。
> - **C 类 — 已配置化**：已有私有配置承载（如云端 `config/cloud.yaml`），不经 Android 设置页。

---

## A 类 — 计划用户可配置

这些是未来设置界面应开放的受限运行参数。开放时全部需要范围校验，并提供安全默认值恢复。

| 参数 | 当前默认值 | 代码归属 | 建议范围/校验 |
|---|---|---|---|
| `max_observation_rounds` | `12` | `RuntimeSettings.kt` | 已在设置页开放；仅允许 1–20，过低会截断突发组件后的多轮流程 |
| `observation_timeout_ms` | `5000` | `RuntimeSettings.kt` / `MainActivity.kt` | 已在设置页开放；仅允许 2000–10000，动作后等待 UI 变化的新鲜度窗口 |
| `agent_host` | `127.0.0.1` | `RuntimeSettings.kt` / `AgentClient.kt` | 已在设置页开放；允许有效 IPv4、IPv6 或域名，不能含协议、路径或端口；远程 HTTP 端点会传输任务文本与 UI 摘要，应使用受信任网络 |
| `agent_port` | `8080` | `RuntimeSettings.kt` / `AgentClient.kt` | 已在设置页开放；仅允许 1–65535，必须与服务端一致 |
| `model_request_timeout_ms` | `210000` | `RuntimeSettings.kt` / `AgentClient.kt` | 已在设置页开放；仅允许 60000–600000，覆盖云端推理与重试预算 |
| `manual_reminder_delay_ms` | `900_000`（15 分钟） | `RuntimeSettings.kt` / `SettingsActivity.kt` | 已在设置页开放；仅允许 1 分钟到 7 天 |
| `new_app_grace_ms` | `4000` | `RuntimeSettings.kt` / `TarsAccessibilityService.kt` | 已在设置页开放；仅允许 0–10000，新应用冷启动时为空树的额外等待宽限 |

## B 类 — 安全白名单（不开放）

改变这些即破坏协议/安全边界，**不得**暴露给用户配置，也不得由 LLM 或运行时输入覆盖。

| 参数 | 当前值 | 代码归属 | 说明 |
|---|---|---|---|
| 允许的 action 类型 | `click,type,swipe,back,home,launch,wait,reply,done` | `ActionExecutor.kt` `ALLOWED` / `bridge/schemas.py` `ACTION_TYPES` | 动作白名单，两处必须一致 |
| 可启动包名白名单 | 系统设置、TARS、Gmail、微信 | `ActionExecutor.kt` `LAUNCHABLE_PACKAGES` / `skill_router.py` `_LAUNCH_SKILLS` | `launch` 仅能启动这些包；不开放 |
| 敏感标签 | `发送,删除,清除,支付,付款,转账,send,delete,pay` | `ActionExecutor.kt` `SENSITIVE_LABELS` / `agent_loop.py` `_SENSITIVE_LABELS` | 触发强制二次确认；**不可放宽**，必要时只能加严 |
| history 轮数上限 | `7` | `bridge/schemas.py` | 每任务最多回传 7 轮历史 |
| 单轮动作数量上限 | `8` | `bridge/schemas.py` | 单次 `agent_response.actions` 与单轮 history 均 ≤ 8 |
| 摘要节点上限 | `60` | `agent/ui_summarizer.py` `MAX_NODES` | 单屏摘要 ≤ 60 节点（≤500 token）；Android `findNode` 取前 60 与之对齐 |
| UI 类名判定 token | `button,edittext,checkbox,radiobutton,switch,imagebutton` | `ui_summarizer.py` `_IMPORTANT_CLASS_TOKENS` / `ActionExecutor.collect()` | 两端必须一致的 contains 语义，避免 ID 漂移 |

## C 类 — 已配置化（私有配置承载）

这些已由私有配置文件控制，不经 Android 设置页展示/编辑。

| 参数 | 默认值 | 承载 | 校验边界 |
|---|---|---|---|
| `llm.base_url` | 无（必填） | `config/cloud.yaml` | 必须以 `https://` 开头 |
| `llm.model` | 无（必填） | `config/cloud.yaml` | 非空、非 `replace-with-*` |
| `llm.api_key` | 无（必填） | `config/cloud.yaml` | 长度 ≥ 8、非 `REPLACE_*`；仅存 Termux 私有文件 |
| `llm.timeout_seconds` | `60` | `config/cloud.yaml` | > 0 |
| `llm.max_retries` | `2` | `config/cloud.yaml` | 0–3 |
| `llm.retry_backoff_seconds` | `1` | `config/cloud.yaml` | 0–10 |

## 维护约定

1. **协议先行**：新增/调整协议相关参数（动作、白名单、边界）必须先改 `docs/DESIGN.md` 与 `bridge/` schema，再更新本登记表与代码。
2. **A 类开放时**：所有 A 类参数均已在 `SettingsActivity` 开放，由 `RuntimeSettings` 集中校验、持久化并提供恢复安全默认值入口。
3. **B 类严禁放宽**：白名单只能收紧、不能放宽；评审任何改动时重点核对 B 类。
4. **一致性**：`ui_summarizer.py` 与 Android `collect()` 的可交互判定、`ALLOWED` 与 `ACTION_TYPES`、两端敏感标签必须始终同步，改一处必须改两处。
