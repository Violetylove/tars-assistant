# 部署与联调

## 1. 前提

- Android 执行侧与 Agent 必须运行在**同一台 Android 设备**。
- Agent 仅监听 `127.0.0.1:8080`；不要用 `adb reverse`、局域网 IP 或公网端点替代。
- 当前低配 AVD（2 GB RAM）仅用于权限、通知和 HTTP 协议联调。真实 3B 模型应在至少 6 GB RAM
  的高配 AVD 或目标真机验证。

## 2. Windows 开发校验

```powershell
.venv\Scripts\python.exe -m pytest agent bridge -q
cd android
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
gradle :app:assembleDebug --console=plain
```

启动不依赖模型的协议服务：

```powershell
.venv\Scripts\python.exe -m agent.server --mock
.venv\Scripts\python.exe scripts\smoke_agent.py
```

`--mock` 只用于联调：它固定返回合法的无动作完成响应“协议联调完成（mock，未调用本地模型）”，
绝不代表本地模型已运行。

## 3. AVD 基线

1. 创建或选择 Google APIs x86_64 AVD，安装 `android/app/build/outputs/apk/debug/app-debug.apk`。
2. 在系统 UI 中授权 TARS 无障碍服务、通知访问和麦克风权限。
3. 从 Termux 官方 GitHub Release 安装与 AVD 架构匹配的 APK；发布文件须与官方 SHA-256 清单一致。

### Shizuku 运行时来源

- Shizuku 管理器统一使用 [`thedjchi/Shizuku`](https://github.com/thedjchi/Shizuku) 的 GitHub
  Release；这是用户指定的维护分支，**不使用** `RikkaApps/Shizuku` 的管理器发布包。
- 安装时固定 Release 标签，记录 APK 的 SHA-256、包名和 versionName；当前 AVD 基线为
  `v13.7.0-thedjchi`，包名 `moe.shizuku.privileged.api`，SHA-256
  `6EA6DEE65D5DDC626B6B75B2C2F67F8CC547FA47D7B437E6892639C37EAFFE43`。
- Android App 继续使用 `dev.rikka.shizuku` 的 API/AIDL 依赖与 Shizuku 服务通信；管理器发行来源
  的变更不放宽 App 的动作白名单、参数校验或用户授权边界。
- AVD 没有已关联 Wi-Fi 时，Android 的无线调试配对不可用；使用 Shizuku 管理器“View command”
  给出的**当前已安装包**启动命令，经已连接的 `adb shell` 执行。该命令的 APK 安装路径会变化，
  不可写死到脚本或文档。设备重启后需重新启动 Shizuku。
4. 在 Termux 中安装 Python 并复制项目源代码。低配 AVD 不下载 GGUF 模型。

Termux 内的最小命令（高配 AVD 或真机）：

```bash
pkg update
pkg install python git
git clone https://gitee.com/violetylove/tars-assistant.git
cd tars-assistant
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m agent.server --mock
python scripts/smoke_agent.py
```

成功后，TARS Android App 的“发送给 TARS”应可访问同设备的 `127.0.0.1:8080`。先用 mock 模式
验证 HTTP 及协议；再停止 mock 服务后进入下一节的真实模型模式。

## 4. 真实模型模式

1. 使用 `scripts/download_model.sh 3b` 下载默认 3B GGUF。
2. 安装或构建与设备架构匹配的 `llama-server`，并确保它在 Termux 的 `PATH` 中。
3. 启动 Agent，首次请求会按需拉起 llama-server：

```bash
. .venv/bin/activate
python -m agent.server --llm-base-url http://127.0.0.1:11434/v1 --model qwen2.5:3b
```

真实模型响应仍必须经过 JSON schema 校验；Android 端仍执行动作白名单和敏感操作确认。模型服务
未就绪时请求应安全失败，而不是降级为未标记的自动执行。

## 5. 验收顺序

1. `GET /health` 返回 `status=ok`。
2. `scripts/smoke_agent.py` 在 mock 模式返回合法 `agent_response`。
3. Android App 手动任务访问本机 Agent，确认没有 cleartext 或 endpoint 校验错误。
4. 启动真实 llama-server 后复测一个非敏感动作。
5. 涉及发送、删除、支付等 UI 节点时，确认 Android 二次弹窗仍出现。
