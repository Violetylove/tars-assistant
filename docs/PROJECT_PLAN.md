# 项目计划书 — TARS Assistant

> 计划与进度跟踪文档。每完成一项工作即更新本文件。

## 1. 项目目标

在 Android 手机上构建 AI 助手：**原生 App 触发 → Python Agent（安全循环 + 云端模型 API）决策 → 原生 App 执行（无障碍/Shizuku 高权限点击滑动）**。

## 2. 已定决策（2026-08-18 与用户确认）

| # | 决策点 | 选择 |
|---|--------|------|
| D1 | 主控形态 | **原生 Android App 触发 + Agent 决策 + 原生 App 执行**（事件驱动） |
| D2 | 感知方式 | 纯文本 UI 树（uiautomator 采集 → Python 端摘要），非视觉 |
| D3 | 模型规格 | 云端 OpenAI-compatible 大模型；模型 ID 经私有配置切换 |
| D4 | 目标范围 | 通用对话 + 少量固定技能 |
| D5 | 推理层生命周期 | 云端模型按请求调用；不在手机部署或托管模型进程 |
| D6 | 通信主干 | 纯 HTTP loopback（127.0.0.1:8080），不引 WebSocket/MQTT 常驻通道 |
| D7 | Termux:API 定位 | 感知插件（剪贴板等），不作通信主干；语音一期用 SpeechRecognizer 原生识别 |
| D8 | Agent Python 环境 | 裸 Termux + venv（独立虚拟环境，**不用 proot-distro 常驻容器**） |
| D9 | 测试环境 | AVD 优先（系统镜像 Google APIs），暂不用真机；主镜像 Google APIs，AOSP 作备用 |
| D10 | **执行侧路线**（2026-08-18 战略变更） | **原生 Android（Kotlin）执行侧**，用无障碍 + Shizuku 官方库，**替换 Tasker** |
| D11 | **触发入口分期** | 一期 = 定时(AlarmManager/WorkManager) + 通知监听(NotificationListenerService) + 悬浮按钮按住说话(SpeechRecognizer)；**常驻唤醒词推迟二期**（受 Android 权限硬约束） |
| D12 | **决策层归属** | 决策层恒用 Python 生态（安全 Agent 循环 + schema 校验）；云端只提供模型 API，可后续接入 SmolAgent 框架，**任何迁移不修改**；执行侧仅提供 HTTP client + 动作执行 |

## 3. 阶段计划与进度

图例：🟢 已完成 · 🔵 进行中 · ⚪ 未开始

| 阶段 | 内容 | 状态 |
|------|------|------|
| 0 | 文档奠基（AGENTS.md / 计划书 / 设计书） | 🟢 已完成 |
| 1 | 项目骨架 + 通信协议契约 | 🟢 已完成 |
| 2 | Agent 端：HTTP 服务 + UI 摘要器 + 决策循环 | 🟢 已完成 |
| 3 | 云端模型接入与私有配置 | 🟢 已完成 |
| 4 | 原生执行侧 App（Kotlin）：UI 采集 + 动作执行 + HTTP client | 🟢 已完成 |
| 5 | 触发一期 + 端到端集成 + 演示技能 + 部署文档 | 🔵 进行中 |

### 阶段 0：文档奠基
- [x] AGENTS.md（项目记忆，含 D1–D4 与工程约定）
- [x] docs/PROJECT_PLAN.md（本文件）
- [x] docs/DESIGN.md（架构 + 协议契约，权威技术文档）

### 阶段 1：项目骨架 + 通信协议契约
- [x] 初始化 git 与目录结构（agent/ bridge/ android/ scripts/ docs/）
- [x] 定义并文档化 JSON Schema：`task_request` / `agent_response` / `action` / `ui_tree`（见 DESIGN.md §5）
- [x] 编写协议校验器 `bridge/validate.py` 与最小端到端 JSON 示例
- **验收**：schema 与示例 JSON 可被校验器解析通过（`python -m bridge.validate`）

### 阶段 2：Agent 端
- [x] `agent/server.py`：FastAPI 服务（/agent/run、/health）
- [x] `agent/ui_summarizer.py`：原始 UI 树 XML → 紧凑交互节点（≤500 token）
- [x] `agent/agent_loop.py`：自研安全决策循环（SmolAgent 风格）+ schema 校验兜底
- **验收**：mock UI 树 + 固定任务跑通 agent 返回合法 action JSON 的单测（✅ 40 passed）

