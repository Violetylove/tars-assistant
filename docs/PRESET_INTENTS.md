# 常用意图模板（TARS 联调/测试用）

> 本文件记录 TARS 联调与测试时反复使用的**意图模板**，便于复制粘贴到 TARS 主界面
> 输入框，代替每次手录长意图（避免手录慢、且容易因 `input text` 丢字）。
>
> 用法：选中一段模板，粘贴/输入到 TARS 主界面输入框，确认无误后点「发送给 TARS」。
>
> 注意：这些模板仅是**用户意图文本**，仍会经过 Agent 决策、schema 校验、动作白名单与
> 敏感操作确认防线，未绕过任何安全边界。

---

## Gmail 发信测试（针对建议组件排查）

```
gointo gmail then compose email to violetylove@163.com subject test subject body hello then send
```

- 目标：验证模型在填入收件人 `violetylove@163.com`、Gmail 弹出「建议」匹配组件（Winter Yuan）
  后，能否继续填主题/正文并发送。
- 备注：Gmail 建议卡会把主题/正文挤入输入法区，属**应用特有布局**；作为通用表单基准不通用，
  稳定复测建议改用无该怪癖的应用（如 `打开联系人，新增联系人，名字填 Zhang San，电话填 13800138000，最后保存。`）。

## 打开系统设置（固定技能 / 白名单 launch）

```
open settings
```

- 目标：验证固定技能路由 + Android 启动白名单。
- 简述：`open settings` 命中 `skill_router._LAUNCH_SKILLS`，返回 `launch com.android.settings`，
  由 Android 白名单启动。

## 打开 Gmail

```
open gmail
```

- 目标：验证固定技能 launch Gmail（白名单包）。
- 简述：命中 `_LAUNCH_SKILLS["gmail"]` → `launch com.google.android.gm`。

---

### 录入小贴士

- 由于模拟器上 `adb input text` 对长文本/中文可能丢字，建议**逐词分段输入**，或直接在
  TARS 输入框中粘贴本模板（若已连接剪贴板）。
- 若要改用其他收件人/文案，直接改模板中 `violetylove@163.com`、`test subject`、`hello` 即可。
