package com.yuilittle.bili

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 直播间播放页。
 *
 * 布局对齐官方客户端：
 * - 竖屏：顶部 16:9 播放器（左下 暂停/刷新/弹幕开关，右下 画质/全屏），下方直播信息与弹幕列表；
 * - 横屏：左侧播放器、右侧弹幕列表面板，系统栏隐藏进入沉浸模式；
 * - 弹幕来自官方长连接（zlib 协议），叠加层复用视频播放页的 [DanmakuOverlayView]，
 *   显示区域比例、字号、只显示后 N 条等设置与视频播放页共享。
 */
class LiveRoomActivity : Activity() {

    companion object {
        private const val EXTRA_ROOM_ID = "live_room_id"
        private const val EXTRA_TITLE = "live_title"
        private const val EXTRA_OWNER = "live_owner"
        private const val EXTRA_AREA = "live_area"
        private const val EXTRA_ONLINE = "live_online"
        private const val EXTRA_COVER = "live_cover"

        internal fun open(context: Context, room: LiveRoom) {
            if (room.roomId <= 0L) return
            context.startActivity(
                Intent(context, LiveRoomActivity::class.java)
                    .putExtra(EXTRA_ROOM_ID, room.roomId)
                    .putExtra(EXTRA_TITLE, room.title)
                    .putExtra(EXTRA_OWNER, room.owner)
                    .putExtra(EXTRA_AREA, room.areaName)
                    .putExtra(EXTRA_ONLINE, room.online)
                    .putExtra(EXTRA_COVER, room.cover)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sessionBaseMs = SystemClock.elapsedRealtime()

    // 直播间基础信息
    private var roomId = 0L
    private var roomTitle = ""
    private var roomOwner = ""
    private var roomArea = ""
    private var roomOnline = 0L

    // 视图
    private lateinit var root: FrameLayout
    private lateinit var playerArea: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var danmakuOverlay: DanmakuOverlayView
    private lateinit var topBar: LinearLayout
    private lateinit var barTitle: TextView
    private lateinit var onlineChip: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var playPauseIcon: LivePlayPauseIcon
    private lateinit var danmakuToggle: DanmakuUi.ToggleIconView
    private lateinit var qualityBtn: TextView
    private lateinit var fullscreenIcon: LiveFullscreenIcon
    private lateinit var infoBlock: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoMeta: TextView
    private lateinit var chatPanel: LiveDanmakuPanelView
    private lateinit var statusBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusAction: TextView

    // 播放状态
    private var player: ExoPlayer? = null
    private var playRequest: BiliRequest? = null
    private var playInfo: LivePlayInfo? = null
    private var playUrls: List<String> = emptyList()
    private var playIndex = 0
    private var qualityQn = 10000
    private var retryCount = 0
    private var loadingStream = false
    /** 播放地址请求代次：切换画质会取消上一请求，只有最新一代的回调能落地。 */
    private var streamGeneration = 0

    // 弹幕状态
    private var danmakuClient: LiveDanmakuClient? = null
    private val danmakuHistory = ArrayDeque<DanmakuItem>()

    /** 本房间内被服务端降级过的档位：点了也只会回到当前清晰度，菜单里不再重复展示。 */
    private val unavailableQn = HashSet<Int>()

    private var isLandscape = false
    private var controlsVisible = true

    private val danmakuTicker = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val playing = player?.isPlaying == true
            danmakuOverlay.syncClock(liveNowMs(), playing, 1f)
            handler.postDelayed(this, 120L)
        }
    }

    private val hideControlsTask = Runnable {
        if (player?.isPlaying == true) setControlsVisible(false)
    }

    private val danmakuSettingsListener: (DanmakuSettingsStore.Snapshot) -> Unit = { snapshot ->
        danmakuOverlay.applySettings(snapshot)
        danmakuToggle.setDanmakuEnabled(snapshot.enabled)
        chatPanel.setLimit(snapshot.historyLimit)
        while (danmakuHistory.size > snapshot.historyLimit) danmakuHistory.removeFirst()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent()
        if (roomId <= 0L) {
            finish()
            return
        }
        PlayerSettingsStore.applyPlaybackOrientation(this)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        Theme.applySystemBars(this, lightContent = true)

        DanmakuSettingsStore.init(this)
        qualityQn = PlayerSettingsStore.liveQuality(this)

        buildViews()
        setContentView(root)
        setupPlayer()
        applyLayout()
        startDanmaku()

        DanmakuSettingsStore.addListener(danmakuSettingsListener)
        danmakuSettingsListener(DanmakuSettingsStore.snapshot())

        loadStream()
    }

    private fun readIntent() {
        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "直播间 $roomId" }
        roomOwner = intent.getStringExtra(EXTRA_OWNER).orEmpty().ifBlank { "哔哩哔哩主播" }
        roomArea = intent.getStringExtra(EXTRA_AREA).orEmpty()
        roomOnline = intent.getLongExtra(EXTRA_ONLINE, 0L)
    }