### 阶段 3：云端模型接入
- [x] 搭建开发虚环境（venv + requirements.txt，Windows 本地）
- [x] 删除本地 llama.cpp、GGUF 下载和模型进程生命周期代码
- [x] `config/cloud.yaml.example`：云端模型私有配置模板（真实文件 Git 忽略）
- [x] `agent/llm_client.py`：可 mock 的 LLM 客户端（base_url + model 配置化）
- [x] Windows 开发环境的云端 OpenAI-compatible 模型与自研 Agent schema 链路验证
- [x] Termux Agent 经 HTTPS 云端模型的设备内验证（私有配置仅复制至 AVD Termux）
- [x] 云端可靠性策略：连接/超时、HTTP 429 与 5xx 有界指数退避重试；认证、其他 4xx 与无效响应直接失败，参数经私有配置限制
- **验收**：Termux Agent 经 HTTPS 云端模型返回 chat completion；当前 APK 通过同设备 loopback 完成无副作用请求闭环；API Key 未进入 APK/Git

### 阶段 4：原生执行侧 App（Kotlin，AVD 上开发）
- [x] `android/`：Gradle 工程骨架（Kotlin + 原生 View）
- [x] UI 采集：无障碍取当前 UI 树 → `task_request` 契约（复用已建 `bridge/` schema）
- [x] UI 采集方案定夺：选定无障碍 `AccessibilityNodeInfo` 直接序列化；跨应用系统设置实测具备包名、可交互节点和 bounds，Shizuku `uiautomator dump` 仅保留为备用
- [x] 无障碍状态：服务连接会回报到主界面，便于授权和联调诊断
- [x] 动作执行：无障碍执行 click/type/back/home/wait；Shizuku 授权与参数受限的 swipe 已接入并在 AVD 验收
- [x] HTTP client：调 Python 决策层 `POST /agent/run`、`GET /health`
- [x] 前台上下文：无障碍事件采集最近前台应用包名和窗口类名，随每轮 UI 快照作为可选协议字段回传 Agent
- [x] Android loopback HTTP 策略：明文 loopback 放行 + 客户端固定端点校验（AVD 已验证不再触发 cleartext 拦截）
- [x] 安全层：动作白名单 + 参数/会话校验 + 双层敏感目标确认弹窗
- [x] 执行失败收敛：动作被拒绝、取消或失败即停止同轮与后续观察，避免在未执行状态继续模型决策
- [x] 多轮观察新鲜度：动作后仅在 UI XML 与动作前快照不同才采样；2 秒内未更新则停止，避免模型基于陈旧界面继续决策
- [x] 多轮历史边界：原生侧回传最多三轮已执行的合法 action；协议拒绝任意对象和超量历史，Agent 保持无状态
- [x] 服务端输出边界：固定技能与决策后端响应在返回 Android 前均重做 schema 与 session_id 校验，拒绝错配或非法动作
- [x] 单轮动作数量边界：协议限制每个 `agent_response` 最多 8 个动作，避免无界执行序列
- [x] **验收**：AVD 已完成 APK 安装/启动、服务声明、主界面、无障碍授权与绑定、loopback HTTP 策略、定时待处理链路、通知访问、Shizuku swipe 和设备内 Termux mock Agent 联调（见 `docs/AVD_TESTING.md`）

### 阶段 5：触发一期 + 端到端集成 + 部署
- [x] 触发一期：通知监听（NotificationListenerService）、15 分钟定时提醒（AlarmManager）和无障碍悬浮语音均实现为“触发 → 用户载入确认”；待处理任务保存在 SharedPreferences，AVD 已验证触发后强制停止并重启 App 仍可手动载入，且不自动调用 Agent
- [~] 固定演示技能 + 通用对话路由：启动白名单应用（设置/TARS/微信）优先走固定技能路由；发送消息演示待真实模型联调
- [x] 真实模型低风险动作闭环：云端模型在 AVD 当前 UI 中选取“15 分钟后提醒”节点；Agent schema 校验后由 Android 无障碍执行，AlarmManager 已登记一次性提醒
- [x] 真实模型敏感动作防线：模型选取带“发送”标签节点时，Android 依标签强制二次确认；AVD 测试取消确认，未执行点击
- [x] `docs/DEPLOY.md`：Windows 开发 → AVD 测试 → 实体机（裸 Termux+venv 决策层 + 原生 App APK）部署手册，含 mock/真实模型分层验收步骤
- [x] 端到端 HTTP 冒烟测试脚本：`scripts/smoke_agent.py`（健康检查 + 合法 `agent_response`）
- [x] Android 构建链路复验：声明阿里云 Maven 镜像并保留官方回退；全新依赖构建成功，当前 Debug APK 已覆盖安装至 AVD，固定 `open settings` 技能经同设备 loopback 与启动白名单实际打开系统设置
- [x] 通用对话收敛修复：将模型单动作 `reply` 规范化为终态文本响应，避免 Android 将答复误作动作并触发无意义的观察轮次
- **验收**：README 描述一条可复现的"空白环境 → 触发 → 跑通一个技能"路径

