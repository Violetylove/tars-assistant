# 设计书 — TARS Assistant

> 本文件是系统的**权威技术契约**：架构、数据流、通信协议、安全边界均以此为准。
> 任何架构变更须先更新本文档，再改代码（约定见 AGENTS.md「协议先行」）。

## 1. 概述

AI 手机助手：核心原则：**事件驱动、协议先行、LLM 输出不可信、安全兜底**。

- 目标设备：Android（裸 Termux + venv 承载 Python Agent）；云端仅提供模型 API
- 开发环境：Windows + ADB + Android Studio AVD
- 感知：纯文本 UI 树（非视觉）；模型：云端 OpenAI-compatible 模型，经私有配置选择
- 隐私边界：Agent 向云端模型发送任务文本与压缩后的 UI 摘要；云端模型服务商的数据处理策略由部署者选择并负责评估

## 2. 系统架构

```
┌──────────────────────────────────────────────┐
│                Android 手机                    │
│                                               │
│  ┌──────────────────────────┐  HTTP(loopback) │
│  │  原生 Kotlin App (执行侧)  │─────▶┌──────────────────────┐ │
│  │  触发:定时/通知/悬浮语音     │◀─────│  :8080 Agent 服务     │ │
│  │  UI采集:uiautomator/无障碍  │ JSON │  FastAPI              │ │
│  │  执行:无障碍性能/Shizuku    │      └────────┬─────────────┘ │
│  └──────────┬───────────────┘               │ 决策循环             │
│             │ Shizuku 高权限                 ┌─▼─────────────────┐ │
│  ┌──────────▼───────────────┐                │ 安全 Agent 循环     │ │
│  │ Shizuku server (系统权限)  │◀───────────────│ ui_summarizer     │ │
│  │ input tap/swipe/text     │                │ llm_client        │ │
│  └──────────────────────────┘                └─────────┬─────────┘ │
└──────────────────────────────────────────────┘
```

Agent 经 HTTPS 调用云端模型 API，并发送任务文本与压缩后的 UI 摘要。云端不直接暴露 Android
执行端点、不会执行动作，也不承载 Agent 的 schema 校验或安全策略。

## 3. 组件职责

| 组件 | 职责 | 位置 |
|------|------|------|
| 原生 App（执行侧） | 触发入口（定时/通知/悬浮语音）、UI 树采集（uiautomator）、动作执行、结果回传 | `android/`（Kotlin） |
| Shizuku | 高权限执行通道（input tap/swipe/text 等），由原生 App 经官方库调用 | Android 服务 |
| Agent 服务 | HTTP 门面：路由请求、调用决策循环、返回动作 | `agent/server.py` |
| 安全 Agent 循环 | 思考→行动→观察；强制结构化 JSON 输出 + schema 校验；当前为自研实现 | `agent/agent_loop.py` |
| ui_summarizer | 原始 UI 树 XML → 紧凑交互节点列表（≤500 token） | `agent/ui_summarizer.py` |
| llm_client | 云端 OpenAI-compatible 模型客户端，可 mock | `agent/llm_client.py` |

## 4. 运行时数据流（一次任务）

```
1. 触发    原生 App 事件（定时器/通知监听/悬浮按钮按住说"打开微信给张三发消息"）
2. 采集    原生 App 采集当前 UI 树（uiautomator dump via Shizuku，见 §6）
3. 请求    POST /agent/run：intent + app + 原始 UI 树 XML + 会话历史
4. 摘要    Agent 端 ui_summarizer 将 XML 压缩为交互节点列表（LLM 视角 ≤500 token）
5. 决策    安全 Agent 循环（≤N 轮）：LLM 输出 action JSON → schema 校验
6. 响应    返回 agent_response（action 列表 + reply + need_observation）
7. 执行    原生 App 解析 action：普通动作走无障碍，高权限走 Shizuku
8. 观察    执行后重新采集 UI 树，作为下一轮输入（need_observation=true 时）
9. 收敛    action.type = reply / done → 任务结束，原生 App 呈现结果
```

## 5. 通信协议契约（protocol_version: 1.0）

**决策 D6 = 纯 HTTP loopback 作为原生 App↔Agent 唯一通信主干**（2026-08-18 与用户确认）。不引入 WebSocket/MQTT 常驻通道（省内存）；Termux:API 定位为**感知插件**（剪贴板等，D7），不作为通信主干，二期再讨论。语音识别一期走 **SpeechRecognizer**（原生，D11），不再经 Termux:API。Agent 主动推送能力预留在 §4 数据流的 future 扩展，MVP 不实现。

### 5.1 HTTP 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agent/run` | POST | 主入口：一次任务（可含多轮动作，状态由 session_id 维系） |
| `/health` | GET | 存活检查（原生 App 联调用） |

