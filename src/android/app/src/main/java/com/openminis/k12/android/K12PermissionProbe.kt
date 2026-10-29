package com.openminis.k12.android

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.openminis.k12.PermissionReport

/**
 * The four booleans from the heartbeat contract. No model string is read.
 * Below the API that introduced a check, the item counts as allowed.
 */
class K12PermissionProbe(private val context: Context) {
    fun read(): PermissionReport {
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = power.isIgnoringBatteryOptimizations(context.packageName)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isBackgroundRestricted.not()
        } else {
            true
        }
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarms.canScheduleExactAlarms()
        } else {
            true
        }
        return PermissionReport(
            notificationsEnabled = notifications,
            batteryUnrestricted = battery,
            backgroundAllowed = background,
            exactAlarmAllowed = exact,
        )
    }
}
