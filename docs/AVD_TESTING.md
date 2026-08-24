# AVD 联调记录

## 当前基线

- AVD：`TARS_MODEL_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5，6 vCPU、6 GB RAM、16 GB 数据盘。
- Android App：`org.atovio.tars` Debug APK；无障碍、通知访问、麦克风和 Shizuku 授权按需由用户在系统 UI 中确认。
- Android 命名空间迁移已完成；迁移前的 `com.tars.assistant` 仅保留为历史验收事实，旧包已从 AVD 卸载。
  当前 `org.atovio.tars` Debug APK 已安装，并已重新获得无障碍与 Shizuku 授权。
- Termux：官方 `v0.118.3` x86_64；保留 `~/tars-assistant` 与 `.venv`，用于运行自研 Python Agent。
- 通信：Android App 仅访问本机 `http://127.0.0.1:8080`；Termux Agent 通过 HTTPS 访问云端 OpenAI-compatible 模型 API。

## 云端模型迁移（2026-08-21）

- 手机本地模型方案已废弃：不再安装或运行 llama.cpp、llama-server、GGUF 模型或本地模型生命周期服务。
- 已停止旧 Agent 并删除 Termux 中的 `llama-server` 二进制、`~/tars-assistant/models/` 与
  `~/tars-assistant/.runtime/`；项目目录核验为 86 MB。
- `command -v llama-server` 与 `pkg list-installed llama.cpp` 均无结果；Termux、项目源码与 `.venv` 保留。

## 当前验收步骤