> 二期（排期外）：常驻唤醒词（D11，受 Android 权限硬约束，暂缓）。

## 4. 风险清单

| 风险 | 缓解 |
|------|------|
| 3B 模型 agent 能力弱（tool calling 格式不稳） | 结构化 JSON + schema 校验兜底 + 失败重试(n=1)；对当前 UI 中存在的纯数字字符串节点 ID 做受限规范化，其它字符串仍拒绝；复杂任务可换 7B |
| UI 树超长撑爆上下文 | ui_summarizer 压缩为可交互节点，单屏 ≤500 token |
| 高权限自动操作误伤 | 动作白名单 + 关键操作确认（三层兜底，见 DESIGN.md §7.2） |
| Kotlin 原生工程量大（新技能栈） | 执行侧仅做 UI采集/动作执行/HTTP client，决策逻辑复用 Python；起 Android Studio 模板工程 |
| 原生触发入口后台限制（Doze/省电） | 定时用 AlarmManager 精确闹钟 + 前台服务保活（阶段 5） |
| 裸 Termux 编译型依赖难装 | 优先纯 Python/预编译 wheel；需编译则 `pkg install clang python-dev`；装不上换等价包 |
| 云端模型不可用或超时 | 对连接/超时、429、5xx 最多重试 2 次并指数退避；认证/其他 4xx 立即报明确错误；不降级为未标记自动执行 |

