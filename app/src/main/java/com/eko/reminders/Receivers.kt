package com.eko.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Woken by the OS at the scheduled time, and by the Done / Snooze buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ID, -1)
        val r = Store.get(ctx, id) ?: return
        val now = System.currentTimeMillis()

        when (intent.action) {
            Scheduler.ACTION_FIRE -> {
                if (!r.enabled) return
                var updated = r.copy(awaitingAck = true)
                if (r.repeat != Repeat.ONCE) {
                    // Advance to the next occurrence right away, even if never marked Done.
                    r.nextAfter(now + 1_000)?.let { updated = updated.copy(timeMillis = it) }
                }
                Store.upsert(ctx, updated)
                if (r.repeat != Repeat.ONCE) Scheduler.schedule(ctx, updated)
                Notifier.show(ctx, updated, "Due now")
                if (r.nagMinutes > 0) Scheduler.scheduleRering(ctx, id, now + r.nagMinutes * 60_000L)
            }

            Scheduler.ACTION_RERING -> {
                if (!r.enabled || !r.awaitingAck) return
                Notifier.show(ctx, r, "Still waiting — tap Done when finished")
                if (r.nagMinutes > 0) Scheduler.scheduleRering(ctx, id, now + r.nagMinutes * 60_000L)
            }

            Scheduler.ACTION_SNOOZE -> {
                Notifier.cancel(ctx, id)
                if (!r.awaitingAck) Store.upsert(ctx, r.copy(awaitingAck = true))
                Scheduler.scheduleRering(ctx, id, now + Scheduler.SNOOZE_MINUTES * 60_000L)
            }

            Scheduler.ACTION_DONE -> {
                Notifier.cancel(ctx, id)
                Scheduler.cancelRering(ctx, id)
                Store.upsert(
                    ctx,
                    r.copy(awaitingAck = false, enabled = r.repeat != Repeat.ONCE && r.enabled)
                )
            }
        }
    }
}

/** Alarms are wiped on reboot, app update, or clock change; re-arm them from storage. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.rescheduleAll(ctx, includeNag = true)
    }
}
