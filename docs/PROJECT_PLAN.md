# 项目计划书 — TARS Assistant

> 计划与进度跟踪文档。每完成一项工作即更新本文件。

## 1. 项目目标

在 Android 手机上构建本地 AI 助手：**原生 App 触发 → Python Agent（安全循环 + Qwen2.5-3B 本地推理）决策 → 原生 App 执行（无障碍/Shizuku 高权限点击滑动）**，全程数据不出设备。

## 2. 已定决策（2026-08-18 与用户确认）

| # | 决策点 | 选择 |
|---|--------|------|
| D1 | 主控形态 | **原生 Android App 触发 + Agent 决策 + 原生 App 执行**（事件驱动） |
| D2 | 感知方式 | 纯文本 UI 树（uiautomator 采集 → Python 端摘要），非视觉 |
| D3 | 模型规格 | Qwen2.5-3B 起步，架构预留 7B（换模型只改配置） |
| D4 | 目标范围 | 通用对话 + 少量固定技能 |
| D5 | 推理层生命周期 | llama-server 按需拉起 + 任务窗口保活 + 空闲超时退出（不常驻） |
| D6 | 通信主干 | 纯 HTTP loopback（127.0.0.1:8080），不引 WebSocket/MQTT 常驻通道 |
| D7 | Termux:API 定位 | 感知插件（剪贴板等），不作通信主干；语音一期用 SpeechRecognizer 原生识别 |
| D8 | Agent Python 环境 | 裸 Termux + venv（独立虚拟环境，**不用 proot-distro 常驻容器**） |
| D9 | 测试环境 | AVD 优先（系统镜像 Google APIs），暂不用真机；主镜像 Google APIs，AOSP 作备用 |
| D10 | **执行侧路线**（2026-08-18 战略变更） | **原生 Android（Kotlin）执行侧**，用无障碍 + Shizuku 官方库，**替换 Tasker** |
| D11 | **触发入口分期** | 一期 = 定时(AlarmManager/WorkManager) + 通知监听(NotificationListenerService) + 悬浮按钮按住说话(SpeechRecognizer)；**常驻唤醒词推迟二期**（受 Android 权限硬约束） |
| D12 | **决策层归属** | 决策层恒用 Python 生态（安全 Agent 循环 + schema 校验 + llama.cpp）；可后续接入 SmolAgent 框架，**任何迁移不修改**；执行侧仅提供 HTTP client + 动作执行 |

## 3. 阶段计划与进度

图例：🟢 已完成 · 🔵 进行中 · ⚪ 未开始

| 阶段 | 内容 | 状态 |
|------|------|------|
| 0 | 文档奠基（AGENTS.md / 计划书 / 设计书） | 🟢 已完成 |
| 1 | 项目骨架 + 通信协议契约 | 🟢 已完成 |
| 2 | Agent 端：HTTP 服务 + UI 摘要器 + 决策循环 | 🟢 已完成 |
| 3 | 本地推理层：llama.cpp 接入 | 🟢 已完成 |
| 4 | 原生执行侧 App（Kotlin）：UI 采集 + 动作执行 + HTTP client | 🟢 已完成 |
| 5 | 触发一期 + 端到端集成 + 演示技能 + 部署文档 | 🔵 进行中 |

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
- [x] `agent/agent_loop.py`：自研安全决策循环（SmolAgent 风格）+ schema 校验兜底
- [~] `agent/config.yaml`：模型档位（3B 默认 / 7B 可选）、监听端口等 → **延后至阶段 3** 随 llama-server 接入一并落地（当前模型档位经 llm_client 参数注入）
- **验收**：mock UI 树 + 固定任务跑通 agent 返回合法 action JSON 的单测（✅ 25 passed）

### 阶段 3：本地推理层（按需拉起）
- [x] 搭建开发虚环境（venv + requirements.txt，Windows 本地）
- [x] `scripts/download_model.sh`（3B 默认，预留 7B 档位）
- [x] `scripts/start_llama.sh`：llama-server（OpenAI 兼容端点，**按需拉起/空闲超时退出**，见 DESIGN.md §8.1）
- [x] `agent/llm_client.py`：可 mock 的 LLM 客户端（base_url + model 配置化）
- [x] 本地真实推理验证：llama-server（b10488）+ Qwen2.5-3B Q4_K_M 返回 chat completion
- **验收**：本地 llama-server 返回一次 chat completion（✅ HTTP 200；smoke_llama.py）