## 5. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-08-18 | 创建文档体系；记录 D1–D4 决策、五阶段计划与验收标准 |
| 2026-08-18 | 增订决策 D5（按需拉起）D6（HTTP 主干）D7（Termux:API 感知插件）D8（裸 Termux+venv 非 proot）D9（AVD 优先、Google APIs 镜像）；更新阶段 3/4/5 与风险与部署拓扑 |
| 2026-08-18 | 阶段 1 完成：git init、四 JSON Schema、bridge/validate.py、示例；`python -m bridge.validate` 通过 |
| 2026-08-18 | 阶段 2 完成：ui_summarizer / llm_client(mock) / agent_loop / server；25 单测全绿；config.yaml 延后至阶段 3 |
| 2026-08-18 至 2026-08-21 | 曾完成本地模型探索与 AVD 验证；该方案已由 2026-08-21 云端模型决策完全替代，相关代码、脚本、模型文件和部署路径均已清理。 |
| 2026-08-18 | **架构战略变更**：执行侧从 Tasker 切换为原生 Android（Kotlin）App（新增 D10/D11/D12）；决策层恒用 Python；触发一期=定时+通知+悬浮语音，常驻唤醒词推迟二期；阶段 4/5 重排；目录新增 android/ |
| 2026-08-19 | Android 通知监听授权入口：主界面新增跳转至受保护的通知访问设置页，便于 AVD/真机由用户显式授权并继续验证通知触发链路。 |
| 2026-08-19 | AVD 验证通知访问入口已正确进入 Android 15 系统授权页，且页面识别 TARS Assistant；实际读取通知权限保留为用户在系统 UI 中显式确认。 |
| 2026-08-19 | 仓库卫生：`.reasonix/` 本地运行元数据与任务缓存移出 Git 跟踪并整体忽略，保留开发工作区副本。 |
| 2026-08-19 | AVD 通知触发验收：用户授权后监听服务处于 live 状态；系统测试通知可被捕获并仅预填为用户审查任务，不会自动调用 Agent 或执行动作。 |
| 2026-08-19 | 悬浮语音入口：无障碍服务创建 `TYPE_ACCESSIBILITY_OVERLAY` 按住说话按钮，最终语音文本仅进入待处理任务；同时收紧所有触发广播，前台只提示用户手动载入而不自动填入。 |
| 2026-08-19 | 悬浮语音 AVD 修复：使用显示器关联的 window context，并将 overlay 清理移至服务销毁阶段；避免 Android 15 Context 崩溃，验证窗口可登记为 `ACCESSIBILITY_OVERLAY`。 |
| 2026-08-19 | 文档校正：当前 Python 决策层是自研 SmolAgent 风格安全循环，尚未引入 `smolagents` 包；保留未来框架接入路径，且不放松既有安全边界。 |
| 2026-08-19 | AVD Termux 基线：安装并校验官方 Termux v0.118.3 x86_64 APK；当前 2 GB AVD 继续用于 Android 功能联调，Python/模型端到端验收移至后续高配 AVD。 |
| 2026-08-19 | 固定演示技能：协议新增受限 `launch` 动作；“打开设置/TARS/微信”由 Python 固定路由和 Android 双重包名白名单执行。 |
| 2026-08-19 | AVD 联调修复：补充 `open settings` 英文别名，并修复 Android `launch` 动作序列化遗漏 `package_name`；追加验收记录。 |
| 2026-08-19 | 代理恢复后使用现有 Gradle 缓存成功重建并安装 APK；mock Agent 固定技能 HTTP 链路通过，重装后的无障碍授权待用户在系统 UI 中重新确认。 |
| 2026-08-19 | Agent 健壮性：缺失或空 `ui_xml` 按空节点处理，避免无障碍服务未连接时产生 502；非法非空 XML 仍 fail-closed。 |
| 2026-08-20 | AVD 跨应用验收完成：无障碍采集到系统设置 UI 树，固定 `launch` 技能实际启动设置页并置前台；记录服务重启后的重新绑定注意事项。 |
| 2026-08-20 | Shizuku AVD 安装基线：按用户指定使用 `thedjchi/Shizuku` 的 `v13.7.0-thedjchi` Release；记录包标识与 SHA-256，待无线调试启动和 UserService 授权验收。 |
| 2026-08-20 | Shizuku AVD 启动基线：因模拟器未关联 Wi-Fi，改用管理器生成的 ADB 启动命令；服务以 shell 身份运行，待用户确认 TARS UserService 授权并验收受限 swipe。 |
| 2026-08-20 | Shizuku 授权入口修正：授权请求改由前台 MainActivity 发起，并区分已授权、已提交、需手动授权和服务不可用状态；AVD 管理器中已手动开启 TARS，受限 swipe 待新 APK 重建后验收。 |
| 2026-08-21 | 阶段 4 验收完成：补齐 ShizukuProvider Manifest 声明，修复 UserService 绑定失败；最新 APK 在 AVD 通过 mock Agent 返回的合法 swipe 动作完成 Shizuku UserService 实际执行验收。 |
| 2026-08-21 | 设备内 Agent 联调完成：AVD 裸 Termux + venv 安装依赖并启动 mock Agent；TARS 未经 adb reverse 直连同设备 127.0.0.1:8080，主界面收到可见协议联调回执。 |
| 2026-08-21 | AVD 环境迁移：删除低配 `TARS_API_35`，创建并验证 `TARS_MODEL_API_35`（Google APIs x86_64 / 6 核 / 6 GB RAM / 16 GB 数据盘）；当前 APK 已安装，后续以此设备重新部署 Termux、Shizuku 与 3B 模型。 |
| 2026-08-21 | 高配 AVD Termux 重部署完成：校验并安装官方 x86_64 Termux 0.118.3，裸 Termux venv 成功构建 Python 原生依赖；mock Agent 监听设备 127.0.0.1:8080，TARS 实际收到无动作协议回执，未使用 adb reverse。 |
| 2026-08-21 | 3B 动作格式加固：提示词明确要求整数节点 ID；Agent 仅把当前 UI 中存在的纯数字字符串 ID 规范化为整数后再过 schema，未知或含歧义的值维持 fail-closed。 |
| 2026-08-21 | 真实模型复验：Android 请求在 210 秒窗口内完成且未提前 timeout；发现无障碍重连广播会覆盖最终回执，已加入请求进行中保护，动作结果待下一版 APK 复验。 |
| 2026-08-21 | 重大架构调整：手机保留 Termux Python Agent 与原生执行侧，云端仅提供大模型 API；清理 llama.cpp/GGUF、本地模型生命周期与历史 AVD 运行路径，增加 Git 忽略的云端配置模板，待真实 Key 验收。 |
| 2026-08-21 | 云端模型开发验证：私有配置可加载，OpenAI-compatible 最小请求成功；自研 Agent 对无界面测试意图生成并 schema 校验了合法 reply 动作，待 Termux 设备内复验。 |
| 2026-08-21 | D5 任务窗口保活修复：Agent 决策期间登记请求进行中状态，空闲监控不再终止超过 60 秒的慢速 CPU 推理；新增生命周期单测与部署说明。 |
| 2026-08-21 | 阶段 3 设备内验收完成：私有 `cloud.yaml` 仅同步至 AVD Termux，Agent `/health` 正常；真实云端请求返回 schema 合法 `reply`。重建并覆盖安装当前 APK、重新绑定无障碍服务后，App 经同设备 loopback 完成真实云端无副作用任务（4 个受控观察轮次，均为 HTTP 200）。真实模型下的敏感动作确认仍作为后续验收项。 |
| 2026-08-21 | 真实模型动作验收：限定任务仅允许点击“15 分钟后提醒”；云端 Agent 通过 schema 后由无障碍执行。AVD 首次授予 TARS 通知权限，系统 AlarmManager 确认已登记 `com.tars.assistant.SCHEDULED_TASK` 一次性提醒；仍须单独复验真实模型下的敏感动作二次确认。 |
| 2026-08-21 | 真实模型敏感动作验收：模型在 TARS UI 选取“发送给 TARS”节点后，Android 强制展示“确认 TARS 操作”弹窗。测试取消确认，最终记录为“等待确认: click”，未执行敏感点击；删除/支付沿用同一标签防线。 |
| 2026-08-21 | 触发持久化验收：以受限定时广播写入待处理任务，强制停止并重启 App 后仍可手动载入；Termux Agent 日志的 POST 计数保持 15，证明触发与载入均不自动调用 Agent。界面统一使用“待处理任务”描述定时、通知与语音三类来源。 |
| 2026-08-21 | Android 构建与 APK 同步复验：项目声明阿里云 Maven 镜像及官方仓库回退，以兼容机器级 Gradle 初始化脚本；无需代理即可完成全新 Debug 构建。当前 APK 已安装至 AVD，验证“载入待处理任务”文案；固定 `open settings` 经 App → Termux loopback → Python 固定路由 → Android 启动白名单打开系统设置。 |
| 2026-08-21 | 通用对话终态修复：模型单对象 `reply` 被规范化为 `done=true` 的文本响应，不再作为 Android 可执行动作或触发后续观察；补充单轮及多轮循环回归测试，并在 AVD Termux 经真实云端无副作用请求复验。 |
| 2026-08-21 | 云端模型可靠性：连接/超时、429 与 5xx 增加配置化有界指数退避重试（默认额外 2 次）；认证、其他 4xx 与无效响应立即失败，错误不含密钥。覆盖超时恢复、限流/服务错误恢复、认证不重试、耗尽重试与配置边界单测；AVD Termux 使用既有私有配置加载默认值并完成真实云端无副作用复验。 |
| 2026-08-22 | 前台上下文补齐：Android 无障碍服务保存最近事件的前台应用包名与窗口类名，经 HTTP 服务写入 Agent 提示词；单测验证传递链路，AVD Termux 真实云端无副作用请求确认模型可使用该上下文。动作仍严格绑定当前 UI 节点和既有安全防线。 |
| 2026-08-22 | 执行失败收敛：原生执行侧将动作结果结构化；拒绝、用户取消或执行失败会立即停止本轮余下动作并阻止后续观察，只有整轮成功才记录 history。AVD 以 TARS 自身发送按钮完成确认取消回归，日志确认仅有单次请求。 |
| 2026-08-22 | 多轮观察新鲜度修复：AVD 两轮探针发现启动设置后过早重采仍为 TARS UI，且该窗口变化未必向无障碍服务交付事件；改为动作前保存 UI XML，仅在根节点导出的快照实际变化后才进入下一轮，2 秒超时 fail-closed。 |
| 2026-08-22 | 多轮观察验收完成：修复版 APK 经临时两轮 Termux 探针启动系统设置；同一会话的第二轮实际回传 `com.android.settings` 上下文、系统设置 UI XML 和首轮 `launch` history。测试结束后恢复真实云端 Agent。 |
| 2026-08-22 | UI 采集方案定夺：选定方案 B，无障碍服务直接序列化 `AccessibilityNodeInfo`。AVD 绑定服务下的系统设置原始树为 21,695 字节，具备正确包名、可交互节点与 bounds；方案 A（Shizuku `uiautomator dump`）保留为未来受限备用路径。 |
| 2026-08-22 | 多轮历史边界：选定原生侧回传历史，避免 Agent 服务端状态；`task_request.history` 限制为最多三轮、每轮最多八个合法 action，拒绝任意对象和超量输入。 |
| 2026-08-22 | Agent 服务输出边界：`/agent/run` 在返回 Android 前统一校验固定技能与决策后端的响应 schema，并拒绝与请求 session_id 不一致的结果。 |
| 2026-08-22 | 单轮动作数量边界：`agent_response.actions` 与 history 中每轮动作统一限制为最多 8 个，协议单测覆盖超限拒绝。 |
