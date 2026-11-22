package com.yuilittle.bili

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 把播放引擎的真实队列投影给 MediaSession：
 * ExoPlayer 仍只装当前这一首，但 Session 能看到完整队列，
 * 通知栏 / 锁屏的上/下一首与播放暂停命令都会转交给引擎。
 */
@UnstableApi
internal class AudioSessionPlayer(
    private val engine: AudioPlaybackEngine
) : SimpleBasePlayer(Looper.getMainLooper()) {

    init {
        engine.addListener(object : EngineListener {
            override fun onSnapshotChanged(snapshot: PlaybackSnapshot) = invalidateState()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                invalidateState()
                scheduleTick(isPlaying)
            }
            override fun onPlaybackEnded() = invalidateState()
        })
    }

    /** 播放中周期性重建 State，让通知栏进度条与时间能持续前进。 */
    private fun scheduleTick(playing: Boolean) {
        handler.removeCallbacks(tickRunnable)
        if (playing) handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private val handler = android.os.Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!engine.isPlaying()) return
            invalidateState()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun handleRelease(): com.google.common.util.concurrent.ListenableFuture<*> {
        handler.removeCallbacks(tickRunnable)
        return super.handleRelease()
    }

    override fun getState(): State {
        val snapshot = engine.snapshot()
        val queue = buildQueue(snapshot)
        val lastIndex = (queue.size - 1).coerceAtLeast(0)
        val currentIndex = snapshot.queueIndex.coerceIn(0, lastIndex)
        val durationMs = engine.durationMs().coerceAtLeast(0L)
        val positionMs = engine.positionMs().coerceIn(0L, durationMs)

        val playerError = snapshot.error.takeIf { it.isNotBlank() }?.let {
            PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
        }
        // SimpleBasePlayer 硬校验：playerError 非空时必须 STATE_IDLE，且此时 isLoading 必须为 false
        val playbackState = when {
            playerError != null -> Player.STATE_IDLE
            queue.isEmpty() -> Player.STATE_IDLE
            snapshot.loading -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val loading = snapshot.loading && playbackState == Player.STATE_BUFFERING

        val builder = State.Builder()
            .setAvailableCommands(buildCommands(queue.size, snapshot.queueIndex))
            .setPlaylist(queue)
            .setPlayWhenReady(engine.playWhenReady(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(playerError)
            .setIsLoading(loading)

        if (queue.isNotEmpty()) {
            // 必须实时读引擎位置：写死快照值会让通知栏进度卡在建快照那一刻
            builder
                .setCurrentMediaItemIndex(currentIndex)
                .setContentPositionMs(
                    SimpleBasePlayer.PositionSupplier { engine.positionMs().coerceAtLeast(0L) }
                )
        }
        return builder.build()
    }

    /** 上/下一首命令仅在队列确实可切时下发，否则通知栏按钮会亮着但点了没反应。 */
    private fun buildCommands(queueSize: Int, currentIndex: Int): Player.Commands {
        val builder = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_STOP)
        if (queueSize > 1) {
            if (engine.canSkipNext()) {
                builder.add(Player.COMMAND_SEEK_TO_NEXT)
                builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (engine.canSkipPrevious()) {
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
        }
        return builder.build()
    }

    private fun buildQueue(snapshot: PlaybackSnapshot): List<MediaItemData> {
        if (snapshot.isOnline) {
            val seasonTitle = snapshot.seasonTitle
            return engine.onlineQueueSnapshot().mapIndexed { index, ep ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(ep.title.ifBlank { "未命名音频" })
                    .setArtist(seasonTitle)
                    .setArtworkUri(ep.cover.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
                val item = MediaItem.Builder()
                    .setMediaId("online:${ep.bvid}:${ep.cid}")
                    .setMediaMetadata(metadata)
                    .build()
                MediaItemData.Builder(index)
                    .setMediaItem(item)
                    .setMediaMetadata(metadata)
                    .setDurationUs((ep.duration.toLong() * 1000L * 1000L).coerceAtLeast(0L))
                    .setIsSeekable(true)
                    .build()
            }
        }
        return engine.localQueueSnapshot().mapIndexed { index, task ->
            val metadata = MediaMetadata.Builder()
                .setTitle(task.title.ifBlank { "未命名音频" })
                .setArtist(task.owner)
                .setArtworkUri(task.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                .build()
            val item = MediaItem.Builder()
                .setMediaId("local:${task.id}")
                .setMediaMetadata(metadata)
                .build()
            MediaItemData.Builder(index)
                .setMediaItem(item)
                .setMediaMetadata(metadata)
                .setDurationUs((task.durationMs * 1000L).coerceAtLeast(0L))
                .setIsSeekable(true)
                .build()
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        engine.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> engine.playNext(auto = false)
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> engine.playPrevious()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> engine.seekTo(positionMs)
            else -> {
                if (mediaItemIndex >= 0 && mediaItemIndex != engine.snapshot().queueIndex) {
                    if (engine.isOnlineMode()) {
                        engine.onlineQueueSnapshot().getOrNull(mediaItemIndex)?.let(engine::selectOnline)
                    } else {
                        engine.localQueueSnapshot().getOrNull(mediaItemIndex)?.let { engine.selectLocal(it.id) }
                    }
                } else {
                    engine.seekTo(positionMs)
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** 通知栏进度刷新间隔（与系统媒体卡片的刷新节奏匹配）。 */
        const val TICK_INTERVAL_MS = 500L
    }
}