### 阶段 4：原生执行侧 App（Kotlin，AVD 上开发）
- [x] `android/`：Gradle 工程骨架（Kotlin + 原生 View）
- [x] UI 采集：无障碍取当前 UI 树 → `task_request` 契约（复用已建 `bridge/` schema）
- [x] 无障碍状态：服务连接会回报到主界面，便于授权和联调诊断
- [x] 动作执行：无障碍执行 click/type/back/home/wait；Shizuku 授权与参数受限的 swipe 已接入并在 AVD 验收
- [x] HTTP client：调 Python 决策层 `POST /agent/run`、`GET /health`
- [x] Android loopback HTTP 策略：明文 loopback 放行 + 客户端固定端点校验（AVD 已验证不再触发 cleartext 拦截）
- [x] 安全层：动作白名单 + 参数/会话校验 + 双层敏感目标确认弹窗
- [~] **验收**：AVD 已完成 APK 安装/启动、服务声明、主界面、无障碍授权与绑定、loopback HTTP 策略、定时待处理链路和通知访问设置入口验证（见 `docs/AVD_TESTING.md`）；待由用户启用通知/Shizuku 并接入 Termux Agent 完成全链路

### 阶段 5：触发一期 + 端到端集成 + 部署
- [~] 触发一期：通知监听（NotificationListenerService）、15 分钟定时提醒（AlarmManager）和无障碍悬浮语音均实现为“触发 → 用户载入确认”；AVD 已验证通知实际捕获后只预填待审查任务，且悬浮语音 overlay 窗口可登记；跨服务重启持久化待观察
- [~] 固定演示技能 + 通用对话路由：启动白名单应用（设置/TARS/微信）优先走固定技能路由；发送消息演示待真实模型联调
- [x] `docs/DEPLOY.md`：Windows 开发 → AVD 测试 → 实体机（裸 Termux+venv 决策层 + 原生 App APK）部署手册，含 mock/真实模型分层验收步骤
- [x] 端到端 HTTP 冒烟测试脚本：`scripts/smoke_agent.py`（健康检查 + 合法 `agent_response`）
- **验收**：README 描述一条可复现的"空白环境 → 触发 → 跑通一个技能"路径

> 二期（排期外）：常驻唤醒词（D11，受 Android 权限硬约束，暂缓）。

## 4. 风险清单

| 风险 | 缓解 |
|------|------|
| 3B 模型 agent 能力弱（tool calling 格式不稳） | 结构化 JSON + schema 校验兜底 + 失败重试(n=1)；实测发现 3B 常把 `target_node_id` 写成"button"字符串而非序号→阶段 5 调 prompt/做容错；或换 7B |
| UI 树超长撑爆上下文 | ui_summarizer 压缩为可交互节点，单屏 ≤500 token |
| 高权限自动操作误伤 | 动作白名单 + 关键操作确认（三层兜底，见 DESIGN.md §7.2） |
| Kotlin 原生工程量大（新技能栈） | 执行侧仅做 UI采集/动作执行/HTTP client，决策逻辑复用 Python；起 Android Studio 模板工程 |
| 原生触发入口后台限制（Doze/省电） | 定时用 AlarmManager 精确闹钟 + 前台服务保活（阶段 5） |
| 裸 Termux 编译型依赖难装 | 优先纯 Python/预编译 wheel；需编译则 `pkg install clang python-dev`；装不上换等价包 |
| 模型/服务常驻内存压垮手机 | llama-server 按需拉起 + 空闲超时退出（D5）；仅两个常驻进程 |

