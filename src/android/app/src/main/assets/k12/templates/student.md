---
# student.md template — copy to the device private profile. Do not commit filled copies.
# No real name, school, class, grades, or contact details belong in git.
schema: k12-student/v1
---

# Student profile (local only)

Fill this on the tablet after onboarding. One device, one child (K3).

| Field | Value | Notes |
| --- | --- | --- |
| nickname | （可选昵称） | Parent-chosen label only — not a legal name |
| stage | 小学低年级 \| 小学高年级 \| 初中 \| 高中 | v1: 高中 reserved, behavior not implemented |
| grade_label | 例如 四年级 / 初二 | Local label; not a school roster id |
| region | 省/市（可选） | Used for catalog aliases only |
| subject_tracks | 语文,数学,英语 | Comma-separated; add more later |
| textbook_aliases | （家长自填俗称） | Publisher codes stay local; samples use neutral aliases |
| daily_limit_minutes | （由家长区或服务端配置） | Shell enforces; do not put secrets here |
| exam_modules_enabled | false | Regional exam modules default off |
| notes | | Teaching preferences only — no grades or scores |

## Forbidden in this file

- Real full name, school name, class number, student ID
- Phone numbers, addresses, parent WeChat / employee ids
- Raw scores, report-card text, or chat transcripts
