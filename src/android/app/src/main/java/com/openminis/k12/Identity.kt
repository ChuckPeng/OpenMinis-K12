package com.openminis.k12

/**
 * Fork identity. The install id is what lets this app sit beside upstream Minis.
 * New code lives in this package; upstream sources stay in `com.openminis.app`.
 */
object Identity {
    const val APPLICATION_ID = "com.openminis.k12"
    const val DISPLAY_NAME = "伴学 Minis"
    const val RELEASE_REPO = "ChuckPeng/OpenMinis-K12"

    /** Product day and weekend calculations. Not a device timezone. */
    const val ZONE_ID = "Asia/Shanghai"

    /** Study day starts at 04:00 in [ZONE_ID] (T-12). */
    const val DAY_BOUNDARY_HOUR = 4
}
