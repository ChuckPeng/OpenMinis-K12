# 错题本与 FSRS

## 职责

按孩子记下错题，用五类错因与四级掌握度，按 FSRS 安排复习，并回到先修知识点。变式不爬网搜原题。

## 分期

| 批次 | 范围 |
| --- | --- |
| v1 M3 | 记录、FSRS、掌握度与推题理由、先修回溯、事件 |
| v1 M5f | 读取 `attempt.outcome`，不在本模块生成亲子建议 |
| 不做 | 参数优化器；海量推题 |

## 公开接口

| 接口 | 说明 |
| --- | --- |
| 错题仓库 | 按 `child_id` 读写 `MistakeEntry` |
| FSRS 调度 | Kotlin 移植。输入评分，输出 `nextReviewAt` 与 Card 状态 |
| 举一反三 | 打开带母题上下文的会话，倾向三档变式 |
| 图谱只读 | 本机已下载的节点与先修边。没有图谱时跳过回溯 |

## 数据

`MistakeEntry` 见 `data-model.md`。FSRS 结构对照 `open-spaced-repetition/java-fsrs` v1.0.0 的 Scheduler、Card、ReviewLog、Rating、State。保留 MIT 声明。时区 `Asia/Shanghai`。

事件：`mistake_created`、`mistake_corrected`、`review_done`。订正 = 未看完整解、独立重做答对一次。

## 依赖

辅导（母题与变式）、课程目录（知识点 id）、同步客户端（事件）。K12-KGraph 与 cn-math-taxonomy 只作本机数据（ADR-0011）。

## 状态与流程

错因在记入时由辅导给出，孩子可改，家长可改。无法判定则「未判定」。

掌握度 `m` 由报告模块按同一公式计算；本模块保存等级与推题理由。复习队列只含 FSRS 到期项。

「待核」题不进错题统计（T-35）。

## 错误处理

| 情况 | 行为 |
| --- | --- |
| 图谱文件缺失或校验失败 | 错题与 FSRS 仍可用，先修显示「未映射」 |
| 移植行为与 py-fsrs 向量不一致 | 测试失败，不发版 |
| 无 `child_id` | 拒绝写入 |

## 配置

无独立服务端开关。复习提醒可走本机调度，不上传题目原文。

## 验收

B1–B4。掌握度向量同时满足 S5。

## 未决

T-34、T-35。v1 使用库默认参数，不暴露调参界面。
