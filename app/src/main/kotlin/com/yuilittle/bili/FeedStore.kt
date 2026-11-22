package com.yuilittle.bili

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object FeedStore {
    private const val MAX_STORED_ITEMS = FeedPagingPolicy.MAX_IN_MEMORY_ITEMS
    private const val PREFS = "bounded_feed_snapshot"

    data class State(
        val videos: List<VideoItem>,
        val lastSuccessfulPage: Int,
        val lastScannedPage: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val disk = ThreadPoolExecutor(1, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
        allowCoreThreadTimeOut(true)
    }
    private val snapshots = arrayOfNulls<State>(2)
    private val waitingReaders = HashMap<Int, MutableList<(State) -> Unit>>()
    private val writeLock = Any()
    private val pendingWrites = LinkedHashMap<Int, State>()
    private var writerScheduled = false

    fun loadState(context: Context, tab: Int, callback: (State) -> Unit): (() -> Unit)? {
        check(Looper.myLooper() == Looper.getMainLooper())
        snapshots[tab]?.let {
            callback(it)
            return null
        }
        val readers = waitingReaders.getOrPut(tab) {
            val application = context.applicationContext
            disk.execute {
                val fromDisk = readState(application, tab)
                mainHandler.post {
                    val state = snapshots[tab] ?: fromDisk.also { snapshots[tab] = it }
                    waitingReaders.remove(tab)?.toList()?.forEach { it(state) }
                }
            }
            ArrayList()
        }
        readers.add(callback)
        return { readers.remove(callback) }
    }

    private fun readState(context: Context, tab: Int): State = try {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val videos = videosFromJson(preferences.getString(key(tab), null))
        val successfulPage = preferences.getInt(pageKey(tab), if (videos.isEmpty()) 0 else 1).coerceAtLeast(0)
        val scannedPage = preferences.getInt(scanKey(tab), successfulPage).coerceAtLeast(0)
        State(videos, successfulPage, scannedPage)
    } catch (error: ClassCastException) {
        Log.w("FeedStore", "Invalid feed snapshot", error)
        State(emptyList(), 0, 0)
    }

    fun saveState(context: Context, tab: Int, videos: List<VideoItem>, page: Int, scannedPage: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val state = State(videos, page, scannedPage)
        snapshots[tab] = state
        val schedule = synchronized(writeLock) {
            pendingWrites[tab] = state
            if (writerScheduled) false else {
                writerScheduled = true
                true
            }
        }
        if (!schedule) return
        val application = context.applicationContext
        disk.execute { flushWrites(application) }
    }

    private fun flushWrites(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        while (true) {
            val batch = synchronized(writeLock) {
                if (pendingWrites.isEmpty()) {
                    writerScheduled = false
                    return
                }
                pendingWrites.toMap().also { pendingWrites.clear() }
            }
            val editor = preferences.edit()
            for ((tab, state) in batch) {
                editor.putString(key(tab), videosToJson(state.videos))
                    .putInt(pageKey(tab), state.lastSuccessfulPage)
                    .putInt(scanKey(tab), state.lastScannedPage)
            }
            if (!editor.commit()) Log.w("FeedStore", "Unable to persist feed snapshots")
        }
    }

    private fun videosFromJson(value: String?): List<VideoItem> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val count = minOf(array.length(), MAX_STORED_ITEMS)
            val videos = ArrayList<VideoItem>(count)
            val seen = HashSet<String>(count)
            for (index in 0 until count) {
                val item = array.optJSONObject(index) ?: continue
                val bvid = item.optString("bvid")
                if (bvid.isBlank() || !seen.add(bvid)) continue
                videos += VideoItem(
                    bvid = bvid,
                    title = item.optString("title"),
                    owner = item.optString("owner"),
                    cover = item.optString("cover"),
                    views = item.optLong("views"),
                    duration = item.optInt("duration"),
                    description = "",
                    publishedAt = item.optLong("publishedAt"),
                    cid = item.optLong("cid"),
                    aid = item.optLong("aid"),
                    danmakuCount = item.optLong("danmakuCount"),
                    rcmdReason = item.optString("rcmdReason"),
                    charge = item.optBoolean("charge")
                )
            }
            videos
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun videosToJson(videos: List<VideoItem>): String {
        val array = JSONArray()
        videos.forEach { video ->
            array.put(JSONObject().apply {
                put("bvid", video.bvid)
                put("title", video.title)
                put("owner", video.owner)
                put("cover", video.cover)
                put("views", video.views)
                put("duration", video.duration)
                put("publishedAt", video.publishedAt)
                put("cid", video.cid)
                put("aid", video.aid)
                put("danmakuCount", video.danmakuCount)
                put("rcmdReason", video.rcmdReason)
                put("charge", video.charge)
            })
        }
        return array.toString()
    }

    private fun key(tab: Int) = if (tab == 0) "recommend" else "popular"
    private fun pageKey(tab: Int) = "${key(tab)}_last_page"
    private fun scanKey(tab: Int) = "${key(tab)}_last_scanned_page"
}
