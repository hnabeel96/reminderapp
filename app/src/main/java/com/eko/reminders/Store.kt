package com.eko.reminders

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.time.LocalDate

data class Stats(val streak: Int)

/** Tiny on-device store: the whole list as JSON in SharedPreferences, plus stats. */
object Store {
    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "nextId"
    private const val KEY_DAYS = "days"
    private const val KEEP_DONE_MS = 30L * 24 * 60 * 60 * 1000

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<Item>) {
        val cutoff = System.currentTimeMillis() - KEEP_DONE_MS
        val arr = JSONArray()
        list.filterNot { it.done && !it.recurring && it.doneAt in 1 until cutoff }
            .sortedWith(compareBy<Item>({ !it.hasTime }, { it.timeMillis }, { it.createdAt }))
            .forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).commit()
    }

    fun get(ctx: Context, id: Int): Item? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(ctx: Context, item: Item) {
        saveAll(ctx, all(ctx).filter { it.id != item.id } + item)
    }

    @Synchronized
    fun delete(ctx: Context, id: Int) {
        saveAll(ctx, all(ctx).filter { it.id != id })
    }

    @Synchronized
    fun newId(ctx: Context): Int {
        val p = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        return id
    }

    // ---- Streak: consecutive days with at least one completed task ----

    fun stats(ctx: Context): Stats = Stats(streak(ctx))

    private fun days(ctx: Context): Set<String> =
        (prefs(ctx).getString(KEY_DAYS, "") ?: "").split(',').filter { it.isNotBlank() }.toSet()

    @Synchronized
    fun markToday(ctx: Context) {
        val all = (days(ctx) + LocalDate.now().toString()).sorted().takeLast(400)
        prefs(ctx).edit().putString(KEY_DAYS, all.joinToString(",")).commit()
    }

    private fun streak(ctx: Context): Int {
        val s = days(ctx)
        var d = LocalDate.now()
        if (d.toString() !in s) d = d.minusDays(1)
        var n = 0
        while (d.toString() in s) {
            n++
            d = d.minusDays(1)
        }
        return n
    }
}
