package com.yuilittle.bili

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 首页「直播」面板：人气直播间网格。
 *
 * 与推荐/热门共用首页 tab 栏；手机 2 列、平板 4 列，滚动到底自动续页。
 * 卡片点击直接进入 [LiveRoomActivity]。
 */
internal class LiveFeedPanel(context: Context) : FrameLayout(context) {

    private val rooms = ArrayList<LiveRoom>()
    private val adapter = LiveAdapter()
    private val grid = GridView(context)
    private val stateText = TextView(context).apply {
        textSize = 13f
        typeface = Ds.body()
        gravity = Gravity.CENTER
        setTextColor(COLOR_MUTED)
        visibility = View.GONE
    }

    private var loadRequest: BiliRequest? = null
    private var page = 0
    private var loading = false
    private var endReached = false
    private var loadedOnce = false
    private var itemWidth = 0
    private var coverHeight = 0

    private val columns: Int
        get() = if (LayoutMode.isTablet) 4 else 2

    init {
        setBackgroundColor(COLOR_BACKGROUND)
        grid.apply {
            numColumns = columns
            stretchMode = GridView.NO_STRETCH
            verticalSpacing = dp(10)
            horizontalSpacing = dp(8)
            setPadding(dp(6), dp(4), dp(6), dp(16))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            adapter = this@LiveFeedPanel.adapter
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                    if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 4) {
                        loadNextPage()
                    }
                }
            })
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(stateText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
        })
        setOnClickListener { if (rooms.isEmpty() && !loading) reload() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val available = w - dp(12) - dp(8) * (columns - 1)
        val nextItemWidth = (available / columns).coerceAtLeast(dp(120))
        if (nextItemWidth != itemWidth) {
            itemWidth = nextItemWidth
            coverHeight = itemWidth * 9 / 16
            grid.columnWidth = itemWidth
            adapter.notifyDataSetChanged()
        }
    }

    fun activate() {
        if (!loadedOnce) reload()
    }

    fun prepareVisible() {
        if (!loadedOnce) reload()
    }

    fun pauseRequests() = Unit

    fun isAtTop(): Boolean = grid.firstVisiblePosition <= 0

    fun firstVisibleRowPosition(): Int = grid.firstVisiblePosition

    fun scrollToTop(): Boolean {
        if (rooms.isEmpty()) return false
        if (grid.firstVisiblePosition <= 0) return false
        grid.smoothScrollToPosition(0)
        return true
    }

    fun cancelScrollToTop() = Unit

    private fun reload() {
        loadRequest?.cancel()
        page = 0
        endReached = false
        loadedOnce = true
        rooms.clear()
        adapter.notifyDataSetChanged()
        requestPage(1)
    }

    private fun loadNextPage() {
        if (loading || endReached || !loadedOnce) return
        requestPage(page + 1)
    }

    private fun requestPage(target: Int) {
        if (loading) return
        loading = true
        if (rooms.isEmpty()) {
            stateText.text = "正在加载直播…"
            stateText.visibility = View.VISIBLE
        }
        loadRequest = LiveApi.fetchRooms(target) { list, error ->
            loading = false
            if (list == null) {
                stateText.text = error ?: "直播列表加载失败"
                stateText.visibility = View.VISIBLE
                return@fetchRooms
            }
            page = target
            if (target == 1) rooms.clear()
            // 房间可能跨页重复（人气榜变动），按 roomId 去重。
            val existing = rooms.mapTo(HashSet()) { it.roomId }
            list.filter { it.roomId > 0L && existing.add(it.roomId) }.forEach(rooms::add)
            if (list.isEmpty()) endReached = true
            adapter.notifyDataSetChanged()
            when {
                rooms.isEmpty() -> {
                    stateText.text = "当前没有正在直播的房间"
                    stateText.visibility = View.VISIBLE
                }
                endReached -> {
                    stateText.text = ""
                    stateText.visibility = View.GONE
                }
                else -> stateText.visibility = View.GONE
            }
        }
    }

    private inner class LiveAdapter : BaseAdapter() {
        override fun getCount(): Int = rooms.size
        override fun getItem(position: Int): Any = rooms[position]
        override fun getItemId(position: Int): Long = rooms[position].roomId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card = convertView as? LinearLayout ?: buildCard().also { it.tag = Holder(it) }
            val holder = card.tag as Holder
            val room = rooms[position]
            holder.title.text = room.title
            holder.owner.text = if (room.areaName.isNotBlank()) "${room.owner} · ${room.areaName}" else room.owner
            holder.online.text = if (room.online > 0L) formatOnline(room.online) else ""
            holder.online.visibility = if (room.online > 0L) View.VISIBLE else View.GONE
            if (room.cover.isNotBlank()) {
                CoverLoader.load(holder.cover, room.cover, animate = false, thumbEdge = itemWidth.coerceAtLeast(240))
            } else {
                holder.cover.setImageDrawable(null)
            }
            card.setOnClickListener { LiveRoomActivity.open(context, room) }
            val wrap = card.getChildAt(0) as FrameLayout
            wrap.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, coverHeight)
            card.layoutParams = AbsListView.LayoutParams(itemWidth, coverHeight + dp(70))
            return card
        }
    }

    private class Holder(val root: LinearLayout) {
        val cover: ImageView = root.getChildAt(0).let { (it as FrameLayout).getChildAt(0) as ImageView }
        val online: TextView = root.getChildAt(0).let {
            ((it as FrameLayout).getChildAt(2) as LinearLayout).getChildAt(0) as TextView
        }
        val title: TextView = root.getChildAt(1) as TextView
        val owner: TextView = root.getChildAt(2) as TextView
    }

    private fun buildCard(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
        }
        val coverWrap = FrameLayout(context).apply {
            background = Ds.card(context, COLOR_SURFACE, Ds.colors.lineSoft, Ds.Radius.card)
            Ds.clipRounded(this, Ds.Radius.card)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_SURFACE)
        }
        coverWrap.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val liveBadge = TextView(context).apply {
            text = "直播中"
            textSize = 9f
            typeface = Ds.body(true)
            setTextColor(COLOR_ON_ROSE)
            background = Ds.pill(context, COLOR_ROSE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        coverWrap.addView(liveBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = dp(6)
            topMargin = dp(6)
        })

        // 底部人气渐变条
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(6), 0, dp(6), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0x99000000.toInt(), 0x00000000)
            )
        }
        val online = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(true)
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
        }
        bottomRow.addView(online, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        coverWrap.addView(bottomRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(22),
            Gravity.BOTTOM
        ))

        card.addView(coverWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0))

        val title = TextView(context).apply {
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(7), 0, 0)
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val owner = TextView(context).apply {
            textSize = 11f
            typeface = Ds.body()
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(owner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return card
    }

    private fun formatOnline(value: Long): String = when {
        value >= 100_000_000L -> String.format("%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format("%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDetachedFromWindow() {
        loadRequest?.cancel()
        super.onDetachedFromWindow()
    }
}
