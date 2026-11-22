package com.yuilittle.bili

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CoverLoader {
    private const val MIB = 1024 * 1024
    private const val THUMB_EDGE = 512
    private const val ORIGINAL_EDGE = 2048
    private const val LONG_IMAGE_MIN_ASPECT = 2.4f
    private const val MAX_LONG_IMAGE_WIDTH = 1440
    private const val MAX_LONG_IMAGE_HEIGHT = 8192
    private const val TOUCH_INTERVAL_MS = 15 * 60_000L
    const val PRIORITY_HIGH = 10
    const val PRIORITY_NORMAL = 5
    const val PRIORITY_LOW = 1
    private val cdnDomains = listOf("hdslb.com", "biliimg.com", "hdslb.net")
    private val main = Handler(Looper.getMainLooper())
    private val taskSeq = AtomicLong(0L)
    private val diskDecodePool = ThreadPoolExecutor(
        2, 2, 20L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(32)
    ).apply { allowCoreThreadTimeOut(true) }
    private val thumbnails = priorityWorkers(6)
    private val originals = workers(2, 12)
    private val prefetches = LinkedHashMap<Key, Flight>()
    private val http = BiliHttp.client.newBuilder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(24, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val largeHttp = http.newBuilder()
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(38, TimeUnit.SECONDS)
        .build()
    private val thumbMemory = bitmapCache(Runtime.getRuntime().maxMemory().div(12).coerceIn(4L * MIB, 16L * MIB).toInt())
    private val originalMemory = bitmapCache(Runtime.getRuntime().maxMemory().div(8).coerceIn(12L * MIB, 36L * MIB).toInt())
    /** 原始资源规范 URL → 最近命中的缩略图 Key（只存 Key，不额外强引用 Bitmap，供大图预览 0 延迟占位）。 */
    private val bestThumbKeyByRaw = object : LinkedHashMap<String, Key>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Key>?): Boolean = size > 192
    }
    private val diskLock = Any()
    private val thumbDisk = DiskCache("cover_thumbs", 64L * MIB)
    private val animationDisk = DiskCache("gif_cache", 16L * MIB)
    private val flights = HashMap<Key, Flight>()
    private val viewports = HashMap<ViewTreeObserver, Viewport>()
    private val visibleBounds = Rect()
    private val scrollViewBounds = Rect()
    private val scrollChildBounds = Rect()
    private var trimRegistered = false

    private const val MAX_PENDING_THUMB_TASKS = 32
    private const val MAX_PREFETCH_IN_FLIGHT = 2
    private const val SCROLL_LOOKAHEAD_RATIO = 0.75f
    private const val SCROLL_RETAIN_BEHIND_RATIO = 0.25f

    /** 可取消的预取句柄，切 Tab/排序/退出页面时释放对应预取任务。 */
    class PrefetchHandle internal constructor(private val keys: List<Key>) {
        @Volatile private var cancelled = false

        fun cancel() {
            if (cancelled) return
            cancelled = true
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cancelPrefetchKeys(keys)
            } else {
                main.post { cancelPrefetchKeys(keys) }
            }
        }
    }

    internal data class Key(val source: String, val edge: Int, val original: Boolean, val gif: Boolean, val local: Boolean)

    private class Binding(
        image: ImageView,
        val key: Key,
        val rawUrl: String,
        val cropThumb: Boolean,
        var animate: Boolean,
        var priority: Int,
        var onFailure: ((ImageView) -> Unit)?,
        var onProgress: ((Int) -> Unit)? = null
    ) {
        val image = WeakReference(image)
        var flight: Flight? = null
        var animationWork: Runnable? = null
        var displayed: Drawable? = null
        var watcher: AnimationVisibility? = null
        var viewport: Viewport? = null
        var retryAt = 0L
        var retry: Runnable? = null
    }

    private class Flight(val key: Key, val executor: ThreadPoolExecutor, var priority: Int) {
        val targets = LinkedHashSet<Binding>()
        lateinit var work: Runnable
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
        @Volatile var lastPercent = -1
        @Volatile var progressListenerCount = 0

        fun addTarget(binding: Binding) {
            if (targets.add(binding) && binding.onProgress != null) {
                progressListenerCount++
            }
        }

        fun removeTarget(binding: Binding): Boolean {
            val removed = targets.remove(binding)
            if (removed && binding.onProgress != null) {
                progressListenerCount = (progressListenerCount - 1).coerceAtLeast(0)
            }
            return removed
        }

        fun cancel() {
            cancelled = true
            executor.remove(work)
            call?.cancel()
        }
    }

    private class PrioritizedTask(
        val priority: Int,
        val seq: Long = taskSeq.incrementAndGet(),
        private val action: Runnable
    ) : Runnable, Comparable<PrioritizedTask> {
        override fun run() = action.run()
        override fun compareTo(other: PrioritizedTask): Int {
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else seq.compareTo(other.seq)
        }
    }

    private sealed interface Result {
        data class Still(val bitmap: Bitmap) : Result
        data class Animated(val file: File) : Result
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val binding = bindingFor(v as ImageView) ?: return
            val animation = binding.displayed as? Animatable
            if (animation != null) watchAnimation(binding, v, animation)
            else if (binding.displayed == null) request(binding)
        }

        override fun onViewDetachedFromWindow(v: View) {
            val binding = bindingFor(v as ImageView) ?: return
            unsubscribe(binding)
            binding.watcher?.close()
            binding.watcher = null
        }
    }

    fun isCachedOriginal(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        val source = imageUrl(rawUrl, original = true, crop = false, thumbEdge = ORIGINAL_EDGE, allowAnimatedGif = true)
            ?: return false
        val gif = source.toHttpUrlOrNull()?.encodedPath?.substringBefore('@')
            ?.endsWith(".gif", ignoreCase = true) == true
        val key = Key(source, ORIGINAL_EDGE, original = true, gif = gif, local = false)
        return !gif && originalMemory.get(key) != null
    }

    /**
     * 无 View 绑定的纯 URL 后台预取（如冷启动提前把登录用户头像解码入 LRU + 落盘）。
     * 若内存已有则零开销返回；若磁盘已命中则走独立解码池；若需网络下载则按高优先级抢先拉取。
     */
    fun prefetch(
        context: Context,
        rawUrl: String,
        original: Boolean = false,
        cropThumb: Boolean = false,
        thumbEdge: Int = 128,
        priority: Int = PRIORITY_HIGH
    ) {
        if (rawUrl.isBlank()) return
        val appContext = context.applicationContext
        val effectiveEdge = if (original) ORIGINAL_EDGE else thumbEdge.coerceIn(64, THUMB_EDGE)
        val source = imageUrl(rawUrl, original, cropThumb, thumbEdge = effectiveEdge, allowAnimatedGif = false) ?: return
        val key = Key(source, effectiveEdge, original, gif = false, local = false)
        if (memory(key).get(key) != null) return
        val startPrefetch = Runnable {
            enqueuePrefetchKey(appContext, key, priority)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startPrefetch.run()
        } else {
            main.post(startPrefetch)
        }
    }

    /** 批量有界预取（供评论区首屏配图/头像提前入队，返回可取消句柄，与 load 共享完全一致的规范化 Key）。 */
    fun prefetchBatch(
        context: Context,
        entries: List<PrefetchSpec>
    ): PrefetchHandle {
        if (entries.isEmpty()) return PrefetchHandle(emptyList())
        val appContext = context.applicationContext
        val resolved = ArrayList<Pair<Key, Int>>(entries.size)
        for (entry in entries) {
            if (entry.rawUrl.isBlank()) continue
            val effectiveEdge = normalizeThumbEdge(entry.thumbEdge)
            val source = imageUrl(
                entry.rawUrl,
                original = false,
                crop = entry.cropThumb,
                thumbEdge = effectiveEdge,
                allowAnimatedGif = false
            ) ?: continue
            val key = Key(source, effectiveEdge, original = false, gif = false, local = false)
            if (memory(key).get(key) == null) {
                resolved.add(key to entry.priority)
            }
        }
        if (resolved.isEmpty()) return PrefetchHandle(emptyList())
        val keys = resolved.map { it.first }
        val handle = PrefetchHandle(keys)
        val start = Runnable {
            for ((key, prio) in resolved) {
                if (prefetches.size >= MAX_PREFETCH_IN_FLIGHT && thumbnails.queue.size >= 8) break
                enqueuePrefetchKey(appContext, key, prio)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start.run() else main.post(start)
        return handle
    }

    data class PrefetchSpec(
        val rawUrl: String,
        val cropThumb: Boolean = false,
        val thumbEdge: Int = 320,
        val priority: Int = PRIORITY_LOW
    )

    /** 规范化缩略图档位（160 / 320 / 480），确保 prefetch、load 与原图占位命中同一个 Key。 */
    internal fun normalizeThumbEdge(edge: Int): Int = when {
        edge <= 160 -> 160
        edge <= 360 -> 320
        else -> 480
    }

    /** 取出指定 URL 当前内存里已就绪的最佳缩略图（供大图预览 0ms 承接占位）。 */
    fun cachedThumbnail(rawUrl: String): Bitmap? {
        if (rawUrl.isBlank()) return null
        val normRaw = normalizeRawAssetKey(rawUrl)
        synchronized(bestThumbKeyByRaw) {
            bestThumbKeyByRaw[normRaw]?.let { key ->
                thumbMemory.get(key)?.let { return it }
            }
        }
        for (edge in intArrayOf(480, 320, 160)) {
            val url = imageUrl(rawUrl, original = false, crop = false, thumbEdge = edge, allowAnimatedGif = false)
                ?: continue
            thumbMemory.get(Key(url, edge, original = false, gif = false, local = false))?.let { return it }
        }
        return null
    }

    private fun enqueuePrefetchKey(appContext: Context, key: Key, priority: Int) {
        if (memory(key).get(key) != null) return
        if (flights.containsKey(key) || prefetches.containsKey(key)) return
        if (thumbnails.queue.size >= MAX_PENDING_THUMB_TASKS) return
        val executor = when {
            key.original -> originals
            else -> thumbnails
        }
        val flight = Flight(key, executor, priority)
        prefetches[key] = flight
        val rawWork = Runnable {
            val result = try {
                if (flight.cancelled) null else loadResult(appContext, flight)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            main.post {
                if (prefetches[key] === flight) prefetches.remove(key)
                if (result is Result.Still && result.bitmap.allocationByteCount <= memory(key).maxSize()) {
                    memory(key).put(key, result.bitmap)
                    rememberThumbKey(key)
                }
                if (flights[key] === flight) {
                    finish(flight, result)
                }
            }
        }
        flight.work = if (executor === thumbnails) PrioritizedTask(priority, action = rawWork) else rawWork
        try {
            executor.execute(flight.work)
        } catch (_: RejectedExecutionException) {
            prefetches.remove(key)
        }
    }

    private fun cancelPrefetchKeys(keys: List<Key>) {
        for (key in keys) {
            val flight = prefetches[key] ?: continue
            // 已被真实 View 订阅提升的 flight 不能取消
            if (flight.targets.isEmpty() && flights[key] !== flight) {
                prefetches.remove(key)
                flight.cancel()
            }
        }
    }

    fun load(
        image: ImageView,
        rawUrl: String,
        original: Boolean = false,
        cropThumb: Boolean = true,
        animate: Boolean = true,
        thumbEdge: Int = THUMB_EDGE,
        priority: Int = PRIORITY_NORMAL,
        allowAnimatedGif: Boolean = animate,
        preservePlaceholder: Boolean = false,
        onProgress: ((Int) -> Unit)? = null,
        onFailure: ((ImageView) -> Unit)? = null
    ) {
        if (rawUrl.isBlank()) {
            clear(image)
            return
        }
        val effectiveEdge = if (original) ORIGINAL_EDGE else normalizeThumbEdge(thumbEdge)
        val source = imageUrl(rawUrl, original, cropThumb, thumbEdge = effectiveEdge, allowAnimatedGif = allowAnimatedGif)
        if (source == null) {
            clear(image)
            return
        }
        val gif = allowAnimatedGif && source.toHttpUrlOrNull()?.encodedPath?.substringBefore('@')
            ?.endsWith(".gif", ignoreCase = true) == true
        val key = Key(source, effectiveEdge, original, gif, local = false)
        val old = bindingFor(image)
        if (old != null && old.key == key && old.rawUrl == rawUrl && old.cropThumb == cropThumb) {
            old.animate = animate
            old.priority = priority
            old.onProgress = onProgress
            old.onFailure = onFailure
            val animation = old.displayed as? Animatable
            if (animation != null) {
                onProgress?.invoke(100)
                watchAnimation(old, image, animation)
            } else if (old.displayed == null || old.displayed !== image.drawable) {
                request(old)
            } else {
                onProgress?.invoke(100)
            }
            return
        }
        val bitmapHit = memory(key).get(key)
        val binding = bind(image, key, rawUrl, cropThumb, animate, priority, onFailure, onProgress, preservePlaceholder)
        if (bitmapHit == null && original && !gif && !preservePlaceholder) {
            cachedThumbnail(rawUrl)?.let(image::setImageBitmap)
        }
        request(binding)
    }

    fun loadLocal(image: ImageView, path: String, edge: Int, onFailure: ((ImageView) -> Unit)? = null) {
        val key = Key(path, edge.coerceIn(1, ORIGINAL_EDGE), edge > THUMB_EDGE, gif = false, local = true)
        val binding = bind(image, key, path, crop = false, animate = false, priority = PRIORITY_NORMAL, onFailure = onFailure)
        request(binding)
    }

    fun clear(image: ImageView) {
        clearInternal(image, preserveDrawable = false)
    }

    private fun clearInternal(image: ImageView, preserveDrawable: Boolean) {
        val binding = bindingFor(image)
        if (binding != null) {
            image.setTag(R.id.cover_request_binding, null)
            unsubscribe(binding)
            binding.watcher?.close()
            binding.onFailure = null
            binding.onProgress = null
            image.removeOnAttachStateChangeListener(attachListener)
        }
        image.animate().cancel()
        image.alpha = 1f
        if (!preserveDrawable) image.setImageDrawable(null)
    }

    private fun bind(
        image: ImageView,
        key: Key,
        rawUrl: String,
        crop: Boolean,
        animate: Boolean,
        priority: Int = PRIORITY_NORMAL,
        onFailure: ((ImageView) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null,
        preservePlaceholder: Boolean = false
    ): Binding {
        clearInternal(image, preserveDrawable = preservePlaceholder)
        if (!trimRegistered) {
            trimRegistered = true
            image.context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                override fun onLowMemory() {
                    thumbMemory.evictAll()
                    originalMemory.evictAll()
                }
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        originalMemory.evictAll()
                        thumbMemory.trimToSize(thumbMemory.maxSize() / 2)
                    }
                    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    ) onLowMemory()
                }
            })
        }
        return Binding(image, key, rawUrl, crop, animate, priority, onFailure, onProgress).also {
            image.setTag(R.id.cover_request_binding, it)
            image.addOnAttachStateChangeListener(attachListener)
        }
    }

    private fun bindingFor(image: ImageView): Binding? = image.getTag(R.id.cover_request_binding) as? Binding

    private fun request(binding: Binding) {
        val image = binding.image.get() ?: return
        if (binding.flight != null || binding.animationWork != null || binding.retry != null ||
            SystemClock.uptimeMillis() < binding.retryAt
        ) return
        memory(binding.key).get(binding.key)?.let { bitmap ->
            binding.viewport?.remove(binding)
            showBitmap(binding, image, bitmap, fade = false)
            return
        }
        if (!image.isAttachedToWindow) return
        // 高优先级图片（如个人头像）不受首页滚动裁剪延迟影响，直接入队执行
        if (binding.priority < PRIORITY_HIGH && !canRequest(binding, image)) {
            val observer = image.viewTreeObserver
            if (observer.isAlive && binding.viewport == null) {
                viewports.getOrPut(observer) { Viewport(observer) }.add(binding)
            }
            return
        }
        binding.viewport?.remove(binding)
        (flights[binding.key] ?: prefetches.remove(binding.key))?.let { flight ->
            flights[binding.key] = flight
            flight.addTarget(binding)
            binding.flight = flight
            // 已有低优先级预取任务尚未开始执行时，立即提升其优先级并重排队列
            if (binding.priority > flight.priority && flight.executor === thumbnails) {
                flight.priority = binding.priority
                val oldWork = flight.work
                if (thumbnails.remove(oldWork)) {
                    val upgraded = PrioritizedTask(binding.priority, action = oldWork)
                    flight.work = upgraded
                    try {
                        thumbnails.execute(upgraded)
                    } catch (_: RejectedExecutionException) {
                        // 回退无需额外处理
                    }
                }
            }
            if (flight.lastPercent >= 0) binding.onProgress?.invoke(flight.lastPercent)
            else if (binding.onProgress != null && !isDiskCached(image.context.applicationContext, binding.key)) {
                binding.onProgress?.invoke(0)
            }
            return
        }
        val context = image.context.applicationContext
        val diskHit = if (binding.key.local || binding.onProgress != null) {
            isDiskCached(context, binding.key)
        } else {
            false
        }
        // 磁盘缓存已命中或本地解码时不在一开始发 0%，避免已缓存图片闪一下加载框
        if (!diskHit && binding.onProgress != null) {
            binding.onProgress?.invoke(0)
        }
        // 队列过满时淘汰最低优先级纯预取任务，绝不拒绝当前可视区请求
        if (thumbnails.queue.size >= MAX_PENDING_THUMB_TASKS) {
            evictLowestPrefetch()
        }
        val executor = when {
            diskHit -> diskDecodePool
            binding.key.original || binding.key.gif -> originals
            else -> thumbnails
        }
        val flight = Flight(binding.key, executor, binding.priority)
        binding.flight = flight
        flight.addTarget(binding)
        flights[flight.key] = flight
        val rawWork = Runnable {
            val result = try {
                if (flight.cancelled) null else loadResult(context, flight)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            main.post { finish(flight, result) }
        }
        flight.work = if (executor === thumbnails) PrioritizedTask(binding.priority, action = rawWork) else rawWork
        try {
            flight.executor.execute(flight.work)
        } catch (_: RejectedExecutionException) {
            flights.remove(binding.key)
            binding.flight = null
            retryWhenCapacityReturns(binding)
        }
    }

    private fun evictLowestPrefetch() {
        val victim = prefetches.entries.firstOrNull { (_, f) -> f.targets.isEmpty() } ?: return
        prefetches.remove(victim.key)
        victim.value.cancel()
    }

    private fun isDiskCached(context: Context, key: Key): Boolean {
        if (key.local) return true
        val cache = if (key.gif) animationDisk else thumbDisk
        val maxBytes = if (key.gif) 12L * MIB else if (key.original) 24L * MIB else 2L * MIB
        val dir = File(context.cacheDir, cache.directory)
        val file = File(dir, sha256(key.source) + if (key.gif) ".gif" else ".img")
        return file.isFile && file.length() in 1L..maxBytes
    }

    /** 若原图已落盘则返回磁盘缓存文件（供长图分块区域渲染使用，避免整张超长图 GPU 重绘卡顿）。 */
    fun cachedOriginalFile(context: Context, rawUrl: String): File? {
        if (rawUrl.isBlank()) return null
        val source = imageUrl(rawUrl, original = true, crop = false, thumbEdge = ORIGINAL_EDGE, allowAnimatedGif = true)
            ?: return null
        val gif = source.toHttpUrlOrNull()?.encodedPath?.substringBefore('@')
            ?.endsWith(".gif", ignoreCase = true) == true
        if (gif) return null
        val dir = File(context.applicationContext.cacheDir, thumbDisk.directory)
        val file = File(dir, sha256(source) + ".img")
        return if (file.isFile && file.length() in 1L..(24L * MIB)) file else null
    }

    private fun canRequest(binding: Binding, image: ImageView): Boolean {
        if (!image.isShown || image.windowVisibility != View.VISIBLE) return false
        if (binding.key.original) return true
        // 优先直接判断全局可见；在 ScrollView 内（如评论区）额外允许向下 0.75 屏、向上 0.25 屏的受控提前加载，
        // 避免滑进屏幕才开始下载，同时确保横向隐藏在另一 Tab 的页面不会提前全量拉图。
        if (image.getGlobalVisibleRect(visibleBounds)) return true
        if (isInsideViewportParent(image)) return true
        return isInsideScrollLookahead(image)
    }

    private fun isInsideScrollLookahead(image: ImageView): Boolean {
        val sv = findVerticalScrollView(image) ?: return false
        if (!sv.isShown || sv.windowVisibility != View.VISIBLE) return false
        // ScrollView 本身必须至少部分在当前屏幕可视区（如 HorizontalPagerView 正在切入或已停留在该 Tab）
        if (!sv.getGlobalVisibleRect(scrollViewBounds)) return false
        val viewportH = sv.height
        if (viewportH <= 0 || image.height <= 0) return false
        scrollChildBounds.set(0, 0, image.width, image.height)
        try {
            sv.offsetDescendantRectToMyCoords(image, scrollChildBounds)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val windowTop = sv.scrollY - (viewportH * SCROLL_RETAIN_BEHIND_RATIO).toInt()
        val windowBottom = sv.scrollY + viewportH + (viewportH * SCROLL_LOOKAHEAD_RATIO).toInt()
        return scrollChildBounds.bottom >= windowTop && scrollChildBounds.top <= windowBottom
    }

    private fun findVerticalScrollView(view: View): android.widget.ScrollView? {
        var current: android.view.ViewParent? = view.parent
        while (current is View) {
            if (current is android.widget.ScrollView) return current
            current = current.parent
        }
        return null
    }

    private fun isInsideViewportParent(image: ImageView): Boolean {
        val grid = findScrollContainer(image) ?: return false
        return grid.isShown && grid.windowVisibility == View.VISIBLE
    }

    private fun findScrollContainer(view: View): android.widget.AbsListView? {
        var current: android.view.ViewParent? = view.parent
        while (current is View) {
            if (current is android.widget.AbsListView) return current
            current = current.parent
        }
        return null
    }

    private class Viewport(private val observer: ViewTreeObserver) {
        private val waiting = LinkedHashSet<Binding>()
        private val dispatch = Runnable {
            val ready = waiting.filter { binding ->
                val image = binding.image.get()
                image == null || canRequest(binding, image)
            }
            for (binding in ready) {
                if (binding.viewport === this) {
                    remove(binding)
                    request(binding)
                }
            }
        }
        private val layout = ViewTreeObserver.OnGlobalLayoutListener { schedule() }
        private val scroll = ViewTreeObserver.OnScrollChangedListener { schedule() }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener { schedule() }

        init {
            observer.addOnGlobalLayoutListener(layout)
            observer.addOnScrollChangedListener(scroll)
            observer.addOnWindowFocusChangeListener(focus)
        }

        fun add(binding: Binding) {
            binding.viewport = this
            waiting.add(binding)
            schedule()
        }

        fun remove(binding: Binding) {
            waiting.remove(binding)
            binding.viewport = null
            if (waiting.isNotEmpty()) return
            main.removeCallbacks(dispatch)
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(layout)
                observer.removeOnScrollChangedListener(scroll)
                observer.removeOnWindowFocusChangeListener(focus)
            }
            viewports.remove(observer)
        }

        private fun schedule() {
            main.removeCallbacks(dispatch)
            main.post(dispatch)
        }
    }

    private fun unsubscribe(binding: Binding) {
        binding.viewport?.remove(binding)
        val flight = binding.flight
        binding.flight = null
        if (flight != null && flight.removeTarget(binding) && flight.targets.isEmpty()) {
            flights.remove(flight.key)
            prefetches.remove(flight.key)
            flight.cancel()
        }
        binding.animationWork?.let(originals::remove)
        binding.animationWork = null
        binding.retry?.let(main::removeCallbacks)
        binding.retry = null
    }

    private fun finish(flight: Flight, result: Result?) {
        if (flights[flight.key] !== flight) return
        flights.remove(flight.key)
        if (result is Result.Still && result.bitmap.allocationByteCount <= memory(flight.key).maxSize()) {
            memory(flight.key).put(flight.key, result.bitmap)
            if (!flight.key.original && !flight.key.local) rememberThumbKey(flight.key)
        }
        for (binding in flight.targets) {
            binding.flight = null
            val image = binding.image.get() ?: continue
            if (bindingFor(image) !== binding) continue
            when (result) {
                is Result.Still -> showBitmap(binding, image, result.bitmap, fade = true)
                is Result.Animated -> prepareAnimation(binding, result.file)
                null -> failed(binding)
            }
        }
        flight.targets.clear()
    }

    private fun rememberThumbKey(key: Key) {
        if (key.original || key.local || key.gif) return
        val normRaw = normalizeRawAssetKey(key.source)
        synchronized(bestThumbKeyByRaw) {
            val prev = bestThumbKeyByRaw[normRaw]
            if (prev == null || key.edge >= prev.edge || thumbMemory.get(prev) == null) {
                bestThumbKeyByRaw[normRaw] = key
            }
        }
    }

    private fun normalizeRawAssetKey(raw: String): String {
        val normalized = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
            else -> raw
        }
        val parsed = normalized.toHttpUrlOrNull() ?: return normalized.substringBefore('@')
        val isBiliCdn = cdnDomains.any { parsed.host == it || parsed.host.endsWith(".$it") }
        return if (isBiliCdn) {
            parsed.newBuilder().encodedPath(parsed.encodedPath.substringBefore('@')).build().toString()
        } else {
            normalized
        }
    }

    private fun showBitmap(binding: Binding, image: ImageView, bitmap: Bitmap, fade: Boolean) {
        image.setImageBitmap(bitmap)
        binding.displayed = image.drawable
        binding.onProgress?.invoke(100)
        binding.onProgress = null
        binding.onFailure = null
        if (fade) fadeIn(binding, image)
    }

    private fun prepareAnimation(binding: Binding, file: File) {
        val task = object : Runnable {
            override fun run() {
                val drawable = try {
                    decodeAnimation(file, binding.key.edge)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                val still = if (drawable == null) decodeBitmap(file, binding.key.edge) else null
                main.post {
                    if (binding.animationWork !== this) return@post
                    binding.animationWork = null
                    val image = binding.image.get() ?: return@post
                    if (bindingFor(image) !== binding) return@post
                    if (drawable != null) {
                        image.setImageDrawable(drawable)
                        binding.displayed = drawable
                        binding.onProgress?.invoke(100)
                        binding.onProgress = null
                        binding.onFailure = null
                        watchAnimation(binding, image, drawable as Animatable)
                        fadeIn(binding, image)
                    } else if (still != null) {
                        showBitmap(binding, image, still, fade = true)
                    } else {
                        failed(binding)
                    }
                }
            }
        }
        binding.animationWork = task
        try {
            originals.execute(task)
        } catch (_: RejectedExecutionException) {
            binding.animationWork = null
            retryWhenCapacityReturns(binding)
        }
    }

    private fun retryWhenCapacityReturns(binding: Binding) {
        val retry = Runnable {
            binding.retry = null
            val target = binding.image.get() ?: return@Runnable
            if (bindingFor(target) === binding && target.isAttachedToWindow) request(binding)
        }
        binding.retry = retry
        main.postDelayed(retry, 250L)
    }

    private fun watchAnimation(binding: Binding, image: ImageView, animation: Animatable) {
        if (!binding.animate || !image.isAttachedToWindow) {
            binding.watcher?.close()
            binding.watcher = null
            return
        }
        if (binding.watcher == null) binding.watcher = AnimationVisibility(image, animation)
        binding.watcher?.sync()
    }

    private class AnimationVisibility(image: ImageView, private val animation: Animatable) {
        private val host = WeakReference(image)
        private val observer = image.viewTreeObserver
        private val visible = Rect()
        private val draw = ViewTreeObserver.OnPreDrawListener { sync(); true }
        private val scroll = ViewTreeObserver.OnScrollChangedListener { sync() }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener { sync() }

        init {
            observer.addOnPreDrawListener(draw)
            observer.addOnScrollChangedListener(scroll)
            observer.addOnWindowFocusChangeListener(focus)
        }

        fun sync() {
            val view = host.get()
            val shouldPlay = view != null && view.isShown && view.windowVisibility == View.VISIBLE &&
                view.hasWindowFocus() && view.getGlobalVisibleRect(visible)
            if (shouldPlay && !animation.isRunning) animation.start()
            if (!shouldPlay && animation.isRunning) animation.stop()
        }

        fun close() {
            animation.stop()
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(draw)
                observer.removeOnScrollChangedListener(scroll)
                observer.removeOnWindowFocusChangeListener(focus)
            }
        }
    }

    private fun fadeIn(binding: Binding, image: ImageView) {
        if (!binding.animate || Build.VERSION.SDK_INT >= 26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        image.alpha = 0.72f
        image.animate().alpha(1f).setDuration(130L).start()
    }

    private fun failed(binding: Binding) {
        binding.retryAt = SystemClock.uptimeMillis() + 800L
        val listener = binding.onFailure
        binding.onFailure = null
        binding.image.get()?.let { listener?.invoke(it) }
    }

    private fun loadResult(context: Context, flight: Flight): Result? {
        val key = flight.key
        if (key.local) return decodeBitmap(File(key.source), key.edge)?.let { Result.Still(it) }
        if (key.gif) return cachedFile(context, flight, key.source, animationDisk, 12L * MIB)?.let { Result.Animated(it) }
        val maxBytes = if (key.original) 24L * MIB else 2L * MIB
        val image = cachedFile(context, flight, key.source, thumbDisk, maxBytes)
        val bitmap = image?.let { decodeBitmap(it, key.edge) }
        if (bitmap != null) return Result.Still(bitmap)
        if (image != null) thumbDisk.discard(image)
        if (!key.original || flight.cancelled) return null
        // Fall back across both CDN high-res webp (handles giant >24MiB originals) and
        // original URL without '@' when CDN rejects a format or exceeds original cap.
        val fallback = imageUrl(key.source, original = false, crop = false, large = true)
        if (fallback != null && fallback != key.source) {
            val file = cachedFile(context, flight, fallback, thumbDisk, maxBytes)
            val fallbackBitmap = file?.let { decodeBitmap(it, key.edge) }
            if (fallbackBitmap != null) return Result.Still(fallbackBitmap)
        }
        return null
    }

    private fun reportProgress(flight: Flight, percent: Int) {
        // 用 volatile 计数在后台线程无锁判定，彻底消除跨线程遍历 LinkedHashSet 的竞态风险
        if (flight.progressListenerCount <= 0) return
        val clamped = percent.coerceIn(0, 99)
        if (clamped == flight.lastPercent) return
        flight.lastPercent = clamped
        main.post {
            if (flight.cancelled || flight.progressListenerCount <= 0) return@post
            for (target in flight.targets) {
                if (target.flight === flight) target.onProgress?.invoke(clamped)
            }
        }
    }

    private fun cachedFile(context: Context, flight: Flight, url: String, cache: DiskCache, maxBytes: Long): File? {
        if (flight.cancelled) return null
        val dir = File(context.cacheDir, cache.directory)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
        val file = File(dir, sha256(url) + if (flight.key.gif) ".gif" else ".img")
        val now = System.currentTimeMillis()
        val isHit = file.isFile && file.length() in 1L..maxBytes
        if (isHit) {
            if (now - file.lastModified() > TOUCH_INTERVAL_MS) file.setLastModified(now)
            // 磁盘已命中时不发 99%，直接快速解码返回，避免切换图片时出现一瞬间 loading 框闪烁
            return file
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", NETWORK_USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        val call = (if (flight.key.original || flight.key.gif) largeHttp else http).newCall(request)
        flight.call = call
        if (flight.cancelled) {
            call.cancel()
            return null
        }
        val temporary = File.createTempFile("image-", ".tmp", dir)
        try {
            call.execute().use { response ->
                val body = response.body ?: return null
                val contentLength = body.contentLength()
                if (!response.isSuccessful || contentLength > maxBytes) return null
                val total = body.byteStream().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var written = 0L
                        while (!flight.cancelled) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            written += size
                            if (written > maxBytes) return null
                            output.write(buffer, 0, size)
                            if (contentLength > 0L) {
                                reportProgress(flight, ((written * 99L) / contentLength).toInt())
                            } else {
                                // Chunked transfer without Content-Length: gently ramp up 5..95%.
                                val estimated = (5 + (written / (32 * 1024L)).coerceAtMost(90)).toInt()
                                reportProgress(flight, estimated)
                            }
                        }
                        written
                    }
                }
                if (flight.cancelled || total == 0L) return null
            }
            return if (cache.commit(temporary, file)) file else null
        } finally {
            flight.call = null
            temporary.delete()
        }
    }

    private class DiskCache(val directory: String, private val limit: Long) {
        private var bytes = -1L
        // 高低水位清理：超过上限时回落到 78% 水位，避免满容量后每写入 1 张图都全目录扫描排序
        private val lowWatermark = (limit * 78L) / 100L

        fun commit(temporary: File, file: File): Boolean = synchronized(diskLock) {
            val previous = file.length()
            if (!temporary.renameTo(file)) return false
            if (bytes >= 0) bytes = (bytes - previous).coerceAtLeast(0L) + file.length()
            if (bytes in 0..limit) return true
            val files = file.parentFile?.listFiles { entry -> entry.isFile && !entry.name.endsWith(".tmp") }.orEmpty()
            bytes = files.sumOf { it.length() }
            if (bytes > limit) {
                for (entry in files.sortedBy { it.lastModified() }) {
                    if (bytes <= lowWatermark) break
                    if (entry == file) continue
                    val length = entry.length()
                    if (entry.delete()) bytes -= length
                }
            }
            true
        }

        fun discard(file: File) = synchronized(diskLock) {
            val length = file.length()
            if (file.delete() && bytes >= 0) bytes = (bytes - length).coerceAtLeast(0L)
        }
    }

    private fun decodeBitmap(file: File, edge: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val outW = options.outWidth
        val outH = options.outHeight
        if (outW <= 0 || outH <= 0) return null
        val isOriginalLongImage = edge >= ORIGINAL_EDGE &&
            outH.toFloat() / outW.toFloat() >= LONG_IMAGE_MIN_ASPECT
        val sample = computeImageDecodeSampleSize(
            outWidth = outW,
            outHeight = outH,
            edge = edge,
            isOriginalLongImage = isOriginalLongImage,
            maxLongWidth = MAX_LONG_IMAGE_WIDTH,
            maxLongHeight = MAX_LONG_IMAGE_HEIGHT
        )
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun decodeAnimation(file: File, edge: Int): Drawable? {
        if (Build.VERSION.SDK_INT >= 28) {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val largest = maxOf(width, height)
                if (largest > edge) {
                    decoder.setTargetSize((width.toLong() * edge / largest).toInt().coerceAtLeast(1),
                        (height.toLong() * edge / largest).toInt().coerceAtLeast(1))
                }
            }
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                return drawable
            }
            return null
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 ||
            options.outWidth.toLong() * options.outHeight > 4L * MIB
        ) return null
        return Movie.decodeFile(file.path)?.let(::MovieDrawable)
    }

    @Suppress("DEPRECATION")
    private class MovieDrawable(private val movie: Movie) : Drawable(), Animatable {
        private var running = false
        private var elapsed = 0L
        private var startedAt = 0L
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val frame = Runnable { invalidateSelf() }
        override fun isRunning(): Boolean = running
        override fun start() {
            if (running) return
            running = true
            startedAt = SystemClock.uptimeMillis()
            invalidateSelf()
        }
        override fun stop() {
            if (!running) return
            elapsed += SystemClock.uptimeMillis() - startedAt
            running = false
            unscheduleSelf(frame)
        }
        override fun draw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val position = elapsed + if (running) now - startedAt else 0L
            movie.setTime((position % movie.duration().coerceAtLeast(1)).toInt())
            movie.draw(canvas, 0f, 0f, paint)
            if (running) scheduleSelf(frame, now + 28L)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = movie.width()
        override fun getIntrinsicHeight(): Int = movie.height()
    }

    private fun imageUrl(
        raw: String,
        original: Boolean,
        crop: Boolean,
        large: Boolean = false,
        thumbEdge: Int = THUMB_EDGE,
        allowAnimatedGif: Boolean = true
    ): String? {
        val normalized = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
            else -> raw
        }
        val url = normalized.toHttpUrlOrNull() ?: return null
        val path = url.encodedPath
        val isBiliCdn = cdnDomains.any { url.host == it || url.host.endsWith(".$it") }
        if (original) return url.toString()
        if (!isBiliCdn) return url.toString()
        if ('@' in path) return url.toString()
        val basePath = path.substringBefore('@')
        if (basePath.endsWith(".gif", true) && allowAnimatedGif) return url.toString()
        val suffix = when {
            large -> "@1080w_1080h.webp"
            crop && thumbEdge <= 160 -> "@108w_108h_1c.webp"
            crop -> "@480w_270h_1c.webp"
            thumbEdge <= 160 -> "@160w_160h.webp"
            thumbEdge <= 360 -> "@320w_320h.webp"
            else -> "@480w_480h.webp"
        }
        return url.newBuilder().encodedPath(basePath + suffix).build().toString()
    }

    private fun sha256(value: String): String {
        val hex = "0123456789abcdef"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val number = byte.toInt() and 0xff
                append(hex[number ushr 4]).append(hex[number and 15])
            }
        }
    }

    private fun memory(key: Key) = if (key.original) originalMemory else thumbMemory

    private fun bitmapCache(size: Int) = object : LruCache<Key, Bitmap>(size) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    private fun workers(count: Int, capacity: Int) = ThreadPoolExecutor(
        count, count, 20L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(capacity)
    ).apply { allowCoreThreadTimeOut(true) }

    private fun priorityWorkers(count: Int) = ThreadPoolExecutor(
        count, count, 20L, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>(64)
    ).apply { allowCoreThreadTimeOut(true) }

    /** 启动预热直通高优先级连接池，限制 128 个最大排队等待数或按需扩展由 thumbnails 调度。 */
}

internal fun computeImageDecodeSampleSize(
    outWidth: Int,
    outHeight: Int,
    edge: Int,
    isOriginalLongImage: Boolean,
    maxLongWidth: Int = 1440,
    maxLongHeight: Int = 8192
): Int {
    if (outWidth <= 0 || outHeight <= 0) return 1
    var sample = 1
    if (isOriginalLongImage) {
        // 超长竖图不能拿总高度除 2048 来降采样，否则宽度会缩成 100~300px 的糊图；
        // 改按宽度不超过 1440px 且总高不超过安全纹理上限（8192px）计算采样率。
        while (outWidth / sample > maxLongWidth || outHeight / sample > maxLongHeight) {
            sample *= 2
        }
    } else {
        val largest = maxOf(outWidth, outHeight)
        while (largest / sample > edge) sample *= 2
    }
    return sample
}
