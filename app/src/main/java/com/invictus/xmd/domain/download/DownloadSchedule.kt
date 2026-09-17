package com.invictus.xmd.domain.download

import com.invictus.xmd.database.entities.QueueItem
import com.invictus.xmd.preferences.Settings
import java.util.Calendar

/**
 * How a queue item's start time is gated by the download scheduler.
 * Persisted on [QueueItem] (see its scheduleMode field) via [Converters].
 */
enum class ScheduleMode {
    /** Download as soon as it's READY -- today's default behavior. */
    NONE,

    /** Follow Settings' global quiet-hours window, if the user has one enabled. */
    INHERIT_GLOBAL,

    /** Wait until [QueueItem.scheduledAtMs], then download immediately (no recurrence). */
    ONE_TIME,

    /** Only allowed to run within this item's own window/day-mask, ignoring the global default. */
    CUSTOM_WINDOW
}

/**
 * Decides whether a queue item is allowed to start/keep downloading right
 * now, and when its allowed-state next flips -- consulted by
 * [com.invictus.xmd.repository.QueueRepository.claimNextReady] (which item a
 * worker may pick up next) and by DownloadService/ScheduleAlarmManager (pause
 * an in-flight download once its window closes, and wake the app back up for
 * the next open window).
 *
 * Windows are expressed as whole minutes since local midnight (0..1439) plus
 * a day-of-week bitmask (bit 0 = Sunday .. bit 6 = Saturday, matching
 * Calendar.DAY_OF_WEEK - 1). A window where start > end wraps past midnight
 * (e.g. 23:00-06:00 is "on from 23:00 to 24:00, then again 00:00 to 06:00").
 */
object DownloadScheduler {

    fun isAllowedNow(item: QueueItem, nowMs: Long = System.currentTimeMillis()): Boolean =
        when (item.scheduleMode) {
            ScheduleMode.NONE -> true
            ScheduleMode.ONE_TIME -> nowMs >= item.scheduledAtMs
            ScheduleMode.INHERIT_GLOBAL ->
                !Settings.schedulerEnabled() || isWithinWindow(
                    nowMs, Settings.schedulerWindowStartMinute(), Settings.schedulerWindowEndMinute(), Settings.schedulerDaysMask()
                )
            ScheduleMode.CUSTOM_WINDOW ->
                isWithinWindow(nowMs, item.windowStartMinute, item.windowEndMinute, item.windowDaysMask)
        }

    /**
     * Next time [isAllowedNow] would flip for this item, or null if it will
     * never change again (NONE, an already-fired ONE_TIME, or global
     * scheduling that's currently turned off). Used to arm the single
     * AlarmManager wakeup in ScheduleAlarmManager.
     */
    fun nextTransitionMs(item: QueueItem, nowMs: Long = System.currentTimeMillis()): Long? =
        when (item.scheduleMode) {
            ScheduleMode.NONE -> null
            ScheduleMode.ONE_TIME -> item.scheduledAtMs.takeIf { nowMs < it }
            ScheduleMode.INHERIT_GLOBAL ->
                if (!Settings.schedulerEnabled()) null
                else nextWindowTransition(nowMs, Settings.schedulerWindowStartMinute(), Settings.schedulerWindowEndMinute(), Settings.schedulerDaysMask())
            ScheduleMode.CUSTOM_WINDOW ->
                nextWindowTransition(nowMs, item.windowStartMinute, item.windowEndMinute, item.windowDaysMask)
        }

    private fun isWithinWindow(nowMs: Long, startMin: Int, endMin: Int, daysMask: Int): Boolean {
        if (startMin < 0 || endMin < 0 || startMin == endMin) return true // misconfigured -> never block
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val wraps = startMin > endMin
        val inRange = if (!wraps) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin
        if (!inRange) return false
        // A wrapping window's post-midnight tail (minuteOfDay < endMin)
        // belongs to *yesterday*'s day-of-week for the mask check.
        val dowIndex = if (wraps && minuteOfDay < endMin) {
            ((cal.get(Calendar.DAY_OF_WEEK) - 1) + 6) % 7
        } else {
            cal.get(Calendar.DAY_OF_WEEK) - 1
        }
        return (daysMask and (1 shl dowIndex)) != 0
    }

    /**
     * Walks forward minute-by-minute (up to 8 days) to find the next
     * open/close boundary. Simple brute force rather than a closed-form
     * calc across the wrap + day-mask combination -- fine since this only
     * runs once per alarm fire, never in a hot loop.
     */
    private fun nextWindowTransition(nowMs: Long, startMin: Int, endMin: Int, daysMask: Int): Long? {
        if (startMin < 0 || endMin < 0 || startMin == endMin) return null
        val currentlyAllowed = isWithinWindow(nowMs, startMin, endMin, daysMask)
        var probe = nowMs - (nowMs % 60_000L) + 60_000L
        val limit = nowMs + 8L * 24 * 60 * 60_000L
        while (probe < limit) {
            if (isWithinWindow(probe, startMin, endMin, daysMask) != currentlyAllowed) return probe
            probe += 60_000L
        }
        return null
    }
}
