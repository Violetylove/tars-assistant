# 设计书 — TARS Assistant

> 本文件是系统的**权威技术契约**：架构、数据流、通信协议、安全边界均以此为准。
> 任何架构变更须先更新本文档，再改代码（约定见 AGENTS.md「协议先行」）。

## 1. 概述

本地 AI 手机助手：数据不出设备。核心原则：**事件驱动、协议先行、LLM 输出不可信、安全兜底**。

- 目标设备：Android（Termux + proot-distro Ubuntu 承载 Python 栈与本地推理）
- 开发环境：Windows + ADB + Android Studio AVD
- 感知：纯文本 UI 树（非视觉）；模型：Qwen2.5-3B 起步，预留 7B

## 2. 系统架构

```
┌──────────────────────────────────────────────┐
│                Android 手机                    │
│                                               │
│  ┌─────────┐  事件触发   ┌──────────────────┐  │
│  │ Tasker  │───────────▶│  HTTP (127.0.0.1) │  │
│  │ 触发/执行 │  JSON 动作  │  :8080 Agent 服务  │  │
│  │ 无障碍服务 │◀───────────│  FastAPI          │  │
│  └────┬────┘            └────────┬─────────┘  │
│       │ 无障碍点击                 │ 决策循环       │
│  ┌────▼────┐          ┌──────────▼─────────┐  │
│  │ Shizuku │◀─────────│ SmolAgent          │  │
│  │ 高权限执行 │          │ ui_summarizer     │  │
│  └─────────┘          │ llm_client (mock)  │  │
│                       └──────────┬─────────┘  │
│                       ┌──────────▼─────────┐  │
│                       │ llama-server       │  │
│                       │ Qwen2.5-3B GGUF    │  │
│                       └────────────────────┘  │
└──────────────────────────────────────────────┘
```

## 3. 组件职责

| 组件 | 职责 | 位置 |
|------|------|------|
| Tasker | 事件入口（语音/通知/定时）、UI 树采集、动作执行、结果回传 | Android 应用 |
| Shizuku | 高权限执行通道（input tap/swipe/text 等） | Android 服务 |
| Agent 服务 | HTTP 门面：路由请求、调用决策循环、返回动作 | `agent/server.py` |
| SmolAgent 循环 | 思考→行动→观察；强制结构化 JSON 输出 + schema 校验 | `agent/agent_loop.py` |
| ui_summarizer | 原始 UI 树 XML → 紧凑交互节点列表（≤500 token） | `agent/ui_summarizer.py` |
| llm_client | llama-server 封装（OpenAI 兼容），可 mock | `agent/llm_client.py` |
| llama-server | 本地推理端点（/v1/chat/completions） | `scripts/start_llama.sh` |

## 4. 运行时数据流（一次任务）

```
1. 触发    Tasker 事件（如语音"打开微信给张三发消息"）
2. 采集    Tasker 采集当前 UI 树（uiautomator dump via Shizuku / 无障碍，方案见 §6）
3. 请求    POST /agent/run：intent + app + 原始 UI 树 XML + 会话历史
4. 摘要    Agent 端 ui_summarizer 将 XML 压缩为交互节点列表（LLM 视角 ≤500 token）
5. 决策    SmolAgent 循环（≤N 轮）：LLM 输出 action JSON → schema 校验
6. 响应    返回 agent_response（action 列表 + reply + need_observation）
7. 执行    Tasker 解析 action：普通动作走无障碍，高权限走 Shizuku
8. 观察    执行后重新采集 UI 树，作为下一轮输入（need_observation=true 时）
9. 收敛    action.type = reply / done → 任务结束，Tasker 呈现结果
```

## 5. 通信协议契约（protocol_version: 1.0）

**决策 D6 = 纯 HTTP loopback 作为 Tasker↔Agent 唯一通信主干**（2026-08-18 与用户确认）。不引入 WebSocket/MQTT 常驻通道（省内存）；Termux:API 定位为**感知插件**（语音/剪贴板等，D7），不作为通信主干，二期再讨论接入。Agent 主动推送能力预留在 §4 数据流的 future 扩展，MVP 不实现。

### 5.1 HTTP 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/agent/run` | POST | 主入口：一次任务（可含多轮动作，状态由 session_id 维系） |
| `/health` | GET | 存活检查（Tasker 联调用） |

- 仅监听 `127.0.0.1`（Termux 本地回环），不暴露局域网
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
| session_id | string | 是 | 一次任务的会话标识（Tasker 生成 UUID） |
| intent | string | 是 | 用户意图（语音转文字/通知文本/定时任务描述） |
| app | string | 否 | 当前前台应用包名 |
| activity | string | 否 | 当前 Activity |
| ui_xml | string | 否 | 当前 UI 树原始 XML（首轮必带；后续轮由 need_observation 驱动重采） |
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
| need_observation | bool | 否 | true = Tasker 执行后须重采 UI 树回传（进入下一轮） |

### 5.5 `action` 类型

