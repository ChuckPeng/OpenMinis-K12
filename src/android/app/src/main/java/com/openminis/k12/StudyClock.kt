package com.openminis.k12

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Study-day key and weekend, both in [Identity.ZONE_ID]. */
object StudyClock {
    val zone: ZoneId = ZoneId.of(Identity.ZONE_ID)
    private val boundary: LocalTime = LocalTime.of(Identity.DAY_BOUNDARY_HOUR, 0)

    fun studyDate(epochMs: Long): LocalDate {
        val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
        val date = zoned.toLocalDate()
        return if (zoned.toLocalTime() < boundary) date.minusDays(1) else date
    }

    fun dayKey(epochMs: Long): String = studyDate(epochMs).toString()

    fun isWeekend(epochMs: Long): Boolean {
        val dow = studyDate(epochMs).dayOfWeek
        return dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
    }
}
