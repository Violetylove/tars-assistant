# TARS Assistant 技术契约

> 本文是当前架构、协议和安全边界的权威来源。部署见 `docs/DEPLOY.md`，运行参数见
> `docs/RUNTIME_CONFIG.md`，代码导航见 `docs/REPO_MAP.md`。历史决断书
> `docs/Violetylove_DECISIONS.md` 独立保存，不在本文重复。

## 架构与隐私边界

```text
Android App (Kotlin)
  触发 / 无障碍采集 / 动作执行 / 用户确认
                 | HTTP
Termux Agent (Python / FastAPI)
  UI 摘要 / 安全决策循环 / schema 校验 / 固定技能
                 | HTTPS
云端 OpenAI-compatible 模型 API
```

| 组件 | 职责 | 明确不负责 |
|---|---|---|
| Android App | 采集 UI、发送请求、执行已校验动作、展示与确认 | 模型推理、保存云端凭据、UI 摘要决策 |
| Python Agent | HTTP 门面、UI 摘要、模型编排、协议校验、固定技能 | 直接操作 Android、持久会话状态 |
| 云端模型 | 根据任务与 UI 摘要生成候选动作 | 访问设备、执行动作、绕过安全层 |
| Shizuku | 受限的输入和滑动回退 | 任意 shell、任意 Intent、网络通信 |

云端只接收任务文本和压缩后的 UI 摘要。API Key 仅存在 Agent 所在机器的私有
`config/cloud.yaml`，不得进入 APK、Git 或任何日志。Agent 默认监听 `0.0.0.0:8080`；Android
默认连接同机 `127.0.0.1:8080`，也可连接受信任远程主机。`0.0.0.0` 只能用作服务监听地址，不能
作为客户端目标。

## 任务生命周期

1. 用户在对话页发送意图，或载入定时、通知、悬浮语音产生的待处理任务。
2. App 先检查无障碍和 Agent 可用性；未就绪时不发送请求，并给出设置入口。
3. App 采集前台包名、窗口类名和原始 UI XML，向 `/agent/run` 提交有限 history 与当前用户授权的
   应用目录。
4. Agent 将 XML 压缩为最多 60 个交互节点，连同窗口层级和结构事实传给模型。
5. 模型候选输出经 JSON Schema、会话、动作与启动目录校验后才返回 Android。
6. Android 再次执行白名单、节点匹配和敏感确认；拒绝、取消或失败会停止本轮剩余动作。
7. `need_observation=true` 时等待动作后稳定 UI；`done` 或文本 `reply` 为终态并显示结果。

Agent 无状态。每个任务由 Android 生成 16 位十六进制 `session_id`，每一轮请求和 Android/Agent
日志都使用该 ID 关联。

## HTTP 协议