## 5. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-18 | 创建文档体系；记录 D1–D4 决策、五阶段计划与验收标准 |
| 2026-08-18 | 增订决策 D5（按需拉起）D6（HTTP 主干）D7（Termux:API 感知插件）D8（裸 Termux+venv 非 proot）D9（AVD 优先、Google APIs 镜像）；更新阶段 3/4/5 与风险与部署拓扑 |
| 2026-08-18 | 阶段 1 完成：git init、四 JSON Schema、bridge/validate.py、示例；`python -m bridge.validate` 通过 |
| 2026-08-18 | 阶段 2 完成：ui_summarizer / llm_client(mock) / agent_loop / server；25 单测全绿；config.yaml 延后至阶段 3 |
| 2026-08-18 | 阶段 3 完成：ModelScope 下载 Qwen2.5-3B Q4_K_M（2.1GB）；llama-server b10488 Windows CPU；config.yaml + LlamaManager(D5 按需拉起/空闲退出)；本地 chat completion HTTP 200；agent_loop 加 max_retries 失败重试；实测 3B tool calling 格式不稳（target_node_id 写成字符串）记为风险 |
| 2026-08-18 | **架构战略变更**：执行侧从 Tasker 切换为原生 Android（Kotlin）App（新增 D10/D11/D12）；决策层恒用 Python；触发一期=定时+通知+悬浮语音，常驻唤醒词推迟二期；阶段 4/5 重排；目录新增 android/ |
| 2026-08-19 | Android 通知监听授权入口：主界面新增跳转至受保护的通知访问设置页，便于 AVD/真机由用户显式授权并继续验证通知触发链路。 |
| 2026-08-19 | AVD 验证通知访问入口已正确进入 Android 15 系统授权页，且页面识别 TARS Assistant；实际读取通知权限保留为用户在系统 UI 中显式确认。 |
| 2026-08-19 | 仓库卫生：`.reasonix/` 本地运行元数据与任务缓存移出 Git 跟踪并整体忽略，保留开发工作区副本。 |
| 2026-08-19 | AVD 通知触发验收：用户授权后监听服务处于 live 状态；系统测试通知可被捕获并仅预填为用户审查任务，不会自动调用 Agent 或执行动作。 |
| 2026-08-19 | 悬浮语音入口：无障碍服务创建 `TYPE_ACCESSIBILITY_OVERLAY` 按住说话按钮，最终语音文本仅进入待处理任务；同时收紧所有触发广播，前台只提示用户手动载入而不自动填入。 |
| 2026-08-19 | 悬浮语音 AVD 修复：使用显示器关联的 window context，并将 overlay 清理移至服务销毁阶段；避免 Android 15 Context 崩溃，验证窗口可登记为 `ACCESSIBILITY_OVERLAY`。 |
| 2026-08-19 | 文档校正：当前 Python 决策层是自研 SmolAgent 风格安全循环，尚未引入 `smolagents` 包；保留未来框架接入路径，且不放松既有安全边界。 |
| 2026-08-19 | AVD Termux 基线：安装并校验官方 Termux v0.118.3 x86_64 APK；当前 2 GB AVD 继续用于 Android 功能联调，Python/模型端到端验收移至后续高配 AVD。 |
| 2026-08-19 | Agent 启动闭环：新增 `python -m agent.server` 运行时配置（显式 `--mock` 或本地 llama-server），并补齐 HTTP 冒烟脚本与部署手册。 |
| 2026-08-19 | 固定演示技能：协议新增受限 `launch` 动作；“打开设置/TARS/微信”由 Python 固定路由和 Android 双重包名白名单执行。 |
| 2026-08-19 | AVD 联调修复：补充 `open settings` 英文别名，并修复 Android `launch` 动作序列化遗漏 `package_name`；追加验收记录。 |
| 2026-08-19 | 代理恢复后使用现有 Gradle 缓存成功重建并安装 APK；mock Agent 固定技能 HTTP 链路通过，重装后的无障碍授权待用户在系统 UI 中重新确认。 |
| 2026-08-19 | Agent 健壮性：缺失或空 `ui_xml` 按空节点处理，避免无障碍服务未连接时产生 502；非法非空 XML 仍 fail-closed。 |
| 2026-08-20 | AVD 跨应用验收完成：无障碍采集到系统设置 UI 树，固定 `launch` 技能实际启动设置页并置前台；记录服务重启后的重新绑定注意事项。 |
| 2026-08-20 | Shizuku AVD 安装基线：按用户指定使用 `thedjchi/Shizuku` 的 `v13.7.0-thedjchi` Release；记录包标识与 SHA-256，待无线调试启动和 UserService 授权验收。 |
| 2026-08-20 | Shizuku AVD 启动基线：因模拟器未关联 Wi-Fi，改用管理器生成的 ADB 启动命令；服务以 shell 身份运行，待用户确认 TARS UserService 授权并验收受限 swipe。 |
| 2026-08-20 | Shizuku 授权入口修正：授权请求改由前台 MainActivity 发起，并区分已授权、已提交、需手动授权和服务不可用状态；AVD 管理器中已手动开启 TARS，受限 swipe 待新 APK 重建后验收。 |
| 2026-08-21 | 阶段 4 验收完成：补齐 ShizukuProvider Manifest 声明，修复 UserService 绑定失败；最新 APK 在 AVD 通过 mock Agent 返回的合法 swipe 动作完成 Shizuku UserService 实际执行验收。 |
