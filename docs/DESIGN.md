# TARS Assistant 技术契约

> 本文只描述当前有效的架构、协议和安全边界，是实现的权威依据。
> 部署步骤见 `docs/DEPLOY.md`，参数登记见 `docs/RUNTIME_CONFIG.md`，验收记录见 `docs/AVD_TESTING.md`，历史决策见 `docs/PROJECT_PLAN.md`。

## 1. 架构边界

TARS 采用事件驱动链路：Android 原生 App 触发并执行，Termux 中的 Python Agent 负责决策，云端只提供 OpenAI-compatible 模型推理。

```text
Android App (Kotlin)
  触发 / 无障碍采集 / 动作执行
             │ HTTP 127.0.0.1:8080
Termux Agent (Python/FastAPI)
  摘要 UI / Agent 循环 / schema 校验 / 固定技能
             │ HTTPS
云端模型 API
```

职责边界：

| 组件 | 负责 | 不负责 |
|---|---|---|
| Android App | 触发入口、UI 采集、动作执行、用户确认、结果展示 | LLM 推理、云端凭据、UI 摘要决策 |
| Python Agent | HTTP 门面、UI 摘要、安全决策循环、协议校验、固定技能路由 | 直接操作 Android、保存会话状态 |
| 云端模型 | 根据任务和 UI 摘要生成候选决策 | 访问设备、执行动作、绕过 schema 或安全规则 |
| Shizuku | 受限的高权限输入/滑动回退通道 | 任意 shell、任意 Intent 或网络通信 |

隐私边界：发送到云端的内容仅包括任务文本和压缩后的 UI 摘要；API Key 只存在 Termux 私有 `config/cloud.yaml`，不得进入 APK、日志或 Git。

## 2. 一次任务

1. 定时、通知或悬浮语音产生待处理文本；用户在 App 中检查并显式发送。
2. App 采集当前无障碍 UI 树、前台包名和窗口类名。
3. App `POST /agent/run`，携带意图、UI XML 和有限的已执行历史。
4. Agent 将 XML 摘要为最多 60 个交互节点（目标约 500 token），交给决策循环。
5. 模型输出候选动作；Agent 先做 JSON Schema、动作和会话校验，再返回响应。
6. App 按白名单和确认规则执行动作。
7. 需要观察时，动作后重新采集 UI；界面新鲜度成立才进入下一轮，否则安全停止。
8. `done` 或终态 `reply` 结束任务并展示结果。

Agent 无状态；`session_id` 由 App 生成并在每轮回传。

## 3. HTTP 协议

协议版本：`1.0`。通信仅使用 JSON over HTTP，Agent 只监听 `127.0.0.1`，Android 客户端固定 loopback 并绕过系统代理。

