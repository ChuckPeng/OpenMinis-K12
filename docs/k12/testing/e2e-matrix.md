# 学习功能模拟器验收

GitHub Actions 的 API 30 与 API 34 模拟器跑 `K12DeviceE2ETest` 和 `K12LearningE2ETest`。讲题模型是工作流里的假 OpenAI 服务（固定回复，含 `k12_calc` 工具调用），经管理接口下发。不使用真实密钥。

无头模拟器没有可用相机。拍照题走系统图片选择，测试用 Espresso Intents 交回一张夹具图。企微按钮回调（J12 / J14）不在 CI 里执行，家长页只断言「稍后开放」行可见。真机步骤见 `docs/k12/ops/j12-live-test.md`。

| 功能 | 期望 | 覆盖 |
| --- | --- | --- |
| 深色第一次设置 | 标题「第一次设置」在深色背景下可读 | `K12DeviceE2ETest.keystorePairModelAndDarkOnboarding`；`learningJourney` 截图 `01-onboarding-stage` |
| 学段 | 点「高中」出现「高中只作预留。这一版不提供高中讲法。」；再选「初中」进入下一步。初中每日默认 90 分钟 | `learningJourney` 截图 `02-stage-senior` |
| 平板编号 | 显示「这台平板的编号」，称呼可留空 | `learningJourney` 截图 `03-identity` |
| 家长 PIN 与备份口令 | 4 到 8 位 PIN，备份口令至少 8 位，两次一致后进入权限页 | `learningJourney` |
| 权限自检 | 引导页和家长区都能打开「权限自检」 | `learningJourney` 截图 `04-permissions`、`12-permissions` |
| 提交配对 | 绑定后离开空表单，显示「已提交，等家长在管理后台/企微确认…」和服务器主机名 | `pendingPairingActivatesAfterRestart`；`learningJourney` 截图 `05-pending` |
| 待确认跨进程 | 回到桌面并按进程启动的方式重新装入状态后，等待页仍在，且不点刷新 | 同上，截图 `06-pending-restart` |
| 家长确认后自动进入 | 管理接口批准后，平板自己变成已绑定，导航离开等待页 | 同上 |
| 心跳 | 设备列表 `last_seen` 有值 | 两个学习测试都断言 |
| 用量 | 心跳里的 `daily_used_minutes` 记入当日 `companion_minutes`，重复上报同一累计值不加倍。设备列表带出该字段 | `learningJourney` |
| 服务器下发模型 | `openAI`、模型名、地址、密钥已设置、版本大于 0，然后进入会话列表 | 设备测试用占位地址；学习测试用假模型地址 |
| 新对话 | 「还没有对话。点下面的按钮开始。」之后点新对话，发出第一条 | `learningJourney` 截图 `07-sessions`、`08-math` |
| 继续对话 | 同一会话再发一句，模型按后续步骤回答 | `learningJourney` 截图 `09-followup` |
| 苏格拉底 / 讲解 | 新会话里非算式问题得到「想一想，这一步可以怎么做？」 | `learningJourney` 截图 `10-socratic` |
| Symja 验算 | 模型发起 `k12_calc`，表达式 `2+2`，界面出现「算式的结果是 4。先想一想为什么。」 | `learningJourney` 截图 `08-math` |
| 拍照 / 看图 | 无头环境不测真实相机。选图夹具后界面出现「题干：2+2。请确认。」 | `learningJourney` 截图 `11-photo` |
| 讲题技能 | 家长页在核心技能资源可读时显示「讲题技能已随应用安装」 | `learningJourney` 截图 `13-parent` |
| 错题与知识点 | 一次错误作答后家长页显示「待复习」和「错题已关联知识点」 | `learningJourney` 截图 `13-parent` |
| 学情日简报 | 同步后的 `stem_confirmed` 与 `attempt` 进入当日汇总；生成报告的作答次数至少为 1 | `learningJourney` |
| 安全拦截 | 危机表述不发给模型。第一步只有「请马上告诉身边的大人。」和「不想说」，没有号码 | `learningJourney` 截图 `14-safety-step1`、`15-safety-step2`。假模型 `/e2e/stats` 的 `crisis_reached_model` 为 false |
| 家长配置的热线 | 服务器下发热线文案和号码后，拒绝后再显示这两项 | `learningJourney` 截图 `16-safety-hotline` |
| 时长芯片 | 服务器把每日分钟改成 10 后，芯片不超过 10 | `learningJourney` 截图 `17-time-chip` |
| 锁屏与 PIN 加时 | 前台时间超过限额后出现「今天的时长用完了」。家长 PIN 加时后回到会话 | `learningJourney` 截图 `18-lock`、`19-extended` |
| 家长齿轮与 PIN | 会话列表的设置进入「家长 PIN」，正确 PIN 后是「家长」 | `learningJourney` |
| J12 稍后开放 | 家长页能看到「揭晓策略」「动态口令」「课程目录拍照」「解锁兜底」。企微回调不在本工作流 | `learningJourney` 截图 `13-parent` |
