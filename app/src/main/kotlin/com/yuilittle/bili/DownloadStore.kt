package com.yuilittle.bili

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 本地下载队列与断点续传。
 *
 * 视频采用 B 站 DASH 流（fnval=4048）：视频轨(.m4s)与音频轨(.m4s)各自按
 * initialization/index 区间分三段下载并拼回原文件布局，随后生成指向本地文件的
 * MPD，由 Media3 的 DashMediaSource 直接本地播放（音画同步、可拖动）。
 * 纯音频任务只下音频轨；单文件流(durl)任务整体下载。封面作为每个视频任务的第一步。
 */
object DownloadManager {

    const val TYPE_VIDEO = 0
    const val TYPE_AUDIO = 1
    const val TYPE_COVER = 2
    const val TYPE_COMMENT = 3

    const val STATE_QUEUED = 0
    const val STATE_RUNNING = 1
    const val STATE_PAUSED = 2
    const val STATE_COMPLETED = 3
    const val STATE_FAILED = 4

    /** 正在“进行中”Tab 展示的状态集合（排队/下载/暂停/失败）。 */
    val ACTIVE_STATES = setOf(STATE_QUEUED, STATE_RUNNING, STATE_PAUSED, STATE_FAILED)

    enum class SeasonSortOrder { DEFAULT, OLDEST_FIRST, NEWEST_FIRST }

    enum class LocalPlayMode {
        SEASON_SEQUENCE,
        SHUFFLE,
        LIST_LOOP,
        SINGLE_LOOP;

        fun label(): String = when (this) {
            SEASON_SEQUENCE -> "顺序播放"
            SHUFFLE -> "随机播放"
            LIST_LOOP -> "列表循环"
            SINGLE_LOOP -> "单集循环"
        }

        fun nextMode(): LocalPlayMode = when (this) {
            SEASON_SEQUENCE -> SHUFFLE
            SHUFFLE -> LIST_LOOP
            LIST_LOOP -> SINGLE_LOOP
            SINGLE_LOOP -> SEASON_SEQUENCE
        }

        /** 本地合集播放页仅提供顺序播放、单集循环与随机播放三档。 */
        fun nextSeasonMode(): LocalPlayMode = when (this) {
            SEASON_SEQUENCE -> SINGLE_LOOP
            SINGLE_LOOP -> SHUFFLE
            else -> SEASON_SEQUENCE
        }
    }

    data class StorageBreakdown(
        val deviceTotalBytes: Long,
        val deviceFreeBytes: Long,
        val deviceUsedBytes: Long,
        val downloadsBytes: Long,
        val appOtherBytes: Long,
        val appTotalBytes: Long,
        val otherUsedBytes: Long
    )

    class Task {
        var id: Long = 0L
        var type: Int = TYPE_VIDEO
        var bvid: String = ""
        var cid: Long = 0L
        var aid: Long = 0L
        var title: String = ""
        var owner: String = ""
        var coverUrl: String = ""
        var coverPath: String = ""
        /** 合集聚合元数据；空 groupId 表示旧任务/普通单视频。 */
        var groupId: String = ""
        var groupTitle: String = ""
        var groupCoverUrl: String = ""
        var episodeNo: Int = 0
        /** 分集或视频发布时间戳（秒），0 表示未知。 */
        var publishedAt: Long = 0L
        var quality: Int = 0
        var qualityLabel: String = ""
        var durationMs: Long = 0L
        @Volatile var state: Int = STATE_QUEUED
        @Volatile var bytesDone: Long = 0L
        @Volatile var bytesTotal: Long = 0L
        @Volatile var speedBytes: Long = 0L
        private var speedSampleAt = 0L
        private var speedSampleBytes = 0L

        internal fun sampleSpeed() {
            val now = SystemClock.elapsedRealtime()
            val bytes = bytesDone
            if (speedSampleAt == 0L) {
                speedSampleAt = now
                speedSampleBytes = bytes
                return
            }
            val elapsed = now - speedSampleAt
            if (elapsed >= 1000L) {
                speedBytes = (bytes - speedSampleBytes).coerceAtLeast(0L) * 1000L / elapsed
                speedSampleAt = now
                speedSampleBytes = bytes
            }
        }

        internal fun resetSpeed() {
            speedSampleAt = 0L
            speedSampleBytes = 0L
            speedBytes = 0L
        }

        var error: String = ""
        var createdAt: Long = 0L
        var finishedAt: Long = 0L
        /** 断点：DASH 下已写好的媒体段字节数；单文件流下已写文件长度。 */
        var resumeOffset: Long = 0L
        /** DASH 任务：本地 MPD 路径（存在即走本地 DashMediaSource）。 */
        var mpdPath: String = ""
        /** 纯音频任务：本地音频文件路径。 */
        var audioPath: String = ""
        /** 单文件流(durl)任务：各分片本地路径（多于1个时用 ConcatenatingMediaSource）。 */
        val singlePaths = CopyOnWriteArrayList<String>()
        val galleryExports = CopyOnWriteArrayList<String>()
        /** 下载中置 true；暂停/删除时置 true 让 worker 快速退出。 */
        @Volatile var cancelRequested: Boolean = false
        /** 是否正在被 worker 执行。 */
        @Volatile var running: Boolean = false
        @Volatile var activeCall: okhttp3.Call? = null

        fun playable(): Boolean {
            if (state != STATE_COMPLETED) return false
            return when {
                mpdPath.isNotBlank() -> File(mpdPath).exists()
                audioPath.isNotBlank() -> File(audioPath).exists()
                singlePaths.isNotEmpty() -> singlePaths.all { File(it).exists() }
                else -> false
            }
        }
    }

    private var appContext: Context? = null
    @Volatile private var initialized = false

