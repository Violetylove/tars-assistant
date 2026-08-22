# AVD 联调记录

## 当前基线

- AVD：`TARS_MODEL_API_35`，Android 15 / Google APIs / x86_64 / Pixel 5，6 vCPU、6 GB RAM、16 GB 数据盘。
- Android App：`com.tars.assistant` Debug APK；无障碍、通知访问、麦克风和 Shizuku 授权按需由用户在系统 UI 中确认。
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
