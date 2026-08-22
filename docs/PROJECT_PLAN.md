# 项目计划书 — TARS Assistant

> 计划与进度跟踪文档。每完成一项工作即更新本文件。

## 1. 项目目标

在 Android 手机上构建本地 AI 助手：**Tasker 触发 → Agent（SmolAgent + Qwen2.5-3B 本地推理）决策 → Tasker 执行（无障碍/Shizuku 高权限点击滑动）**，全程数据不出设备。

## 2. 已定决策（2026-08-18 与用户确认）

| # | 决策点 | 选择 |
|---|--------|------|
| D1 | 主控形态 | Tasker 触发 + Agent 决策 + Tasker 执行（事件驱动） |
| D2 | 感知方式 | 纯文本 UI 树（采集 → Agent 端摘要），非视觉 |
| D3 | 模型规格 | Qwen2.5-3B 起步，架构预留 7B（换模型只改配置） |
| D4 | 目标范围 | 通用对话 + 少量固定技能 |
| D5 | 推理层生命周期 | llama-server 按需拉起 + 任务窗口保活 + 空闲超时退出（不常驻） |
| D6 | 通信主干 | 纯 HTTP loopback（127.0.0.1:8080），不引 WebSocket/MQTT 常驻通道 |
| D7 | Termux:API 定位 | 感知插件（语音/剪贴板等），不作通信主干，二期再议 |
| D8 | Agent Python 环境 | 裸 Termux + venv（独立虚拟环境，**不用 proot-distro 常驻容器**） |
| D9 | 测试环境 | AVD 优先（系统镜像 Google APIs），暂不用真机；主镜像 Google APIs，AOSP 作备用 |

## 3. 阶段计划与进度

图例：🟢 已完成 · 🔵 进行中 · ⚪ 未开始

| 阶段 | 内容 | 状态 |
|------|------|------|
| 0 | 文档奠基（AGENTS.md / 计划书 / 设计书） | 🟢 已完成 |
| 1 | 项目骨架 + 通信协议契约 | 🟢 已完成 |
| 2 | Agent 端：HTTP 服务 + UI 摘要器 + 决策循环 | 🟢 已完成 |
| 3 | 本地推理层：llama.cpp 接入 | 🔵 进行中 |
| 4 | Tasker/Shizuku 执行端：感知与执行 | ⚪ 未开始 |
| 5 | 端到端集成 + 演示技能 + 部署文档 | ⚪ 未开始 |

### 阶段 0：文档奠基
- [x] AGENTS.md（项目记忆，含 D1–D4 与工程约定）
- [x] docs/PROJECT_PLAN.md（本文件）
- [x] docs/DESIGN.md（架构 + 协议契约，权威技术文档）

### 阶段 1：项目骨架 + 通信协议契约
- [x] 初始化 git 与目录结构（agent/ bridge/ tasker/ scripts/ docs/）
- [x] 定义并文档化 JSON Schema：`task_request` / `agent_response` / `action` / `ui_tree`（见 DESIGN.md §5）
- [x] 编写协议校验器 `bridge/validate.py` 与最小端到端 JSON 示例
- **验收**：schema 与示例 JSON 可被校验器解析通过（`python -m bridge.validate`）

### 阶段 2：Agent 端
- [x] `agent/server.py`：FastAPI 服务（/agent/run、/health）
- [x] `agent/ui_summarizer.py`：原始 UI 树 XML → 紧凑交互节点（≤500 token）
- [x] `agent/agent_loop.py`：SmolAgent 决策循环 + schema 校验兜底
- [~] `agent/config.yaml`：模型档位（3B 默认 / 7B 可选）、监听端口等 → **延后至阶段 3** 随 llama-server 接入一并落地（当前模型档位经 llm_client 参数注入）
- **验收**：mock UI 树 + 固定任务跑通 agent 返回合法 action JSON 的单测（✅ 25 passed）

### 阶段 3：本地推理层（按需拉起）
- [ ] 搭建开发虚环境（venv + requirements.txt，Windows 本地）
- [ ] `scripts/download_model.sh`（3B 默认，预留 7B 档位）
- [ ] `scripts/start_llama.sh`：llama-server（OpenAI 兼容端点，**按需拉起/空闲超时退出**，见 DESIGN.md §8.1）
- [ ] `agent/llm_client.py`：可 mock 的 LLM 客户端（base_url + model 配置化）
- **验收**：本地 llama-server 返回一次 chat completion（Windows/AVD 验证）

### 阶段 4：Tasker/Shizuku 执行端（AVD 上装配）
- [ ] AVD（**Google APIs 镜像**）+ ADB + Shizuku starter 就绪
- [ ] Tasker 触发入口：语音/通知/定时事件 → HTTP 请求 Agent
- [ ] `tasker/ui_dump`：采集当前 UI 树（uiautomator dump via Shizuku 或无障碍，方案待实测）
- [ ] `tasker/action_exec`：解析 action JSON → 无障碍/Shizuku 执行（click/swipe/type/back 等）
- [ ] 安全层：动作白名单 + 关键操作确认
- **验收**：AVD 上完成"触发 → 采集 UI → HTTP → 决策 → 执行点击"连通性测试

### 阶段 5：端到端集成 + 部署
- [ ] 2-3 个演示技能（如"打开 XX 应用""给 XX 发消息"）+ 通用对话路由
- [ ] `docs/DEPLOY.md`：Windows 开发 → AVD 测试 → 实体机裸 Termux（venv，非 proot）部署手册
- [ ] 端到端冒烟测试脚本
- **验收**：README 描述一条可复现的"空白环境 → 跑通一个技能"路径

## 4. 风险清单

| 风险 | 缓解 |
|------|------|
| 3B 模型 agent 能力弱（tool calling 不稳） | 结构化 JSON + schema 校验兜底；可换 7B 档位 |
| UI 树超长撑爆上下文 | ui_summarizer 压缩为可交互节点，单屏 ≤500 token |
| 高权限自动操作误伤 | 动作白名单 + 关键操作确认（三层兜底，见 DESIGN.md §7.2） |
| Shizuku 在 AVD 授权繁琐 | Google APIs 镜像 + `shizuku_starter.sh` + 无线调试 |
| 裸 Termux 编译型依赖难装 | 优先纯 Python/预编译 wheel；需编译则 `pkg install clang python-dev`；装不上换等价包 |
| 模型/服务常驻内存压垮手机 | llama-server 按需拉起 + 空闲超时退出（D5）；仅两个常驻进程 |

## 5. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-18 | 创建文档体系；记录 D1–D4 决策、五阶段计划与验收标准 |
| 2026-08-18 | 增订决策 D5（按需拉起）D6（HTTP 主干）D7（Termux:API 感知插件）D8（裸 Termux+venv 非 proot）D9（AVD 优先、Google APIs 镜像）；更新阶段 3/4/5 与风险与部署拓扑 |
| 2026-08-18 | 阶段 1 完成：git init、四 JSON Schema、bridge/validate.py、示例；`python -m bridge.validate` 通过 |
| 2026-08-18 | 阶段 2 完成：ui_summarizer / llm_client(mock) / agent_loop / server；25 单测全绿；config.yaml 延后至阶段 3 |