    /** 相册导出（mp4 合并/MediaStore 写入）专用线程，避免阻塞下载 worker。 */
    private val galleryIo = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "YuiBiliGalleryExport").apply { isDaemon = true }
    }
    private val taskIo = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 15L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private var writePending = false
    private var writerScheduled = false

    private val lock = java.lang.Object()
    private val tasks = LinkedHashMap<Long, Task>()
    private val queue = LinkedHashSet<Long>()
    private val listeners = CopyOnWriteArrayList<(List<Task>) -> Unit>()
    private val main: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Handler(Looper.getMainLooper()) }
    private val deliveryPosted = AtomicBoolean()
    private val deliverUpdates = Runnable {
        deliveryPosted.set(false)
        if (listeners.isNotEmpty()) {
            val current = snapshot()
            listeners.forEach { it(current) }
        }
    }
    private val idSeq = AtomicLong(System.currentTimeMillis())
    private val workers = Array(3) { index ->
        Thread(::workerLoop, "YuiBiliDownloader-$index").apply { isDaemon = true }
    }
    private var workersStarted = false

    private val lastNotifyAt = AtomicLong(0L)
    private val galleryWork = AtomicInteger()
    private var downloadDir: File? = null
    private var notificationManager: NotificationManager? = null

    private const val NOTIFICATION_CHANNEL_ID = "yuibili_downloads"
    private const val NOTIFICATION_ID = 0x5942
    /** 下载目录（供 UI 统计占用、清理残留等）。 */
    fun downloadDir(): File? = downloadDir

    fun init(context: Context) {
        if (initialized) return
        val hasPendingTransfers = synchronized(lock) {
            if (initialized) return
            val application = context.applicationContext
            BiliSessionStore.init(application)
            appContext = application
            downloadDir = File(application.filesDir, "downloads").apply { mkdirs() }
            notificationManager = application.getSystemService(NotificationManager::class.java)
            createNotificationChannel()
            load()
            initialized = true
            queue.isNotEmpty()
        }
        if (hasPendingTransfers) {
            if (context !is DownloadService) startDownloadService(context)
            kick()
        }
    }

    // ── 对外 API ────────────────────────────────────────────────

    fun addListener(listener: (List<Task>) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (List<Task>) -> Unit) { listeners.remove(listener) }

    fun snapshot(): List<Task> = synchronized(lock) { tasks.values.toList() }

    fun task(id: Long): Task? = synchronized(lock) { tasks[id] }

    fun enqueueVideo(
        context: Context,
        item: VideoItem,
        quality: Int,
        label: String,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        if (item.charge) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_VIDEO && it.bvid == item.bvid && it.cid == item.cid &&
                    it.quality == quality && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_VIDEO
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                this.quality = quality; qualityLabel = label
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                publishedAt = item.publishedAt
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueAudio(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        if (item.charge) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_AUDIO && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_AUDIO
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title; owner = item.owner; coverUrl = item.cover
                qualityLabel = "仅音频"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                publishedAt = item.publishedAt
                durationMs = item.duration * 1000L
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    fun enqueueCover(
        context: Context,
        item: VideoItem,
        groupId: String = "",
        groupTitle: String = "",
        groupCoverUrl: String = "",
        episodeNo: Int = 0
    ) {
        init(context)
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.bvid == item.bvid && it.cid == item.cid && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                bvid = item.bvid; cid = item.cid; aid = item.aid
                title = item.title + "（封面）"; owner = item.owner; coverUrl = item.cover
                qualityLabel = "封面"
                this.groupId = groupId; this.groupTitle = groupTitle
                this.groupCoverUrl = groupCoverUrl; this.episodeNo = episodeNo
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 评论图片（评论区图片直链）下载任务。 */
    fun enqueueComment(context: Context, title: String, url: String, owner: String = "") {
        init(context)
        if (url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COMMENT && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COMMENT
                this.title = title.ifBlank { "评论图片" }; this.owner = owner
                coverUrl = url
                qualityLabel = "图片"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 按直链下载封面图片（合集封面等非主视频封面场景）。 */
    fun enqueueCoverUrl(context: Context, title: String, url: String) {
        init(context)
        if (url.isBlank()) return
        synchronized(lock) {
            val existing = tasks.values.firstOrNull {
                it.type == TYPE_COVER && it.coverUrl == url && it.state != STATE_COMPLETED
            }
            if (existing != null) return
            val t = Task().apply {
                id = idSeq.incrementAndGet()
                type = TYPE_COVER
                this.title = title.ifBlank { "封面" }
                coverUrl = url
                qualityLabel = "封面"
                state = STATE_QUEUED; createdAt = System.currentTimeMillis()
            }
            addTaskLocked(t)
        }
        notifyListeners()
        startDownloadService(context)
        kick()
    }

    /** 重命名任务标题（不改本地文件名，仅展示用）。 */
    fun rename(id: Long, title: String) {
        val t = task(id) ?: return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(t) { t.title = name }
        save()
        notifyListeners()
    }

    /** 重命名合集标题：该 groupId 下所有任务的 groupTitle 一起改（各自分集标题不动）。 */
    fun renameGroup(groupId: String, title: String) {
        if (groupId.isBlank()) return
        val name = title.trim()
        if (name.isBlank()) return
        synchronized(lock) {
            var changed = false
            tasks.values.filter { it.groupId == groupId }.forEach { t ->
                synchronized(t) { t.groupTitle = name }
                changed = true
            }
            if (changed) saveLocked()
        }
        notifyListeners()
    }

    fun pause(id: Long) {
        var callToCancel: okhttp3.Call? = null
        synchronized(lock) {
            val task = tasks[id] ?: return
            synchronized(task) {
                if (task.state != STATE_RUNNING && task.state != STATE_QUEUED) return
                task.cancelRequested = true
                callToCancel = task.activeCall
                if (task.state == STATE_QUEUED) {
                    task.state = STATE_PAUSED
                    queue.remove(id)
                }
                saveLocked()
            }
        }
        callToCancel?.cancel()
        notifyListeners()
    }

    fun resume(id: Long) = requeue(id, STATE_PAUSED)

    fun retry(id: Long) = requeue(id, STATE_FAILED)

    private fun requeue(id: Long, previousState: Int) {
        synchronized(lock) {
            val task = tasks[id] ?: return
            synchronized(task) {
                if (task.state != previousState) return
                task.state = STATE_QUEUED
                task.cancelRequested = false
                task.error = ""
            }
            queue.add(id)
            saveLocked()
        }
        notifyListeners()
        appContext?.let(::startDownloadService)
        kick()
    }

    fun delete(id: Long) = deleteMany(listOf(id))

    fun deleteMany(ids: List<Long>) {
        val idleTasks = ArrayList<Task>()
        val callsToCancel = ArrayList<okhttp3.Call>()
        synchronized(lock) {
            var changed = false
            for (id in ids) {
                val task = tasks.remove(id) ?: continue
                queue.remove(id)
                synchronized(task) {
                    task.cancelRequested = true
                    task.activeCall?.let(callsToCancel::add)
                    if (!task.running) idleTasks.add(task)
                }
                changed = true
            }
            if (!changed) return
            saveLocked()
            if (idleTasks.isNotEmpty()) cleanRemovedTasks(idleTasks)
        }
        callsToCancel.forEach { it.cancel() }
        notifyListeners()
    }

    private fun cleanRemovedTasks(removed: List<Task>) {
        galleryWork.incrementAndGet()
        taskIo.execute {
            galleryIo.execute {
                try {
                    val ids = removed.mapTo(HashSet()) { it.id }
                    downloadDir?.listFiles()?.forEach { file ->
                        val id = file.name.substringBefore('_').removeSuffix(".mpd").toLongOrNull()
                        if (id != null && id in ids) file.delete()
                    }
                    removed.forEach(::deleteGalleryExports)
                } finally {
                    galleryWork.decrementAndGet()
                    notifyListeners()
                }
            }
        }
    }

    /** 清空某个已完成/未完成任务在下载目录下的关联媒体文件但保留任务（暂未用）。 */
    fun computeDownloadProgress(bytesDone: Long, bytesTotal: Long): Float =
        if (bytesTotal > 0L) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

    /** 计算本软件真实物理磁盘占用与设备空间拆分（不含系统其它应用）。 */
    fun measureStorageBreakdown(context: Context): StorageBreakdown {
        init(context)
        val app = context.applicationContext
        val (total, used, free) = try {
            val dir = Environment.getDataDirectory()
            val stat = android.os.StatFs(dir.path)
            val block = stat.blockSizeLong
            val t = stat.blockCountLong * block
            val f = stat.availableBlocksLong * block
            Triple(t, (t - f).coerceAtLeast(0L), f)
        } catch (_: Throwable) {
            Triple(0L, 0L, 0L)
        }
        val dlDir = downloadDir
        refreshTaskDiskBytesMap()
        val downloadsBytes = computeDirectoryBytes(dlDir)
        val dataBytes = computeDirectoryBytes(File(app.applicationInfo.dataDir))
        val cacheBytes = computeDirectoryBytes(app.externalCacheDir)
        val extFilesBytes = computeDirectoryBytes(app.getExternalFilesDir(null))
        val apkBytes = listOfNotNull(app.applicationInfo.sourceDir)
            .plus(app.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
            .filter { it.isNotBlank() }
            .sumOf { p -> File(p).takeIf { it.exists() }?.length() ?: 0L }
        val rawAppTotal = (dataBytes + cacheBytes + extFilesBytes + apkBytes).coerceAtLeast(downloadsBytes)
        val appOtherBytes = (rawAppTotal - downloadsBytes).coerceAtLeast(0L)
        val otherUsedBytes = (used - rawAppTotal).coerceAtLeast(0L)
        return StorageBreakdown(
            deviceTotalBytes = total,
            deviceFreeBytes = free,
            deviceUsedBytes = used,
            downloadsBytes = downloadsBytes,
            appOtherBytes = appOtherBytes,
            appTotalBytes = rawAppTotal,
            otherUsedBytes = otherUsedBytes
        )
    }

    private fun computeDirectoryBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** 后台单次聚合扫描下载目录（taskId -> 实际磁盘字节），避免主线程逐卡 listFiles。 */
    @Volatile
    private var cachedTaskDiskMap: Map<Long, Long> = emptyMap()

    fun refreshTaskDiskBytesMap(): Map<Long, Long> {
        val dir = downloadDir
        if (dir == null || !dir.exists()) {
            cachedTaskDiskMap = emptyMap()
            return emptyMap()
        }
        val map = HashMap<Long, Long>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val id = file.name.substringBefore('_').removeSuffix(".mpd").toLongOrNull() ?: return@forEach
            map[id] = (map[id] ?: 0L) + file.length()
        }
        cachedTaskDiskMap = map
        return map
    }

    /** 读取单任务实际磁盘占用（O(1) 命中后台聚合缓存，零主线程目录遍历）。 */
    fun taskDiskBytes(task: Task): Long {
        cachedTaskDiskMap[task.id]?.takeIf { it > 0L }?.let { return it }
        return when {
            task.bytesTotal > 0L && task.state == STATE_COMPLETED -> task.bytesTotal
            task.bytesDone > 0L -> task.bytesDone
            else -> 0L
        }
    }

    /** 判断某批已完成任务所属合集/排序。 */
    fun sortEpisodes(episodes: List<UgcEpisode>, order: SeasonSortOrder): List<UgcEpisode> = when (order) {
        SeasonSortOrder.DEFAULT -> episodes
        SeasonSortOrder.OLDEST_FIRST -> episodes.withIndex()
            .sortedWith(
                compareBy<IndexedValue<UgcEpisode>> {
                    if (it.value.publishedAt > 0L) it.value.publishedAt else Long.MAX_VALUE
                }.thenBy { it.index }
            ).map { it.value }
        SeasonSortOrder.NEWEST_FIRST -> episodes.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<UgcEpisode>> {
                    if (it.value.publishedAt > 0L) it.value.publishedAt else Long.MIN_VALUE
                }.thenBy { it.index }
            ).map { it.value }
    }

    /** 本地已下载合集内分集排序。 */
    fun sortTasks(tasks: List<Task>, order: SeasonSortOrder): List<Task> = when (order) {
        SeasonSortOrder.DEFAULT -> tasks.sortedWith(
            compareBy<Task> { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
                .thenBy { it.createdAt }
        )
        SeasonSortOrder.OLDEST_FIRST -> tasks.sortedWith(
            compareBy<Task> {
                val ts = if (it.publishedAt > 0L) it.publishedAt else it.createdAt / 1000L
                ts
            }.thenBy { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
        )
        SeasonSortOrder.NEWEST_FIRST -> tasks.sortedWith(
            compareByDescending<Task> {
                val ts = if (it.publishedAt > 0L) it.publishedAt else it.createdAt / 1000L
                ts
            }.thenBy { if (it.episodeNo > 0) it.episodeNo else Int.MAX_VALUE }
        )
    }

    /** 离线合集下一集调度（支持顺序播放、随机播放、列表循环、单集循环）。 */
    fun resolveNextPlayableTaskId(
        playlist: List<Task>,
        currentTaskId: Long,
        mode: LocalPlayMode,
        randomIndexProvider: (Int) -> Int = { size -> kotlin.random.Random.nextInt(size) }
    ): Long? {
        if (playlist.isEmpty()) return null
        val curIdx = playlist.indexOfFirst { it.id == currentTaskId }
        if (curIdx < 0) return playlist.first().id
        return when (mode) {
            LocalPlayMode.SINGLE_LOOP -> playlist[curIdx].id
            LocalPlayMode.SEASON_SEQUENCE -> playlist.getOrNull(curIdx + 1)?.id
            LocalPlayMode.LIST_LOOP -> playlist[(curIdx + 1) % playlist.size].id
            LocalPlayMode.SHUFFLE -> {
                if (playlist.size == 1) playlist[0].id
                else {
                    val candidates = playlist.filter { it.id != currentTaskId }
                    val pick = randomIndexProvider(candidates.size).coerceIn(0, candidates.lastIndex)
                    candidates[pick].id
                }
            }
        }
    }

    /** 离线合集上一集调度（单集循环/顺序/列表循环退回上一集，随机也优先历史/上一项）。 */
    fun resolvePrevPlayableTaskId(
        playlist: List<Task>,
        currentTaskId: Long,
        mode: LocalPlayMode
    ): Long? {
        if (playlist.isEmpty()) return null
        val curIdx = playlist.indexOfFirst { it.id == currentTaskId }
        if (curIdx < 0) return playlist.first().id
        return when (mode) {
            LocalPlayMode.SINGLE_LOOP -> playlist[curIdx].id
            LocalPlayMode.SEASON_SEQUENCE -> if (curIdx > 0) playlist[curIdx - 1].id else null
            LocalPlayMode.LIST_LOOP, LocalPlayMode.SHUFFLE ->
                playlist[(curIdx - 1 + playlist.size) % playlist.size].id
        }
    }

    /** 查询指定任务同一合集+同类型的全部任务（用于离线播放页合集列表）。 */
    fun siblingTasksForPlayback(task: Task): List<Task> {
        if (task.groupId.isBlank()) return emptyList()
        val snap = snapshot()
        return sortTasks(
            snap.filter { it.groupId == task.groupId && it.type == task.type && it.playable() },
            SeasonSortOrder.DEFAULT
        )
    }

    /** 音乐播放器的随机下一曲解析：仅在多首时尽量避开当前曲目。 */
    fun mergeAudioPlaylist(currentIds: List<Long>, addedIds: List<Long>): List<Long> {
        val result = ArrayList<Long>(currentIds.size + addedIds.size)
        val seen = HashSet<Long>()
        for (id in currentIds) {
            if (id > 0L && seen.add(id)) result.add(id)
        }
        for (id in addedIds) {
            if (id > 0L && seen.add(id)) result.add(id)
        }
        return result
    }

    /** 基于任务 ID 列表过滤当前仍存在且可播的纯音频任务，并保持有序去重。 */
    fun resolveSavedAudioPlaylist(savedIds: List<Long>, allTasks: List<Task>): List<Task> {
        val byId = allTasks.associateBy { it.id }
        val result = ArrayList<Task>(savedIds.size)
        val seen = HashSet<Long>()
        for (id in savedIds) {
            if (!seen.add(id)) continue
            val t = byId[id] ?: continue
            if (t.type == TYPE_AUDIO && t.playable()) result.add(t)
        }
        return result
    }

    // ── 内部：队列调度 ──────────────────────────────────────────

    private fun addTaskLocked(t: Task) {
        tasks[t.id] = t
        queue.add(t.id)
        saveLocked()
    }

    private fun kick() {
        synchronized(lock) {
            if (!workersStarted) {
                workers.forEach { it.start() }
                workersStarted = true
            }
            lock.notifyAll()
        }
    }

    private fun workerLoop() {
        while (true) {
            val task = synchronized(lock) {
                var next = claimTaskLocked()
                while (next == null) {
                    lock.wait()
                    next = claimTaskLocked()
                }
                next
            }
            try {
                notifyListeners()
                execute(task)
            } finally {
                synchronized(lock) {
                    task.running = false
                    if (task.state != STATE_QUEUED) queue.remove(task.id)
                    if (queue.isNotEmpty()) lock.notifyAll()
                    if (tasks[task.id] !== task) cleanRemovedTasks(listOf(task))
                }
                notifyListeners()
            }
        }
    }

    private fun claimTaskLocked(): Task? {
        for (id in queue) {
            val task = tasks[id] ?: continue
            synchronized(task) {
                if (task.state == STATE_QUEUED && !task.running) {
                    task.cancelRequested = false
                    task.resetSpeed()
                    task.running = true
                    task.state = STATE_RUNNING
                    return task
                }
            }
        }
        return null
    }

    // ── 内部：执行 ──────────────────────────────────────────────

    private fun execute(t: Task) {
        val dir = downloadDir ?: run { fail(t, "存储不可用"); return }
        try {
            when (t.type) {
                TYPE_COVER -> runCover(t, dir)
                TYPE_AUDIO -> runAudio(t, dir)
                TYPE_COMMENT -> runComment(t, dir)
                else -> runVideo(t, dir)
            }
        } catch (c: CancelledException) {
            synchronized(t) {
                if (t.state == STATE_RUNNING) t.state = STATE_PAUSED
            }
            t.resetSpeed()
            t.cancelRequested = false
            save()
            notifyListeners()
        } catch (e: Exception) {
            fail(t, e.message?.takeIf { it.isNotBlank() } ?: "下载失败")
        }
    }

    private fun runCover(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "封面地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_cover.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runComment(t: Task, dir: File) {
        checkNotCancelled(t)
        val url = t.coverUrl
        if (url.isBlank()) { fail(t, "图片地址为空"); return }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').takeIf { it.length in 2..5 } ?: "jpg"
        val out = File(dir, "${t.id}_comment.$ext")
        t.coverPath = out.absolutePath
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(url), out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            if (t.bytesTotal > 0L) maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        complete(t)
    }

    private fun runAudio(t: Task, dir: File) {
        // 第一步：封面（失败不阻塞音频本体，用于下载管理与本地播放器封面展示）
        if (t.coverPath.isBlank() && t.coverUrl.isNotBlank()) {
            runCatching {
                val ext = t.coverUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    .takeIf { it.length in 2..5 } ?: "jpg"
                val cover = File(dir, "${t.id}_cover.$ext")
                downloadFullQuiet(t.coverUrl, cover)
                t.coverPath = cover.absolutePath
            }
        }
        checkNotCancelled(t)
        t.qualityLabel = "仅音频"
        save(); notifyListeners()
        // 防下错：cid 缺失时 B 站播放接口会返回该 bvid 默认分集（主视频）的流，导致合集分集音频静默下错
        if (t.cid <= 0L || t.bvid.isBlank()) {
            fail(t, "音频任务缺少分集标识，请删除后重新添加")
            return
        }
        val stream = fetchStream(t) ?: return
        val audio = pickAudio(stream) ?: run { fail(t, "该视频没有可下载的音频流"); return }
        val out = File(dir, "${t.id}_audio.m4s")
        t.audioPath = out.absolutePath
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)
        // 整段下载（BiliTools 同款）：DASH baseUrl 直接 Range 下载即为完整 m4s，
        // 避免 init/media 分段拼接在部分 CDN 节点上出错
        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(audio.url) + audio.backups, out, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            maybeNotify()
        }
        t.bytesDone = out.length()
        t.bytesTotal = out.length()
        t.resumeOffset = 0L
        complete(t)
    }

    private fun runVideo(t: Task, dir: File) {
        checkNotCancelled(t)
        // 第一步：封面（失败不阻塞视频本体）
        if (t.coverPath.isBlank() && t.coverUrl.isNotBlank()) {
            runCatching {
                val ext = t.coverUrl.substringAfterLast('.', "jpg").substringBefore('?')
                    .takeIf { it.length in 2..5 } ?: "jpg"
                val cover = File(dir, "${t.id}_cover.$ext")
                downloadFullQuiet(t.coverUrl, cover)
                t.coverPath = cover.absolutePath
            }
        }
        checkNotCancelled(t)
        save(); notifyListeners()

        val stream = fetchStream(t) ?: return
        t.durationMs = stream.durationMs.coerceAtLeast(t.durationMs)

        if (!stream.isDash) {
            // 单文件流(durl)：逐段下载，已存在的分片从断点续传（网络中断/重试不再从头）
            val segments = stream.durlSegments.ifEmpty {
                listOf(BiliPlayUrl.DurlSegment(
                    stream.fallbackUrls.ifEmpty { listOf(stream.videoUrl) },
                    stream.durationMs, 0L
                ))
            }
            t.singlePaths.clear()
            var segIndex = 0
            var segDone = 0L
            for (segment in segments) {
                checkNotCancelled(t)
                val urls = segment.urls.filter { it.isNotBlank() }
                if (urls.isEmpty()) continue
                val segFile = File(dir, "${t.id}_seg${segIndex++}.mp4")
                t.singlePaths.add(segFile.absolutePath)
                t.bytesTotal = -1L
                save(); notifyListeners()
                downloadFullResume(urls, segFile, t, base = segDone) { done ->
                    // done 已含 base（前序分片字节），即累计已下载字节
                    t.bytesDone = done
                    t.sampleSpeed()
                    maybeNotify()
                }
                segDone += segFile.length()
            }
            t.bytesTotal = t.singlePaths.sumOf { File(it).length() }
            t.bytesDone = t.bytesTotal
            t.resumeOffset = 0L
            complete(t)
            return
        }

        // DASH：视频轨 + 音频轨（选 <= 目标画质的最高档，AVC 优先）
        // 整段下载（BiliTools 同款）：baseUrl 直接 Range 下载即为完整可播放 m4s，
        // 不拆 init/index/media 分段，避免部分 CDN 节点 range 语义差异导致几 KB 假文件
        val video = pickVideo(stream, t.quality)
        val audio = pickAudio(stream)
        if (video == null) { fail(t, "没有可用的视频流"); return }

        val videoFile = File(dir, "${t.id}_video.m4s")
        val audioFile = File(dir, "${t.id}_audio.m4s")
        t.singlePaths.clear()

        t.bytesTotal = -1L
        save(); notifyListeners()
        downloadFullResume(listOf(video.url) + video.backups, videoFile, t) { done ->
            t.bytesDone = done
            t.sampleSpeed()
            maybeNotify()
        }

        if (audio != null) {
            val videoDone = videoFile.length()
            downloadFullResume(listOf(audio.url) + audio.backups, audioFile, t, base = videoDone) { done ->
                // done 已含 base（视频轨字节），即累计已下载字节
                t.bytesDone = done
                t.sampleSpeed()
                maybeNotify()
            }
        }

        checkNotCancelled(t)
        t.bytesDone = videoFile.length() + (if (audio != null) audioFile.length() else 0L)
        t.bytesTotal = t.bytesDone
        t.resumeOffset = 0L

        // 生成本地 MPD：BaseURL 指向本地文件，区间与原始布局一致
        val mpdFile = File(dir, "${t.id}.mpd")
        val localVideo = BiliPlayUrl.DashVideoTrack(
            id = video.id, url = Uri.fromFile(videoFile).toString(), backups = emptyList(),
            bandwidth = video.bandwidth, mimeType = video.mimeType, codecs = video.codecs,
            width = video.width, height = video.height, frameRate = video.frameRate,
            initializationRange = video.initializationRange, indexRange = video.indexRange
        )
        val localAudio = audio?.let {
            BiliPlayUrl.DashAudioTrack(
                id = it.id, url = Uri.fromFile(audioFile).toString(), backups = emptyList(),
                bandwidth = it.bandwidth, mimeType = it.mimeType, codecs = it.codecs,
                initializationRange = it.initializationRange, indexRange = it.indexRange
            )
        }
        val streamForMpd = BiliPlayUrl.DashStream(
            videoTracks = listOf(localVideo),
            audioTracks = listOfNotNull(localAudio),
            durationMs = t.durationMs.coerceAtLeast(1L),
            actualQuality = video.id,
            acceptedQualities = emptyList(),
            acceptedDescriptions = emptyList(),
            isDash = true
        )
        mpdFile.writeText(BiliDashManifest.build(streamForMpd))
        t.mpdPath = mpdFile.absolutePath
        t.qualityLabel = BiliPlayUrl.qualityLabel(video.id).replace("P", "p")
        complete(t)
    }

    // ── 内部：流获取与轨道选择 ─────────────────────────────────

    private fun fetchStream(t: Task): BiliPlayUrl.DashStream? {
        val latch = CountDownLatch(1)
        var result: BiliPlayUrl.DashStream? = null
        var err: String? = null
        val quality = if (t.type == TYPE_AUDIO) BiliPlayUrl.QUALITY_720 else t.quality
        if (t.bvid.isBlank() || t.cid <= 0L) {
            fail(t, "任务缺少分集标识，请删除后重新添加")
            return null
        }
        val request = BiliPlayUrl.fetchPlayUrl(t.bvid, t.cid, quality, forDownload = true) { stream, error ->
            result = stream
            err = error
            latch.countDown()
        }
        val limit = SystemClock.elapsedRealtime() + 30_000L
        while (!latch.await(300L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (t.cancelRequested || SystemClock.elapsedRealtime() >= limit) {
                request.cancel()
                checkNotCancelled(t)
                fail(t, "获取视频流超时")
                return null
            }
        }
        checkNotCancelled(t)
        if (result == null) { fail(t, err ?: "获取视频流失败"); return null }
        return result
    }

    private fun pickVideo(stream: BiliPlayUrl.DashStream, targetQn: Int): BiliPlayUrl.DashVideoTrack? {
        val candidates = stream.videoTracks.filter { it.id <= targetQn }.ifEmpty { stream.videoTracks }
        val bestQn = candidates.maxOfOrNull { it.id } ?: return null
        return candidates
            .filter { it.id == bestQn }
            .minWithOrNull(compareBy<BiliPlayUrl.DashVideoTrack> { codecRank(it.codecs) }
                .thenByDescending { it.bandwidth })
    }

    private fun pickAudio(stream: BiliPlayUrl.DashStream): BiliPlayUrl.DashAudioTrack? =
        stream.audioTracks
            .filter { it.codecs.startsWith("mp4a", true) }
            .maxByOrNull { it.bandwidth }
            ?: stream.audioTracks.maxByOrNull { it.bandwidth }

    private fun codecRank(codec: String): Int = when {
        codec.startsWith("avc", true) -> 0
        codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 1
        codec.startsWith("av01", true) -> 2
        else -> 3
    }

    private class CancelledException : Exception()

    private fun checkNotCancelled(t: Task) {
        if (t.cancelRequested) throw CancelledException()
    }

    /**
     * 整体下载 + 断点续传：已存在的文件从 [length] 处继续（服务端支持 Range 则 206 追加，
     * 否则 200 从头覆盖）。onProgress 回调当前文件已写字节数（不含续传起点）。
     * 多个候选 URL 依次尝试，403/截断等异常自动切换下一节点。
     */
    /**
     * 断点续传前校验已有文件头：旧版本可能把 CDN 403 错误页（HTML，几 KB）写入
     * 了文件头。媒体文件以 ftyp（mp4/m4s）、JPEG/PNG/WebP/GIF 等 magic 开头，
     * 不是这些就视为坏文件删除，避免断点续传把坏头拼进成品。
     */
    private fun discardCorruptedPart(out: File) {
        if (!out.exists() || out.length() == 0L) return
        val magic = ByteArray(12)
        try {
            RandomAccessFile(out, "r").use { raf -> raf.readFully(magic) }
        } catch (_: Exception) {
            return
        }
        val b = { i: Int -> magic[i] }
        val isFtyp = magic.size >= 8 && b(4) == 'f'.code.toByte() && b(5) == 't'.code.toByte() &&
            b(6) == 'y'.code.toByte() && b(7) == 'p'.code.toByte()
        val isJpeg = magic.size >= 3 && (b(0).toInt() and 0xFF) == 0xFF && (b(1).toInt() and 0xFF) == 0xD8 &&
            (b(2).toInt() and 0xFF) == 0xFF
        val isPng = magic.size >= 8 && b(0) == 0x89.toByte() && b(1) == 'P'.code.toByte() &&
            b(2) == 'N'.code.toByte() && b(3) == 'G'.code.toByte()
        val isWebp = magic.size >= 12 && b(0) == 'R'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == 'F'.code.toByte() && b(8) == 'W'.code.toByte()
        val isGif = magic.size >= 6 && b(0) == 'G'.code.toByte() && b(1) == 'I'.code.toByte() &&
            b(2) == 'F'.code.toByte() && b(3) == '8'.code.toByte()
        if (!(isFtyp || isJpeg || isPng || isWebp || isGif)) {
            out.delete()
        }
    }

    // 长媒体流必须显式清除从 BiliHttp.client 继承的 28 秒整次 callTimeout，
    // 否则大视频持续下载到 28 秒会被 OkHttp 强行中断；仅保留 connect/read 超时 + 暂停真实 Call 取消。
    private val downloadClient: OkHttpClient = BiliHttp.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun downloadFullResume(
        urls: List<String>,
        out: File,
        t: Task,
        /** 本次下载在任务总量中的起始偏移（多轨任务：视频轨完成后音频轨从 videoSize 起算）。 */
        base: Long = 0L,
        onProgress: ((Long) -> Unit)? = null
    ) {
        var lastError: Exception? = null
        val anonCookie = BiliPlayUrl.buvidCookie()
        for (u in urls.filter { it.isNotBlank() }) {
            try {
                discardCorruptedPart(out)
                val start = if (out.exists()) out.length() else 0L
                executeMediaGet(u, start, null, anonCookie, task = t).use { response ->
                    val code = response.code
                    if (code == 416 && start > 0L) {
                        val rangeSpec = MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
                        if (rangeSpec != null && rangeSpec.isUnsatisfiable && rangeSpec.total == start) {
                            val absolute = base + start
                            if (absolute > 0L && absolute != t.bytesTotal) {
                                t.bytesTotal = absolute
                                save(); notifyListeners()
                            }
                            onProgress?.invoke(absolute)
                            return
                        }
                        out.delete()
                        throw IllegalStateException("续传偏移越界（HTTP 416），已重置分片")
                    }
                    requireMediaResponse(response, code)
                    val body = response.body ?: throw IOException("媒体响应体为空")
                    val append = if (code == 206 && start > 0L) {
                        val contentRange = MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
                            ?: throw IllegalStateException("206 续传缺少合法 Content-Range")
                        if (!contentRange.isSatisfiableRange || contentRange.start != start) {
                            throw IllegalStateException("206 续传偏移不匹配（期待 $start，实际 ${contentRange.start}）")
                        }
                        if (contentRange.total != null && contentRange.total < start) {
                            throw IllegalStateException("206 总长度小于本地已写偏移")
                        }
                        true
                    } else {
                        false
                    }
                    val contentRange = if (append) {
                        MediaTransportPolicy.parseContentRange(response.header("Content-Range"))
                    } else null
                    val bodyLength = body.contentLength()
                    val fullLength = when {
                        append && contentRange?.total != null -> contentRange.total
                        append && bodyLength >= 0L -> start + bodyLength
                        !append && bodyLength >= 0L -> bodyLength
                        else -> -1L
                    }
                    if (fullLength > 0L) {
                        val absolute = base + fullLength
                        if (absolute != t.bytesTotal) {
                            t.bytesTotal = absolute
                            save(); notifyListeners()
                        }
                    }
                    val stream = body.byteStream()
                    val fos = FileOutputStream(out, append)
                    try {
                        val buf = ByteArray(256 * 1024)
                        var written = 0L
                        while (true) {
                            checkNotCancelled(t)
                            val n = stream.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                fos.write(buf, 0, n)
                                written += n
                                onProgress?.invoke(if (append) base + start + written else base + written)
                            }
                        }
                        fos.flush()
                        if (bodyLength >= 0L && written < bodyLength) {
                            throw IllegalStateException("响应被截断（$written/$bodyLength 字节）")
                        }
                        if (append && contentRange?.total != null && start + written != contentRange.total) {
                            throw IllegalStateException("续传总字节不一致（${start + written}/${contentRange.total}）")
                        }
                        return
                    } finally {
                        fos.close()
                    }
                }
            } catch (c: CancelledException) {
                throw c
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无可用下载地址")
    }

    private fun downloadFullQuiet(url: String, out: File, onProgress: ((Long) -> Unit)? = null) {
        discardCorruptedPart(out)
        val anonCookie = BiliPlayUrl.buvidCookie()
        executeMediaGet(url, null, null, anonCookie).use { response ->
            val code = response.code
            requireMediaResponse(response, code)
            val body = response.body ?: throw IOException("媒体响应体为空")
            val total = body.contentLength()
            val input = body.byteStream()
            val fos = FileOutputStream(out)
            try {
                val buf = ByteArray(256 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        written += n
                        onProgress?.invoke(written)
                    }
                }
                fos.flush()
                if (total >= 0L && written < total) {
                    throw IllegalStateException("响应被截断（$written/$total 字节）")
                }
            } finally {
                fos.close()
            }
        }
    }

    private fun executeMediaGet(
        url: String,
        start: Long?,
        end: Long?,
        anonymousCookie: String,
        task: Task? = null
    ): Response {
        val range = if (start != null) {
            if (end != null) "bytes=$start-$end" else "bytes=$start-"
        } else {
            null
        }
        return MediaTransportPolicy.executeWithSafeRedirects(
            client = downloadClient,
            initialUrl = url,
            anonymousCookie = anonymousCookie,
            rangeHeader = range,
            onCallCreated = { call ->
                if (task != null) {
                    task.activeCall = call
                    if (task.cancelRequested) call.cancel()
                }
            }
        )
    }

    /**
     * 校验媒体响应：非 2xx（403/416/503 等）或返回 HTML/JSON 错误页时直接抛异常，
     * 由上层切换到备用 CDN 节点。这是「下载到十几 KB 假完成」问题的关键防线。
     */
    private fun requireMediaResponse(response: Response, code: Int) {
        if (code !in 200..299) {
            throw IllegalStateException("媒体服务器响应异常（HTTP $code）")
        }
        val type = response.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        if (type.contains("text/html") || type.contains("application/json") || type.contains("text/plain")) {
            throw IllegalStateException("媒体服务器返回了非媒体内容（HTTP $code）")
        }
    }

    // ── 内部：状态落库 ──────────────────────────────────────────

    private fun complete(t: Task) {
        synchronized(t) {
            checkNotCancelled(t)
            if (t.state != STATE_RUNNING) return
            t.state = STATE_COMPLETED
            t.finishedAt = System.currentTimeMillis()
            t.error = ""
            t.resumeOffset = 0L
        }
        t.resetSpeed()
        save()
        if (t.type == TYPE_VIDEO) scheduleGalleryWork { exportToGallery(t) }
        notifyListeners()
    }

    private fun scheduleGalleryWork(work: () -> Unit) {
        galleryWork.incrementAndGet()
        galleryIo.execute {
            try {
                work()
            } finally {
                galleryWork.decrementAndGet()
                notifyListeners()
            }
        }
    }

    private fun fail(t: Task, message: String) {
        synchronized(t) {
            if (t.state == STATE_RUNNING) {
                t.state = STATE_FAILED
                t.error = message
                t.finishedAt = System.currentTimeMillis()
            }
        }
        t.resetSpeed()
        save()
        notifyListeners()
    }

    // ─────────────────────────────────────────────────
    //  相册导出：下载产物复制到公共媒体库（Pictures/YuiBili、Movies/YuiBili），
    //  让系统相册 / 文件管理器能看到。应用私有目录不会被媒体扫描。
    //  Android 10+ 走 MediaStore（免权限）；旧系统回退公共目录 + MediaScanner。
    //  任何失败都静默跳过，绝不影响下载主流程。
    // ─────────────────────────────────────────────────

    private fun exportToGallery(t: Task) {
        val ctx = appContext ?: return
        if (t.state != STATE_COMPLETED) return
        // 任务已被删除时不再导出，避免删除后相册里又冒出副本
        if (synchronized(lock) { !tasks.containsKey(t.id) }) return
        // 封面导出：只由独立封面任务(TYPE_COVER)负责，且仅在用户开启「封面图片」时才会创建；
        // 视频/音频任务内部的封面(coverPath)仅用于列表缩略图，不导出到相册，
        // 避免用户没开封面开关时相册里也自动出现一张封面。
        if (t.type == TYPE_COVER && t.coverPath.isNotBlank()) {
            val cover = File(t.coverPath)
            if (cover.exists() && cover.length() > 0L) {
                exportToMediaStore(ctx, t, cover, "Pictures/YuiBili", false)
            }
        }
        if (t.type == TYPE_AUDIO || t.type == TYPE_COVER || t.type == TYPE_COMMENT) return
        // 视频 → 相册视频
        if (t.mpdPath.isNotBlank()) {
            // DASH：视频轨(.m4s)+音频轨(.m4s) 合并为有声 mp4 再导出
            val dir = downloadDir ?: return
            val v = File(dir, "${t.id}_video.m4s")
            val a = File(dir, "${t.id}_audio.m4s")
            if (v.exists() && v.length() > 0L) {
                val merged = File(dir, "${t.id}_merged.mp4")
                try {
                    if (mergeDashToMp4(v, a.takeIf { it.length() > 0L }, merged)) {
                        exportToMediaStore(ctx, t, merged, "Movies/YuiBili", true)
                    }
                } finally {
                    merged.delete()
                }
            }
        } else {
            // 单文件 / 分段流：整段 mp4 直接导出
            val single = t.singlePaths.firstOrNull { it.endsWith(".mp4") && File(it).exists() }
            if (single != null) {
                exportToMediaStore(ctx, t, File(single), "Movies/YuiBili", true)
            }
        }
    }

    private fun exportToMediaStore(ctx: Context, task: Task, src: File, relativeDir: String, isVideo: Boolean) {
        if (task.cancelRequested || src.length() == 0L) return
        try {
            val ext = src.extension.lowercase(Locale.ROOT).ifBlank { if (isVideo) "mp4" else "jpg" }
            val mime = if (isVideo) {
                "video/mp4"
            } else {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
            }
            val display = sanitizeFileName(task.title.ifBlank { src.nameWithoutExtension }) + ".$ext"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                 else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, display)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(collection, values) ?: return
                var published = false
                try {
                    val output = resolver.openOutputStream(uri) ?: return
                    output.use { out -> src.inputStream().use { it.copyTo(out) } }
                    if (task.cancelRequested) return
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    published = resolver.update(uri, values, null, null) > 0
                    if (published) task.galleryExports.addIfAbsent(uri.toString())
                } finally {
                    if (!published) resolver.delete(uri, null, null)
                }
            } else {
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                val folder = File(pubDir, "YuiBili").apply { mkdirs() }
                val base = display.substringBeforeLast('.')
                var target = File(folder, display)
                var suffix = 1
                while (true) {
                    if (task.cancelRequested) return
                    if (target.createNewFile()) break
                    target = File(folder, "$base (${suffix++}).$ext")
                }
                var published = false
                try {
                    src.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    if (task.cancelRequested) return
                    android.media.MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime), null)
                    task.galleryExports.addIfAbsent(Uri.fromFile(target).toString())
                    published = true
                } finally {
                    if (!published) target.delete()
                }
            }
            save()
        } catch (error: java.io.IOException) {
            Log.w("Downloads", "Unable to export downloaded media", error)
        } catch (error: SecurityException) {
            Log.w("Downloads", "Export permission denied", error)
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "video" }

    /** 按导出记录清理相册副本。 */
    private fun deleteGalleryExports(t: Task) {
        val ctx = appContext ?: return
        for (entry in t.galleryExports) {
            try {
                when {
                    entry.startsWith("content://media/") -> ctx.contentResolver.delete(Uri.parse(entry), null, null)
                    entry.startsWith("file://") -> Uri.parse(entry).path?.let { File(it).delete() }
                    '|' in entry -> deleteMediaEntry(ctx, entry.substringBefore('|'), entry.substringAfter('|'))
                }
            } catch (error: SecurityException) {
                Log.w("Downloads", "Unable to delete the exported media", error)
            }
        }
    }

    /** 按相对目录 + 文件名删除公共媒体库条目（MediaStore）或旧系统公共目录文件。 */
    private fun deleteMediaEntry(ctx: Context, relativeDir: String, display: String) {
        runCatching {
            val isVideo = relativeDir.startsWith("Movies")
            val base = display.substringBeforeLast('.', display)
            val ext = display.substringAfterLast('.', "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                 else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                // 转义 LIKE 通配符，只匹配「原名」与「原名 (n).ext」两种形态
                val escapedBase = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                // RELATIVE_PATH 在不同系统上可能带/不带尾部斜杠，两种都匹配
                val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) AND (" +
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\')"
                val args = arrayOf("$relativeDir/", relativeDir, display, "$escapedBase (%.$ext")
                val ids = ArrayList<Long>()
                ctx.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null
                )?.use { c ->
                    while (c.moveToNext()) ids.add(c.getLong(0))
                }
                ids.forEach { id ->
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    // 不带 recycle 参数直接删条目（部分 ROM 不识别 recycle token 会抛异常，runCatching 兜住）
                    runCatching { ctx.contentResolver.delete(uri, null, null) }
                }
            } else {
                // 旧系统：公共目录直接删文件
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                val parent = File(pubDir, "YuiBili")
                parent.listFiles()?.filter {
                    it.name.startsWith(base + " (") && it.name.endsWith(".$ext")
                }?.forEach { runCatching { it.delete() } }
                File(File(pubDir, "YuiBili"), display).delete()
            }
        }
        // 物理删除（旧系统公共目录或有权限时）已移除 MANAGE_EXTERNAL_STORAGE 依赖。
    }

    /** 用 MediaMuxer 把 DASH 的视频轨/音频轨混流成单个有声 mp4（B 站整段 m4s 是完整 MP4）。 */
    private fun mergeDashToMp4(video: File, audio: File?, out: File): Boolean {
        var extractorV: MediaExtractor? = null
        var extractorA: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            val ev = MediaExtractor().apply { setDataSource(video.absolutePath) }
            extractorV = ev
            val vi = (0 until ev.trackCount).firstOrNull {
                ev.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            ev.selectTrack(vi)
            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val mv = mux.addTrack(ev.getTrackFormat(vi))
            var ea: MediaExtractor? = null
            var ma = -1
            if (audio != null) {
                runCatching {
                    val e = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                    val ai = (0 until e.trackCount).firstOrNull {
                        e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                    if (ai != null) {
                        e.selectTrack(ai)
                        ma = mux.addTrack(e.getTrackFormat(ai))
                        ea = e
                        extractorA = e
                    }
                }
            }
            mux.start()
            val vBuf = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val aBuf = java.nio.ByteBuffer.allocate(2 * 1024 * 1024)
            val vInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            var vPending = true
            var aPending = ea != null
            while (vPending || aPending) {
                if (vPending && (!aPending || ev.sampleTime <= ea!!.sampleTime)) {
                    vInfo.offset = 0
                    vInfo.size = ev.readSampleData(vBuf, 0)
                    if (vInfo.size < 0) { vPending = false } else {
                        vInfo.presentationTimeUs = ev.sampleTime
                        vInfo.flags = muxSampleFlags(ev.sampleFlags)
                        mux.writeSampleData(mv, vBuf, vInfo)
                        ev.advance()
                    }
                } else if (aPending) {
                    aInfo.offset = 0
                    aInfo.size = ea!!.readSampleData(aBuf, 0)
                    if (aInfo.size < 0) { aPending = false } else {
                        aInfo.presentationTimeUs = ea!!.sampleTime
                        aInfo.flags = muxSampleFlags(ea!!.sampleFlags)
                        mux.writeSampleData(ma, aBuf, aInfo)
                        ea!!.advance()
                    }
                }
            }
            mux.stop()
            ev.release(); ea?.release(); mux.release()
            true
        } catch (_: Throwable) {
            runCatching { muxer?.stop() }
            runCatching { extractorV?.release() }
            runCatching { extractorA?.release() }
            runCatching { muxer?.release() }
            runCatching { out.delete() }
            false
        }
    }

    private fun muxSampleFlags(extractorFlags: Int): Int {
        var flags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun maybeNotify() {
        val previous = lastNotifyAt.get()
        val now = SystemClock.elapsedRealtime()
        if (now - previous >= 250L && lastNotifyAt.compareAndSet(previous, now)) notifyListeners()
    }

    private fun notifyListeners() {
        if (listeners.isNotEmpty() && deliveryPosted.compareAndSet(false, true)) main.post(deliverUpdates)
    }

    // ── 后台通知/保活 ─────────────────────────────────────────

    private fun startDownloadService(context: Context) {
        val intent = Intent(context, DownloadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (error: IllegalStateException) {
            Log.w("Downloads", "Unable to start foreground download service", error)
        } catch (error: SecurityException) {
            Log.w("Downloads", "Foreground download service permission denied", error)
        }
    }

    /** 在独立前台服务中托管同一 DownloadManager 单例，避免 Activity 退出后进程被回收。 */
    class DownloadService : Service() {
        private val main = Handler(Looper.getMainLooper())
        private var lastStartId = 0
        @Volatile private var observing = false
        private val updatePosted = AtomicBoolean()
        private val reconcile = Runnable {
            updatePosted.set(false)
            if (!observing || lastStartId == 0) return@Runnable
            val active = snapshot().filter { it.running || it.state == STATE_QUEUED }
            val writing = synchronized(lock) { writerScheduled }
            if (active.isEmpty() && galleryWork.get() == 0 && !writing) {
                if (stopSelfResult(lastStartId)) {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
                    else @Suppress("DEPRECATION") stopForeground(true)
                }
            } else if (active.isNotEmpty()) {
                buildDownloadNotification(active)?.let { notificationManager?.notify(NOTIFICATION_ID, it) }
            } else {
                notificationManager?.notify(NOTIFICATION_ID, emptyServiceNotification(this))
            }
        }
        private fun scheduleUpdate() {
            if (observing && updatePosted.compareAndSet(false, true)) main.post(reconcile)
        }

        private val listener: (List<Task>) -> Unit = { scheduleUpdate() }

        override fun onCreate() {
            super.onCreate()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW))
            }
            val notification = DownloadManager.emptyServiceNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            DownloadManager.init(this)
            observing = true
            addListener(listener)
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            lastStartId = startId
            scheduleUpdate()
            return START_STICKY
        }

        override fun onDestroy() {
            observing = false
            removeListener(listener)
            main.removeCallbacks(reconcile)
            super.onDestroy()
        }

        override fun onBind(intent: Intent?) = null
    }

    private fun emptyServiceNotification(context: Context): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle("YuiBili 下载")
            .setContentText("下载服务已启动")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "下载任务",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "YuiBili 后台下载进度"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notificationIntent(): PendingIntent? {
        val ctx = appContext ?: return null
        val intent = Intent(ctx, DownloadManagerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    private fun buildDownloadNotification(active: List<Task>): Notification? {
        val ctx = appContext ?: return null
        val running = active.filter { it.state == STATE_RUNNING }
        val totalDone = active.sumOf { it.bytesDone.coerceAtLeast(0L) }
        val total = active.sumOf { it.bytesTotal.takeIf { b -> b > 0L } ?: 0L }
        val title = when {
            running.size == 1 -> "正在下载：${running.first().title.ifBlank { "视频" }}"
            running.isNotEmpty() -> "正在下载 ${running.size} 个视频"
            else -> "下载任务等待中"
        }
        val text = when {
            total > 0L -> "${formatBytesForNotification(totalDone)} / ${formatBytesForNotification(total)} · 最多同时 3 个"
            else -> "${active.size} 个任务 · 最多同时 3 个"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(ctx)
        }
        builder.setSmallIcon(com.yuilittle.bili.R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(notificationIntent())
            .setOngoing(running.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (total > 0L) {
            builder.setProgress(100, ((totalDone * 100L) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun formatBytesForNotification(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fGB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
        bytes >= 1024L -> String.format(Locale.US, "%.0fKB", bytes / 1024f)
        else -> "${bytes}B"
    }


    private fun save() {
        synchronized(lock) { saveLocked() }
    }

    private fun saveLocked() {
        writePending = true
        if (writerScheduled) return
        writerScheduled = true
        taskIo.execute(::persistTaskChanges)
    }

    private fun persistTaskChanges() {
        while (true) {
            val snapshot = synchronized(lock) {
                if (writePending) {
                    writePending = false
                    JSONArray().apply { tasks.values.forEach { put(toJson(it)) } }
                } else {
                    writerScheduled = false
                    null
                }
            }
            if (snapshot == null) break
            persistTaskSnapshot(snapshot)
        }
        notifyListeners()
    }

    private fun persistTaskSnapshot(snapshot: JSONArray) {
        val dir = downloadDir ?: return
        val file = android.util.AtomicFile(File(dir, "tasks.json"))
        try {
            val output = file.startWrite()
            try {
                output.write(JSONObject().put("tasks", snapshot).toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: java.io.IOException) {
                file.failWrite(output)
                throw error
            }
        } catch (error: java.io.IOException) {
            Log.w("Downloads", "Unable to persist downloads", error)
        }
    }

    private fun load() {
        val dir = downloadDir ?: return
        val file = android.util.AtomicFile(File(dir, "tasks.json"))
        try {
            val arr = JSONObject(file.readFully().toString(Charsets.UTF_8)).optJSONArray("tasks") ?: return
            for (i in 0 until arr.length()) {
                val t = fromJson(arr.getJSONObject(i))
                tasks[t.id] = t
                if (t.state == STATE_RUNNING || t.state == STATE_QUEUED) {
                    // 进程重启后未完成任务回到排队/暂停，断点保留
                    t.state = if (t.cancelRequested || t.resumeOffset > 0L) STATE_PAUSED else STATE_QUEUED
                    t.running = false
                    t.cancelRequested = false
                    if (t.state == STATE_QUEUED) queue.add(t.id)
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            return
        } catch (error: java.io.IOException) {
            Log.w("Downloads", "Unable to read downloads", error)
        } catch (error: org.json.JSONException) {
            Log.w("Downloads", "Invalid download tasks", error)
        }
    }

    private fun toJson(t: Task): JSONObject = JSONObject().apply {
        put("id", t.id); put("type", t.type)
        put("bvid", t.bvid); put("cid", t.cid); put("aid", t.aid)
        put("title", t.title); put("owner", t.owner)
        put("coverUrl", t.coverUrl); put("coverPath", t.coverPath)
        put("groupId", t.groupId); put("groupTitle", t.groupTitle)
        put("groupCoverUrl", t.groupCoverUrl); put("episodeNo", t.episodeNo)
        put("publishedAt", t.publishedAt)
        put("quality", t.quality); put("qualityLabel", t.qualityLabel)
        put("durationMs", t.durationMs)
        put("state", t.state)
        put("bytesDone", t.bytesDone); put("bytesTotal", t.bytesTotal)
        put("error", t.error)
        put("createdAt", t.createdAt); put("finishedAt", t.finishedAt)
        put("resumeOffset", t.resumeOffset)
        put("cancelRequested", t.cancelRequested)
        put("mpdPath", t.mpdPath); put("audioPath", t.audioPath)
        put("singlePaths", JSONArray(t.singlePaths))
        put("galleryExports", JSONArray(t.galleryExports))
    }

    private fun fromJson(o: JSONObject): Task = Task().apply {
        id = o.optLong("id"); type = o.optInt("type")
        bvid = o.optString("bvid"); cid = o.optLong("cid"); aid = o.optLong("aid")
        title = o.optString("title"); owner = o.optString("owner")
        coverUrl = o.optString("coverUrl"); coverPath = o.optString("coverPath")
        groupId = o.optString("groupId"); groupTitle = o.optString("groupTitle")
        groupCoverUrl = o.optString("groupCoverUrl"); episodeNo = o.optInt("episodeNo")
        publishedAt = o.optLong("publishedAt", 0L)
        quality = o.optInt("quality"); qualityLabel = o.optString("qualityLabel")
        durationMs = o.optLong("durationMs")
        state = o.optInt("state")
        bytesDone = o.optLong("bytesDone"); bytesTotal = o.optLong("bytesTotal")
        error = o.optString("error")
        createdAt = o.optLong("createdAt"); finishedAt = o.optLong("finishedAt")
        resumeOffset = o.optLong("resumeOffset")
        cancelRequested = o.optBoolean("cancelRequested")
        mpdPath = o.optString("mpdPath"); audioPath = o.optString("audioPath")
        val paths = o.optJSONArray("singlePaths")
        if (paths != null) for (i in 0 until paths.length()) singlePaths.add(paths.optString(i))
        val gExports = o.optJSONArray("galleryExports")
        if (gExports != null) for (i in 0 until gExports.length()) galleryExports.add(gExports.optString(i))
    }
}