端点：

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/health` | 存活检查 |
| `POST` | `/agent/run` | 单轮决策请求 |

### 3.1 `task_request`

```json
{
  "protocol_version": "1.0",
  "session_id": "uuid",
  "intent": "打开设置",
  "app": "com.android.settings",
  "activity": "...",
  "ui_xml": "<node ... />",
  "observation_note": "上一轮动作未使界面发生变化…",
  "history": []
}
```

`protocol_version`、`session_id`、`intent` 必填；`app`、`activity`、`ui_xml`、`observation_note`、`history` 可选。`ui_xml` 缺失按空节点处理，非法非空 XML 拒绝。`observation_note` 是执行侧在「上一轮动作未产生界面变化」后重采界面时捎带的反馈（最多 500 字），Agent 会将其注入模型提示词，提示模型不要重复一个对未变化界面无效的动作；`history` 最多 7 轮，每轮最多 8 个合法动作。

### 3.2 `agent_response`

```json
{
  "protocol_version": "1.0",
  "session_id": "uuid",
  "done": false,
  "reply": "",
  "actions": [{"type": "click", "target_node_id": 12}],
  "need_observation": true
}
```

`session_id` 必须与请求一致；`actions` 有序且最多 8 个。`done=true` 表示终态。模型单对象 `reply` 会被 Agent 规范化为 `done=true` 的文本响应，不下发 Android 执行。

### 3.3 动作

| 动作 | 参数 | 执行 |
|---|---|---|
| `click` | `target_node_id` | 无障碍点击 |
| `type` | `target_node_id`, `text` | 无障碍输入，失败时受限 Shizuku 回退 |
| `swipe` | `x1,y1,x2,y2,duration_ms` | 受限 Shizuku |
| `back` / `home` | 无 | 无障碍全局动作 |
| `launch` | `package_name` | 固定包名白名单启动 |
| `wait` | `ms` | 等待 |
| `reply` | `text` | 文本响应 |
| `done` | 无 | 结束 |

所有动作必须通过 `bridge/` Schema；非法输出 fail-closed，绝不执行。

## 4. UI 感知契约

Android 使用 `AccessibilityNodeInfo` 遍历并序列化 UI XML；`uiautomator dump` 仅为备用方案。Agent 侧 `ui_summarizer` 负责解析，Android 不做摘要决策。

摘要节点包含：`id`（整数句柄）、`type`、`text`、`bounds`、`clickable`、`focusable`、`focused`、`layer`、`depth`、`container`。只保留可交互节点或带文本的可点击节点；交互节点的标签合并自身与可见后代的文本/描述，补全复合控件语义但不新增动作节点。类名匹配使用 `button/edittext/checkbox/radiobutton/switch/imagebutton` 的 contains 语义，必须与 Android 执行侧一致。

动作目标只能引用当前摘要中的节点。`type` 命中容器时，Android 必须向下解析到实际输入组件，先聚焦再设置文本。

观察新鲜度规则：动作前保存 UI XML、前台包名和无障碍事件序号；无障碍服务须订阅窗口、内容、焦点、文本和选中事件。动作后必须取得与当前前台包名一致的 UI XML，且包名或 XML 至少一项相对动作前变化；焦点/内容/窗口事件只触发重采样，不能单独判定更新成功。仍受观察超时和最大轮数限制，超时即停止。

## 5. 安全边界

1. **协议校验**：Agent 和 Android 均校验 schema、`protocol_version`、`session_id`、历史长度和动作数量。
2. **动作白名单**：只允许 `click/type/swipe/back/home/launch/wait/reply/done`；`launch` 仅允许系统设置、TARS、Gmail、微信，禁止任意 Intent、组件、URI 和 shell 参数。
3. **敏感操作确认**：发送、删除、支付、转账等目标即使模型未标记，也由 Python 和 Android 按当前节点文本再次判定并要求用户确认。
4. **失败即收敛**：动作被拒绝、取消、失败或无法观察到新鲜 UI 时，停止本轮后续动作和观察，不把未执行动作写入历史。
5. **权限隔离**：Shizuku 只暴露参数受限的输入/滑动方法；云端不可直接访问设备。
6. **日志最小化**：审计日志只记录会话、前台上下文、节点数、动作类型和观察状态，不记录任务正文、原始 UI、输入文本、模型原文或凭据。

## 6. 模型与可靠性

Agent 当前使用 Python 自研安全循环；未来可替换编排框架，但不得改变本契约或绕过安全层。模型端点、模型 ID 和凭据来自私有配置；手机不运行 GGUF、llama.cpp 或本地模型服务。

云端请求仅对连接/超时、HTTP `429` 和 `5xx` 做有界重试；认证错误、其他 `4xx` 和无效响应直接失败，错误信息不得泄露 API Key。

## 7. 当前状态

- 已完成：基础协议、Agent 服务、云端接入、Android 执行侧、一期触发入口、AVD 基线、敏感操作确认；多图层 UI 采集、可见性规划（先图层再坐标）、窗口图层/完整矩形/交互状态并入 prompt、动作后新应用冷启动采集宽限与超时放宽。
- 未闭环：Gmail 建议卡把主题/正文挤到输入法区这类**应用特有布局**不宜作为通用基准，改用表单覆盖后无该怪癖的应用（如联系人）实测；`awaitFresh`/冷启动采集已在 Android 端加固。
- 二期候选：精确启动目标应用、常驻唤醒词。受限运行参数设置界面已完成：所有 A 类参数由 Android 设置页在范围与 loopback 校验后持久化，支持恢复安全默认值。

实现变更前先更新本契约；过程记录和测试证据分别写入项目计划与 AVD 验收文档。