| type | 参数 | 执行通道 | 说明 |
|------|------|----------|------|
| click | target_node_id | 无障碍（兜底 Shizuku） | 点击节点中心 |
| type | target_node_id, text | 无障碍 + Shizuku input | 输入文本（先聚焦后输入） |
| swipe | x1,y1,x2,y2,duration_ms | Shizuku | 滑动 |
| back | — | 无障碍全局动作 | 返回 |
| home | — | 无障碍全局动作 | 回桌面 |
| wait | ms | 无 | 等待界面稳定 |
| reply | text | 无 | 向用户回复（配合 done） |
| done | — | 无 | 任务结束 |

**铁律**：所有 `action` 必须通过 `bridge/` 的 JSON Schema 校验；非法/缺失字段的决策一律拒绝，返回安全错误，不执行任何操作。

## 6. 感知层设计（ui_summarizer）

- 输入：原始 UI 树 XML（uiautomator dump 或无障碍采集，可能数千行）
- 处理管线：
  1. **过滤**：仅保留可交互节点（clickable / input / 有文本的可点击项）
  2. **截断**：文本超长截断；节点数超限（暂定 60 个）按可视区域优先级裁剪
  3. **排序**：按 bounds 从上到下、从左到右
- 输出：紧凑行文本（喂 LLM）+ 结构化 nodes（供执行引用 id）
- 验收基线：单屏摘要 ≤ 500 token（3B 上下文 8K 内留足决策余量）

UI 采集方案（阶段 4 实测后定，倾向 A）：
- **方案 A（推荐）**：Shizuku 执行 `uiautomator dump` 导出完整 XML（含 bounds），Tasker 读文件回传
- 方案 B：Tasker 无障碍 UI Query（元素扁平列表，bounds 获取受限）

## 7. 执行层与安全设计

### 7.1 双通道执行

| 通道 | 覆盖动作 | 授权 |
|------|----------|------|
| 无障碍服务 | click（普通）、back/home 全局动作 | 无障碍服务开关 |
| Shizuku | input tap/swipe/text、模拟按键 | `adb shell sh /data/local/tmp/shizuku_starter.sh` 或无线调试 |

### 7.2 三层安全兜底

1. **Schema 校验**（Agent 端）：非法 action 一律拒绝，不执行
2. **动作白名单**（Tasker 端）：高权限动作类型 + 目标应用白名单
3. **关键操作确认**（Tasker 端）：支付/删除/发送等敏感动作默认 `requires_confirmation=true`，弹窗确认后才执行

## 8. 模型层与升级路径

- 推理端点：llama-server（OpenAI 兼容 `/v1/chat/completions`）
- 模型档位（`agent/config.yaml`）：
  - `default`: Qwen2.5-3B-Instruct Q4_K_M（约 2GB，8K 上下文）
  - `optional`: Qwen2.5-7B-Instruct Q4（约 4GB，需 ≥8GB RAM 手机）
- llm_client 以 `base_url` + `model_name` 配置化接入 → 换 7B 只改配置不改代码
- 注意：Qwen2.5 系列**非多模态**；截图视觉理解列为二期，需换 Qwen2.5-VL 一族（届时感知层加截图通道）

### 8.1 llama-server 生命周期：按需拉起

决策 **D5 = 按需拉起**（2026-08-18 与用户确认）：不为省内存长期常驻，也不用每次冷启动。

| 阶段 | 触发 | 说明 |
|------|------|------|
| 拉起 | Agent 收到新任务时 | 若 llama-server 未运行，则 spawn 并等其就绪（加载模型 1–3s） |
| 保活 | 任务多轮循环期间 | 任务进行中保持存活，多轮决策复用同一实例 |
| 退出 | 任务结束 + 空闲超时 | 空闲超时（默认 60s）后自动退出释放内存 |

收益：空闲时手机零模型内存占用（解放约 2GB）；任务期才占内存，首轮决策多付一次加载延迟（1–3s），换来低频事件驱动场景下的最佳内存/体验平衡。进程管理由 `scripts/start_llama.sh` + agent 服务协同实现。

## 9. 部署拓扑

| 环境 | 用途 | 说明 |
|------|------|------|
| Windows | 开发/单测 | agent + bridge 全量可测（LLM mock）；llama-server 跑 x86 版验证推理 |
| AVD | 集成测试 | **系统镜像：Google APIs**（Shizuku/Tasker 授权最顺，贴近真机）；`shizuku_starter.sh` 启动 |
| 实体机 | 生产 | **裸 Termux + venv**（独立虚拟环境隔离依赖，**不用 proot-distro 常驻容器**）；Shizuku + 无线调试授权 |

**模拟器优先原则**：开发测试始终先走 AVD（x86 镜像），不用真机；生产部署以真机为准，二者共用同一份代码与 `docs/DESIGN.md` 契约。

## 10. 开放问题（阶段推进中逐项关闭）

- [ ] UI 采集方案 A/B 实测定夺（§6）
- [ ] 每轮执行后是否都重采 UI（观察频率与成本实测）
- [ ] 语音输入走 Tasker 内置识别还是接入 Termux:API（二期，D7）
- [ ] 会话历史由 Tasker 回传（history 字段）还是服务端缓存（session_id）——倾向服务端缓存，减小请求体
- [ ] `start_llama.sh` 空闲超时阈值实测标定（默认 60s，见 §8.1）
