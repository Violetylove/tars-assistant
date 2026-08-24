# AVD 联调记录

> 记录在受控 AVD（`TARS_MODEL_API_35`）上的联调与验收结论：验证"能做什么、当前基线可否复现"。
> 不再逐条堆叠带日期的过程稿；逐轮决策/日志细节以 `tars-agent.log` 与 `DIAG* ` 日志为准。

## 环境基线

- AVD：`TARS_MODEL_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5；6 vCPU、6 GB RAM、16 GB 数据盘。
- Android App：`org.atovio.tars` Debug APK。通知访问、麦克风、Shizuku 授权按需由用户在系统 UI 确认；受控 AVD 可用 ADB 自动授权（见下）。
- Termux：官方 x86_64；保留 `~/tars-assistant` 与 `.venv` 运行自研 Python Agent；**不再安装/运行 llama.cpp、GGUF、本地模型服务**（本地模型方案已废弃）。
- 通信：Android App 仅访问本机 `http://127.0.0.1:8080`；Termux Agent 经 HTTPS 访问云端 OpenAI-compatible 模型 API。
- 私有云端配置（`config/cloud.yaml`）仅存 Termux 私有目录，不进入 Git/APK/日志；API Key 不得泄露。

## 已验收能力

- **Android 执行侧**：APK 安装、无障碍 UI 树采集（`getWindows` 多图层 + 跨图层遮挡剔除）、loopback HTTP、定时提醒、通知监听、悬浮语音、Shizuku 受限 swipe/type 回退。
- **Agent 决策侧**：FastAPI 服务、UI 摘要器（可交互节点含层/完整矩形/交互状态）、自研安全循环 + schema 校验、固定技能路由、云端接入与有界重试。
- **协议与安全**：`task_request`/`agent_response`/`action` 校验、动作白名单、敏感操作二次确认、失败收敛、观察新鲜度、history/动作数量边界。
- **真实模型关键链路**：TARS→桌面→目标应用→输入→`done` 的完整多轮；敏感点击确认弹窗取消后安全停止；`launch` 白名单仅启动系统设置/Gmail 等。
- **触发持久化**：定时/通知任务写入本地，强制停止重启后仍可"载入待处理任务"恢复。

## 最近修复要点

- **多图层采集 + 跨图层遮挡剔除**：桌面图标错位、Gmail 建议卡不可见等多起 UI 感知问题的根因；当前只跨图层互剔（同层不互剔、跳过全屏容器）。
- **可见性规划**：SYSTEM_PROMPT 要求先图层（z 轴）再坐标，并写入"父不可见则子不可见"；节点行并入窗口图层（z 轴）与区域（`to_window_layers`）+ 完整矩形 + clickable/focusable/focused（`to_llm_line`）。
- **动作后采集加固**（修复"启动应用后采集断、无下一轮"）：`awaitFresh` 对新应用冷启动空树加 `NEW_APP_GRACE_MS` 宽限、`OBSERVATION_TIMEOUT_MS` 放宽、前台包名变化不算"无变化"。
- **空采集提示**：当前节点采集为空时，向模型提示"采集为空，请返回 wait(ms) 重新采集"，避免沿用上一轮节点。

## AVD 自动授权（仅开发）

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell settings put secure enabled_accessibility_services org.atovio.tars/org.atovio.tars.TarsAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
```

Shizuku 仍需先由管理器启动，再在 TARS 中授权。上述 ADB 授权仅用于开发 AVD，不构成实体机部署建议。
