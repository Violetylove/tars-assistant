# TARS Assistant

基于原生 Android App、Termux Python Agent 与云端 OpenAI-compatible 模型的 AI 手机助手。Android
采集无障碍 UI 并执行受限动作；Python Agent 负责 UI 摘要、安全决策与 schema 校验；云端只提供
推理，不直接访问设备。

启用云端模式会发送用户任务文本及压缩 UI 摘要。请仅在接受目标服务商数据处理边界时使用。

## 架构

```text
Android App -> HTTP Agent -> HTTPS 云端模型
     ^            |
     +-- 已校验的受限动作 --+
```

Agent 默认监听 `0.0.0.0:8080`。Android 默认连接同机 `http://127.0.0.1:8080`，也可在设置页配置
受信任远程 Agent 的实际 IP、域名及端口。云端 API Key 只保存在 Agent 主机的私有
`config/cloud.yaml`，不进入 Android APK 或 Git。

## 快速开始

```bash
cd ~/tars-assistant
cp config/cloud.yaml.example config/cloud.yaml
# 填写 base_url、model、api_key
./scripts/deploy_agent.sh
```

Android 开发构建：

```powershell
.\.venv\Scripts\python.exe -m pytest agent bridge -q
cd android
.\gradlew.bat :app:assembleDebug
```

安装 Debug APK 后，在系统中按需启用 TARS 无障碍服务、通知访问、麦克风与 Shizuku。设置页可配置
运行参数、允许启动的应用和 Android 诊断日志上传。

## 安全与诊断

- 所有模型动作先经 schema 和 Android 白名单校验。
- `launch` 只能启动用户在设置页勾选且仍已安装的应用。
- 发送、删除、支付等敏感动作会在前台应用上显示二次确认浮层。
- Android 诊断日志含原始 UI XML、动作和节点解析，仅在用户主动上传时发送；Agent 审计日志不保存
  原始 XML。

## 文档

- [技术契约](docs/DESIGN.md)
- [部署与联调](docs/DEPLOY.md)
- [运行时配置](docs/RUNTIME_CONFIG.md)
- [AVD 验收基线](docs/AVD_TESTING.md)
- [代码导航](docs/REPO_MAP.md)
- [项目计划](docs/PROJECT_PLAN.md)

## 许可证

Apache License 2.0，版权所有者 Violetylove。详见 `LICENSE` 与 `NOTICE`。
