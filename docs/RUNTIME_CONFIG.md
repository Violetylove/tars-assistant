# 运行时配置登记

> 本表登记会影响 TARS 行为的参数及其安全边界。Android 设置页只开放 A 类；私有云端配置不
> 进入 APK 或 Git。协议定义以 `docs/DESIGN.md` 为准。

## A 类：用户可配置

这些值由 `RuntimeSettings.kt` 集中校验、持久化，并可在设置页恢复安全默认值。

| 参数 | 默认值 | 有效范围 | 说明 |
|---|---:|---|---|
| `max_observation_rounds` | `12` | 1-20 | 单任务可推进的最大观察轮数 |
| `observation_timeout_ms` | `5000` | 2000-10000 | 每次动作后等待稳定 UI 的基础时限 |
| `agent_host` | `127.0.0.1` | IPv4、IPv6 或域名 | 不含协议、路径、端口；远程主机必须受信任 |
| `agent_port` | `8080` | 1-65535 | 与 Agent 监听端口一致 |
| `model_request_timeout_ms` | `210000` | 60000-600000 | 覆盖云端推理及有限重试的 Android 请求时限 |
| `manual_reminder_delay_ms` | `900000` | 60000-604800000 | 将当前草稿设为定时提醒的延时 |
| `new_app_grace_ms` | `4000` | 0-10000 | 新应用切换后，无障碍树仍为空时的额外等待宽限 |
| `allowed_launch_packages` | 空 | 至多 50 项 | 在独立应用列表页勾选；刷新/保存时均剔除已卸载应用 |

`agent_host=127.0.0.1` 是客户端默认目标；Agent 的服务监听默认值为 `0.0.0.0`。后者只表示
监听所有网卡，不能填作客户端连接地址。

## B 类：安全边界（不可配置）

| 项目 | 当前值 | 归属 | 约束 |
|---|---|---|---|
| 动作白名单 | `click,type,swipe,back,home,launch,wait,reply,done` | Android + `bridge/schemas.py` | 两端必须一致 |
| 敏感标签 | 发送、删除、清除、支付、付款、转账及英文对应词 | Android + Python | 只能加严，不能放宽 |
| history 轮数上限 | 用户设置 `max_observation_rounds`（1-20） | Android `RuntimeSettings` | 轮数检查在 Android 侧，协议不设上限 |
| 单轮动作上限 | 8 | `bridge/schemas.py` | 响应和 history 每轮均受限 |
| 摘要节点上限 | 60 | `UiSummarizer.kt`（Android） | 目标约 500 token；动作 ID 与执行侧同源 |
| 关键类名 token | `button, edittext, checkbox, radiobutton, switch, imagebutton` | Android `UiSummarizer.kt` | 采用相同 contains 语义；`ui_summarizer.py` 仅作回退 |

## C 类：私有云端配置

`config/cloud.yaml` 只保存在 Agent 所在机器，字段如下：

| 参数 | 默认值 | 校验 |
|---|---:|---|
| `llm.base_url` | 必填 | http 或 https；http 需 `allow_insecure_http=true` |
| `llm.model` | 必填 | 非空且非占位符 |
| `llm.api_key` | 必填 | 仅私有文件保存，绝不写入日志；自托管（见下）可放宽长度 |
| `llm.timeout_seconds` | `60` | 正数 |
| `llm.max_retries` | `2` | 0-3 |
| `llm.retry_backoff_seconds` | `1` | 0-10 |
| `llm.allow_insecure_http` | `false` | 允许明文 HTTP 端点（自部署模型；仅限可信网络） |
| `llm.verify_ssl` | `true` | 跳过 TLS 证书校验（自签名证书端点用） |

## 日志

日志不属于 RuntimeSettings。Android 本地日志为 `files/log/android.log`，由用户在设置页主动上传；
Agent 将其保存到项目根目录的 `log/android/`。Agent 自身审计日志默认写入 `log/agent/agent.log`。
这些运行产物均被 Git 忽略。

## 维护规则

1. 改协议、动作或安全边界时，先更新 `docs/DESIGN.md` 与 `bridge/`，再同步本表和两端实现。
2. 新的用户设置必须有默认值、范围/格式校验及恢复默认值路径。
3. 改 UI 节点筛选、动作 ID 或敏感判定时，以 Android `UiSummarizer.kt` 为准，同步回退实现
   `ui_summarizer.py` 并添加回归测试。