    // ─────────────────────────────────────────────────
    //  视图搭建
    // ─────────────────────────────────────────────────

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerArea = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener { toggleControls() }
        }
        playerArea.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        danmakuOverlay = DanmakuOverlayView(this).apply {
            isClickable = false
            isFocusable = false
        }
        playerArea.addView(danmakuOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        buildTopBar()
        buildBottomBar()
        buildStatusBox()
        buildInfoBlock()

        chatPanel = LiveDanmakuPanelView(this).apply {
            setLimit(DanmakuSettingsStore.snapshot().historyLimit)
            onSettingsClick = { showDanmakuSettings() }
        }
    }

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3000000.toInt(), 0x00000000)
            )
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.circle(0x59000000)
            isClickable = true
            contentDescription = "返回"
            Ds.bindPressFeedback(this, 0.9f)
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(34), dp(34)))

        barTitle = TextView(this).apply {
            text = roomTitle
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            setShadowLayer(2f, 0f, 1f, 0x66000000)
        }
        topBar.addView(barTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        onlineChip = TextView(this).apply {
            text = buildOnlineText()
            textSize = 10.5f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(0xF2FFFFFF.toInt())
            background = Ds.pill(this@LiveRoomActivity, 0x40000000, 0x2EFFFFFF)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            if (roomOnline <= 0L) visibility = View.GONE
        }
        topBar.addView(onlineChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        playerArea.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xD9000000.toInt(), 0x00000000)
            )
        }

        playPauseIcon = LivePlayPauseIcon(this).apply {
            isClickable = true
            contentDescription = "暂停"
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener {
                val exo = player ?: return@setOnClickListener
                if (exo.isPlaying) exo.pause() else exo.play()
                showControlsBriefly()
            }
        }
        bottomBar.addView(playPauseIcon, LinearLayout.LayoutParams(dp(32), dp(32)))

        val refresh = LiveRefreshIcon(this).apply {
            isClickable = true
            contentDescription = "刷新直播"
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener {
                retryCount = 0
                loadStream()
                restartDanmaku()
                Toast.makeText(this@LiveRoomActivity, "正在重新连接直播", Toast.LENGTH_SHORT).show()
            }
        }
        bottomBar.addView(refresh, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            leftMargin = dp(8)
        })

        danmakuToggle = DanmakuUi.ToggleIconView(this, onDarkPlayer = true).apply {
            isClickable = true
            setDanmakuEnabled(DanmakuSettingsStore.snapshot().enabled)
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener {
                val next = DanmakuSettingsStore.toggleEnabled()
                toast(if (next.enabled) "弹幕已开启" else "弹幕已关闭")
                showControlsBriefly()
            }
        }
        bottomBar.addView(danmakuToggle, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            leftMargin = dp(10)
        })

        val settings = DanmakuUi.SettingsIconView(this).apply {
            isClickable = true
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { showDanmakuSettings() }
        }
        bottomBar.addView(settings, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            leftMargin = dp(4)
        })

        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        qualityBtn = TextView(this).apply {
            text = "画质"
            textSize = 11f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            maxLines = 1
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ds.pill(this@LiveRoomActivity, 0x33000000, 0x2EFFFFFF)
            isClickable = true
            Ds.bindPressFeedback(this, 0.90f)
            setOnClickListener { showQualityMenu() }
        }
        bottomBar.addView(qualityBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply { rightMargin = dp(8) })

        fullscreenIcon = LiveFullscreenIcon(this).apply {
            isClickable = true
            contentDescription = "切换全屏"
            Ds.bindPressFeedback(this, 0.88f)
            setOnClickListener { toggleFullscreen() }
        }
        bottomBar.addView(fullscreenIcon, LinearLayout.LayoutParams(dp(30), dp(30)))

        playerArea.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun buildStatusBox() {
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = Ds.plate(0xA6000000.toInt(), 0x1FFFFFFF, Ds.hairline(this@LiveRoomActivity), dp(14).toFloat())
            visibility = View.VISIBLE
        }
        statusText = TextView(this).apply {
            text = "正在连接直播…"
            textSize = 12.5f
            typeface = Ds.body()
            gravity = Gravity.CENTER
            setTextColor(0xF2FFFFFF.toInt())
        }
        statusBox.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusAction = TextView(this).apply {
            text = "重新连接"
            textSize = 12f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ds.pill(this@LiveRoomActivity, COLOR_ROSE)
            isClickable = true
            visibility = View.GONE
            setPadding(dp(16), dp(7), dp(16), dp(7))
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                retryCount = 0
                loadStream()
                restartDanmaku()
            }
        }
        statusBox.addView(statusAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        playerArea.addView(statusBox, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun buildInfoBlock() {
        infoBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(0xFF0B0C10.toInt())
        }
        infoTitle = TextView(this).apply {
            text = roomTitle
            textSize = 16.5f
            typeface = Ds.body(true)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        infoBlock.addView(infoTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        infoMeta = TextView(this).apply {
            text = buildMeta()
            textSize = 12f
            typeface = Ds.body()
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(7), 0, 0)
        }
        infoBlock.addView(infoMeta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "直播画面存在延迟；卡顿时可点击画面中的刷新按钮重新连接。"
            textSize = 11.5f
            typeface = Ds.body()
            setTextColor(0x59FFFFFF)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        infoBlock.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ─────────────────────────────────────────────────
    //  布局适配（竖屏 / 横屏）
    // ─────────────────────────────────────────────────

    private fun applyLayout() {
        val config = resources.configuration
        val nextLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        isLandscape = nextLandscape

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        (playerArea.parent as? ViewGroup)?.removeView(playerArea)
        (infoBlock.parent as? ViewGroup)?.removeView(infoBlock)
        (chatPanel.parent as? ViewGroup)?.removeView(chatPanel)
        root.removeAllViews()

        if (nextLandscape) {
            // 全屏 = 整屏直播画面：不保留右侧历史弹幕面板，也不留信息区；
            // 弹幕只以叠加层形式浮在画面上。
            root.addView(playerArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            danmakuOverlay.setFullscreenMode(true)
            hideSystemBars()
        } else {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            val playerHeight = minOf(
                (screenW * 9f / 16f).toInt(),
                (screenH * 0.46f).toInt()
            ).coerceAtLeast(dp(120))
            column.addView(playerArea, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, playerHeight))
            column.addView(infoBlock, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            chatPanel.setBackgroundColor(0xFF0E0F13.toInt())
            column.addView(chatPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            danmakuOverlay.setFullscreenMode(false)
            showSystemBars()
        }

        fullscreenIcon.setFullscreen(nextLandscape)
        // 竖屏时标题在播放区下方展示，顶部栏不再重复，只保留返回与人气。
        barTitle.text = if (nextLandscape) roomTitle else ""
        danmakuOverlay.syncClock(liveNowMs(), player?.isPlaying == true, 1f)
    }

    // ─────────────────────────────────────────────────
    //  播放器
    // ─────────────────────────────────────────────────

    private fun setupPlayer() {
        val http = OkHttpDataSource.Factory(BiliHttp.mediaClient)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to NETWORK_USER_AGENT,
                    "Referer" to "https://live.bilibili.com/",
                    "Origin" to "https://live.bilibili.com"
                )
            )
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    hideStatus()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPauseIcon.setPlaying(isPlaying)
                playPauseIcon.contentDescription = if (isPlaying) "暂停" else "播放"
                if (isPlaying) {
                    scheduleHideControls()
                } else {
                    handler.removeCallbacks(hideControlsTask)
                    setControlsVisible(true)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (playIndex + 1 < playUrls.size) {
                    playIndex++
                    playCurrent()
                } else if (retryCount < 1) {
                    retryCount++
                    loadStream()
                } else {
                    setControlsVisible(true)
                    showError("直播流连接失败，请稍后重试")
                }
            }
        })
    }

    private fun loadStream(fromQualityChange: Boolean = false) {
        // 旧请求直接取消并用代次隔离回调：取消的请求不会再回调，
        // 之前用 loadingStream 布尔锁会让后续切换画质被静默吞掉。
        val generation = ++streamGeneration
        playRequest?.cancel()
        playRequest = null
        loadingStream = true
        if (!fromQualityChange) showStatus("正在连接直播…", actionVisible = false)
        playRequest = LiveApi.fetchPlayInfo(roomId, qualityQn) { info, error ->
            if (generation != streamGeneration) return@fetchPlayInfo
            loadingStream = false
            if (isFinishing || isDestroyed) return@fetchPlayInfo
            if (info == null) {
                showError(error ?: "直播加载失败")
                statusAction.visibility = View.VISIBLE
                return@fetchPlayInfo
            }
            playInfo = info
            qualityQn = info.qn
            qualityBtn.text = info.qualityLabel
            if (fromQualityChange && info.qn != info.requestedQn) {
                unavailableQn += info.requestedQn
                toast("当前仅可观看 ${info.qualityLabel}")
            }
            playUrls = info.urls
            playIndex = 0
            retryCount = 0
            playCurrent()
        }
    }

    private fun playCurrent() {
        val exo = player ?: return
        val url = playUrls.getOrNull(playIndex) ?: return
        showStatus("正在缓冲…", actionVisible = false)
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun startDanmaku() {
        if (danmakuClient != null) return
        val client = LiveDanmakuClient(roomId, ::onDanmakuReceived) { connected ->
            chatPanel.setConnectionState(connected)
        }
        danmakuClient = client
        client.start()
    }

    private fun restartDanmaku() {
        danmakuClient?.stop()
        danmakuClient = null
        startDanmaku()
    }

    private fun onDanmakuReceived(item: LiveDanmaku) {
        chatPanel.addDanmaku(item)
        val snapshot = DanmakuSettingsStore.snapshot()
        if (!snapshot.enabled) return

        val nowMs = liveNowMs()
        val model = DanmakuItem(
            id = item.id,
            progressMs = nowMs.toInt(),
            text = item.text,
            colorArgb = 0xFF000000.toInt() or (item.color and 0xFFFFFF),
            fontSize = 25,
            mode = DanmakuItem.MODE_SCROLL_NORMAL,
            weight = 0,
            pool = DanmakuItem.POOL_NORMAL,
            flags = 0
        )
        danmakuHistory.addLast(model)
        while (danmakuHistory.size > snapshot.historyLimit) danmakuHistory.removeFirst()
        danmakuOverlay.setSegmentData(0, danmakuHistory.toList(), nowMs)
    }

    // ─────────────────────────────────────────────────
    //  全屏 / 控制栏
    // ─────────────────────────────────────────────────

    private fun toggleFullscreen() {
        if (isLandscape) {
            if (PlayerSettingsStore.orientation(this) == PlayerSettingsStore.OrientationMode.LANDSCAPE) {
                toast("已按设置锁定横屏，可在「设置 - 播放页屏幕方向」中调整")
                return
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            showSystemBars()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            hideSystemBars()
        }
        setControlsVisible(true)
    }

    private fun toggleControls() {
        if (controlsVisible) setControlsVisible(false) else setControlsVisible(true)
    }

    private fun showControlsBriefly() {
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) {
            if (visible) scheduleHideControls()
            return
        }
        controlsVisible = visible
        handler.removeCallbacks(hideControlsTask)
        if (visible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            topBar.animate().alpha(1f).setDuration(150).start()
            bottomBar.animate().alpha(1f).setDuration(150).start()
            scheduleHideControls()
        } else {
            topBar.animate().alpha(0f).setDuration(200)
                .withEndAction { if (!controlsVisible) topBar.visibility = View.INVISIBLE }
                .start()
            bottomBar.animate().alpha(0f).setDuration(200)
                .withEndAction { if (!controlsVisible) bottomBar.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsTask)
        if (player?.isPlaying == true) handler.postDelayed(hideControlsTask, 3600L)
    }

    private fun showStatus(text: String, actionVisible: Boolean) {
        statusText.text = text
        statusBox.visibility = View.VISIBLE
        statusAction.visibility = if (actionVisible) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        statusBox.visibility = View.GONE
    }

    private fun showError(message: String) {
        showStatus(message, actionVisible = true)
        setControlsVisible(true)
        bottomBar.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        bottomBar.alpha = 1f
        topBar.alpha = 1f
        controlsVisible = true
    }

    // ─────────────────────────────────────────────────
    //  画质
    // ─────────────────────────────────────────────────

    private fun showQualityMenu() {
        val info = playInfo ?: return
        val qualities = info.qualities
            .filter { it.qn == info.qn || it.qn !in unavailableQn }
            .ifEmpty { listOf(LiveQuality(info.qn, info.qualityLabel)) }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = Ds.plate(0xE6181A20.toInt(), 0x26FFFFFF, Ds.hairline(this@LiveRoomActivity), dp(14).toFloat())
        }
        lateinit var popup: PopupWindow
        qualities.forEachIndexed { index, quality ->
            val selected = quality.qn == info.qn
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                if (selected) background = Ds.card(
                    this@LiveRoomActivity, fill = 0x28F27D9E, border = null, radiusDp = 9
                )
                isClickable = true
                Ds.bindPressFeedback(this, 0.96f)
                setOnClickListener {
                    popup.dismiss()
                    if (quality.qn != info.qn) {
                        PlayerSettingsStore.setLiveQuality(this@LiveRoomActivity, quality.qn)
                        qualityQn = quality.qn
                        qualityBtn.text = quality.label
                        // 接口本次已下发的档位直接用它的地址，切档立即生效且不再等一次网络往返。
                        val cached = info.streamsByQn[quality.qn]
                        if (!cached.isNullOrEmpty()) {
                            playInfo = info.copy(
                                urls = cached,
                                qn = quality.qn,
                                qualityLabel = quality.label,
                                requestedQn = quality.qn
                            )
                            playUrls = cached
                            playIndex = 0
                            retryCount = 0
                            playCurrent()
                        } else {
                            loadStream(fromQualityChange = true)
                        }
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = quality.label
                textSize = 13f
                typeface = Ds.body(selected)
                setTextColor(if (selected) 0xFFF27D9E.toInt() else Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "当前"
                    textSize = 10.5f
                    setTextColor(0xFFF27D9E.toInt())
                })
            }
            menu.addView(row, LinearLayout.LayoutParams(dp(150), dp(38)).apply {
                if (index > 0) topMargin = dp(2)
            })
        }
        if (qualities.size <= 1) {
            menu.addView(TextView(this).apply {
                text = "该直播间当前仅提供这一档清晰度（受推流与账号权限限制）"
                textSize = 10.5f
                typeface = Ds.body()
                setTextColor(0x8CFFFFFF.toInt())
                maxLines = 3
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(12), dp(8), dp(12), dp(6))
            }, LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        menu.measure(
            View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuW = menu.measuredWidth.coerceAtLeast(dp(132))
        val menuH = menu.measuredHeight
        popup = PopupWindow(menu, menuW, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(10).toFloat()
            isOutsideTouchable = true
        }
        val location = IntArray(2)
        qualityBtn.getLocationInWindow(location)
        val x = (location[0] + qualityBtn.width - menuW).coerceAtLeast(dp(8))
        val y = (location[1] - menuH - dp(8)).coerceAtLeast(dp(8))
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        showControlsBriefly()
    }

    // ─────────────────────────────────────────────────
    //  弹幕设置
    // ─────────────────────────────────────────────────

    private fun showDanmakuSettings() {
        setControlsVisible(true)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            background = Ds.plate(0xFF15171D.toInt(), 0x26FFFFFF, Ds.hairline(this@LiveRoomActivity), dp(20).toFloat())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "弹幕设置"
            textSize = 15f
            typeface = Ds.body(true)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this).apply {
            text = "完成"
            textSize = 12f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(0xFFF27D9E.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sheet.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val areaChips = ArrayList<Pair<Float, TextView>>()
        val sizeChips = ArrayList<Pair<Float, TextView>>()
        val limitChips = ArrayList<Pair<Int, TextView>>()

        fun styleChip(chip: TextView, active: Boolean) {
            if (active) {
                chip.background = Ds.card(
                    this, fill = 0x33F27D9E, border = 0xFFF27D9E.toInt(), radiusDp = Ds.Radius.chip
                )
                chip.setTextColor(0xFFF7A0B8.toInt())
            } else {
                chip.background = Ds.card(
                    this, fill = 0x16FFFFFF, border = 0x24FFFFFF, radiusDp = Ds.Radius.chip
                )
                chip.setTextColor(0xDCFFFFFF.toInt())
            }
        }

        fun applyStates() {
            val snapshot = DanmakuSettingsStore.snapshot()
            areaChips.forEach { (fraction, chip) ->
                styleChip(chip, kotlin.math.abs(snapshot.areaFraction - fraction) < 0.05f)
            }
            sizeChips.forEach { (scale, chip) ->
                styleChip(chip, kotlin.math.abs(snapshot.textScale - scale) < 0.01f)
            }
            limitChips.forEach { (limit, chip) -> styleChip(chip, snapshot.historyLimit == limit) }
        }

        fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener {
                onClick()
                applyStates()
            }
        }

        fun addSection(title: String) {
            sheet.addView(TextView(this).apply {
                text = title
                textSize = 11.5f
                typeface = Ds.body(true)
                setTextColor(0xA6FFFFFF.toInt())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            })
        }

        fun addChipRow(chips: List<TextView>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chips.forEachIndexed { index, chip ->
                row.addView(chip, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
            sheet.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 显示区域（弹幕覆盖屏幕比例）
        addSection("弹幕显示区域")
        val areaOptions = listOf(0.25f to "1/4 屏", 0.50f to "半屏", 0.75f to "3/4 屏", 1.00f to "全屏")
        addChipRow(areaOptions.map { (fraction, label) ->
            chip(label) {
                DanmakuSettingsStore.update { it.copy(areaFraction = fraction) }
            }.also { areaChips += fraction to it }
        })

        // 字号
        addSection("弹幕大小")
        val sizeOptions = listOf(0.80f to "小", 1.00f to "标准", 1.20f to "大", 1.40f to "特大")
        addChipRow(sizeOptions.map { (scale, label) ->
            chip(label) {
                DanmakuSettingsStore.update { it.copy(textScale = scale) }
            }.also { sizeChips += scale to it }
        })

        // 只显示后 N 条
        addSection("显示条数（只保留最近 N 条）")
        addChipRow(DanmakuSettingsStore.HISTORY_LIMIT_OPTIONS.map { limit ->
            chip("$limit 条") {
                DanmakuSettingsStore.setHistoryLimit(limit)
            }.also { limitChips += limit to it }
        })

        sheet.addView(TextView(this).apply {
            text = "设置与视频播放页共用；弹幕关闭后仍可在控制栏重新开启。"
            textSize = 11f
            typeface = Ds.body()
            setTextColor(0x66FFFFFF)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val host = FrameLayout(this).apply {
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        host.addView(sheet, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(host)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
        dialog.setOnDismissListener { DanmakuSettingsStore.flushPersist() }
        dialog.show()
        applyStates()
    }

    // ─────────────────────────────────────────────────
    //  系统栏 / 生命周期
    // ─────────────────────────────────────────────────

    private fun hideSystemBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun showSystemBars() {
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decor.windowInsetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        Theme.applySystemBars(this, lightContent = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            if (isFinishing || isDestroyed) return@post
            applyLayout()
            if (isLandscape) hideSystemBars() else showSystemBars()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isLandscape) {
            toggleFullscreen()
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (playUrls.isNotEmpty()) player?.play()
        startDanmaku()
        handler.removeCallbacks(danmakuTicker)
        handler.post(danmakuTicker)
        setControlsVisible(true)
    }

    override fun onPause() {
        player?.pause()
        handler.removeCallbacks(danmakuTicker)
        handler.removeCallbacks(hideControlsTask)
        danmakuClient?.stop()
        danmakuClient = null
        chatPanel.setConnectionState(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        DanmakuSettingsStore.removeListener(danmakuSettingsListener)
        DanmakuSettingsStore.flushPersist()
        danmakuClient?.stop()
        danmakuClient = null
        playRequest?.cancel()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────
    //  文案与工具
    // ─────────────────────────────────────────────────

    private fun liveNowMs(): Long =
        (SystemClock.elapsedRealtime() - sessionBaseMs).coerceAtLeast(0L)

    private fun buildOnlineText(): String {
        if (roomOnline <= 0L) return ""
        return "${formatCount(roomOnline)} 人气"
    }

    private fun buildMeta(): String {
        val parts = ArrayList<String>(3)
        parts += roomOwner
        if (roomArea.isNotBlank()) parts += roomArea
        if (roomOnline > 0L) parts += "${formatCount(roomOnline)} 人气"
        return parts.joinToString(" · ")
    }

    private fun formatCount(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
