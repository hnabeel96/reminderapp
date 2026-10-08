package com.eko.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Hands "tickets" to the OS alarm clock. The app does not need to be running;
 * Android wakes AlarmReceiver at the exact time.
 */
object Scheduler {
    const val ACTION_FIRE = "com.eko.reminders.FIRE"
    const val ACTION_RERING = "com.eko.reminders.RERING"
    const val ACTION_DONE = "com.eko.reminders.DONE"
    const val ACTION_SNOOZE = "com.eko.reminders.SNOOZE"
    const val EXTRA_ID = "id"
    const val SNOOZE_MINUTES = 10

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun broadcast(ctx: Context, action: String, id: Int, code: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun fireIntent(ctx: Context, id: Int) = broadcast(ctx, ACTION_FIRE, id, id * 4)
    fun reringIntent(ctx: Context, id: Int) = broadcast(ctx, ACTION_RERING, id, id * 4 + 1)
    fun doneIntent(ctx: Context, id: Int) = broadcast(ctx, ACTION_DONE, id, id * 4 + 2)
    fun snoozeIntent(ctx: Context, id: Int) = broadcast(ctx, ACTION_SNOOZE, id, id * 4 + 3)

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    private fun setAt(ctx: Context, at: Long, op: PendingIntent) {
        val am = am(ctx)
        if (canExact(ctx)) {
            // Alarm-clock alarms are the most reliable kind: they survive Doze and OEM battery savers.
            val show = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
        }
    }

    /** (Re)arm the main alarm for an item. Safe to call repeatedly. */
    fun schedule(ctx: Context, r: Item) {
        am(ctx).cancel(fireIntent(ctx, r.id))
        if (!r.armed) return
        val now = System.currentTimeMillis()
        val next = r.nextAfter(now)
        when {
            next != null -> {
                if (next != r.timeMillis) Store.upsert(ctx, r.copy(timeMillis = next))
                setAt(ctx, next, fireIntent(ctx, r.id))
            }
            // One-time reminder whose time passed while the phone was off: fire it now.
            r.repeat == Repeat.ONCE && !r.awaitingAck -> setAt(ctx, now + 3_000, fireIntent(ctx, r.id))
        }
    }

    fun scheduleRering(ctx: Context, id: Int, at: Long) = setAt(ctx, at, reringIntent(ctx, id))

    fun cancelRering(ctx: Context, id: Int) = am(ctx).cancel(reringIntent(ctx, id))

    fun cancelAll(ctx: Context, id: Int) {
        am(ctx).cancel(fireIntent(ctx, id))
        am(ctx).cancel(reringIntent(ctx, id))
    }

    /** After reboot / app update / clock change: re-arm everything from storage. */
    fun rescheduleAll(ctx: Context, includeNag: Boolean) {
        val now = System.currentTimeMillis()
        Store.all(ctx).forEach { r ->
            schedule(ctx, r)
            if (includeNag && r.armed && r.awaitingAck) {
                val delay = if (r.nagMinutes > 0) r.nagMinutes * 60_000L else 5_000L
                scheduleRering(ctx, r.id, now + delay)
            }
        }
    }
}
