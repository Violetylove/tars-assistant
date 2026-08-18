# TARS Assistant

基于 **Shizuku + Tasker + Termux** 的本地 AI 手机助手：llama.cpp + Qwen2.5-3B 本地推理驱动 Agent 决策，Tasker 负责屏幕感知与高权限执行，全程数据不出设备。

## 架构一句话

**Tasker 触发 → Agent（SmolAgent + 本地模型）决策 → Tasker 执行（无障碍/Shizuku）**

## 关键决策（详见 docs/PROJECT_PLAN.md §2）

| # | 决策 | 选择 |
|---|------|------|
| D1 | 主控形态 | Tasker 触发 + Agent 决策 + Tasker 执行（事件驱动） |
| D2 | 感知方式 | 纯文本 UI 树（采集 → Agent 端摘要），非视觉 |
| D3 | 模型规格 | Qwen2.5-3B 起步，架构预留 7B |
| D5 | 推理生命周期 | llama-server 按需拉起 + 空闲超时退出 |
| D6 | 通信主干 | 纯 HTTP loopback（127.0.0.1:8080） |
| D8 | 运行环境 | 裸 Termux + venv（非 proot） |
| D9 | 测试环境 | AVD 优先（Google APIs 镜像） |

## 文档

- `docs/DESIGN.md` — 系统权威技术契约（架构、协议 Schema、安全）
- `docs/PROJECT_PLAN.md` — 计划与进度跟踪
- `AGENTS.md` — 项目记忆（每个新会话自动加载）

## 状态

阶段 1「项目骨架 + 通信协议契约」进行中。
