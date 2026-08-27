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
| Android App | 采集并摘要 UI、发送请求、执行已校验动作、展示与确认 | 模型推理、保存云端凭据、持久会话状态 |
| Python Agent | HTTP 门面、节点校验与模型编排、协议校验、固定技能 | 直接操作 Android、持久会话状态、UI 摘要 |
| 云端模型 | 根据任务与 UI 摘要生成候选动作 | 访问设备、执行动作、绕过安全层 |
| Shizuku | 受限的输入和滑动回退 | 任意 shell、任意 Intent、网络通信 |

云端只接收任务文本和压缩后的 UI 摘要（Android 侧已摘要的紧凑节点）。API Key 仅存在 Agent
所在机器的私有 `config/cloud.yaml`，不得进入 APK、Git 或任何日志。Agent 默认监听 `0.0.0.0:8080`；
Android 默认连接同机 `127.0.0.1:8080`，也可连接受信任远程主机。`0.0.0.0` 只能用作服务监听地址，
不能作为客户端目标。

## 任务生命周期

1. 用户在对话页发送意图，或载入定时、通知、悬浮语音产生的待处理任务。
2. App 先检查无障碍和 Agent 可用性；未就绪时不发送请求，并给出设置入口。
3. App 采集前台包名、窗口类名，并在本机将 UI 摘要为紧凑节点（`UiSummarizer.kt`，采集即摘要），
   向 `/agent/run` 提交 `nodes`、`window_layers` 与有限 history、当前用户授权的应用目录。
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
  "nodes": [
    {"id": 0, "_resource_id": "com.android.settings:id/entry", "type": "button",
     "text": "设置", "bounds": [0, 0, 100, 50], "clickable": true,
     "focusable": false, "focused": false, "layer": 0, "depth": 0, "container": ""}
  ],
  "window_layers": "- application@层0 bounds=[0,0][1080,2400]",
  "launchable_apps": [{"label": "Gmail", "package_name": "com.google.android.gm"}],
  "observation_note": "上一轮动作未使界面发生变化",
  "history": []
}
```

`protocol_version`、`session_id`、`intent` 必填。`nodes` 是 Android 侧已摘要的交互节点（最多 60
项，动作 ID 与执行侧同源），为事实来源；`ui_xml` 仅作旧客户端/测试回退（若提供必须是合法 XML）。
`launchable_apps` 最多 50 项，且只能来自用户勾选、当前仍安装的 launcher 应用；`history` 每轮最多
8 个合法动作，**轮数上限由 Android 侧用户设置的 `max_observation_rounds` 决定，协议不再设上限**。
`observation_note` 最多 500 字，用于说明上一轮无变化，避免模型重复同一无效动作。

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

Android 在 `UiSummarizer.kt` 中把 `AccessibilityNodeInfo` 树摘要为紧凑节点（采集即摘要）：节点
包含 ID、类型、文本、bounds、交互状态、层级、深度和容器标记。**摘要节点与执行节点同源**——同一
份节点列表既用于请求、也用于执行与确认，因此动作 ID 天然一致，无需跨端对齐。`agent/ui_summarizer.py`
保留仅作测试与旧客户端回退（语义与 `UiSummarizer.kt` 对齐）。序列化 XML 只作为本地新鲜度指纹，
不发送给 Agent。

采集支持多应用窗口层。`getWindows()` 的应用窗口为空骨架或无可访问内容时不能视为有效树；此时
回退 `rootInActiveWindow`；两者均为空骨架时，再回退到最近一次窗口级无障碍事件的有效
`event.source`。事件回退只缓存 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOW_CONTENT_CHANGED`，
不会使用焦点或文本变化事件中的局部节点。空树不发送任何节点：仅在本地等待稳定树，超时则记录并
安全停止。
动作后进入下一轮前必须同时满足：

1. 树包含可访问内容，且摘要与当前前台包名一致。
2. 前台包名或摘要相对动作前发生变化。
3. 同一新鲜摘要连续采集两次一致。

无障碍事件只触发重新采样，不能单独证明 UI 已更新；事件来源只作为窗口根失效时的回退。新启动应用的无障碍树为空时，App 在本地的
受限等待窗口内重采稳定树；超时则记录拒绝采集并安全停止，不使用陈旧节点，也不让模型对空树
反复输出 `wait`。

## 安全规则

1. **双层协议校验**：Agent 与 Android 均校验协议版本、schema、session ID、history 结构与动作数量；
   history 轮数上限由 Android 侧用户设置的 `max_observation_rounds` 决定，协议不设上限。
2. **启动目录**：`launch` 只启动用户勾选且当前仍安装的包名，禁止任意 Intent、组件、URI 和 shell
   参数。
3. **敏感确认**：发送、删除、支付、转账等语义由 Python 与 Android 分别判断。Android 通过
   `TYPE_ACCESSIBILITY_OVERLAY` 在当前前台应用上显示确认浮层，显示动作、控件类型和文本，但不泄露
   内部节点编号。
4. **失败收敛**：拒绝、取消、执行失败或 UI 不新鲜时不执行后续动作，也不把未执行动作写进 history。
5. **权限隔离**：Shizuku 只暴露参数受限的输入/滑动；云端无法直接访问设备。
6. **日志分层**：Agent 审计日志不保存原始 XML；Android 诊断日志保存摘要节点、Agent 动作和实际
   节点（不再保存原始 XML）。有效树缺失时还记录窗口根与事件来源的结构元数据，且仅在状态变化时
   追加；日志仅在用户主动上传时发送到 Agent。

## 可靠性与当前范围

云端请求只对连接/超时、HTTP `429` 和 `5xx` 有界重试；认证错误、其他 `4xx` 和无效响应立即失败，
且错误不得泄露 API Key。手机不运行本地模型。

自部署模型接入：默认强制 HTTPS 并校验证书；`config/cloud.yaml` 中 `llm.allow_insecure_http=true`
允许明文 HTTP 端点（仅限可信网络，密钥会明文传输），`llm.verify_ssl=false` 跳过 TLS 证书校验
（自签名证书端点用）。两者均为显式 opt-in，且仅在自托管场景开启。自托管时放宽 `api_key` 长度与
HTTPS 校验，以兼容 Ollama / llama.cpp 等本地 OpenAI-compatible 服务。

当前已具备对话入口、一期触发器、运行设置、应用授权目录、诊断上传和多轮安全闭环。常驻唤醒词
仍因 Android 权限约束而暂缓；复杂应用的特有布局须按实际无障碍树单独验证，不能用 Gmail 的建议卡
行为代表通用表单能力。
