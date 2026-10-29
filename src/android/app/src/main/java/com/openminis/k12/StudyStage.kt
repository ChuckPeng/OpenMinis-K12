package com.openminis.k12

/**
 * Stages from the data model. 高中 is stored only. v1 has no high-school teaching,
 * so its time defaults follow 初中 and the shell says so.
 */
enum class StudyStage(val stored: String, val label: String) {
    PRIMARY_LOWER("小学低年级", "小学低年级"),
    PRIMARY_UPPER("小学高年级", "小学高年级"),
    JUNIOR("初中", "初中"),
    SENIOR("高中", "高中"),
    ;

    fun isPrimary(): Boolean = this == PRIMARY_LOWER || this == PRIMARY_UPPER

    /** v1 teaches primary and junior only. */
    fun curriculumInV1(): Boolean = this != SENIOR

    fun dailyLimitMinutes(): Int = if (isPrimary()) 40 else 90

    fun weekendExtraMinutes(): Int = 30

    /** C4: primary 25, junior (and the reserved senior default) 40. */
    fun breakReminderMinutes(): Int = if (isPrimary()) 25 else 40

    companion object {
        fun fromStored(value: String?): StudyStage? =
            entries.firstOrNull { it.stored == value }
    }
}
