/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.os.Build
import com.machiav3lli.backup.dbs.ODatabase
import com.machiav3lli.backup.dbs.entity.Schedule
import com.machiav3lli.backup.preferences.pref_useAlarmClock
import com.machiav3lli.backup.preferences.pref_useExactAlarm
import com.machiav3lli.backup.services.AlarmReceiver
import com.machiav3lli.backup.traceSchedule
import timber.log.Timber
import java.util.concurrent.TimeUnit

fun calculateTimeToRun(schedule: Schedule, now: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = schedule.timePlaced
    c[Calendar.HOUR_OF_DAY] = schedule.timeHour
    c[Calendar.MINUTE] = schedule.timeMinute
    c[Calendar.SECOND] = 0
    c[Calendar.MILLISECOND] = 0
    if (now >= c.timeInMillis)
        c.add(Calendar.DAY_OF_MONTH, schedule.interval)
    return c.timeInMillis
}

fun scheduleAlarm(context: Context, scheduleId: Long, rescheduleBoolean: Boolean) {
    if (scheduleId >= 0) {
        Thread {
            val scheduleDao = ODatabase.getInstance(context).scheduleDao
            var schedule = scheduleDao.getSchedule(scheduleId)
            if (schedule?.enabled == true) {

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                val now = System.currentTimeMillis()
                val timeLeft = calculateTimeToRun(schedule, now) - now

                if (rescheduleBoolean) {
                    traceSchedule { "re-scheduling $schedule" }
                    schedule = schedule.copy(
                        timePlaced = now,
                        timeToRun = calculateTimeToRun(schedule, now)
                    )
                } else if (timeLeft <= TimeUnit.MINUTES.toMillis(1)) {
                    traceSchedule { "set schedule $schedule" }
                    schedule = schedule.copy(
                        timeToRun = now + TimeUnit.MINUTES.toMillis(1)
                    )
                }
                scheduleDao.update(schedule)

                val hasPermission: Boolean =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }
                val pendingIntent = createPendingIntent(context, scheduleId)
                if (hasPermission && pref_useAlarmClock.value) {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(schedule.timeToRun, null),
                        pendingIntent
                    )
                } else {
                    if (hasPermission && pref_useExactAlarm.value)
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            schedule.timeToRun,
                            pendingIntent
                        )
                    else
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            schedule.timeToRun,
                            pendingIntent
                        )
                }
                traceSchedule {
                    "schedule starting in: ${
                        TimeUnit.MILLISECONDS.toMinutes(schedule.timeToRun - System.currentTimeMillis())
                    } minutes"
                }
            } else
                traceSchedule { "schedule is disabled. Nothing to schedule!" }
        }.start()
    } else {
        Timber.e("got id: $scheduleId from $context")
    }
}

fun cancelAlarm(context: Context, scheduleId: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pendingIntent = createPendingIntent(context, scheduleId)
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
    traceSchedule { "cancelled schedule with id: $scheduleId" }
}

fun createPendingIntent(context: Context, scheduleId: Long): PendingIntent {
    val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
        action = "schedule"
        putExtra("scheduleId", scheduleId)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
    return PendingIntent.getBroadcast(
        context,
        scheduleId.toInt(),
        alarmIntent,
        PendingIntent.FLAG_IMMUTABLE
    )
}
