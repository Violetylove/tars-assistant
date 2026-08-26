# AVD 联调记录

> 本文记录受控 AVD 上已验证的基线和复测方法，不以日志片段替代验收。架构与协议以
> `docs/DESIGN.md` 为准；部署操作见 `docs/DEPLOY.md`。

## 环境基线

- AVD：`TARS_MODEL_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5。
- APK：`org.atovio.tars` Debug 构建。无障碍、通知访问、麦克风和 Shizuku 均由用户在系统 UI
  授权；仅受控 AVD 可用 ADB 预置无障碍授权。
- Agent：Termux 中的裸 Python venv，项目目录为 `~/tars-assistant`。不部署 GGUF、llama.cpp
  或本地模型服务。
- 通信：App 默认请求 `http://127.0.0.1:8080`；可改为受信任远程 Agent。Agent 默认监听
  `0.0.0.0:8080`，云端模型请求使用 HTTPS。

## 已验证基线

- 对话主界面区分用户意图与 Agent 前台/执行日志；输入框随 IME 上移，任务可终止、清除记录，
  已发意图可复制或重新发送。
- 设置页汇集权限入口、运行参数、诊断日志上传和应用启动授权；应用列表可刷新，保存前会剔除
  已卸载应用。
- 无障碍采集覆盖多个应用窗口层，并将窗口层级、完整矩形和交互状态交给 Agent 摘要器。
- `launch` 只允许用户勾选且仍已安装的应用；固定技能和模型动作均经过同一目录校验。
- `click`、`type`、`swipe`、全局动作和 `launch` 通过 schema、执行白名单和失败收敛处理；
  `type` 无障碍失败时仅允许参数受限的 Shizuku 回退。
- 发送、删除、支付等敏感动作会在前台应用上方显示无障碍确认浮层；取消、拒绝或执行失败均停止
  当前轮后续动作。
- Agent 返回 `done` 时，App 会显示“任务完成”。

## 采集与观察加固

动作后进入下一轮前，执行侧必须取得稳定的有效 UI：

1. 采集优先读取 `getWindows()` 的应用窗口根；空骨架根不会作为有效树。
2. 多窗口根均为空或无内容时，回退 `rootInActiveWindow`；若其仍为空，再使用最近一次窗口级
   无障碍事件的有效 `event.source`。焦点、文本变化等局部事件不会覆盖该回退来源。
3. XML 必须包含当前前台包名，且树已包含可访问内容；仅包名变化、空 XML 或事件到达均不能单独
   判定刷新完成。
4. 新鲜 XML 要连续两次一致；新应用冷启动可在受限 `new_app_grace_ms` 内等待无障碍树就绪。

这项策略防止跨应用切换时把占位根、旧界面或过渡帧送入下一轮。占位根仅写入 Android 诊断日志；
App 在本地等待稳定有效树，超时则安全停止，不把空树交给模型。每轮会生成一个 `UiSnapshot`，
其中的 XML、前台上下文和可执行节点列表同时提供给 Agent 请求与动作执行，避免重新采集造成
节点 ID 漂移。

## 诊断与复测

- Android 私有诊断日志：`files/log/android.log`。同一任务使用 16 位十六进制 session ID，记录
  每轮原始 XML、Agent 响应、执行动作和实际解析节点。
- 若有效树缺失，日志还会记录 `getWindows()`、`rootInActiveWindow` 和最后一个关键无障碍事件的
  结构元数据；等待期间仅在来源状态变化时追加，供定位窗口根、事件时序或应用未暴露语义树的原因。
- 设置页的“发送 Android 日志”上传该文件至 Agent 的 `log/android/`；Agent 审计日志位于
  `log/agent/agent.log`，不保存原始 XML。
- 联调应至少覆盖：服务未就绪提示、固定技能 `launch`、非敏感多轮动作、敏感确认取消/确认、
  任务终止、应用卸载后的授权目录清理，以及动作后的稳定 UI 等待。

## AVD 自动授权（仅开发）

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell settings put secure enabled_accessibility_services org.atovio.tars/org.atovio.tars.TarsAccessibilityService
& $adb shell settings put secure accessibility_enabled 1
```

Shizuku 仍须在其管理器中启动并授权。这组命令仅适用于开发 AVD，不能作为真机部署方案。