1. [x] 在 Termux 更新项目并将私有 `config/cloud.yaml` 仅复制到该设备的项目目录。
2. [x] 启动 `. .venv/bin/activate && python -m agent.server`；`/health` 返回 `status=ok`。
3. [x] 重建并覆盖安装当前 Debug APK，启用并绑定 TARS 无障碍服务。
4. [x] 发送无副作用任务；Android -> Termux loopback -> HTTPS 云端模型 -> schema -> Android 完成，App 会话中的请求均返回 HTTP 200。
5. [x] 发送限定的低风险动作任务；真实模型仅点击“15 分钟后提醒”，Android 申请并获得通知权限，`dumpsys alarm` 确认已登记 `com.tars.assistant.SCHEDULED_TASK`。
6. [x] 在真实模型输出点击发送类节点时，确认 Android 二次确认仍出现；测试中取消确认，未执行敏感点击。
7. [x] 以受限定时广播写入待处理任务后，强制停止并重启 App；用户手动“载入待处理任务”仍可恢复文本，Agent POST 计数保持不变。
8. [x] 使用项目声明的阿里云 Maven 镜像和官方回退完成全新 Debug 构建，覆盖安装当前 APK；主界面已验证“载入待处理任务”文案。
9. [x] 输入 `open settings` 并发送；Termux Agent 返回 HTTP 200，Android 仅经固定启动包名白名单将系统设置置于前台。
10. [x] 同步 Agent 单动作 `reply` 收敛修复并重启 Termux 服务；真实云端无副作用请求返回 `done=true`、答复文本、空 `actions` 与 `need_observation=false`。
11. [x] 同步云端重试实现并重启 Termux 服务；既有私有配置未增加字段时采用受限默认值，真实云端无副作用请求仍返回终态文本且无动作。
12. [x] 重建并覆盖安装含前台上下文采集的 Debug APK；无障碍服务已绑定，且系统确认其订阅 `TYPE_WINDOW_STATE_CHANGED` 与 `TYPE_WINDOW_CONTENT_CHANGED`。
13. [x] 同步前台上下文的 Agent 传递链路；真实云端无副作用请求根据 `app=com.android.settings` 仅回显该包名，返回终态空动作。
14. [x] 重建并覆盖安装执行失败收敛修复版 Debug APK；模型定位 TARS 自身的敏感“发送给 TARS”按钮，确认弹窗取消后状态为“已取消: click”，Agent 仅记录一次请求且未进入下一轮观察。
15. [x] 临时两轮探针首轮启动系统设置；第二轮使用同一 `session_id` 回传 `com.android.settings` 前台上下文、系统设置 UI XML 与首轮 `launch` history，随后已恢复真实云端 Agent。
16. [x] 在绑定的无障碍服务下采集系统设置原始树，得到 21,695 字节 XML，含 `com.android.settings`、可交互节点和 bounds；正式选定无障碍直接序列化作为 UI 采集主路径。
17. [x] 重建并覆盖安装通用动作轨迹版本；发送 `open settings` 后实际进入 `com.android.settings`，返回 TARS 后回执显示 `第 1 轮前台：com.tars.assistant` 和 `已执行: launch (com.android.settings)`，未触发第三方 App 或敏感操作。
18. [x] 迁移后的 `org.atovio.tars` 已重新授予无障碍和 Shizuku API 权限；`open settings` 实际进入 `com.android.settings`，回执显示 `第 1 轮前台：org.atovio.tars` 与 `已执行: launch (com.android.settings)`。
19. [x] 清除遗留的 ADB `tcp:10000` 转发并重启 Termux Agent；取消 `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY` 后，设备内 Agent 直接访问国内云端模型成功，`127.0.0.1:10000` 不再监听。
20. [~] 受控 Gmail 输入任务已启动 Gmail 并聚焦收件人输入框；当前 UI 仍为空，尚未确认云端是否继续生成或执行 `type`，未填写主题/正文、未选择联系人、未发送邮件。该结果不能归因于 Shizuku 输入失败。
21. [x] AVD 全局代理设为宿主机 `10.0.2.2:10000` 后，`open gmail` 成功进入 `com.google.android.gm`；后续模型动作触发 TARS 敏感点击确认弹窗，测试选择取消，回执为 `已取消: click`，未发送邮件。
22. [~] 自动跨应用任务再次成功进入 Gmail `ComposeActivityGmail`，收件人 `EditText` 保持焦点且主题/正文为空；云端本轮未继续下发 `type`，未触发发送确认，未发送邮件。剩余问题限定为模型动作链连续规划。
23. [x] 设备审计日志复验：同步最新 `agent/server.py` 后，真实 Gmail 会话日志记录 `home`、`click`、`click`、`type` 四轮响应；TARS 最终回执为 `执行失败: type`。诊断 APK 已安装，暂停继续操作，待后续读取 `TarsAction`/`TarsShizuku` 细节。
24. [x] Gmail 输入执行层诊断：三阶段确定性探针（launch Gmail → 点写邮件 → type 收件人）驱动 TARS 完整复现；`TarsAction` 日志确认 `type target=7 class=android.view.ViewGroup focused=false setText=false`，`findNode` 命中收件人行容器而非内部 `EditText`；Shizuku `input text` 回退将真实邮箱 `violetylove@163.com` 注入当前焦点，Gmail 解析出联系人 Winter Yuan。
25. [x] 执行层修复验证：`ActionExecutor` 新增 `findEditableNode`（type 目标向下解析为实际可输入节点，先聚焦再 SET_TEXT），并统一 `agent/ui_summarizer.py` 与 Kotlin `collect()` 的类名可交互判定为 contains 语义。修复后复跑两次均 `type target=7 class=android.widget.EditText focused=true setText=true`，无障碍直写成功、无需 Shizuku 回退；收件人字段保留 `violetylove@163.com` 与联系人解析。
26. [x] 真实云端模型端到端复验：聚焦意图（"open gmail compose email to violetylove@163.com do not send"）下，Agent 审计日志记录 `home → click → click → type → done` 五轮全绿；type 执行 `class=android.widget.EditText focused=true setText=true`，收件人字段填入 `violetylove@163.com` 并解析出联系人 Winter Yuan，修复在真实链路生效。首次尝试中模型曾引用无效节点 `type target=8 nodeFound=false`，执行层 fail-closed 安全收敛（未记录 history、停止任务）。
27. [x] 真实云端敏感防线复验：模型点击含"发送"标签的节点（TARS 自身"发送给 TARS"）时，Android 强制弹出"确认 TARS 操作"；测试点击"取消"后回执为 `已取消: click`，点击未执行、任务安全停止（仅一轮请求），确认防线与失败收敛在真实模型下均生效。
28. [x] 可见性规划 SYSTEM_PROMPT 优化验证（2026-08-24）：要求模型先做可见性规划（先图层再坐标）再决策，并写入"父不可见则子不可见"规则。真实链路（带标点意图 `open Gmail, then compose a new email. To violetylove@163.com, subject TARS test, body this is a test. Then stop, do not send.`）下，模型每轮思考均以"当前可见性规划"开头，先对照 `input_method@层1` 等窗口图层（z 轴），再核对坐标：识别出 `[12]`/`[13]`（y=1578/1576）被键盘层（起始 y=1507）覆盖而不可操作，同时正确判断 `[14] 主题 (540,1497)` 未被覆盖可点击；各轮均含"父容器可见"推断。与旧 prompt 对比：旧版用"建议面板底部 y=2263"当参照、忽略键盘层导致误判主题"未遮挡"，新版按图层+坐标精确判断。验证了"先图层再坐标"+父不可见子不可见均生效，且坚守"只给事实、模型自决策"原则（提示词未写任何具体场景）。

## 已完成验收

- Android APK 安装、无障碍 UI 树采集、loopback HTTP、定时待处理提醒、通知监听、悬浮语音与 Shizuku
  参数受限 swipe 均已在 AVD 验证。
