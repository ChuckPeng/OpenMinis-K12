# ADR-0010 FSRS 从 java-fsrs 移植

- 状态：已接受
- 日期：2026-10-29

## 背景

错题间隔不要写死 1/2/4/7/15。FSRS-Kotlin 的说明写 MIT，但仓库没有 LICENSE 文件。fsrs-rs 是 BSD-3-Clause，本决定不采用。

## 决定

把 `open-spaced-repetition/java-fsrs` v1.0.0（MIT，Copyright (c) 2025 Open Spaced Repetition）移植为 Kotlin。源文件头保留版权与 MIT 声明。FSRS-6，21 个默认参数，不含优化器。用同组织的 py-fsrs 做对拍向量。时区 `Asia/Shanghai`。

## 后果

M3 不依赖上游补 LICENSE。行为以 Java 实现和 py-fsrs 向量为准，不在文档里重写参数表。
