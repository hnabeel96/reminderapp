package com.eko.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shared complete / undo logic, used by the board checkbox and the notification button. */
object Actions {
    /** Marks an item done. Returns rating points earned (0 if nothing changed). */
    fun complete(ctx: Context, id: Int): Int {
        val r = Store.get(ctx, id) ?: return 0
        Notifier.cancel(ctx, id)
        Scheduler.cancelRering(ctx, id)
        val now = System.currentTimeMillis()
        if (r.recurring) {
            val alreadyToday = Dates.isToday(r.doneAt)
            Store.upsert(ctx, r.copy(awaitingAck = false, doneAt = now))
            if (alreadyToday) return 0
        } else {
            if (r.done) return 0
            Scheduler.cancelAll(ctx, id)
            Store.upsert(ctx, r.copy(done = true, doneAt = now, awaitingAck = false))
        }
        Store.addRating(ctx, r.priority.points)
        Store.markToday(ctx)
        return r.priority.points
    }

    fun uncomplete(ctx: Context, id: Int) {
        val r = Store.get(ctx, id) ?: return
        val now = System.currentTimeMillis()
        if (r.recurring) {
            if (!Dates.isToday(r.doneAt)) return
            Store.upsert(ctx, r.copy(doneAt = 0L))
        } else {
            if (!r.done) return
            val reopened = r.copy(
                done = false,
                doneAt = 0L,
                // A past-due timed task goes back to "overdue" instead of ringing again.
                awaitingAck = r.hasTime && r.timeMillis <= now,
            )
            Store.upsert(ctx, reopened)
            Scheduler.schedule(ctx, reopened)
        }
        Store.addRating(ctx, -r.priority.points)
    }
}

/** Woken by the OS at the scheduled time, and by the Done / Snooze buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val r = Store.get(ctx, id) ?: return
        val now = System.currentTimeMillis()

        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                if (!r.armed) return
                var updated = r.copy(awaitingAck = true)
                if (r.recurring) {
                    // Advance to the next occurrence right away, even if never marked done.
                    r.nextAfter(now + 1_000)?.let { updated = updated.copy(timeMillis = it) }
                }
                Store.upsert(ctx, updated)
                if (r.recurring) Scheduler.schedule(ctx, updated)
                Notifier.show(ctx, updated, "Your move")
                if (r.nagMinutes > 0) Scheduler.scheduleRering(ctx, id, now + r.nagMinutes * 60_000L)
            }

            Scheduler.ACTION_RERING -> {
                if (!r.armed || !r.awaitingAck) return
                Notifier.show(ctx, r, "Still your move. Tap Done when finished")
                if (r.nagMinutes > 0) Scheduler.scheduleRering(ctx, id, now + r.nagMinutes * 60_000L)
            }

            Scheduler.ACTION_SNOOZE -> {
                Notifier.cancel(ctx, id)
                if (!r.awaitingAck) Store.upsert(ctx, r.copy(awaitingAck = true))
                Scheduler.scheduleRering(ctx, id, now + Scheduler.SNOOZE_MINUTES * 60_000L)
            }

            Scheduler.ACTION_DONE -> Actions.complete(ctx, id)
        }
    }
}

/** Alarms are wiped on reboot, app update, or clock change; re-arm them from storage. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx, includeNag = true)
    }
}
