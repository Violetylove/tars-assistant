# TARS Android 执行侧

Android 原生模块负责对话入口、设置、无障碍采集、受限动作执行和用户确认；不承担模型推理或云端
凭据保存。

## 能力边界

- `MainActivity` 提供对话式任务界面、状态提示、复制/重发、清除记录和任务终止。输入框通过 Window
  Insets 随软键盘上移。
- `TarsAccessibilityService` 采集多应用窗口层的 UI XML；空骨架根会回退到
  `rootInActiveWindow`，动作后只有稳定且与前台包名匹配的有效 UI 才能进入下一轮。
- `ActionExecutor` 仅执行经过校验的 `click/type/swipe/back/home/launch/wait/reply/done`；失败、
  拒绝或取消后立即停止当前轮。
- `launch` 仅允许设置页中用户勾选且当前仍安装的 launcher 应用，Agent 与 Android 双端校验。
- `type` 优先使用无障碍 API；失败时仅以 Shizuku UserService 执行受限 `input text` 回退，滑动同理。
- 发送、删除、支付等敏感动作通过 `TYPE_ACCESSIBILITY_OVERLAY` 在当前前台应用上显示确认浮层。
- 通知、定时、悬浮语音只创建待处理任务，用户须载入、检查并发送。

## 设置与日志

设置页包含系统授权入口、运行参数、独立应用列表和“发送 Android 日志”。应用列表可刷新，保存时会
复查已卸载应用。Android 私有诊断日志写入 `files/log/android.log`，包含短 session ID、原始 XML、
Agent 响应、动作与实际节点；用户主动上传后，Agent 保存到 `log/android/`。

## 构建与安装

使用 Android Studio 打开本目录，或执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。安装后需在系统无障碍设置中启用
“TARS Assistant”，并先启动 Agent 服务。通知访问、麦克风和 Shizuku 授权均应在系统界面显式完成。

`AgentClient` 默认请求 `http://127.0.0.1:8080`。设置页接受有效 IPv4、IPv6 或域名和端口；
loopback 请求不使用系统代理，远程请求走设备网络。远程 HTTP Agent 只应部署在受信任网络中。
