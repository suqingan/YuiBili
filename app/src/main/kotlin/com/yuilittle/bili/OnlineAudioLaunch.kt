package com.yuilittle.bili

import android.content.Context
import android.content.Intent

/**
 * 在线音频页启动参数：入口分集、起始播放位置与可选合集队列。
 * 只携带元数据，签名 URL 与 Cookie 一律不落 Intent。
 */
internal data class OnlineAudioLaunch(
    val entry: UgcEpisode,
    val startPositionMs: Long,
    val season: UgcSeason? = null,
    /** 收藏夹「播放全部」队列；非空时音频列表以收藏夹为准。 */
    val favorite: FavoritePlayback? = null
)

internal object OnlineAudioLauncher {
    private const val EXTRA_BVID = "online_audio_bvid"
    private const val EXTRA_AID = "online_audio_aid"
    private const val EXTRA_CID = "online_audio_cid"
    private const val EXTRA_TITLE = "online_audio_title"
    private const val EXTRA_DURATION = "online_audio_duration"
    private const val EXTRA_COVER = "online_audio_cover"
    private const val EXTRA_PUBLISHED_AT = "online_audio_published_at"
    private const val EXTRA_START_MS = "online_audio_start_ms"
    private const val EXTRA_SEASON_ID = "online_audio_season_id"
    private const val EXTRA_SEASON_TITLE = "online_audio_season_title"
    private const val EXTRA_SEASON_COVER = "online_audio_season_cover"
    private const val EXTRA_SEASON_MULTI_PAGE = "online_audio_season_multi_page"
    private const val EXTRA_EPISODE_COUNT = "online_audio_episode_count"
    private const val EXTRA_EPISODE_PREFIX = "online_audio_episode_"
    private const val EXTRA_FAV_NAME = "online_audio_fav_name"
    private const val EXTRA_FAV_FOLDER_ID = "online_audio_fav_folder_id"
    private const val EXTRA_FAV_START_INDEX = "online_audio_fav_start_index"
    private const val EXTRA_FAV_COUNT = "online_audio_fav_count"
    private const val EXTRA_FAV_PREFIX = "online_audio_fav_ep_"

