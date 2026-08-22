# AVD 联调记录

## 2026-08-21 云端模型迁移

- 架构调整为：Android App -> 本机 Termux Python Agent -> HTTPS 云端模型 API；云端只提供模型，
  不承载 Agent 或执行侧。
- 已停止旧 Agent，删除 Termux 中的 `llama-server` 二进制、`~/tars-assistant/models/` 和
  `~/tars-assistant/.runtime/`；项目目录核验为 86 MB，`command -v llama-server` 与
  `pkg list-installed llama.cpp` 均无结果。
- 保留 Termux、`~/tars-assistant` 与 `.venv`，等待在其中填写 `config/cloud.yaml`、更新源码并启动
  云端模型模式的自研 Agent。

## 环境

- 日期：2026-08-19（以下历史验收环境）
- AVD：`TARS_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5（已于 2026-08-21 删除）
- APK：Debug，`com.tars.assistant`，version `0.1.0`

## 2026-08-21 高配置 AVD 迁移

- 低配 `TARS_API_35`（2 GB RAM、4 核、6 GB 数据盘）已停止并永久删除；它只用于完成
  权限、Shizuku 与设备内 mock HTTP 验收，不再作为后续环境。
- 已创建并启动 `TARS_MODEL_API_35`：Android 15 / Google APIs / x86_64 / Pixel 5，6 核、
  6 GB RAM、16 GB 数据盘。设备内 `/proc/meminfo` 确认 `MemTotal` 为约 5.8 GiB。
- 当前 Debug APK 已安装并可启动。删除旧 AVD 会清除其应用与 Termux 状态；新设备上的
  无障碍、通知访问、Shizuku、Termux venv 和模型文件均须按部署手册重新初始化。
- 本设备将作为阶段 5 的真实 3B GGUF 模型与端到端性能验收环境；尚未下载模型，因此不把
  本次启动结果记录为真实推理验收。

## 2026-08-21 高配置 AVD Termux 重部署

- 从 Termux 官方 `v0.118.3` GitHub Release 下载并校验 x86_64 APK，SHA-256 为
  `3550E61F4D9EB49B712FD1BD9519DC37085A4D8EB597C57A340F0A64859B7144`；已安装至
  `TARS_MODEL_API_35`。
- 在裸 Termux（未使用 `proot-distro`）安装 `python`、`git`、`rust`，克隆项目并创建
  `.venv`。使用 `PIP_NO_BUILD_ISOLATION=1 pip install -r requirements.txt` 成功安装依赖；
  `pydantic-core`、`rpds-py`、`httptools`、`uvloop`、`watchfiles` 等 Android x86_64 wheel
  已构建并缓存。
- 在设备内运行 `python -m agent.server --mock`，服务确认监听 `127.0.0.1:8080`。TARS 发送
  无动作测试任务后显示“协议联调完成（mock，未调用本地模型）”；没有配置 `adb reverse`。
  验收后的临时 mock 服务已停止。

## 已验证

- `gradle :app:assembleDebug` 成功，APK 可由 ADB 安装并启动。
- 包管理器已识别 `TarsAccessibilityService`（`AccessibilityService`）和
  `NotificationTriggerService`（`NotificationListenerService`）的受保护服务声明。
- 主界面的意图输入、发送、15 分钟提醒、通知载入和 Shizuku 授权控件均存在于
  `uiautomator` UI 树，启动日志无崩溃。
- Android 明文流量策略已允许固定的本机 Agent endpoint。发送测试任务在模拟器中到达
  `127.0.0.1:8080` 并得到“连接失败”（模拟器没有 Termux Agent），不再出现
  `Cleartext HTTP traffic ... not permitted`。
- 15 分钟提醒显示“已安排 15 分钟后的待处理提醒”；`dumpsys alarm` 显示一次性的
  `com.tars.assistant.SCHEDULED_TASK` 广播。没有后台 Agent 请求或动作执行。
- 按住说话触发 Android 的 `RECORD_AUDIO` 运行时授权对话框（使用期间/仅此一次/拒绝）；
  未授权前不会创建语音识别请求。
- 通过系统无障碍设置中的 TARS Assistant 条目和“完整控制设备”警告确认后，`dumpsys
  accessibility` 显示服务处于 Bound/Enabled 状态；返回主界面后显示“无障碍服务已连接”。
- 新增的通知访问入口可打开 Android 15 的“通知读取、回复和控制”系统授权页；该页在
  “Not allowed”列表中显示 `TARS Assistant`，证明受保护服务声明和设置跳转均可用。
- 用户在系统 UI 显式启用通知访问后，`dumpsys notification` 确认
  `NotificationTriggerService` 已启用并处于 live 状态。通过 `cmd notification post` 发布的测试通知被
  捕获；点击“载入最新通知”后，任务意图预填为“处理来自 `com.android.shell` 的通知：TARS：测试”，
  状态提示要求用户检查后发送，未自动调用 Agent 或执行动作。
- 麦克风权限授权后，主界面可切换为“停用悬浮语音”，且 `dumpsys window` 可见
  TARS 的 `ty=ACCESSIBILITY_OVERLAY` 窗口（`appop=CREATE_ACCESSIBILITY_OVERLAY`）。
  重装 APK 或无障碍服务重连期间系统会回收旧窗口，需重新启用；跨服务重启的持久化行为仍待
  后续观察。
- 已从 Termux 官方 GitHub Release 安装 `v0.118.3` 的 x86_64 APK；下载文件 SHA-256 与
  官方发布清单一致。Termux 可首次启动，但 Python 包安装受当前 AVD 所选镜像源速度限制而未完成；
  本低配 AVD 仅继续用于 Android 功能联调，不作为本地模型推理性能验收环境。

## 待验证

- 无障碍服务已在 AVD 的系统 UI 中启用；待进一步验证跨应用树采集与动作执行。通知监听仍待
- 待结合真实应用通知继续观察不同 payload 的兼容性；通知访问已由用户在系统 UI 中显式启用并完成
  测试通知捕获。Android 15 阻止 ADB shell 直接打开单项无障碍详情页，也未
  接受直接 secure-setting 写入；App 提供“打开无障碍设置”入口，须在系统 UI 中显式授权。
- 在 AVD 启动 Shizuku 后，验证用户授权与受限 swipe UserService。
- 在 Android 设备的 Termux 中运行 Agent 服务后，验证真实 `127.0.0.1:8080` 端到端流程。

## 2026-08-19 追加验收

- 最新 Debug APK 已重新安装到 `TARS_API_35`，并通过 `adb reverse tcp:8080 tcp:8080`
  连接主机 mock Agent；Python/bridge 单测 29 项通过。
- 联调发现并修复英文固定意图 `open settings` 路由别名，以及 Android `launch` 动作
  序列化遗漏 `package_name` 的问题；修复后的 APK 待重新安装后验证实际跳转。
- AVD 当前仍有 Termux `apt` 进程运行；Python 安装未完成，不将其记录为已部署。
- 本轮离线重建未完成：用户全局 Gradle 缓存的 native-platform 锁文件被占用；切换到
  项目专用缓存后，代理关闭使 Android Gradle Plugin `8.5.2` 无法解析。该限制不影响
  Python/bridge 的 30 项离线测试；待依赖缓存可用时重新构建并安装 APK 复验。
- 代理开启后使用用户现有 Gradle 缓存重新构建成功，最新 APK 已安装到 AVD。通过
  `adb reverse` 和 mock Agent 验证 `open settings` 返回固定 `launch` 响应；重装后无障碍
  服务需在 Android 系统 UI 中再次显式授权，因此本轮执行结果为“无障碍服务未连接”，未
  进行实际跳转。重新授权后即可复测动作执行与跨应用 UI 树采集。
- 修复 Agent 对缺失或空 `ui_xml` 的处理：无障碍服务未连接时请求不再因 XML 空串返回
  `502`，而是进入空节点安全决策；非空非法 XML 仍保持失败关闭。
- 用户重新启用无障碍服务后完成跨应用验收：服务处于 `Bound/Enabled`，通过 mock Agent
  发送 `open settings`，Android 执行侧实际启动 `com.android.settings/.Settings` 并将其
  置于前台。设置页 UI 树包含 `Settings`、`Search settings`、`Notifications` 等外部应用
  节点，证明当前采集不是仅限 TARS 自身。测试过程中不再使用 `force-stop`，避免系统回收
  无障碍绑定；重启 App 后须等待服务重新绑定。
- Shizuku 安装基线：从用户指定的 `thedjchi/Shizuku` GitHub Release 安装
  `v13.7.0-thedjchi`（`moe.shizuku.privileged.api`，versionCode `1361`）；APK SHA-256 为
  `6EA6DEE65D5DDC626B6B75B2C2F67F8CC547FA47D7B437E6892639C37EAFFE43`。管理器显示
  “Shizuku is not running”，无线调试 `Pairing`/`Start` 和 TARS UserService 授权仍待用户在系统 UI
  中完成。
- Shizuku AVD 启动基线：模拟器 Wi-Fi 已启用但未关联网络，无线调试无法配对；改用管理器
  “View command”提供的当前安装包 ADB 启动命令，`shizuku_server` 以 `shell` 身份运行，管理器
  显示 “Shizuku is running / Version 13.6, adb”。TARS 已发出 UserService 授权请求，等待用户在
  Shizuku UI 中确认后再验收受限 `swipe`。

## 2026-08-20 Shizuku 授权验收

- AVD 上未出现系统弹窗，但 Shizuku 管理器的 `Application management` 已列出
  `TARS Assistant (com.tars.assistant)`；手动打开该条目的开关后，管理器显示授权开关为开启。
- 原授权入口由无障碍 Service 发起，且将“请求已提交”误报为布尔失败。已改为由前台 `MainActivity`
  发起请求，并区分 `GRANTED`、`REQUESTED`、`RATIONALE_REQUIRED` 与 `UNAVAILABLE` 状态；后续授权
  不依赖弹窗是否自动出现。
- 受限 `swipe` UserService 的实际动作验收仍待重新安装本次 APK 后执行。当前 Android 构建受本机
  Gradle 缓存/初始化脚本环境阻断，未将旧 APK 误记为包含本次修改。

## 2026-08-21 Shizuku `swipe` 验收

- 恢复 Gradle 构建链路：Scoop 配置的 `GRADLE_USER_HOME` 注入了与项目仓库策略冲突的初始化脚本；
  本轮改用标准 `C:\Users\Winter\.gradle` 缓存并经本机代理补齐插件元数据，`assembleDebug` 成功。
- 发现并修复 UserService 无法绑定的根因：Manifest 缺少官方
  `rikka.shizuku.ShizukuProvider` 声明，Shizuku server 日志此前显示
  `provider is null com.tars.assistant.shizuku`。
- 最新 Debug APK 安装后，Shizuku 管理器显示 `Authorized 1 application`，无障碍服务仍为
  `Bound/Enabled`。经 ADB reverse 连接到一次性 mock Agent 返回合法坐标和时长的 `swipe` 动作，
  TARS 界面显示“已执行: swipe”，且 logcat 显示 `Shizuku UserService connected`。

## 2026-08-21 Termux 设备内 Agent 验收

- AVD 内的 Termux `0.118.3` 已完成裸环境部署：安装 `python`、`git`，克隆仓库并在项目目录创建
  `.venv`；未使用 `proot-distro` 容器层。
- 当前 Python 3.14 的 Termux x86_64 缺少若干 PyPI 原生 wheel。安装 `requirements.txt` 前需安装
  Termux 原生 `rust` 包，并用 `PIP_NO_BUILD_ISOLATION=1 pip install -r requirements.txt` 构建
  `pydantic-core`、`rpds-py`、`uvloop`、`watchfiles` 等依赖；生成的 wheel 会缓存供后续复用。
- 在 Termux venv 中运行 `python -m agent.server --mock` 后，服务仅监听设备
  `127.0.0.1:8080`。未配置任何 `adb reverse` 时，TARS 主界面发送任务显示
  “协议联调完成（mock，未调用本地模型）”；Termux 日志记录 `POST /agent/run` 返回 `200 OK`。
- 该 AVD 仍只承担 Android/HTTP/权限联调，不将 Rust 编译耗时或本次 mock 验收当作 3B GGUF 性能结论。

## 2026-08-21 Termux 本地模型后台启动修正

- 初次真实请求确认到达 `llama-server`，但 CPU 推理超过旧的 60 秒 HTTP 等待上限；Agent 默认已调整为 180 秒。
- 旧的按需启动实现丢弃了 `llama-server` 的所有输出，且即使启动未就绪仍会继续请求端口，导致难以诊断的连接失败。现已改为独立后台会话启动并记录 `.runtime/llama-server.log`；未就绪时安全返回 HTTP 503。
- 后续在 AVD 上从更新后的仓库启动 Agent，发送无动作真实模型任务，记录首 token/总响应时间和返回的 schema 校验结果。

## 2026-08-21 Termux 真实模型请求验收

- 使用独立 Termux 会话运行 `llama-server`，另一会话运行更新后的 Agent（`--llm-timeout-seconds 180`）；两者均只监听设备 loopback。
- TARS 发送 `say welcome only`，请求成功完成模型推理并返回至 Android App，未再出现连接拒绝或 60 秒读取超时。
- Qwen2.5-3B Q4_K_M 此次将 `target_node_id` 输出为字符串 `'1'`；Agent 的 JSON schema 校验拒绝该响应，App 显示“LLM 响应未通过 schema 校验”，且未执行任何动作。这是预期的 fail-closed 结果，确认不可信模型输出不会越过执行安全边界。
- 按需后台拉起代码已具备独立会话和日志诊断能力；本次完整模型验收先使用独立会话托管，后续继续针对 AVD 的自动拉起钩子收集日志并复验。

## 2026-08-21 Termux 自动后台模型生命周期验收

- 先停止手工运行的 Agent 与 `llama-server`，仅在新 Termux 会话中执行
  `. .venv/bin/activate && python -m agent.server --llm-timeout-seconds 180`。新会话未激活 venv
  时会报 `ModuleNotFoundError: fastapi`；这是 Termux shell 环境未继承，不是模型启动失败。
- TARS 发送任务后，Agent 自动创建独立的 `llama-server` 进程，加载并完成真实推理；响应仍因字符串型
  节点 ID 被 schema 安全拒绝，HTTP 返回 200 且未执行动作。
- 请求结束超过 60 秒后，`llama-server` 已自动退出，仅 Agent 进程保留，确认 D5 的按需拉起、任务窗口保活与空闲释放内存闭环在 `TARS_MODEL_API_35` 上通过。

## 2026-08-21 冷启动空闲监控修正

- 后续复验发现：若 Agent 已运行超过空闲阈值才收到首个真实任务，旧实现会在模型加载期沿用旧活动时间，
  使空闲监控错误终止尚未就绪的 `llama-server`。后台日志表现为停在 `load_model` 阶段，Agent 安全返回 503。
- 已修正为创建子进程前立即刷新活动时间，并添加单元测试；模型加载完成前不会因空闲策略被回收。
- 进一步确认慢加载可本身超过 60 秒，已将“启动中”设为独立状态；直到模型健康或达到启动超时前，
  空闲监控均不回收该进程。

## 2026-08-21 AVD 开发者模式测试前置

- 用户确认模型服务继续由 Termux/Python 决策端负责，Android 原生侧仅执行；不改变该架构边界。
- 已通过 ADB 启用本 AVD 的开发者模式（`development_settings_enabled=1`），并设置
  `settings_enable_monitor_phantom_procs=0`，用于继续验证 Termux 的后台 llama-server 生命周期。
- 此项只适用于当前 AVD 开发测试配置，真机部署策略另行评估。

## 2026-08-21 Android 冷启动请求窗口

- Agent 的真实模型请求预算为 180 秒；Android `AgentClient` 的旧 120 秒读取上限可能先于
  Agent 完成冷启动或 CPU 推理而使 UI 显示无上下文的 timeout。
- Android 读取上限现为 210 秒，连接上限仍为 5 秒；提交安装后需在 `TARS_MODEL_API_35` 重新执行
  一次真实模型任务，记录模型日志、实际耗时和最终动作结果。
- 本次安装后的真实任务在 210 秒窗口内完成，未出现 Android 客户端 timeout；但无障碍服务重连广播
  覆盖了最终状态文本，动作回执未能可靠判定。已增加请求进行中状态保护，需用下一版 APK 重复
  同一任务并记录最终回执及定时提醒是否实际创建。
- 第二次使用状态保护版 APK 重试同一任务：客户端仍未提前 timeout，最终收到 Agent `HTTP 502`，
  具体为 llama-server `RemoteDisconnected`；模型进程随后退出，未执行任何动作。下一步需读取
  `.runtime/llama-server.log`，区分模型服务崩溃、资源耗尽或请求协议异常。
- 诊断确认 Android 无低内存杀进程记录；根因是 Agent 的 60 秒空闲监控在模型健康后、慢速 CPU
  推理尚未返回时回收 llama-server。D5 现已增加请求进行中计数，推理期间禁止回收，请求结束后
  才重新开始空闲窗口；下一次真实模型验收用于验证该修复。
