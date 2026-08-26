# 部署与联调

> 本文覆盖当前 Termux Agent 与 Android App 的部署、诊断和发布。技术边界以
> `docs/DESIGN.md` 为准。

## 1. 准备私有云端配置

在运行 Agent 的 Termux 或 Linux 主机中：

```bash
cd ~/tars-assistant
mkdir -p config
cp config/cloud.yaml.example config/cloud.yaml
```

填写以下私有字段：

| 字段 | 内容 |
|---|---|
| `llm.base_url` | OpenAI-compatible HTTPS 地址，通常以 `/v1` 结尾 |
| `llm.model` | 服务商模型 ID |
| `llm.api_key` | API Key，仅保留在该文件 |
| `llm.timeout_seconds` | 单次云端请求上限，建议 60-120 秒 |
| `llm.max_retries` | 额外重试次数，0-3 |
| `llm.retry_backoff_seconds` | 首次重试等待秒数，0-10，后续指数退避 |

`config/cloud.yaml` 被 Git 忽略。不要把密钥放入 Android 工程、APK、截图、日志或提交历史。

## 2. 部署 Agent

Termux 需安装 `python` 和 `git`；首次部署还建议安装 `rust` 以应对依赖构建：

```bash
pkg update
pkg install python git rust
git clone https://github.com/violetylove/tars-assistant.git
cd tars-assistant
./scripts/deploy_agent.sh
```

部署脚本会创建或复用 `.venv`、安装依赖、检查私有配置并启动服务。

```bash
./scripts/deploy_agent.sh              # 前台运行，Ctrl+C 停止
./scripts/deploy_agent.sh --background # 后台运行，PID 写入 .agent.pid
./scripts/deploy_agent.sh --mock       # 无需云端 Key 的协议联调
./scripts/deploy_agent.sh --port 8081  # 修改监听端口
./scripts/deploy_agent.sh --stop       # 停止后台服务
```

脚本默认在 `0.0.0.0:8080` 监听。需要指定监听地址时，直接运行：

```bash
. .venv/bin/activate
python -m agent.server --host 127.0.0.1 --port 8080
```

Android 默认连接同机 `127.0.0.1:8080`；远程 Agent 请在设置页填写真实 IP 或域名及端口，绝不能填
`0.0.0.0`。远程 HTTP 传输任务文本和压缩 UI 摘要，只应部署在受信任网络。

## 3. 构建与安装 Android App

在仓库根目录：

```powershell
.\.venv\Scripts\python.exe -m pytest agent bridge -q
cd android
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。安装后，在系统设置中按需启用：

- TARS 无障碍服务；
- 通知访问；
- 麦克风权限和悬浮语音；
- Shizuku 服务及其对 TARS 的授权。

受控 AVD 可使用以下命令预置无障碍授权，真机应由用户在系统 UI 授权：

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell settings put secure enabled_accessibility_services org.atovio.tars/org.atovio.tars.TarsAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
```

## 4. 日志与诊断

| 位置 | 内容 | 使用方式 |
|---|---|---|
| `log/agent/agent.log` | Agent 请求、摘要元数据、响应和错误 | 在 Agent 主机读取；不记录原始 XML |
| Android `files/log/android.log` | 原始 XML、Agent 响应、执行动作及实际节点 | 设置页点击“发送 Android 日志” |
| `log/android/` | 用户上传后的 Android 诊断日志 | 在 Agent 项目目录查看 |

一个任务使用相同的 16 位十六进制 session ID 串联 Android 与 Agent 日志。上传接口为
`POST /logs/android`，最大文件大小为 10 MiB。日志目录均已被 Git 忽略。

## 5. 联调检查

1. 访问 `GET http://127.0.0.1:<port>/health`，应返回 `status=ok`。
2. 在 App 中检查无障碍与 Agent 状态；未就绪时任务应被阻止并显示对应设置入口。
3. 使用已勾选且已安装的应用验证 `launch`；目录外包名必须被拒绝。
4. 用非敏感任务验证多轮采集与输入；用敏感任务验证前台确认浮层的取消和确认路径。
5. 动作后确认 App 仅在稳定、有效的新 UI 树出现后才进入下一轮；失败应安全停止。

## 6. GitHub 发布

推送 `v*` tag 会触发 GitHub Actions 构建 Debug APK、上传构建产物并创建 GitHub Release。Release
附件命名为 `TARS-Assistant-<tag>.apk`。工作流使用 Node.js 24 和 `actions/setup-java@v5`。

```bash
git tag -a vX.Y.Z -m "发布说明"
git push github main
git push github vX.Y.Z
```

发布前应完成 Python 测试；涉及 Android 行为时还应构建 APK 并做相应的 AVD 或真机验证。
