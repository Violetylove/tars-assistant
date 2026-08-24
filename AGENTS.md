# TARS Assistant

基于原生 Android App + Termux Python Agent + 云端模型 API 的 AI 手机助手：原生 Kotlin App 负责屏幕感知与高权限执行，Python 决策层（自研安全 Agent 循环）负责思考，云端仅提供大模型推理。

> 隐私边界：启用云端模型时，Agent 会向配置的服务商发送任务文本与压缩后的 UI 摘要；Android App 不直接暴露网络端点，原生 App↔Agent 仍为本机 loopback。

## Project

- 形态（决策 D1/D10）：事件驱动。**原生 Kotlin App** 监听触发（定时/通知/悬浮语音）→ 调 Python Agent 决策 → **原生 App** 执行动作。
- 感知（决策 D2）：纯文本 UI 树（uiautomator/无障碍采集 → Agent 端摘要），非视觉模型。
- 模型（决策 D3）：云端 OpenAI-compatible 模型，经私有 `config/cloud.yaml` 接入；换模型只改配置。
- 推理生命周期（决策 D5）：云端模型按请求调用；手机不部署 GGUF、llama.cpp 或模型进程。
- 通信（决策 D6）：原生 App↔Agent 仅用纯 HTTP loopback（127.0.0.1:8080），不引 WebSocket/MQTT 常驻通道；Termux:API 仅作感知插件（D7）。
- 环境（决策 D8/D9）：Python 决策层运行于**裸 Termux + venv**（非 proot 常驻容器）；开发测试**AVD 优先（Google APIs 镜像）**，暂不用真机。
- 范围（决策 D4）：通用对话 + 少量固定技能。
- 结果归属（决策 D12）：**决策层恒用 Python 生态，任何迁移不修改**；云端只提供模型 API，原生侧仅提供 HTTP client + 动作执行。
- 触发分期（决策 D11）：一期=定时+通知+悬浮语音；**常驻唤醒词二期**（受 Android 权限硬约束）。
- 入口点：`agent/server.py`（FastAPI，决策层），`android/`（原生执行侧 App）。
- 权威技术契约：`docs/DESIGN.md`；计划与进度：`docs/PROJECT_PLAN.md`。

## Commands

> 当前运行方式：Termux 保留自研 Python Agent；云端仅提供模型 API。

- Agent 单测：`python -m pytest agent/`（LLM 走 mock，不依赖真实模型）
- 启动 Agent 服务（推荐）：`./scripts/deploy_agent.sh`（自动建 venv/装依赖/校验配置/启动；`--background` 后台、`--mock` 联调、`--stop` 停止）；手动 `python -m agent.server`（协议联调用 `python -m agent.server --mock`）
- 协议校验：`python -m bridge.validate`（对示例 JSON 做 schema 校验）
- 云端模型配置：`cp config/cloud.yaml.example config/cloud.yaml`，填写后以 `python -m agent.server` 启动
- 开发环境：`python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt`
- 原生 App 构建：`cd android && ./gradlew assembleDebug`（执行侧，AVD 上安装验证）

## Architecture

| 模块 | 角色 |
|------|------|
| `agent/` | FastAPI 服务 + 自研安全 Agent 决策循环 + LLM 客户端（决策层，Python） |
| `agent/ui_summarizer.py` | 原始 UI 树 XML → 紧凑交互节点列表（≤500 token） |
| `bridge/` | 通信契约：JSON Schema 定义 + 校验器 |
| `android/` | 原生执行侧 App（Kotlin）：UI 采集、动作执行（无障碍/Shizuku）、HTTP client、触发入口 |
| `scripts/` | 协议冒烟与部署辅助脚本（`smoke_agent.py`、`deploy_agent.sh`） |
| `docs/` | DESIGN.md（设计书）、PROJECT_PLAN.md（计划与进度） |

## Conventions

- **协议先行**：原生 App ↔ Agent 任何新交互，先改 `docs/DESIGN.md` 契约 + `bridge/` schema，再实现两端。
- **LLM 输出不可信**：决策 JSON 必须过 schema 校验；非法输出返回安全错误，绝不执行。
- **安全边界**：高权限动作（点击/滑动/输入）须过白名单；关键操作（支付/删除/发送）默认要求确认。
- **模型解耦**：LLM 客户端可 mock；开发与测试不依赖真实模型和网络。
- **云端模型解耦**：模型端点和模型 ID 位于 Git 忽略的 `config/cloud.yaml`，换模型不改代码。
- **UI 摘要归属 Agent 侧**：原生 App 只采集并回传原始 UI 树，摘要逻辑在 `agent/ui_summarizer.py`（可单测），App 端不做解析。
- **HTTP 服务仅监听 127.0.0.1**，不暴露到局域网。
- **决策层不可动**：Agent 决策逻辑恒为 Python（D12），原生侧只做 HTTP client + 动作执行。
- **框架现状**：当前循环是 SmolAgent 风格的自研实现，尚未依赖 `smolagents`；后续接入该框架时，schema 校验、动作白名单与用户确认仍不可绕过。

## Notes

- （待补充：AVD 联调记录、Termux 部署踩坑、UI 采集方案实测结论）