    fun intent(context: Context, launch: OnlineAudioLaunch): Intent =
        Intent(context, AudioPlayerActivity::class.java).apply {
            val entry = launch.entry
            putExtra(EXTRA_BVID, entry.bvid)
            putExtra(EXTRA_AID, entry.aid)
            putExtra(EXTRA_CID, entry.cid)
            putExtra(EXTRA_TITLE, entry.title)
            putExtra(EXTRA_DURATION, entry.duration)
            putExtra(EXTRA_COVER, entry.cover)
            putExtra(EXTRA_PUBLISHED_AT, entry.publishedAt)
            putExtra(EXTRA_START_MS, launch.startPositionMs)

            val season = launch.season
            if (season != null) {
                putExtra(EXTRA_SEASON_ID, season.id)
                putExtra(EXTRA_SEASON_TITLE, season.title)
                putExtra(EXTRA_SEASON_COVER, season.cover)
                putExtra(EXTRA_SEASON_MULTI_PAGE, season.isMultiPage)
                val episodes = season.episodes
                putExtra(EXTRA_EPISODE_COUNT, episodes.size)
                episodes.forEachIndexed { index, ep ->
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_bvid", ep.bvid)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_aid", ep.aid)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_cid", ep.cid)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_title", ep.title)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_duration", ep.duration)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_cover", ep.cover)
                    putExtra(EXTRA_EPISODE_PREFIX + index + "_published_at", ep.publishedAt)
                }
            }

            launch.favorite?.let { fav ->
                putExtra(EXTRA_FAV_FOLDER_ID, fav.folderId)
                putExtra(EXTRA_FAV_NAME, fav.folderName)
                putExtra(EXTRA_FAV_START_INDEX, fav.startIndex)
                putExtra(EXTRA_FAV_COUNT, fav.entries.size)
                fav.entries.forEachIndexed { index, ep ->
                    putExtra(EXTRA_FAV_PREFIX + index + "_bvid", ep.bvid)
                    putExtra(EXTRA_FAV_PREFIX + index + "_aid", ep.aid)
                    putExtra(EXTRA_FAV_PREFIX + index + "_cid", ep.cid)
                    putExtra(EXTRA_FAV_PREFIX + index + "_title", ep.title)
                    putExtra(EXTRA_FAV_PREFIX + index + "_duration", ep.duration)
                    putExtra(EXTRA_FAV_PREFIX + index + "_cover", ep.cover)
                    putExtra(EXTRA_FAV_PREFIX + index + "_published_at", ep.publishedAt)
                    putExtra(EXTRA_FAV_PREFIX + index + "_owner", ep.owner)
                }
            }
        }

    fun read(intent: Intent?): OnlineAudioLaunch? {
        if (intent == null) return null
        val bvid = intent.getStringExtra(EXTRA_BVID) ?: return null
        val cid = intent.getLongExtra(EXTRA_CID, 0L)
        if (bvid.isBlank() || cid <= 0L) return null
        val entry = UgcEpisode(
            bvid = bvid,
            aid = intent.getLongExtra(EXTRA_AID, 0L),
            cid = cid,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            duration = intent.getIntExtra(EXTRA_DURATION, 0),
            cover = intent.getStringExtra(EXTRA_COVER).orEmpty(),
            publishedAt = intent.getLongExtra(EXTRA_PUBLISHED_AT, 0L)
        )
        val count = intent.getIntExtra(EXTRA_EPISODE_COUNT, 0)
        val season = if (count > 0) {
            val episodes = ArrayList<UgcEpisode>(count)
            for (index in 0 until count) {
                val prefix = EXTRA_EPISODE_PREFIX + index + "_"
                val epBvid = intent.getStringExtra(prefix + "bvid").orEmpty()
                val epCid = intent.getLongExtra(prefix + "cid", 0L)
                if (epCid <= 0L) continue
                episodes.add(
                    UgcEpisode(
                        bvid = epBvid,
                        aid = intent.getLongExtra(prefix + "aid", 0L),
                        cid = epCid,
                        title = intent.getStringExtra(prefix + "title").orEmpty(),
                        duration = intent.getIntExtra(prefix + "duration", 0),
                        cover = intent.getStringExtra(prefix + "cover").orEmpty(),
                        publishedAt = intent.getLongExtra(prefix + "published_at", 0L)
                    )
                )
            }
            UgcSeason(
                id = intent.getLongExtra(EXTRA_SEASON_ID, 0L),
                title = intent.getStringExtra(EXTRA_SEASON_TITLE).orEmpty(),
                cover = intent.getStringExtra(EXTRA_SEASON_COVER).orEmpty(),
                episodes = episodes,
                isMultiPage = intent.getBooleanExtra(EXTRA_SEASON_MULTI_PAGE, false)
            )
        } else {
            null
        }
        return OnlineAudioLaunch(
            entry = entry,
            startPositionMs = intent.getLongExtra(EXTRA_START_MS, 0L),
            season = season,
            favorite = readFavorite(intent)
        )
    }

    private fun readFavorite(intent: Intent): FavoritePlayback? {
        val count = intent.getIntExtra(EXTRA_FAV_COUNT, 0)
        if (count <= 0) return null
        val entries = ArrayList<UgcEpisode>(count)
        for (index in 0 until count) {
            val prefix = EXTRA_FAV_PREFIX + index + "_"
            val bvid = intent.getStringExtra(prefix + "bvid").orEmpty()
            val cid = intent.getLongExtra(prefix + "cid", 0L)
            // 收藏夹队列的 cid 可能为 0（收藏接口不返回），只校验 bvid
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
            folderId = intent.getLongExtra(EXTRA_FAV_FOLDER_ID, 0L),
            folderName = intent.getStringExtra(EXTRA_FAV_NAME).orEmpty(),
            entries = entries,
            startIndex = intent.getIntExtra(EXTRA_FAV_START_INDEX, 0).coerceIn(0, entries.lastIndex)
        )
    }
}
