package com.yuilittle.bili

import android.content.Context
import org.json.JSONArray

/** Local-only, bounded search history. It is never sent to a server. */
object SearchHistoryStore {
    const val MAX_HISTORY_ITEMS = 12
    private const val PREFS = "search_history"
    private const val KEY_ITEMS = "items"

    fun load(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val values = ArrayList<String>(MAX_HISTORY_ITEMS)
            for (index in 0 until minOf(array.length(), MAX_HISTORY_ITEMS)) {
                val value = array.optString(index).trim()
                if (value.isNotBlank() && value !in values) values += value
            }
            values
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(context: Context, rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        val next = ArrayList<String>(MAX_HISTORY_ITEMS)
        next += keyword
        load(context).forEach { if (it != keyword && next.size < MAX_HISTORY_ITEMS) next += it }
        save(context, next)
    }

    fun remove(context: Context, rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        save(context, load(context).filterNot { it == keyword })
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    private const val PREFS_SPACE = "space_search_history"
    private const val MAX_SPACE_HISTORY_ITEMS = 10

    fun loadForSpace(context: Context, mid: Long): List<String> =
        loadFromPrefs(context, PREFS_SPACE, "items_$mid", MAX_SPACE_HISTORY_ITEMS)

    fun recordForSpace(context: Context, mid: Long, rawKeyword: String) {
        if (mid <= 0L) return
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        val key = "items_$mid"
        val next = ArrayList<String>(MAX_SPACE_HISTORY_ITEMS)
        next += keyword
        loadFromPrefs(context, PREFS_SPACE, key, MAX_SPACE_HISTORY_ITEMS).forEach {
            if (it != keyword && next.size < MAX_SPACE_HISTORY_ITEMS) next += it
        }
        saveToPrefs(context, PREFS_SPACE, key, next, MAX_SPACE_HISTORY_ITEMS)
    }

    fun removeForSpace(context: Context, mid: Long, rawKeyword: String) {
        if (mid <= 0L) return
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return
        val key = "items_$mid"
        val remaining = loadFromPrefs(context, PREFS_SPACE, key, MAX_SPACE_HISTORY_ITEMS)
            .filterNot { it == keyword }
        saveToPrefs(context, PREFS_SPACE, key, remaining, MAX_SPACE_HISTORY_ITEMS)
    }

    fun clearForSpace(context: Context, mid: Long) {
        if (mid <= 0L) return
        context.applicationContext.getSharedPreferences(PREFS_SPACE, Context.MODE_PRIVATE)
            .edit()
            .remove("items_$mid")
            .apply()
    }

    private fun loadFromPrefs(context: Context, prefsName: String, key: String, limit: Int): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val values = ArrayList<String>(limit)
            for (index in 0 until minOf(array.length(), limit)) {
                val value = array.optString(index).trim()
                if (value.isNotBlank() && value !in values) values += value
            }
            values
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToPrefs(context: Context, prefsName: String, key: String, values: List<String>, limit: Int) {
        val array = JSONArray()
        values.take(limit).forEach { value -> array.put(value) }
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(key, array.toString())
            .apply()
    }

    private fun save(context: Context, values: List<String>) {
        saveToPrefs(context, PREFS, KEY_ITEMS, values, MAX_HISTORY_ITEMS)
    }
}
