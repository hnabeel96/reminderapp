package com.eko.reminders

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class Repeat(val label: String) {
    ONCE("Once"),
    DAILY("Daily"),
    WEEKDAYS("Weekdays"),
    WEEKLY("Weekly"),
    EVERY_N_DAYS("Every N days"),
}

data class Reminder(
    val id: Int,
    val title: String,
    /** Next (or current, if awaiting acknowledgement) occurrence, epoch millis. */
    val timeMillis: Long,
    val repeat: Repeat = Repeat.ONCE,
    val everyNDays: Int = 2,
    /** Ring like an alarm (loud, looping, full screen) instead of a normal notification. */
    val alarmStyle: Boolean = false,
    /** Re-notify every N minutes until marked Done. 0 = off. */
    val nagMinutes: Int = 0,
    val enabled: Boolean = true,
    /** Fired but not yet marked Done. */
    val awaitingAck: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("timeMillis", timeMillis)
        put("repeat", repeat.name)
        put("everyNDays", everyNDays)
        put("alarmStyle", alarmStyle)
        put("nagMinutes", nagMinutes)
        put("enabled", enabled)
        put("awaitingAck", awaitingAck)
    }

    private fun stepDays(): Long = when (repeat) {
        Repeat.WEEKLY -> 7L
        Repeat.EVERY_N_DAYS -> everyNDays.coerceAtLeast(1).toLong()
        else -> 1L
    }

    private fun matches(t: ZonedDateTime): Boolean =
        repeat != Repeat.WEEKDAYS ||
            (t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY)

    /** First occurrence strictly after [now], or null if a one-time reminder has passed. */
    fun nextAfter(now: Long): Long? {
        if (repeat == Repeat.ONCE) return if (timeMillis > now) timeMillis else null
        val zone = ZoneId.systemDefault()
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone)
        val step = stepDays()
        // Jump close to "now" quickly if the base time is far in the past.
        val nowDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), nowDate)
        if (days > step) t = t.plusDays(((days / step) - 1).coerceAtLeast(0) * step)
        var guard = 0
        while ((t.toInstant().toEpochMilli() <= now || !matches(t)) && guard < 5000) {
            t = t.plusDays(step)
            guard++
        }
        return t.toInstant().toEpochMilli()
    }

    fun repeatText(): String =
        if (repeat == Repeat.EVERY_N_DAYS) "Every $everyNDays days" else repeat.label

    fun summary(): String {
        val parts = mutableListOf(formatTime(timeMillis), repeatText())
        if (alarmStyle) parts += "Alarm"
        if (nagMinutes > 0) parts += "Nag ${nagMinutes}m"
        return parts.joinToString(" · ")
    }

    companion object {
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

        fun formatTime(millis: Long): String =
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(FMT)

        fun fromJson(o: JSONObject) = Reminder(
            id = o.getInt("id"),
            title = o.optString("title", ""),
            timeMillis = o.getLong("timeMillis"),
            repeat = runCatching { Repeat.valueOf(o.optString("repeat", "ONCE")) }
                .getOrDefault(Repeat.ONCE),
            everyNDays = o.optInt("everyNDays", 2),
            alarmStyle = o.optBoolean("alarmStyle", false),
            nagMinutes = o.optInt("nagMinutes", 0),
            enabled = o.optBoolean("enabled", true),
            awaitingAck = o.optBoolean("awaitingAck", false),
        )
    }
}
