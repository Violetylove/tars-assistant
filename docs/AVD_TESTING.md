# AVD 联调记录

## 环境

- 日期：2026-08-19
- AVD：`TARS_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5
- APK：Debug，`com.tars.assistant`，version `0.1.0`

## 已验证

- `gradle :app:assembleDebug` 成功，APK 可由 ADB 安装并启动。
- 包管理器已识别 `TarsAccessibilityService`（`AccessibilityService`）和
  `NotificationTriggerService`（`NotificationListenerService`）的受保护服务声明。
- 主界面的意图输入、发送、15 分钟提醒、通知载入和 Shizuku 授权控件均存在于
  `uiautomator` UI 树，启动日志无崩溃。
- Android 明文流量策略已允许固定的本机 Agent endpoint。发送测试任务在模拟器中到达
  `127.0.0.1:8080` 并得到“连接失败”（模拟器没有 Termux Agent），不再出现
  `Cleartext HTTP traffic ... not permitted`。
- 15 分钟提醒显示“已安排 15 分钟后的待处理提醒”；`dumpsys alarm` 显示一次性的
  `com.tars.assistant.SCHEDULED_TASK` 广播。没有后台 Agent 请求或动作执行。
- 按住说话触发 Android 的 `RECORD_AUDIO` 运行时授权对话框（使用期间/仅此一次/拒绝）；
  未授权前不会创建语音识别请求。
- 通过系统无障碍设置中的 TARS Assistant 条目和“完整控制设备”警告确认后，`dumpsys
  accessibility` 显示服务处于 Bound/Enabled 状态；返回主界面后显示“无障碍服务已连接”。
- 新增的通知访问入口可打开 Android 15 的“通知读取、回复和控制”系统授权页；该页在
  “Not allowed”列表中显示 `TARS Assistant`，证明受保护服务声明和设置跳转均可用。
- 用户在系统 UI 显式启用通知访问后，`dumpsys notification` 确认
  `NotificationTriggerService` 已启用并处于 live 状态。通过 `cmd notification post` 发布的测试通知被
  捕获；点击“载入最新通知”后，任务意图预填为“处理来自 `com.android.shell` 的通知：TARS：测试”，
  状态提示要求用户检查后发送，未自动调用 Agent 或执行动作。
- 麦克风权限授权后，主界面可切换为“停用悬浮语音”，且 `dumpsys window` 可见
  TARS 的 `ty=ACCESSIBILITY_OVERLAY` 窗口（`appop=CREATE_ACCESSIBILITY_OVERLAY`）。
  重装 APK 或无障碍服务重连期间系统会回收旧窗口，需重新启用；跨服务重启的持久化行为仍待
  后续观察。
- 已从 Termux 官方 GitHub Release 安装 `v0.118.3` 的 x86_64 APK；下载文件 SHA-256 与
  官方发布清单一致。Termux 可首次启动，但 Python 包安装受当前 AVD 所选镜像源速度限制而未完成；
  本低配 AVD 仅继续用于 Android 功能联调，不作为本地模型推理性能验收环境。

## 待验证

- 无障碍服务已在 AVD 的系统 UI 中启用；待进一步验证跨应用树采集与动作执行。通知监听仍待
- 待结合真实应用通知继续观察不同 payload 的兼容性；通知访问已由用户在系统 UI 中显式启用并完成
  测试通知捕获。Android 15 阻止 ADB shell 直接打开单项无障碍详情页，也未
  接受直接 secure-setting 写入；App 提供“打开无障碍设置”入口，须在系统 UI 中显式授权。
- 在 AVD 启动 Shizuku 后，验证用户授权与受限 swipe UserService。
- 在 Android 设备的 Termux 中运行 Agent 服务后，验证真实 `127.0.0.1:8080` 端到端流程。

## 2026-08-19 追加验收

- 最新 Debug APK 已重新安装到 `TARS_API_35`，并通过 `adb reverse tcp:8080 tcp:8080`
  连接主机 mock Agent；Python/bridge 单测 29 项通过。
- 联调发现并修复英文固定意图 `open settings` 路由别名，以及 Android `launch` 动作
  序列化遗漏 `package_name` 的问题；修复后的 APK 待重新安装后验证实际跳转。
- AVD 当前仍有 Termux `apt` 进程运行；Python 安装未完成，不将其记录为已部署。
- 本轮离线重建未完成：用户全局 Gradle 缓存的 native-platform 锁文件被占用；切换到
  项目专用缓存后，代理关闭使 Android Gradle Plugin `8.5.2` 无法解析。该限制不影响
  Python/bridge 的 30 项离线测试；待依赖缓存可用时重新构建并安装 APK 复验。
- 代理开启后使用用户现有 Gradle 缓存重新构建成功，最新 APK 已安装到 AVD。通过
  `adb reverse` 和 mock Agent 验证 `open settings` 返回固定 `launch` 响应；重装后无障碍
  服务需在 Android 系统 UI 中再次显式授权，因此本轮执行结果为“无障碍服务未连接”，未
  进行实际跳转。重新授权后即可复测动作执行与跨应用 UI 树采集。
- 修复 Agent 对缺失或空 `ui_xml` 的处理：无障碍服务未连接时请求不再因 XML 空串返回
  `502`，而是进入空节点安全决策；非空非法 XML 仍保持失败关闭。
- 用户重新启用无障碍服务后完成跨应用验收：服务处于 `Bound/Enabled`，通过 mock Agent
  发送 `open settings`，Android 执行侧实际启动 `com.android.settings/.Settings` 并将其
  置于前台。设置页 UI 树包含 `Settings`、`Search settings`、`Notifications` 等外部应用
  节点，证明当前采集不是仅限 TARS 自身。测试过程中不再使用 `force-stop`，避免系统回收
  无障碍绑定；重启 App 后须等待服务重新绑定。
- Shizuku 安装基线：从用户指定的 `thedjchi/Shizuku` GitHub Release 安装
  `v13.7.0-thedjchi`（`moe.shizuku.privileged.api`，versionCode `1361`）；APK SHA-256 为
  `6EA6DEE65D5DDC626B6B75B2C2F67F8CC547FA47D7B437E6892639C37EAFFE43`。管理器显示
  “Shizuku is not running”，无线调试 `Pairing`/`Start` 和 TARS UserService 授权仍待用户在系统 UI
  中完成。
- Shizuku AVD 启动基线：模拟器 Wi-Fi 已启用但未关联网络，无线调试无法配对；改用管理器
  “View command”提供的当前安装包 ADB 启动命令，`shizuku_server` 以 `shell` 身份运行，管理器
  显示 “Shizuku is running / Version 13.6, adb”。TARS 已发出 UserService 授权请求，等待用户在
  Shizuku UI 中确认后再验收受限 `swipe`。

## 2026-08-20 Shizuku 授权验收

- AVD 上未出现系统弹窗，但 Shizuku 管理器的 `Application management` 已列出
  `TARS Assistant (com.tars.assistant)`；手动打开该条目的开关后，管理器显示授权开关为开启。
- 原授权入口由无障碍 Service 发起，且将“请求已提交”误报为布尔失败。已改为由前台 `MainActivity`
  发起请求，并区分 `GRANTED`、`REQUESTED`、`RATIONALE_REQUIRED` 与 `UNAVAILABLE` 状态；后续授权
  不依赖弹窗是否自动出现。
- 受限 `swipe` UserService 的实际动作验收仍待重新安装本次 APK 后执行。当前 Android 构建受本机
  Gradle 缓存/初始化脚本环境阻断，未将旧 APK 误记为包含本次修改。

## 2026-08-21 Shizuku `swipe` 验收

- 恢复 Gradle 构建链路：Scoop 配置的 `GRADLE_USER_HOME` 注入了与项目仓库策略冲突的初始化脚本；
  本轮改用标准 `C:\Users\Winter\.gradle` 缓存并经本机代理补齐插件元数据，`assembleDebug` 成功。
- 发现并修复 UserService 无法绑定的根因：Manifest 缺少官方
  `rikka.shizuku.ShizukuProvider` 声明，Shizuku server 日志此前显示
  `provider is null com.tars.assistant.shizuku`。
- 最新 Debug APK 安装后，Shizuku 管理器显示 `Authorized 1 application`，无障碍服务仍为
  `Bound/Enabled`。经 ADB reverse 连接到一次性 mock Agent 返回合法坐标和时长的 `swipe` 动作，
  TARS 界面显示“已执行: swipe”，且 logcat 显示 `Shizuku UserService connected`。
