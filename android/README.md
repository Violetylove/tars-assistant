# TARS Android 执行侧

这是阶段 4 的原生 Kotlin 执行侧最小工程：

- `AgentClient` 使用 `HttpURLConnection` 调用默认的 `127.0.0.1:8080` 或设置页配置的 Agent 主机
- `TarsAccessibilityService` 采集当前无障碍树并序列化为 `ui_xml`
- 无障碍事件提供最近前台应用包名和窗口类名，随每轮 `task_request` 一并回传给 Agent
- 无障碍服务连接后会在主界面显示已连接状态
- `ActionExecutor` 执行 click/type/back/home/wait；未知动作默认拒绝
- 任一动作被拒绝、取消或失败时，执行侧立即停止该轮，不再下发后续动作或观察请求
- 任务的最终执行结果或失败信息会保留在主界面，不会被后续无障碍连接广播覆盖
- 需要下一轮观察时，执行侧轮询直到动作后 UI XML 与原快照不同；2 秒内无更新则安全停止，避免传回陈旧或过渡 UI
- `launch` 仅可启动系统设置、TARS Assistant、Gmail 或微信；包名在 Agent 固定路由和 Android 执行侧均有白名单校验
- `ShizukuGateway` 用官方 UserService + AIDL 执行参数受限的 `input swipe`；当无障碍文本设置失败时，以 `input text` 回退，需用户显式授权
- `VoiceIntentCapture` 使用原生 `SpeechRecognizer`；按住说话只填入任务意图，用户仍须检查并发送
- 悬浮语音由已授权的无障碍服务创建 `TYPE_ACCESSIBILITY_OVERLAY` 按钮；按住识别的最终结果仅写入待处理任务，用户须在 App 中载入、检查并发送
- `MainActivity` 提供手动任务入口，并按 `need_observation` 最多推进 12 轮

使用 Android Studio 打开本目录并同步 Gradle，或在此目录运行
`gradlew.bat :app:assembleDebug`。Debug APK 输出到
`app/build/outputs/apk/debug/app-debug.apk`。安装后需要在系统无障碍设置中启用
“TARS Assistant”，并先启动 Termux 中的 Agent 服务。需要滑动时，在 App 内点击“授权
Shizuku”并在 Shizuku 弹窗确认；服务不可用、未授权或参数非法时，动作保持 fail-closed。
可使用“打开无障碍设置”进入系统设置并显式启用服务。
通知触发需要单独在“打开通知访问设置”中显式授权“TARS 通知触发”；收到通知后只会生成待
处理任务，仍须由用户载入、检查并发送。
启用悬浮语音前，须先启用无障碍服务并授予麦克风权限；它不需要广泛的“显示在其他应用上层”
权限。

Android 的 cleartext 默认策略会阻止 HTTP，因此 Manifest 明确启用了 cleartext。`AgentClient` 默认访问
`http://127.0.0.1:8080`，设置页可改为有效 IPv4、IPv6 或域名与端口。loopback 请求显式使用
`Proxy.NO_PROXY`，远程主机则使用设备网络路径；远程 HTTP Agent 应仅部署在受信任网络中。
