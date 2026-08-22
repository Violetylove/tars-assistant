# TARS Assistant

基于 **原生 Android App + Termux + llama.cpp** 的本地 AI 手机助手：原生 Kotlin App 负责屏幕感知与高权限执行，Python 决策层（安全 Agent 循环 + Qwen2.5-3B 本地推理）负责思考，全程数据不出设备。

## 架构一句话

**原生 App 触发 → Python Agent 决策（安全循环 + 本地模型）→ 原生 App 执行（无障碍/Shizuku）**

## 关键决策（详见 docs/PROJECT_PLAN.md §2）

| # | 决策 | 选择 |
|---|------|------|
| D1 | 主控形态 | 原生 App 触发 + Agent 决策 + 原生 App 执行（事件驱动） |
| D2 | 感知方式 | 纯文本 UI 树（采集 → Agent 端摘要），非视觉 |
| D3 | 模型规格 | Qwen2.5-3B 起步，架构预留 7B |
| D5 | 推理生命周期 | llama-server 按需拉起 + 空闲超时退出 |
| D6 | 通信主干 | 纯 HTTP loopback（127.0.0.1:8080） |
| D8 | 运行环境 | 裸 Termux + venv（非 proot） |
| D9 | 测试环境 | AVD 优先（Google APIs 镜像） |
| D10 | 执行侧 | 原生 Kotlin App（无障碍 + Shizuku 官方库），替换 Tasker |
| D11 | 触发分期 | 一期=定时+通知+悬浮语音；常驻唤醒词二期 |
| D12 | 决策层归属 | 恒用 Python 生态，任何迁移不修改 |

## 文档

- `docs/DESIGN.md` — 系统权威技术契约（架构、协议 Schema、安全）
- `docs/PROJECT_PLAN.md` — 计划与进度跟踪
- `docs/DEPLOY.md` — Windows、AVD 与真机部署/联调步骤
- `AGENTS.md` — 项目记忆（每个新会话自动加载）

## 状态

阶段 0-3 已完成；阶段 4「原生执行侧 App」正在实现。当前已具备 Android 工程骨架、
无障碍 UI 树采集、loopback HTTP client、基础动作执行、受限多轮观察，以及响应会话校验
与关键动作确认；Shizuku 高权限动作与阶段 5 端到端联调尚未完成。
通知触发和定时提醒均采用“触发后由用户载入确认”的安全模式；主界面按住说话会填入意图，悬浮语音入口仍待实现。
AVD 基础安装与策略验证记录见 `docs/AVD_TESTING.md`；Termux Agent 的设备内端到端联调仍待完成。

当前 `agent/agent_loop.py` 是自研的 SmolAgent 风格循环，负责思考、动作、观察与 schema
校验；尚未引入 `smolagents` Python 包。后续若接入该框架，现有 schema、动作白名单与用户
确认边界仍为不可绕过的安全层。

当前提供的无模型固定演示技能是“打开设置”“打开 TARS”和“打开微信”。它们仅启动 Python 与
Android 两端共同白名单中的应用；其它意图交由安全 Agent 循环处理。

最小协议联调可在不下载模型的情况下运行：`python -m agent.server --mock`，然后执行
`python scripts/smoke_agent.py`。完整步骤见 `docs/DEPLOY.md`。

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源，版权所有者为 Violetylove。详见 `LICENSE` 与
`NOTICE`。