- Termux mock Agent 已完成与 Android 的同设备 loopback 协议联调；mock 回执不代表云端模型已配置或可用。
- 私有云端配置已复制到 Termux 私有项目目录，未写入 Git、APK 或 Android 工程。真实无 UI 请求返回合法 `reply` 动作；当前 APK 的无副作用任务完成 4 个受控观察轮次，未执行屏幕动作。
- 真实云端动作验收：任务明确限定为仅点击“15 分钟后提醒”。Agent 的 HTTP 响应均为 200；执行侧触发系统通知权限并成功登记一次性 `SCHEDULED_TASK` Alarm。主界面的最终状态会被无障碍重连提示覆盖，验收以 AlarmManager 系统记录为准。
- 真实云端敏感动作验收：任务要求仅点击“发送给 TARS”（当前 UI 节点 #1）。模型输出经 schema 后，Android 依据目标标签“发送”强制显示“确认 TARS 操作”弹窗；测试选择“取消”，最终状态为“等待确认: click”，敏感点击未执行。删除和支付标签复用同一 `SENSITIVE_LABELS` 防线，未在 AVD 另行触发实际业务界面。
- 触发持久化验收：`ScheduledTaskReceiver` 将任务保存到 `SharedPreferences` 后，即使强制停止并重启 App，用户仍可通过“载入待处理任务”恢复它。测试前后 Termux Agent 的 `POST /agent/run` 总数均为 15；保存、通知与手动载入不会自动进入决策或执行链路。
- Android 构建与 APK 同步验收：`settings.gradle.kts` 按顺序使用阿里云 Maven 镜像与 Google、Maven Central、Gradle Plugin Portal 官方回退；无需代理完成 `:app:assembleDebug` 全新依赖构建。覆盖安装后，无障碍服务仍为已启用和已绑定状态。
- 固定技能冒烟验收：当前 APK 发送 `open settings` 后，Termux 日志新增 `POST /agent/run 200`；AVD 前台窗口为 `com.android.settings/.Settings`。这同时验证了 App HTTP client、同设备 loopback、Python 固定路由与 Android 启动白名单。
- 通用对话终态验收：同步 `agent_loop.py` 后重启 AVD Termux Agent；云端仅被要求返回一句文本，实际响应为终态 `reply`、空动作且无需观察。临时 ADB 转发已在测试结束后移除；未执行任何界面操作。
- 云端可靠性部署验收：同步客户端、配置加载和服务启动模块后，旧版私有 `cloud.yaml` 未改动也可使用默认的最大 2 次重试与 1 秒初始退避。真实云端请求正常返回终态文本；模拟的超时、限流、服务错误和认证错误路径由本地单测覆盖。临时 ADB 转发已移除。
- 前台上下文安装基线：当前 Debug APK 已安装至 AVD；`dumpsys accessibility` 显示 TARS 服务处于 Bound 状态，并订阅窗口状态与内容变化事件。跨应用任务将在第一步动作后的下一轮请求中使用最新的目标 UI 树与前台上下文；该多步路径留待后续受控任务验收。
- 前台上下文端到端验收：同步 `agent_loop.py` 与 `server.py` 并重启 Termux Agent 后，传入系统设置包名及窗口类名的无副作用请求由真实云端仅答复 `com.android.settings`，响应为 `done=true`、空动作且无需观察。临时 ADB 转发已移除。
- 执行失败收敛验收：当前 APK 包含逐动作结构化结果；取消、拒绝或失败会使执行结果标记为未完成，主循环在记录 history 和请求下一轮前立即停止。受控任务仅允许模型点击 TARS 自身的“发送给 TARS”按钮，确认弹窗出现后选择取消，最终状态为“已取消: click”；Termux 日志仅新增本轮一次 `POST /agent/run 200`，未产生后续观察请求。
- UI 采集方案验收：在 TARS 无障碍服务已绑定时，跨应用系统设置原始树为 21,695 字节，含正确包名、可交互节点和 bounds。选定 `AccessibilityNodeInfo` 直接序列化（方案 B）为当前主路径；它无需 Shizuku 文件导出，方案 A 仅保留为未来受限备用。
- 多轮观察验收：临时确定性 Termux 服务的第一轮只启动系统设置并请求观察；执行侧仅在当前 UI XML 与首轮快照不同后才发起第二轮。探针记录确认第二轮使用同一会话，含 `app=com.android.settings`、系统设置的原始 UI XML 与首轮 `launch` history；真实云端 Agent 已在测试结束后恢复。
- 设备内云端无代理验收：移除遗留 ADB `tcp:10000` 转发并清除 Termux 代理环境变量后，Agent 进程保持监听 `127.0.0.1:8080`，无 UI、无动作请求经 HTTPS 直连国内云端模型成功；Android 与 Agent 的 loopback 通信仍固定使用 8080。
- Gmail 受控输入阶段结果：真实云端动作链成功启动 Gmail 并将收件人字段置于焦点，但当前字段仍为空；测试严格未选择联系人、未填写主题或正文、未点击发送。下一步需用更明确的通用任务描述确认模型是否继续返回 `type`，再判断执行层表现。
- Gmail 代理与安全回归：AVD 使用 `10.0.2.2:10000` 全局代理后仍可启动 Gmail；Shizuku 已启动并授权，真实动作到达 Android 后触发敏感确认，取消后安全停止，未发送邮件。
- Gmail 自动表单回归：完整任务可由 Agent 自动启动 Gmail 并点击写邮件，收件人字段真实获得焦点；当前模型未在下一轮生成 `type`，因此不归因于输入执行层失败。
- Agent 日志诊断：此前服务仅记录异常，无法判断 Gmail 多轮中是云端未返回 `type` 还是执行侧未发起下一轮；已补充安全元数据日志，重启 Termux Agent 后可按 session 追踪每轮 `actions`、`done` 与 `need_observation`。
- Agent/执行层定位：设备日志确认同一 Gmail 会话实际返回 `home`、`click`、`click`、`type` 四轮动作；TARS 最终回执为 `执行失败: type`，故 Agent 规划链正常，阻塞在 Android 输入执行层。诊断 APK 已增加 `nodeFound/focused/setText` 与 Shizuku 回退日志并完成构建安装。
- AVD 授权自动化：在受控模拟器中通过 ADB 写入 `org.atovio.tars/org.atovio.tars.TarsAccessibilityService` 并启用无障碍总开关，`dumpsys accessibility` 确认服务 Bound；实体机仍须遵循系统授权流程。
- 跨应用切换"未观察界面更新"修复复测：点 Gmail 图标后任务提前停止（回执"未观察到界面更新，已停止任务"）。定位 `awaitFreshUiAfter` 仅用 `currentUiXml` 字符串相等判断，跨应用切换期 `rootInActiveWindow` 节点树滞后导致误判无变化。修复后改为**包名变化主信号 + XML 辅助**，复测任务成功从 TARS→桌面→进入 Gmail 并持续多轮。
- Gmail 建议组件定位复测：模型在撰写页点 `node 13` 时该节点确为"主题"输入框（选对目标），但点击后 `awaitFreshUiAfter` 检测不到 UI 变化（`xmlChanged=false` 连续）而提前停止。抓撰写页 XML：`Winter Yuan` 建议组件在 XML 中但 `clickable=false`（不可交互），主题/正文输入框不在 XML 中。结论：建议组件弹出后 UI 更新判断未识别该变化、未重采完整新树（含建议组件）送给模型；主题/正文在建议组件出现时未被采集为可交互节点。附：`DIAG` 日志已增强为记录 `DIAG prompt`（最终发给 LLM 的节点文本）与 `DIAG llm_reply`（模型思考+指令），可精确回答"模型看到什么、如何回复"。
- Gmail 建议组件观察复测：事件序号逻辑已触发收件人文本变化后的等待，但服务 XML 原先只订阅窗口事件，未订阅代码依赖��焦点、文本和选中事件，导致序号不会增加。已补齐 `typeViewFocused|typeViewTextChanged|typeViewSelected` 声明，待新 APK 覆盖安装并重新绑定无障碍服务后复验。
- 多图层采集修复复测：`accessibility_service_config.xml` 声明 `android:accessibilityFlags="flagRetrieveInteractiveWindows"` 后，`getWindows()` 返回交互窗口，`currentUiXml` 不再为空（桌面轮 `ui_bytes` 从 0 → 4000、`DIAG nodes count` 从 0 → 14，Gmail/相册/YouTube 等图标均可见）；`collectVisibleWindowRoots` 在 `windows` root 取不到时回退 `rootInActiveWindow` 避免空树；遮挡剔除只跨图层生效（同层不互剔、跳过全屏容器），不再把桌面节点误剔。模型能基于完整桌面节点决策。
- 桌面图标错位问题（已解决，本次会话确认）：模型在桌面点 `[3] text"Gmail"`（小组件文字）后实际进入 Google 日历而非 Gmail；桌面轮模型输入里 `[9] text"TARS Assistant / 预测的"` 与真实可点图标错位。根因：桌面图标的文字标签与可点击目标不一致，模型按文字标签决策、点击落点却命中其他图标。修复链路（HEAD 已含）：多图层采集（`getWindows()`）补全桌面完整窗口树 → 保留零尺寸节点 ID 对齐两端编号 → `agent/ui_summarizer.to_llm_context` 向模型注入结构事实（resource-id/class/path），使模型能区分文字标签节点与可点图标节点。
