package com.eko.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

object Notifier {
    private const val CH_REMINDER = "reminders_v1"
    private const val CH_ALARM = "alarms_v1"

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(ctx: Context) {
        val reminder = NotificationChannel(
            CH_REMINDER, "Reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Normal reminders"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarm = NotificationChannel(
            CH_ALARM, "Alarm-style reminders", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Rings until you tap Done or Snooze"
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm(ctx).createNotificationChannels(listOf(reminder, alarm))
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, r: Item, text: String) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)

        val open = PendingIntent.getActivity(
            ctx, r.id * 4, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, if (r.alarmStyle) CH_ALARM else CH_REMINDER)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF7DF9FF.toInt())
            .setContentTitle(r.title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(
                if (r.alarmStyle) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_REMINDER
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(r.alarmStyle)
            .addAction(0, "Done", Scheduler.doneIntent(ctx, r.id))
            .addAction(0, "Snooze ${Scheduler.SNOOZE_MINUTES}m", Scheduler.snoozeIntent(ctx, r.id))

        if (r.alarmStyle) {
            val fs = PendingIntent.getActivity(
                ctx, r.id * 4 + 1,
                Intent(ctx, AlarmActivity::class.java)
                    .putExtra(Scheduler.EXTRA_ID, r.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.setFullScreenIntent(fs, true)
            b.setContentIntent(fs)
        }

        val n = b.build()
        // Loop the sound until the user acts on it.
        if (r.alarmStyle) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm(ctx).notify(r.id, n)
    }

    fun cancel(ctx: Context, id: Int) = nm(ctx).cancel(id)

    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || nm(ctx).canUseFullScreenIntent()
}