- 仅监听 `127.0.0.1`（Termux 本地回环），不暴露局域网；Android 客户端固定为 `http://127.0.0.1:8080`（Manifest 为此受限 loopback 用途启用 cleartext，客户端拒绝任何非 loopback endpoint）。服务应以 `python -m agent.server` 启动：`--mock` 仅做协议联调，默认模式经 HTTPS 调用云端模型。
- `Content-Type: application/json`，UTF-8
- 未知字段忽略；必填字段缺失或非法 → 400 + 结构化错误

### 5.2 `task_request`

```json
{
  "protocol_version": "1.0",
  "session_id": "4f2a9c1e-...",
  "intent": "打开微信给张三发消息：今晚八点开会",
  "app": "com.tencent.mm",
  "activity": "com.tencent.mm.ui.LauncherUI",
  "ui_xml": "<node ...>...</node>",
  "history": []
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| protocol_version | string | 是 | 当前 "1.0" |
| session_id | string | 是 | 一次任务的会话标识（原生 App 生成 UUID） |
| intent | string | 是 | 用户意图（语音转文字/通知文本/定时任务描述） |
| app | string | 否 | 当前前台应用包名 |
| activity | string | 否 | 当前 Activity |
| ui_xml | string | 否 | 当前 UI 树原始 XML；未采集到 UI 树时可省略或传空字符串，Agent 按空节点安全决策；后续轮由 need_observation 驱动重采 |
| history | array | 否 | 前序轮次的 action 与观察摘要（服务端亦可按 session_id 缓存） |

### 5.3 `ui_tree`（Agent 内部结构，调试/执行用）

```json
{
  "nodes": [
    {
      "id": 12,
      "type": "button",
      "text": "发送",
      "bounds": [450, 900, 650, 980],
      "clickable": true,
      "focused": false
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int | 摘要内唯一序号（LLM 引用动作目标的句柄） |
| type | string | button / input / text / checkbox / list_item / 其他 |
| text | string | 可见文本（空则省略） |
| bounds | [x1,y1,x2,y2] | 坐标（点击取中心点） |
| clickable | bool | 是否可交互 |
| focused | bool | 是否聚焦（输入框） |

LLM 视角的紧凑行格式：`[12] 按钮"发送" (450,900)`——供 prompt 使用，不参与传输。

### 5.4 `agent_response`

```json
{
  "protocol_version": "1.0",
  "session_id": "4f2a9c1e-...",
  "done": false,
  "reply": "已定位到发送按钮",
  "actions": [
    { "type": "click", "target_node_id": 12, "requires_confirmation": false }
  ],
  "need_observation": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| done | bool | 是 | true = 任务结束（reply 为最终答复） |
| reply | string | 否 | 给用户的中间/最终文本 |
| actions | array | 否 | 待执行动作列表（有序执行；为空表示无动作） |
| need_observation | bool | 否 | true = 原生 App 执行后须重采 UI 树回传（进入下一轮） |

### 5.5 `action` 类型

| type | 参数 | 执行通道 | 说明 |
|------|------|----------|------|
| click | target_node_id | 无障碍（兜底 Shizuku） | 点击节点中心 |
| type | target_node_id, text | 无障碍 + Shizuku input | 输入文本（先聚焦后输入） |
| swipe | x1,y1,x2,y2,duration_ms | Shizuku | 滑动 |
| back | — | 无障碍全局动作 | 返回 |
| home | — | 无障碍全局动作 | 回桌面 |
| launch | package_name | 原生 App | 启动固定技能白名单中的应用 |
| wait | ms | 无 | 等待界面稳定 |
| reply | text | 无 | 向用户回复（配合 done） |
| done | — | 无 | 任务结束 |

**铁律**：所有 `action` 必须通过 `bridge/` 的 JSON Schema 校验；非法/缺失字段的决策一律拒绝，返回安全错误，不执行任何操作。

模型若以单对象 `{"type":"reply","text":"..."}` 输出答复，Agent 将其规范化为 `done=true`、
`reply` 填入该文本且 `actions=[]` 的终态响应；该文本不会作为 Android 可执行动作下发，也不会触发额外观察轮次。

`launch` 仅由 Agent 侧固定技能路由生成，当前白名单为系统设置、TARS Assistant 与微信；Android
执行侧再次校验包名，并只使用 `PackageManager.getLaunchIntentForPackage()` 创建启动 Intent。未知应用、
未安装应用和任何任意 Intent/命令均拒绝，不提供模型可控的组件、URI 或 shell 参数。

### 5.6 触发任务的用户审查

通知、定时和悬浮语音均只能将文本保存为本地待处理任务，并显示本地通知；它们不得自动请求
`/agent/run` 或执行动作。用户须在主界面点击“载入最新通知”、检查预填意图后，再显式点击
“发送给 TARS”。悬浮语音使用无障碍服务的 `TYPE_ACCESSIBILITY_OVERLAY`，避免申请可覆盖所有
应用的 `SYSTEM_ALERT_WINDOW` 权限。

## 6. 感知层设计（ui_summarizer）

- 输入：原始 UI 树 XML（uiautomator dump 或无障碍采集，可能数千行）
- 处理管线：
  1. **过滤**：仅保留可交互节点（clickable / input / 有文本的可点击项）
  2. **截断**：文本超长截断；节点数超限（暂定 60 个）按可视区域优先级裁剪
  3. **排序**：按 bounds 从上到下、从左到右
- 输出：紧凑行文本（喂 LLM）+ 结构化 nodes（供执行引用 id）
- 验收基线：单屏摘要 ≤ 500 token，以控制云端请求体与延迟

UI 采集方案（阶段 4 在 AVD 实测后定，倾向 A，但均在原生 App 内完成，非独立工具）：
- **方案 A（推荐）**：原生 App 经 Shizuku 执行 `uiautomator dump` 导出完整 XML（含 bounds），App 读文件并回传
- 方案 B：原生无障碍服务直接取节点树（`AccessibilityNodeInfo`），元素完整、bounds 可得但不含部分扩展属性，传输更轻可控

## 7. 执行层与安全设计

### 7.1 双通道执行（原生 App 内）

| 通道 | 覆盖动作 | 授权 | 库 |
|------|----------|------|-----|
| 无障碍服务 | click（普通）、back/home 全局动作 | 无障碍服务开关 | `AccessibilityService` |
| Shizuku | 参数受限的 input swipe（一期实现） | Shizuku 授权（starter/无线调试） | `dev.rikka.shizuku` 官方 UserService + AIDL |

原生 App 作为执行侧统一调度两通道：普通动作走无障碍（无需额外权限逻辑），高权限走 Shizuku。Shizuku UserService 仅暴露已定义的动作方法；当前仅允许坐标和时长均通过 App 校验的 swipe，不传递 LLM 生成的 shell 命令。

### 7.2 三层安全兜底

1. **Schema 校验**（Agent 端）：非法 action 一律拒绝，不执行
2. **动作白名单**（原生 App 端）：高权限动作类型 + 目标应用白名单
3. **关键操作确认**（双层）：Python 按目标 UI 文本将支付/删除/发送等敏感 click 强制标为 `requires_confirmation=true`；原生 App 以当前节点文本再次判定并弹窗确认，不能信任 LLM 给出的 false

## 8. 云端模型层

当前 `agent/agent_loop.py` 是自研的 SmolAgent 风格循环，项目尚未安装或导入 `smolagents`
Python 包。后续可将其作为 Python 决策层的编排实现，但不改变本协议或削弱 §7.2 的安全边界。

- 推理端点：云端 OpenAI-compatible `/v1/chat/completions`。
- 私有配置：`config/cloud.yaml`，由 `config/cloud.yaml.example` 初始化并被 Git 忽略。
- `llm_client` 以 `base_url`、`model`、`api_key` 配置化接入；切换服务商或模型不改 Agent 代码。
- 云端请求仅对连接/超时、HTTP `429` 和 `5xx` 进行有界指数退避重试（默认额外 2 次）；认证、其他 `4xx` 和响应格式错误直接失败，错误信息不得包含 API Key。
- 手机不安装或运行 GGUF、llama.cpp、llama-server；Termux 只承载 Python Agent。
- 云端返回内容仍是不可信输入，必须经 Agent schema 校验与 Android 执行安全层。

## 9. 部署拓扑

| 环境 | 用途 | 说明 |
|------|------|------|
| Windows | 开发/单测 | agent + bridge 全量可测（LLM mock）；Kotlin 代码 Gradle 编译 |
| AVD | 集成测试 | **系统镜像：Google APIs**（Shizuku/无障碍授权最顺，贴近真机）；`shizuku_starter.sh` 启动 |
| 实体机 | 生产 | **裸 Termux + venv**（决策层 Python）；**原生 App**（执行侧，安装 APK）；Shizuku + 无线调试授权 |

**模拟器优先原则**：开发测试始终先走 AVD（x86 镜像），不用真机；生产部署以真机为准，二者共用同一份代码与 `docs/DESIGN.md` 契约。

## 10. 开放问题（阶段推进中逐项关闭）

- [x] 模型常见的纯数字字符串节点 ID 在 Agent 侧按当前 UI 快照做受限规范化；非数字、未知节点 ID 仍由 schema 拒绝
- [ ] UI 采集方案 A/B 实测定夺（§6，原生 App 内完成的两种取树方式）
- [ ] 每轮执行后是否都重采 UI（观察频率与成本实测）
- [ ] 常驻唤醒词：二期评估（受 Android 权限硬约束，D11 已排除一期）
- [ ] 会话历史由原生 App 回传（history 字段）还是服务端缓存（session_id）——倾向服务端缓存，减小请求体
- [ ] 云端模型服务商的超时、额度与失败重试策略实测标定
