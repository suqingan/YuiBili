package com.yuilittle.bili

import android.os.SystemClock
import android.util.LruCache
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * B 站链接标题解析（有界 2 线程池 + LRU 缓存 + 失败冷却 + 同资源在途合并）：
 * - 消除此前每条评论创建裸 `Thread {}` 的开销；
 * - 同一视频/番剧链接在热评中高频出现时直接命中内存缓存，零网络往返。
 */
internal object BiliLinkTitle {

    private const val CACHE_TTL_MS = 10 * 60_000L
    private const val FAIL_COOLDOWN_MS = 60_000L

    private data class CacheEntry(val title: String?, val expireAtMs: Long)

    private val titleCache = object : LruCache<String, CacheEntry>(128) {}
    private val inFlight = ConcurrentHashMap<String, MutableList<(String?) -> Unit>>()

    private val pool = ThreadPoolExecutor(
        1, 2, 20L, TimeUnit.SECONDS, LinkedBlockingQueue(24)
    ).apply { allowCoreThreadTimeOut(true) }

    private val VIDEO_BVID_RE = Regex("""/video/(BV[0-9A-Za-z]+)""")
    private val QUERY_BVID_RE = Regex("""[?&]bvid=(BV[0-9A-Za-z]+)""")
    private val PLAY_EP_RE = Regex("""/bangumi/play/ep(\d+)""")
    private val QUERY_EP_RE = Regex("""[?&]ep_id=(\d+)""")

    /** 异步解析多个链接标题，命中缓存立即返回，未命中走有界线程池与单飞合并。 */
    fun resolveBatchAsync(urls: List<String>, onDone: (Map<String, String>) -> Unit) {
        if (urls.isEmpty()) return
        val result = HashMap<String, String>()
        val pending = ArrayList<Pair<String, String>>()
        val now = SystemClock.elapsedRealtime()
        for (url in urls) {
            val key = canonicalKey(url) ?: continue
            val cached = titleCache.get(key)
            if (cached != null && now < cached.expireAtMs) {
                if (!cached.title.isNullOrBlank()) result[url] = cached.title
            } else {
                pending.add(url to key)
            }
        }
        if (pending.isEmpty()) {
            if (result.isNotEmpty()) onDone(result)
            return
        }
        val lock = Any()
        var remaining = pending.size
        for ((url, key) in pending) {
            resolveSingleAsync(url, key) { title ->
                val doneSnapshot: Map<String, String>? = synchronized(lock) {
                    if (!title.isNullOrBlank()) result[url] = title
                    remaining--
                    if (remaining <= 0 && result.isNotEmpty()) HashMap(result) else null
                }
                if (doneSnapshot != null) onDone(doneSnapshot)
            }
        }
    }

    private fun resolveSingleAsync(url: String, key: String, callback: (String?) -> Unit) {
        val waiters = ArrayList<(String?) -> Unit>(2).apply { add(callback) }
        val existing = inFlight.putIfAbsent(key, waiters)
        if (existing != null) {
            synchronized(existing) { existing.add(callback) }
            return
        }
        try {
            pool.execute {
                val resolved = resolve(url)
                val ttl = if (resolved != null) CACHE_TTL_MS else FAIL_COOLDOWN_MS
                titleCache.put(key, CacheEntry(resolved, SystemClock.elapsedRealtime() + ttl))
                val callbacks = inFlight.remove(key) ?: return@execute
                val copy = synchronized(callbacks) { callbacks.toList() }
                copy.forEach { it(resolved) }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.remove(key)
            callback(null)
        }
    }

    /** 同步解析单个链接（供后台线程或单测直接调用）。 */
    fun resolve(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (!isBiliUrl(url)) return null
        VIDEO_BVID_RE.find(url)?.let { return videoTitle(it.groupValues[1]) }
        QUERY_BVID_RE.find(url)?.let { return videoTitle(it.groupValues[1]) }
        PLAY_EP_RE.find(url)?.let {
            return episodeTitle(it.groupValues[1].toLongOrNull() ?: return null)
        }
        QUERY_EP_RE.find(url)?.let {
            return episodeTitle(it.groupValues[1].toLongOrNull() ?: return null)
        }
        return null
    }

    private fun canonicalKey(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        if (!isBiliUrl(url)) return null
        VIDEO_BVID_RE.find(url)?.let { return "bv:${it.groupValues[1]}" }
        QUERY_BVID_RE.find(url)?.let { return "bv:${it.groupValues[1]}" }
        PLAY_EP_RE.find(url)?.let { return "ep:${it.groupValues[1]}" }
        QUERY_EP_RE.find(url)?.let { return "ep:${it.groupValues[1]}" }
        return null
    }

    /** 是否 B 站自家链接（用于决定显示标题还是原链接）。 */
    fun isBiliUrl(url: String): Boolean {
        val lower = url.lowercase()
        return BILI_HOSTS.any { lower.contains("//$it/") || lower.contains("//$it?") }
    }

    private fun videoTitle(bvid: String): String? = runCatching {
        val data = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            .optJSONObject("data") ?: return null
        data.optString("title").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun episodeTitle(epId: Long): String? = runCatching {
        val result = getJson("https://api.bilibili.com/pgc/view/web/season?ep_id=$epId")
            .optJSONObject("result") ?: return null
        val episodes = result.optJSONArray("episodes")
        if (episodes != null) {
            for (i in 0 until episodes.length()) {
                val ep = episodes.optJSONObject(i) ?: continue
                if (ep.optLong("id") != epId) continue
                ep.optString("share_copy").takeIf { it.isNotBlank() }?.let { return it }
                ep.optString("long_title").takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        result.optString("season_title").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun getJson(address: String): JSONObject {
        val request = BiliHttp.request(address)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", NETWORK_USER_AGENT)
            .build()
        return BiliHttp.client.newCall(request).execute().use(BiliHttp::json)
    }

    private val BILI_HOSTS = listOf(
        "bilibili.com", "b23.tv", "bili2233.cn", "acg.tv"
    )
}
