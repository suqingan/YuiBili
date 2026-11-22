package com.yuilittle.bili

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 音频播放前台服务：唯一持有播放引擎与 MediaSession。
 * 离开页面 / Home / 锁屏后继续出声，通知栏使用系统默认媒体样式。
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private var engine: AudioPlaybackEngine? = null
    private var session: MediaSession? = null
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var pauseCleanup: Runnable? = null

    inner class LocalBinder : android.os.Binder() {
        internal fun engine(): AudioPlaybackEngine? = this@AudioPlaybackService.engine
    }

    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
        val playbackEngine = AudioPlaybackEngine(this)
        engine = playbackEngine
        val sessionPlayer = AudioSessionPlayer(playbackEngine)
        session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    AudioPlayerActivity.attachIntent(this),
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        playbackEngine.addListener(object : EngineListener {
            override fun onSnapshotChanged(snapshot: PlaybackSnapshot) {
                schedulePauseCleanup()
                // 队列被清空（用户离开音频页）时立即退出前台播放态
                if (snapshot.queueSize == 0) stopPlaybackAndSelf()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = schedulePauseCleanup()
            override fun onPlaybackEnded() = Unit
        })
        addSession(session!!)
    }

    /** 暂停后保留一段时间供恢复，超时则清理会话与通知。 */
    private fun schedulePauseCleanup() {
        pauseCleanup?.let { handler.removeCallbacks(it) }
        val playing = engine?.playWhenReady() == true || engine?.isPlaying() == true
        if (playing) {
            pauseCleanup = null
            return
        }
        val task = Runnable {
            if (engine?.playWhenReady() != true && engine?.isPlaying() != true) {
                stopPlaybackAndSelf()
            }
        }
        pauseCleanup = task
        handler.postDelayed(task, PAUSE_CLEANUP_DELAY_MS)
    }

    private fun stopPlaybackAndSelf() {
        engine?.pause()
        (session?.player as? AudioSessionPlayer)?.stop()
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onBind(intent: Intent?): IBinder? {
        // 同进程私有绑定走本地 Binder，其余交给 MediaSessionService 处理
        return if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 播放中继续；非播放状态清理退出
        if (engine?.isPlaying() == true || engine?.playWhenReady() == true) {
            super.onTaskRemoved(rootIntent)
        } else {
            stopPlaybackAndSelf()
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onDestroy() {
        pauseCleanup?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL_BIND = "com.yuilittle.bili.action.LOCAL_BIND"
        private const val PAUSE_CLEANUP_DELAY_MS = 5 * 60_000L
    }
}
