# TARS Assistant

原生 Android App + Termux Python Agent 的 AI 手机助手。Android 负责感知与受限动作执行；项目自研
Python Agent 负责安全决策；云端仅提供 OpenAI-compatible 大模型推理。

## 架构

**原生 App -> 本机 Termux Agent -> HTTPS 云端模型 -> Agent 安全校验 -> 原生 App 执行**

Android 与 Agent 仍只通过 `http://127.0.0.1:8080` 通信。模型 API Key 仅保存在 Termux 的私有
配置中，绝不放入 Android APK、Git 或云端模型请求以外的地方。

## 云端配置

复制 `config/cloud.yaml.example` 为
`config/cloud.yaml`，填写云端模型服务商提供的 `base_url`、`model` 与 `api_key`。私有配置已被 Git
忽略。

```bash
cd ~/tars-assistant
cp config/cloud.yaml.example config/cloud.yaml
# 编辑 config/cloud.yaml 后
. .venv/bin/activate
python -m agent.server
```

`--mock` 仅用于协议联调：`python -m agent.server --mock`。

## 状态

阶段 0-4 已完成，阶段 5 继续完成云端模型的真实动作闭环验收。原生侧保留 UI 树采集、动作白名单、
敏感操作确认、通知/定时/语音触发；本地 llama.cpp、GGUF 模型及其生命周期不再属于部署方案。

## 文档

- `docs/DESIGN.md` - 技术契约与安全边界
- `docs/PROJECT_PLAN.md` - 计划与进度
- `docs/DEPLOY.md` - 部署步骤

## 许可证

Apache License 2.0，版权所有者 Violetylove。详见 `LICENSE` 与 `NOTICE`。
