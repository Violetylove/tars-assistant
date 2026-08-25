# 部署与联调

## 架构边界

- Android 原生 App：采集 UI、调用本机 Agent、执行已校验动作。
- Termux Python Agent：自研安全决策循环、UI 摘要、schema 校验和固定技能路由。
- 云端：只提供 OpenAI-compatible 大模型 API；不托管 TARS Agent，也不直接调用 Android。

Android 默认连接同一设备上的 `http://127.0.0.1:8080`，也可在设置页配置受信任的远程 Agent 主机与端口。Agent 调云端模型必须使用 HTTPS。
云端调用会发送任务文本与压缩后的 UI 摘要；部署前应确认所选服务商的数据处理和保留策略符合使用场景。

## 1. 云端模型私有配置

在运行 Agent 的 Termux 中：

```bash
cd ~/tars-assistant
mkdir -p config
cp config/cloud.yaml.example config/cloud.yaml
```

编辑 `config/cloud.yaml`：

| 字段 | 填写内容 |
|---|---|
| `llm.base_url` | 服务商 OpenAI-compatible HTTPS 地址，通常以 `/v1` 结束 |
| `llm.model` | 服务商模型 ID |
| `llm.api_key` | 服务商 API Key，仅保留在此私有文件 |
| `llm.timeout_seconds` | 云端请求上限；建议 60-120 秒 |
| `llm.max_retries` | 单次模型请求的额外重试次数，范围 0-3，默认 2 |
| `llm.retry_backoff_seconds` | 首次重试前等待秒数，范围 0-10，后续指数翻倍，默认 1 |

该文件被 Git 忽略。不要把 API Key 放入 Android 工程、APK、截图、日志或提交历史。

## 2. Termux Agent

Termux 保留，因为它承载项目的 Python 决策层；不再安装或运行 llama.cpp、GGUF 模型或本地模型服务。

```bash
pkg update
pkg install python git rust
git clone https://gitee.com/violetylove/tars-assistant.git
cd tars-assistant
python -m venv .venv
. .venv/bin/activate
PIP_NO_BUILD_ISOLATION=1 pip install -r requirements.txt
cp config/cloud.yaml.example config/cloud.yaml
python -m agent.server
```

Agent 日志默认同时输出到终端和当前目录的 `tars-agent.log`。在 Termux 后台读取：

```sh
run-as com.termux cat files/home/tars-assistant/tars-agent.log
```

也可通过 `--log-file <path>` 指定日志文件路径。

Agent 只监听设备 loopback。启动后 Android App 的请求经 Agent 转发到云端模型，模型输出仍必须通过
Python schema 校验与 Android 动作白名单、敏感操作确认。

Agent 只对连接/超时、HTTP `429` 和 HTTP `5xx` 重试；认证、配额以外的 `4xx` 和无效响应立即失败。
请求失败信息不会包含 API Key。重试只发生在模型决策请求尚未返回时，Android 仍只会执行最终通过
schema 校验的单个响应。

不配置云端 Key 时可执行协议联调：

```bash
. .venv/bin/activate
python -m agent.server --mock
python scripts/smoke_agent.py
```

### 一键部署脚本（推荐）

手动步骤可整体交给自带脚本 `scripts/deploy_agent.sh`，自动完成环境检查、venv、依赖安装、配置校验与启动：

```bash
chmod +x scripts/deploy_agent.sh
./scripts/deploy_agent.sh              # 前台启动（Ctrl+C 停止）
./scripts/deploy_agent.sh --background # 后台（nohup，PID 写入 .agent.pid + /health 探活）
./scripts/deploy_agent.sh --mock       # 协议联调（无需云端 Key）
./scripts/deploy_agent.sh --port 8081  # 指定服务监听端口（默认 8080）
./scripts/deploy_agent.sh --stop       # 停止后台服务
./scripts/deploy_agent.sh --help
```

脚本行为：检测 `python3`（缺失提示 `pkg install python`）、创建/复用 `.venv`、`pip install -r requirements.txt`、校验 `config/cloud.yaml`（缺失从示例复制并提醒填 Key；`api_key` 仍为占位符时警告）、检查 `:8080` 是否被占用，再启动 `agent.server`。`--background` 用 nohup 后台运行并写 `.agent.pid`，启动后自检 `GET /health`；`--stop` 按 PID 停止。输出为 `[INFO]/[ OK ]/[WARN]/[ERR ]` 颜色分层，非 TTY 自动退化为纯文本。

## 3. Android

```powershell
.venv\Scripts\python.exe -m pytest agent bridge -q
cd android
gradle :app:assembleDebug --console=plain
```

Android 的 `settings.gradle.kts` 已声明阿里云 Maven 镜像并保留 Google、Maven Central 与 Gradle Plugin
Portal 回退，无需配置代理即可首次下载公开构建依赖。若所在网络仍无法访问这些仓库，再为 Gradle 配置代理；
不要把代理凭据提交到仓库。

安装 Debug APK 后，在系统设置中显式开启 TARS 无障碍服务；根据功能需要授予通知访问、麦克风和
Shizuku 权限。Android App 默认使用本机 loopback Agent；若改用远程主机，任务文本与压缩 UI 摘要将发送至该 Agent，应仅使用受信任网络。

### AVD 自动授权与 Shizuku

受控 AVD 可由开发者使用 ADB 完成授权，无需用户在设置页操作：

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell settings put secure enabled_accessibility_services org.atovio.tars/org.atovio.tars.TarsAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
```

Shizuku 仍需先由管理器启动，再在 TARS 中授权；启动后应在 TARS 主界面看到“Shizuku 已授权”。
这组 ADB 授权步骤仅适用于开发 AVD，不作为实体机部署建议。

### Agent 审计日志

Agent 正常启动时会以 INFO 记录请求/响应元数据：会话 ID、前台包名、UI 节点数、history 轮数、动作类型、
`done` 和 `need_observation`。不会记录任务正文、原始 UI、输入文本或 API Key。日志用于定位跨应用多轮
动作是否由模型生成、是否送达 Android；Android 执行细节通过 `TarsAction` 和 `TarsShizuku` 日志查看。

## 4. 清理旧本地模型

本地模型方案已废弃：从 Termux 中删除旧模型目录、`llama-server` 二进制和诊断日志即可；**不要删除**
`~/tars-assistant` 或 `.venv`，它们仍用于运行 Python Agent（概要见 `docs/AVD_TESTING.md` 环境基线）。

## 5. 验收

1. 启动 Agent 后，`GET http://127.0.0.1:<port>/health` 返回 `status=ok`（默认端口为 8080）。服务默认监听 `0.0.0.0`；远程设备须填写该服务所在主机的实际 IP 或域名，不能填写 `0.0.0.0`。
2. Android 发送固定技能，确认本机 loopback 与执行白名单正常。
3. Android 发送非敏感任务，确认 Agent 调云端模型并返回通过 schema 的结果。
4. 发送、删除、支付等敏感节点仍必须出现 Android 二次确认。
