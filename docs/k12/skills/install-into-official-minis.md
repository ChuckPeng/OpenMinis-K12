# 把 K12 技能装进官方 Minis（K4）

伴学 Minis（`com.openminis.k12`）会在首次启动时从 APK 资源安装三层技能。若只想在**官方 Minis**（`com.openminis.app`）里试用同一套教法文件，按下面做。不要把填好的 `student.md` 或含个人目录的 catalog 提交到任何公开仓库。

## 文件从哪来

本仓库（无个人数据）：

| 层 | 路径 |
| --- | --- |
| 教法核心 | `src/android/app/src/main/assets/k12/skills/study-companion-core/SKILL.md` |
| 学生档案模板 | `src/android/app/src/main/assets/k12/templates/student.md` |
| 样例 catalog | `src/android/app/src/main/assets/k12/catalogs/*/catalog.md` |

## 官方 Minis 上的目标位置

官方应用把用户技能放在设备上的 Minis 全局技能目录（与上游 `SkillRepository` 一致），对 shell 呈现为：

```text
/var/minis/skills/<skill-id>/SKILL.md
```

`study-companion-core` 的 `<skill-id>` 就是 `study-companion-core`（由 `name:` 字段 slug 化得到）。

`student.md` 与各科 `catalog.md` **不是**提示词技能包：在官方 Minis 里请放到你自己约定的工作目录（例如某会话工作区或笔记目录），由你在对话里 `@` 或粘贴。伴学 Minis 会把它们放进应用私有目录，不进 git。

## 安装步骤（官方 Minis）

1. 从本仓库复制 `study-companion-core/SKILL.md`（保持 YAML frontmatter：`name` / `description` / `version`）。
2. 在官方 Minis 打开技能导入：粘贴 `SKILL.md` 全文，或导入仅含该文件的 zip（根目录或一层子目录内有 `SKILL.md`）。
3. 确认技能列表出现 `study-companion-core`，且已启用。上游提示词最多挂 20 条技能；不要一次性塞入大量无关技能导致核心被挤出。
4. （可选）复制 `templates/student.md` 为本地草稿，只填昵称/学段等非身份字段；再按需复制样例 catalog 后改成自家目录摘要。
5. 不要在官方 Minis 里期望伴学壳层的揭晓状态机、时长锁或 PIN——那些只在 `com.openminis.k12`。K4 只要求**同一套技能文件**可被官方技能加载器识别。

## 升级

若本地已有同 id 技能且 `version` 不低于包内版本，伴学 Minis **不会**覆盖（H-18）。在官方 Minis 手动导入时，导入流程会按上游规则更新内容；导入前可先核对 frontmatter 的 `version`。

## 验收对照

- **K1**：`study-companion-core` 含分层提示、不代写整篇、超纲标记、休息提醒、PIN 拒答等红线。
- **K3**：仓库与 APK 模板中的 `student.md` 无真实姓名/学校/班级。
- **K4**：按本文可在官方 Minis 装入同一 `SKILL.md`。
