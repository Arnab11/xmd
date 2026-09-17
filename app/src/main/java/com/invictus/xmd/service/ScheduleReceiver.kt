package com.invictus.xmd.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Fires on our own exact-alarm wakeup (a scheduled window is about to open
 * or close) and on BOOT_COMPLETED (AlarmManager alarms don't survive a
 * reboot, so whatever was armed needs to be recomputed). Both cases just
 * hand off to DownloadService.ACTION_SCHEDULE_CHECK, which does the actual
 * pause/resume work and re-arms the next alarm itself -- it already has
 * direct access to QueueRepository/Settings, no need to duplicate that
 * logic here.
 */
class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM_FIRED = "com.invictus.xmd.action.SCHEDULE_ALARM_FIRED"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_SCHEDULE_CHECK)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
