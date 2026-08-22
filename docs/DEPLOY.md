# 部署与联调

## 架构边界

- Android 原生 App：采集 UI、调用本机 Agent、执行已校验动作。
- Termux Python Agent：自研安全决策循环、UI 摘要、schema 校验和固定技能路由。
- 云端：只提供 OpenAI-compatible 大模型 API；不托管 TARS Agent，也不直接调用 Android。

Android 与 Agent 固定为同一设备上的 `http://127.0.0.1:8080`。Agent 调云端模型必须使用 HTTPS。

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

Agent 只监听设备 loopback。启动后 Android App 的请求经 Agent 转发到云端模型，模型输出仍必须通过
Python schema 校验与 Android 动作白名单、敏感操作确认。

不配置云端 Key 时可执行协议联调：

```bash
. .venv/bin/activate
python -m agent.server --mock
python scripts/smoke_agent.py
```

## 3. Android

```powershell
.venv\Scripts\python.exe -m pytest agent bridge -q
cd android
gradle :app:assembleDebug --console=plain
```

安装 Debug APK 后，在系统设置中显式开启 TARS 无障碍服务；根据功能需要授予通知访问、麦克风和
Shizuku 权限。Android App 只使用本机 loopback Agent，不能配置外部 URL。

## 4. 清理旧本地模型

切换后可从设备的 Termux 中删除旧模型、模型服务二进制和诊断日志；不要删除 `~/tars-assistant` 或
`.venv`，它们仍用于运行 Python Agent。具体命令见 `docs/AVD_TESTING.md` 的迁移记录。

## 5. 验收

1. 启动 Agent 后，`GET http://127.0.0.1:8080/health` 返回 `status=ok`。
2. Android 发送固定技能，确认本机 loopback 与执行白名单正常。
3. Android 发送非敏感任务，确认 Agent 调云端模型并返回通过 schema 的结果。
4. 发送、删除、支付等敏感节点仍必须出现 Android 二次确认。
