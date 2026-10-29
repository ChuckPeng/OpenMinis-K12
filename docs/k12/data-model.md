# 数据模型

默认数据在平板。服务端保存配对、配置、去标识事件与报告。不保存题目原文、聊天原文、真实姓名。唯一的聊天例外是告警触发片段：前后各至多 2 句，传输字段 `snippet`，入库 `snippet_enc`。

## 平板

能放文件的优先放文件，便于同一技能装进官方 Minis。

| 实体 | 关键内容 | 保留 |
| --- | --- | --- |
| `student.md` | 家长自定昵称（可选）、学段、年级、地区、分科版本、时长、会考模块开关 | 本机一份。不进仓库。随加密备份 |
| `catalog.md` | 章—节—知识点—要点—难度—进度；英语可含单元词表；可映射外部图谱 id | 家长拍照生成。不存课文或例题原文 |
| `ChildIdentity` | `child_id`（UUIDv4，首次向导生成）、`device_id`、配对状态 | 换机经备份沿用同一 `child_id` |
| `ParentSettings` | `pinHash`（加盐）、`revealPolicy`、中继地址、证书指纹、中继公钥、`deviceId`、`configVersion`、`kidMode` | 设备私钥、`unlockSecret` / `codeSecret`、`totpSecret` 在 Keystore，不进普通文件 |
| `MistakeEntry` | 题目、错因五类、知识点、掌握度四级、日期、图、会话 id、FSRS 状态 | 按 `child_id` 隔离。FSRS 列以 java-fsrs v1.0.0 的 Card / ReviewLog 为准，本文不另起列名 |
| 本机事件库 | 与下方事件同构 | 默认 90 天。未确认上传的不删 |
| 本机 `kp` 快照 | M3 起，口径与服务端 `kp_daily` 相同 | 供离线报告 |

学段枚举：`小学低年级`、`小学高年级`、`初中`、`高中`（只预留，v1 不实现高中行为）。文中「小学」指前两者。

掌握度四级：精通 `m ≥ 0.85`，良好 `0.65 ≤ m < 0.85`，合格 `0.40 ≤ m < 0.65`，薄弱 `m < 0.40`。计算见 `modules/reports.md`。

错因五类：概念不清、审题错误、计算粗心、不会方法、表达不规范。另有「未判定」（不进占比分母）。待核用 `disposition=pending_check`，不占用错因。

家长设置里的时长、休息、揭晓策略、用量上限、学段以服务端为准，经心跳下发。PIN、TOTP、模型 Key 只在本机。

## 服务端表

SQLite，第一天带 `family_id`。v1 只有一个家庭。敏感列用主密钥 AES-256-GCM：应用 Secret、EncodingAESKey、`codeSecret`、钉钉令牌、模型 API Key。

### 身份与通道

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `families` | `id`，`name`，`created_at` | `name` 只存标签 |
| `admins` | `id`，`username`，`password_hash`（argon2id），`failed_count`，`locked_until`，`last_login` | 无二步验证 |
| `wecom_apps` | `id`，`family_id`，`corpid`，`agentid`，`secret_enc`，`token`，`aeskey_enc`，`api_base_url`，`callback_key`，`enabled` | `callback_key` 决定回调路径 |
| `parents` | `id`，`family_id`，`wecom_userid`，`label`，`can_approve`，`channel`（企微 App / 微信插件） | 用标签，不存真名 |
| `devices` | `id`，`family_id`，`child_id`（唯一），`label`，`pubkey`，`code_secret_enc`，`status`，`paired_at`，`revoked_at`，`last_seen`，`app_version`，`config_version_applied` | 一机一孩。`status` 为 `pending_confirm`、`active`、`revoked`。`label` 由家长填写，不存设备型号 |
| `device_config` | `device_id`，`version`，`config_json`，`updated_by`，`updated_at` | 版本递增。键封闭，见下 |
| `pair_codes` | `code_hash`，`family_id`，`expires_at`，`used_at` | 一次性，10 分钟 |
| `nonces` | `device_id`，`nonce`，`seen_at` | 10 分钟清理 |

`config_json` 只含这些键：`stage`，`daily_limit_minutes`，`weekend_extra_minutes`（默认 30），`break_reminder_minutes`，`late_night_start`，`late_night_end`（默认 `06:00`），`reveal_policy`，`turn_limit`（默认 60），`image_limit`（默认 20），`companion_limit_minutes`（默认 15），`study_day_min_minutes`（默认 10）。不含告警阈值。

