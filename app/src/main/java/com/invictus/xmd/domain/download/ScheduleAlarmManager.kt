package com.invictus.xmd.domain.download

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.invictus.xmd.repository.QueueRepository
import com.invictus.xmd.service.ScheduleReceiver

/**
 * Arms a single AlarmManager wakeup for whichever scheduled queue item (or
 * the global quiet-hours window, via any INHERIT_GLOBAL item) has the
 * soonest open/close transition -- so the app gets woken up to start or
 * pause downloads even if the process has been killed by Doze/App Standby
 * in the meantime.
 *
 * Only one alarm is ever outstanding. [rearm] recomputes the earliest
 * transition across the whole queue and replaces whatever alarm was there,
 * which is far simpler and less bug-prone than tracking N per-item alarms.
 * Call it any time a scheduled item is added/changed and, most importantly,
 * from DownloadService.onScheduleCheck() every time the alarm actually
 * fires -- that's what keeps it re-arming itself night after night.
 */
object ScheduleAlarmManager {

    private const val REQUEST_CODE = 4200

    fun rearm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(context)
        val nextMs = QueueRepository.current()
            .filter { it.scheduleMode != ScheduleMode.NONE }
            .mapNotNull { DownloadScheduler.nextTransitionMs(it) }
            .minOrNull()

        if (nextMs == null) {
            alarmManager.cancel(pendingIntent)
            return
        }

        // Exact alarms need a runtime grant on Android 12+ (the Settings >
        // Downloads screen offers a direct link to it). Without the grant,
        // fall back to an inexact alarm rather than not scheduling at
        // all -- the window is honored approximately instead of exactly.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextMs, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_ALARM_FIRED)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
