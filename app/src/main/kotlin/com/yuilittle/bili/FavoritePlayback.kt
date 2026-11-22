package com.yuilittle.bili

import android.content.Context
import android.content.Intent

/**
 * 收藏夹「播放全部」队列：只在从收藏夹播放全部进入时携带。
 * 携带该队列时，播放页与后台音频页都以收藏夹列表为准，不再使用视频自身的 ugc_season。
 */
internal data class FavoritePlayback(
    val folderId: Long,
    val folderName: String,
    val entries: List<UgcEpisode>,
    /** 入口视频在 entries 中的下标。 */
    val startIndex: Int = 0,
    val startPositionMs: Long = 0L
)

internal object FavoritePlaybackLauncher {
    private const val EXTRA_FOLDER_ID = "fav_play_folder_id"
    private const val EXTRA_FOLDER_NAME = "fav_play_folder_name"
    private const val EXTRA_START_INDEX = "fav_play_start_index"
    private const val EXTRA_START_MS = "fav_play_start_ms"
    private const val EXTRA_COUNT = "fav_play_count"
    private const val EXTRA_PREFIX = "fav_play_ep_"

    fun intent(context: Context, playback: FavoritePlayback): Intent =
        Intent(context, VideoDetailActivity::class.java).apply {
            writeTo(this, playback)
        }

    /** 把收藏夹队列写入任意 Intent（播放页、下载页共用）。 */
    fun writeTo(intent: Intent, playback: FavoritePlayback) {
        intent.putExtra(EXTRA_FOLDER_ID, playback.folderId)
        intent.putExtra(EXTRA_FOLDER_NAME, playback.folderName)
        intent.putExtra(EXTRA_START_INDEX, playback.startIndex)
        intent.putExtra(EXTRA_START_MS, playback.startPositionMs)
        val entries = playback.entries
        intent.putExtra(EXTRA_COUNT, entries.size)
        entries.forEachIndexed { index, ep ->
            intent.putExtra(EXTRA_PREFIX + index + "_bvid", ep.bvid)
            intent.putExtra(EXTRA_PREFIX + index + "_aid", ep.aid)
            intent.putExtra(EXTRA_PREFIX + index + "_cid", ep.cid)
            intent.putExtra(EXTRA_PREFIX + index + "_title", ep.title)
            intent.putExtra(EXTRA_PREFIX + index + "_duration", ep.duration)
            intent.putExtra(EXTRA_PREFIX + index + "_cover", ep.cover)
            intent.putExtra(EXTRA_PREFIX + index + "_published_at", ep.publishedAt)
            intent.putExtra(EXTRA_PREFIX + index + "_owner", ep.owner)
        }
    }

    /** 从 Intent 读取收藏夹队列；不是播放全部入口时返回 null。 */
    fun read(intent: Intent?): FavoritePlayback? {
        if (intent == null) return null
        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        if (count <= 0) return null
        val entries = ArrayList<UgcEpisode>(count)
        for (index in 0 until count) {
            val prefix = EXTRA_PREFIX + index + "_"
            val bvid = intent.getStringExtra(prefix + "bvid").orEmpty()
            val cid = intent.getLongExtra(prefix + "cid", 0L)
            // 收藏接口不返回 cid，这里只要求有 bvid；cid 由播放页拉详情后补全
            if (bvid.isBlank()) continue
            entries.add(
                UgcEpisode(
                    bvid = bvid,
                    aid = intent.getLongExtra(prefix + "aid", 0L),
                    cid = cid,
                    title = intent.getStringExtra(prefix + "title").orEmpty(),
                    duration = intent.getIntExtra(prefix + "duration", 0),
                    cover = intent.getStringExtra(prefix + "cover").orEmpty(),
                    publishedAt = intent.getLongExtra(prefix + "published_at", 0L),
                    owner = intent.getStringExtra(prefix + "owner").orEmpty()
                )
            )
        }
        if (entries.isEmpty()) return null
        return FavoritePlayback(
            folderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0L),
            folderName = intent.getStringExtra(EXTRA_FOLDER_NAME).orEmpty(),
            entries = entries,
            startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, entries.lastIndex),
            startPositionMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        )
    }
}