### 解锁与告警

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `unlock_requests` | `id`，`device_id`，`question_hash`，`subject`，`hints_used`，`status`，`scope`，`created_at`，`expires_at`，`decided_by`，`decided_at`，`task_id`，`response_code`，`consumed_at` | 不存题目原文。状态：`pending` → `approved` / `denied` / `expired` → `consumed` |
| `grants` | `id`，`request_id`，`device_id`，`scope`，`expires_at`，`signature`，`consumed_at` | 单次 |
| `alerts` | `id`，`device_id`，`child_id`，`level`（提示 / 关注 / 紧急），`category`，`occurred_at`，`snippet_enc`，`snippet_expires_at`，`acknowledged_by` | 片段 30 天删除 |

### 学习与报告

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `children` | `id`（= `child_id`），`family_id`，`label`，`stage`，`device_id`（唯一），`report_settings_json` | 设备接口永不返回 `parent_advice_json` |
| `study_event` | `event_id`（PK），`child_id`，`device_id`，`type`，`occurred_at`，`local_date`，`seq`，`subject`，`kp_ids`，`payload_json`，`schema_version`，`received_at` | 默认 180 天 |
| `daily_aggregate` | PK（`child_id`，`local_date`，`subject`）；时长、作答、提示直方图、揭晓、错题、任务、drill、旗标、`is_study_day`、`metric_version`、`computed_at` | 报告只读聚合表。字段名以 §18.7 那一行为准 |
| `kp_daily` | PK（`child_id`，`local_date`，`kp_id`）；`attempts`，`correct`，`mastery`，`level`，`open_mistakes` | 每日收盘快照 |
| `report` | `id`，`child_id`，`kind`（`daily`/`weekly`/`monthly`/`term`/`adhoc`），周期，`metrics_json`，`analysis_json`，`parent_text_json`，`child_text_json`，`parent_advice_json`，`text_source`（`llm`/`template`），`data_basis_json`，`metric_version`，`version`，`status`（`final`/`revised`），`generated_at` | `adhoc` 24 小时后清理 |
| `advice_feedback` | `id`，`child_id`，`report_id`，`entry_id`，`vote`（`useful`/`not_useful`/`already_doing`），`created_at` | 保留 1 年。M5f |
| `report_delivery` | `id`，`report_id`，`channel`（`wecom`/`app`），`recipient`，`outbox_id`，`link_id`，`link_expires_at`，`link_revoked_at`，`status`，`sent_at`，`first_opened_at` | 保留 1 年 |
| `usage_daily` | `child_id`，`date`，`turns`，`images`，`companion_minutes`，`extensions_granted` | 与平板本地计数并存 |
| `schedules` / `templates` | `cron`，`kind`，`template_text`（Go `text/template`） | 变量白名单，避开整点/半点 |

### 队列、审计、备份、钉钉

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `outbox` | `id`，`app_id`，`kind`，`payload_json`，`attempts`，`next_at`，`status`，`expires_at` | 解锁类过期即丢，不补发 |
| `audit_log` | `actor`，`action`，`target`，`at` | 只写不改。默认 180 天 |
| `settings` | `key`，`value` | 含模型 `base_url`、`model`、`api_key_enc`、超时、保留期、限流、时区、`public_base_url`、`trusted_proxies` |
| `device_backup` | `id`，`device_id`，`child_id`，`created_at`，`size`，`sha256`，`blob_path`，`kdf_params` | 密文。保留最近 7 份日备份 + 4 份周备份 |
| `dingtalk_account` | `id`，`family_id`，`dt_user_label`，`auth_method`（`oauth`/`dws`），令牌密文，`expires_at`，`status`，`authorized_at`，`last_sync_at`，`last_error` | v1 每家庭一个。v1.2 才使用 |
| `child_group_binding` | `id`，`family_id`，`child_id`，`child_label`，`group_conv_id`，`group_label`，`teacher_rule`（`role`/`list`），`teacher_ids_json`，`enabled` | 一孩可多群 |
| `class_message` | `id`，`binding_id`，`dt_msg_id`（唯一），`sender_role`，`sender_label`，`sent_at`，`text`，`attachments_json`，`extract_status`，`retry_at`，`expires_at` | 只存老师/管理员。其他家长消息不入库 |
| `extracted_task` | `id`，`message_id`，`child_id`，`kind`（作业/通知/待办/考试），`subject`，`title`，`detail`，`due_at`，`attachment_refs`，`confidence`，`delivered_to_json`，`status` | 抽取失败时 `kind` 为原文 |

## 事件

