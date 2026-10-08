package com.eko.reminders

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
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

enum class Priority(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    companion object {
        /** Also accepts the old chess-themed names from build 2. */
        fun parse(s: String): Priority = when (s) {
            "HIGH", "KING" -> HIGH
            "MEDIUM", "ROOK" -> MEDIUM
            else -> LOW
        }
    }
}

/** One entry: a to-do, optionally with a reminder time (and repeat). */
data class Item(
    val id: Int,
    val title: String,
    val priority: Priority = Priority.LOW,
    val hasTime: Boolean = false,
    /** Next (or current, if awaiting acknowledgement) occurrence, epoch millis. */
    val timeMillis: Long = 0L,
    val repeat: Repeat = Repeat.ONCE,
    val everyNDays: Int = 2,
    val alarmStyle: Boolean = false,
    val nagMinutes: Int = 0,
    /** Reminder sound on/off. The task itself stays on the board either way. */
    val enabled: Boolean = true,
    /** Reminder fired but not yet marked done. */
    val awaitingAck: Boolean = false,
    val done: Boolean = false,
    /** When completed (for recurring: last completed occurrence). */
    val doneAt: Long = 0L,
    val createdAt: Long = 0L,
) {
    val recurring: Boolean get() = hasTime && repeat != Repeat.ONCE
    val armed: Boolean get() = hasTime && enabled && !done

    /** Done for the board's purposes (recurring = done today). */
    val doneNow: Boolean get() = if (recurring) Dates.isToday(doneAt) && !awaitingAck else done

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("priority", priority.name)
        put("hasTime", hasTime)
        put("timeMillis", timeMillis)
        put("repeat", repeat.name)
        put("everyNDays", everyNDays)
        put("alarmStyle", alarmStyle)
        put("nagMinutes", nagMinutes)
        put("enabled", enabled)
        put("awaitingAck", awaitingAck)
        put("done", done)
        put("doneAt", doneAt)
        put("createdAt", createdAt)
    }

    private fun stepDays(): Long = when (repeat) {
        Repeat.WEEKLY -> 7L
        Repeat.EVERY_N_DAYS -> everyNDays.coerceAtLeast(1).toLong()
        else -> 1L
    }

    private fun matches(t: ZonedDateTime): Boolean =
        repeat != Repeat.WEEKDAYS ||
            (t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY)

    /** First occurrence strictly after [now], or null if a one-time time has passed. */
    fun nextAfter(now: Long): Long? {
        if (!hasTime) return null
        if (repeat == Repeat.ONCE) return if (timeMillis > now) timeMillis else null
        val zone = ZoneId.systemDefault()
        var t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone)
        val step = stepDays()
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

    fun detail(): String {
        if (!hasTime) return "Anytime"
        val parts = mutableListOf(Dates.formatWhen(timeMillis))
        if (repeat != Repeat.ONCE) parts += repeatText()
        if (alarmStyle) parts += "Alarm"
        if (nagMinutes > 0) parts += "Nag ${nagMinutes}m"
        if (!enabled && !done) parts += "Muted"
        return parts.joinToString(" · ")
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            val repeat = runCatching { Repeat.valueOf(o.optString("repeat", "ONCE")) }
                .getOrDefault(Repeat.ONCE)
            val enabled = o.optBoolean("enabled", true)
            val awaiting = o.optBoolean("awaitingAck", false)
            // v1 marked finished one-time reminders as disabled; treat those as done.
            val legacyDone = !o.has("done") && repeat == Repeat.ONCE && !enabled && !awaiting
            return Item(
                id = o.getInt("id"),
                title = o.optString("title", ""),
                priority = Priority.parse(o.optString("priority", "LOW")),
                hasTime = o.optBoolean("hasTime", true),
                timeMillis = o.optLong("timeMillis", 0L),
                repeat = repeat,
                everyNDays = o.optInt("everyNDays", 2),
                alarmStyle = o.optBoolean("alarmStyle", false),
                nagMinutes = o.optInt("nagMinutes", 0),
                enabled = enabled || legacyDone,
                awaitingAck = awaiting,
                done = o.optBoolean("done", legacyDone),
                doneAt = o.optLong("doneAt", if (legacyDone) o.optLong("timeMillis", 0L) else 0L),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    }
}

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
    private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

    fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun isToday(millis: Long): Boolean = millis > 0 && date(millis) == LocalDate.now(zone)

    fun time(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(TIME)

    fun formatWhen(millis: Long): String {
        val d = date(millis)
        val today = LocalDate.now(zone)
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val day = when {
            d == today -> "Today"
            d == today.plusDays(1) -> "Tomorrow"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today) && d.isBefore(today.plusDays(7)) -> t.format(DAY)
            else -> t.format(FULL)
        }
        return "$day, ${t.format(TIME)}"
    }
}