协议版本为 `1.0`，仅使用 JSON over HTTP。

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/health` | 存活检查 |
| `POST` | `/agent/run` | 单轮 Agent 决策 |
| `POST` | `/logs/android` | 接收用户主动上传的 Android 诊断日志 |

### 请求 `task_request`

```json
{
  "protocol_version": "1.0",
  "session_id": "895097fd0db74f89",
  "intent": "打开设置",
  "app": "com.android.settings",
  "activity": "...",
  "ui_xml": "<hierarchy ... />",
  "launchable_apps": [{"label": "Gmail", "package_name": "com.google.android.gm"}],
  "observation_note": "上一轮动作未使界面发生变化",
  "history": []
}
```

`protocol_version`、`session_id`、`intent` 必填。可选 XML 必须是合法 XML；`launchable_apps` 最多
50 项，且只能来自用户勾选、当前仍安装的 launcher 应用；`history` 最多 7 轮，每轮最多 8 个合法
动作。`observation_note` 最多 500 字，用于说明上一轮无变化，避免模型重复同一无效动作。

### 响应 `agent_response`

```json
{
  "protocol_version": "1.0",
  "session_id": "895097fd0db74f89",
  "done": false,
  "reply": "",
  "actions": [{"type": "click", "target_node_id": 12}],
  "need_observation": true
}
```

响应的 `session_id` 必须完全等于请求；`actions` 有序且最多 8 项。单对象 `reply` 会被 Agent
规范为终态文本响应，不触发 Android 动作。

| 动作 | 参数 | 约束 |
|---|---|---|
| `click` | `target_node_id` | 当前摘要节点的无障碍点击 |
| `type` | `target_node_id`, `text` | 先无障碍输入，失败后才可走受限 Shizuku 回退 |
| `swipe` | `x1,y1,x2,y2,duration_ms` | 仅参数受限的 Shizuku 输入 |
| `back` / `home` | 无 | 无障碍全局动作 |
| `launch` | `package_name` | 必须在本轮用户授权且仍已安装的应用目录中 |
| `wait` | `ms` | 有界等待 |
| `reply` | `text` | 文本响应 |
| `done` | 无 | 结束任务 |

全部动作必须通过 `bridge/` schema；任何非法输出均 fail-closed。

## UI 感知与观察新鲜度

Android 使用 `AccessibilityNodeInfo` 序列化原始 XML；`uiautomator dump` 仅是调试备用。摘要逻辑
完全在 Agent 的 `ui_summarizer`：节点包含 ID、类型、文本、bounds、交互状态、层级、深度和容器
标记。动作 ID 只属于可交互节点；Android 与 Python 必须采用相同筛选和排序规则。

采集支持多应用窗口层。`getWindows()` 的应用窗口为空骨架或无可访问内容时不能视为有效树；此时
回退 `rootInActiveWindow`。空骨架仅写入 Android 本地诊断日志，绝不作为 `/agent/run` 的 UI XML。
动作后进入下一轮前必须同时满足：

1. 树包含可访问内容，且 XML 与当前前台包名一致。
2. 前台包名或 XML 相对动作前发生变化。
3. 同一新鲜 XML 连续采集两次一致。

无障碍事件只触发重新采样，不能单独证明 UI 已更新。新启动应用的无障碍树为空时，App 在本地的
受限等待窗口内重采稳定树；超时则记录拒绝采集并安全停止，不使用陈旧节点，也不让模型对空树
反复输出 `wait`。

## 安全规则

1. **双层协议校验**：Agent 与 Android 均校验协议版本、schema、session ID、history 与动作数量。
2. **启动目录**：`launch` 只启动用户勾选且当前仍安装的包名，禁止任意 Intent、组件、URI 和 shell
   参数。
3. **敏感确认**：发送、删除、支付、转账等语义由 Python 与 Android 分别判断。Android 通过
   `TYPE_ACCESSIBILITY_OVERLAY` 在当前前台应用上显示确认浮层，显示动作、控件类型和文本，但不泄露
   内部节点编号。
4. **失败收敛**：拒绝、取消、执行失败或 UI 不新鲜时不执行后续动作，也不把未执行动作写进 history。
5. **权限隔离**：Shizuku 只暴露参数受限的输入/滑动；云端无法直接访问设备。
6. **日志分层**：Agent 审计日志不保存原始 XML；Android 诊断日志保存 XML、Agent 动作和实际节点。
   有效树缺失时还记录窗口根与事件来源的结构元数据，且仅在状态变化时追加；日志仅在用户主动上传
   时发送到 Agent。

## 可靠性与当前范围

云端请求只对连接/超时、HTTP `429` 和 `5xx` 有界重试；认证错误、其他 `4xx` 和无效响应立即失败，
且错误不得泄露 API Key。手机不运行本地模型。

当前已具备对话入口、一期触发器、运行设置、应用授权目录、诊断上传和多轮安全闭环。常驻唤醒词
仍因 Android 权限约束而暂缓；复杂应用的特有布局须按实际无障碍树单独验证，不能用 Gmail 的建议卡
行为代表通用表单能力。
