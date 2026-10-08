package com.eko.reminders

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Tiny on-device store: the whole list as JSON in SharedPreferences. */
object Store {
    private const val PREFS = "reminders"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT_ID = "nextId"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(ctx: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.sortedBy { it.timeMillis }.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).commit()
    }

    fun get(ctx: Context, id: Int): Reminder? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(ctx: Context, r: Reminder) {
        saveAll(ctx, all(ctx).filter { it.id != r.id } + r)
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
}
