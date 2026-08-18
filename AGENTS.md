# TARS Assistant

基于 Shizuku + Tasker + Termux 的本地 AI 手机助手：llama.cpp + Qwen2.5-3B 本地推理驱动 Agent 决策，Tasker 负责屏幕感知与高权限执行，全程数据不出设备。

## Project

- 形态（决策 D1）：事件驱动。Tasker 监听触发（语音/通知/定时）→ 调 Agent 决策 → Tasker 执行动作。
- 感知（决策 D2）：纯文本 UI 树（无障碍/uiautomator 采集 → Agent 端摘要），非视觉模型。
- 模型（决策 D3）：Qwen2.5-3B GGUF 起步，经 llama-server OpenAI 兼容端点接入；预留 7B 档位（换模型只改配置）。
- 推理生命周期（决策 D5）：llama-server **按需拉起 + 任务窗口保活 + 空闲超时退出**，非常驻。
- 通信（决策 D6）：Tasker↔Agent 仅用纯 HTTP loopback（127.0.0.1:8080），不引 WebSocket/MQTT 常驻通道；Termux:API 仅作感知插件（D7）。
- 环境（决策 D8/D9）：Agent 运行于**裸 Termux + venv**（非 proot 常驻容器）；开发测试**AVD 优先（Google APIs 镜像）**，暂不用真机。
- 范围（决策 D4）：通用对话 + 少量固定技能。
- 入口点：`agent/server.py`（FastAPI，规划中）。
- 权威技术契约：`docs/DESIGN.md`；计划与进度：`docs/PROJECT_PLAN.md`。

## Commands

> 项目尚处文档奠基阶段，以下为规划命令（未实现，待落地后验证更新）。

- Agent 单测：`python -m pytest agent/`（LLM 走 mock，不依赖真实模型）
- 启动 Agent 服务：`uvicorn agent.server:app --host 127.0.0.1 --port 8080`
- 协议校验：`python -m bridge.validate`（对示例 JSON 做 schema 校验）
- 启动推理：`scripts/start_llama.sh`（llama-server，**按需拉起/空闲超时退出**，OpenAI 兼容端点）
- 下载模型：`scripts/download_model.sh`（3B 默认，7B 可选档位）
- 开发环境：`python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt`

## Architecture

| 模块 | 角色 |
|------|------|
| `agent/` | FastAPI 服务 + SmolAgent 决策循环 + LLM 客户端 |
| `agent/ui_summarizer.py` | 原始 UI 树 XML → 紧凑交互节点列表（≤500 token） |
| `bridge/` | 通信契约：JSON Schema 定义 + 校验器 |
| `tasker/` | Tasker 端：`ui_dump`（采集 UI 树）、`action_exec`（执行动作） |
| `scripts/` | 模型下载、llama-server 启动、部署脚本 |
| `docs/` | DESIGN.md（设计书）、PROJECT_PLAN.md（计划与进度） |

## Conventions

- **协议先行**：Tasker ↔ Agent 任何新交互，先改 `docs/DESIGN.md` 契约 + `bridge/` schema，再实现两端。
- **LLM 输出不可信**：决策 JSON 必须过 schema 校验；非法输出返回安全错误，绝不执行。
- **安全边界**：高权限动作（点击/滑动/输入）须过白名单；关键操作（支付/删除/发送）默认要求确认。
- **模型解耦**：LLM 客户端可 mock；开发与测试不依赖真实模型和网络。
- **7B 升级路径**：模型档位是配置（`agent/config.yaml`），换模型不改代码。
- **UI 摘要归属 Agent 侧**：Tasker 只采集并回传原始 UI 树，摘要逻辑在 `agent/ui_summarizer.py`（可单测），Tasker 端不做解析。
- **HTTP 服务仅监听 127.0.0.1**，不暴露到局域网。

## Notes

- （待补充：AVD 联调记录、Termux 部署踩坑、UI 采集方案实测结论）