`POST /api/v1/device/events`。`schema_version` 从 1 起，服务端至少兼容前一版。

| 字段 | 规则 |
| --- | --- |
| `event_id` | UUIDv7，平板生成，幂等键 |
| `device_id` / `child_id` | 已配对设备；`child_id` 为 UUIDv4 |
| `type` | 下表 |
| `occurred_at` | UTC 毫秒 |
| `tz` | 记录时的 IANA 时区。归日不看这个字段 |
| `seq` | 设备内单调递增 |
| `subject` / `kp_ids` | 科目代码；catalog 节点 id，可附图谱 id |
| `payload` | 只有数字、枚举、哈希。无题目文字、图片、聊天原文 |

| `type` | `payload` |
| --- | --- |
| `session_start` / `session_end` | `session_id`，`mode`（拍题/练习/复习/背默/口语/任务/陪伴），`subject` |
| `active_span` | `session_id`，`start`，`end`。上报合并后的区间，不逐次上报点击 |
| `stem_confirmed` | `question_hash`，`char_count`。无题干 |
| `calc_checked` | `question_hash`，`match`（布尔）。无算式 |
| `attempt` | `question_hash`，`source`（练习/变式/复习/测验/拍题），`first_try`，`correct`，`hint_max`（0–3），`duration_s`，可选 `outcome`（`solved`/`revealed`/`abandoned`，M5f 使用） |
| `hint_used` | `question_hash`，`level`（`guide`/`hint`/`step`） |
| `answer_reveal` | `question_hash`，`method`（`pin`/`backup_code`/`totp`/`remote`/`auto`/`review`），`hints_before` |
| `mistake_created` / `mistake_corrected` / `review_done` | `mistake_id`，`question_hash`，`cause`（五类或未判定），`disposition`（`recorded`/`pending_check`），`fsrs_rating`，`next_due` |
| `task_created` / `task_done` | `task_id`，`source`（`dingtalk`/`parent`/`self`），`kind`，`due_at`，`done_at` |
| `drill_result` | `drill`（听写/背诵/口语），`list_id`，`total`，`correct`。v1.1 不写 `score` |
| `break` / `limit_reached` / `wellbeing_flag` | 休息时长；上限类型（时长/回合/图片）；`level` 与 `category`，无原文 |
| `mood_checkin` | 可选，默认关。`mood` 1–5，`want_talk` 布尔。无文字 |

题目哈希：确认后的题干做 Unicode NFC、去掉首尾空白、内部连续空白压成一个空格，再取 UTF-8 的 SHA-256，小写十六进制。不含图片字节。迟到 30 天内的事件重算对应日期；已发出的报告标「已修订」。超过 30 天只进原始表。

## 保留期

| 数据 | 默认 | 可配 |
| --- | --- | --- |
| 平板事件 | 90 天，未上传确认的除外 | 设计未写可配 |
| 解锁申请、告警及片段 | 30 天 | 是 |
| 审计 | 180 天 | 是 |
| `study_event` | 180 天 | 是 |
| 钉钉原文与附件 | 30 天 | 是 |
| 抽取后的任务 | 180 天 | 是 |
| `daily_aggregate`、`kp_daily`、`report` | 3 年 | 是 |
| `report_delivery`、`advice_feedback` | 1 年 | 是 |
| 平板备份密文 | 7 个日备份 + 4 个周备份 | 设计写死这一档 |
| 随时查看报告 | 24 小时 | 否 |

删除孩子档案时删除其全部学情数据。也可按日期范围删除并重算。

## 授权对象

响应体：

```json
{"payload":{"v":1,"grant_id":"...","request_id":"...","device_id":"...","question_hash":"...","scope":"question","expires_at":"...","decided_by_label":"..."},"signature":"..."}
```

`scope` 为 `question` 或 `minutes_15`。`signature` 是对 payload 规范化 JSON（键排序、无多余空白、UTF-8）的 Ed25519 签名，base64url、无填充。平板记下已用的 `grant_id`。

## 备份文件

魔数 ASCII `K12B`，版本 `uint16 = 1`，Argon2id（内存 65536 KiB，迭代 3，并行 1），盐 16 字节，nonce 12 字节，然后是 AES-GCM 密文与 16 字节 tag。一块文件，不分卷。

## 报告链接

HMAC-SHA256 的密钥由主密钥派生。明文是 UTF-8 的 `link_id`、一个换行、十进制 Unix 秒 `exp`。`sig` 为 base64url、无填充。

## 错误体

`{"error":"snake_code","message":"短说明"}`。`message` 不包含秘密或题目。
