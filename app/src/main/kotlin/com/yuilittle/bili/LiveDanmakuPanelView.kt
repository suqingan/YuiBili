package com.yuilittle.bili

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 直播弹幕历史面板（手机横屏放播放器右侧，竖屏放播放区下方）。
 *
 * 只保留最近 [limit] 条，超出后从顶部移除；用户停留在底部时自动跟随新弹幕，
 * 手动上滑浏览历史时不会被强制拉回底部。
 */
internal class LiveDanmakuPanelView(context: Context) : LinearLayout(context) {

    private val scrollView: ScrollView
    private val listBox: LinearLayout
    private val emptyHint: TextView
    private val countText: TextView
    private val titleText: TextView
    private val settingsButton: TextView

    private var limit = DanmakuSettingsStore.snapshot().historyLimit
    private var totalReceived = 0

    var onSettingsClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF0E0F13.toInt())

        // ── 标题栏 ──
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(8))
        }
        titleText = TextView(context).apply {
            text = "弹幕"
            textSize = 13f
            typeface = Ds.body(true)
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }
        header.addView(titleText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        countText = TextView(context).apply {
            textSize = 10.5f
            typeface = Ds.body()
            setTextColor(0x80FFFFFF.toInt())
            includeFontPadding = false
            setPadding(dp(6), dp(1), 0, 0)
        }
        header.addView(countText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(View(context), LayoutParams(0, 1, 1f))

        settingsButton = TextView(context).apply {
            text = "设置"
            textSize = 11f
            typeface = Ds.body(true)
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ds.pill(context, 0x1FFFFFFF, 0x24FFFFFF)
            isClickable = true
            Ds.bindPressFeedback(this, 0.92f)
            setOnClickListener { onSettingsClick?.invoke() }
        }
        header.addView(settingsButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(26)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(View(context).apply { setBackgroundColor(0x14FFFFFF) },
            LayoutParams(LayoutParams.MATCH_PARENT, Ds.hairline(context)))

        // ── 消息列表 ──
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(10))
        }
        listBox = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
        }
        emptyHint = TextView(context).apply {
            text = "正在连接弹幕…"
            textSize = 12f
            typeface = Ds.body()
            setTextColor(0x66FFFFFF)
            setPadding(dp(14), dp(14), dp(14), 0)
        }
        val scrollHost = android.widget.FrameLayout(context).apply {
            addView(scrollView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            addView(emptyHint, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            })
        }
        scrollView.addView(listBox, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(scrollHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateEmptyState() }
        updateCount()
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun setLimit(nextLimit: Int) {
        limit = nextLimit.coerceAtLeast(1)
        while (listBox.childCount > limit) listBox.removeViewAt(0)
        updateCount()
        updateEmptyState()
    }

    fun clear() {
        listBox.removeAllViews()
        totalReceived = 0
        updateCount()
        updateEmptyState()
    }

    /** 标记连接状态：未连接且列表为空时给出更明确的提示。 */
    fun setConnectionState(connected: Boolean) {
        emptyHint.text = if (connected) "暂无弹幕，等待观众发言…" else "弹幕连接中…"
        updateEmptyState()
    }

    fun addDanmaku(item: LiveDanmaku) {
        val atBottom = isNearBottom()
        val row = TextView(context).apply {
            textSize = 12.5f
            typeface = Ds.body()
            setLineSpacing(dp(2).toFloat(), 1f)
            includeFontPadding = false
            setPadding(0, dp(3), 0, dp(3))
            val builder = SpannableStringBuilder()
            val name = item.user.ifBlank { "匿名用户" }
            builder.append(name)
            builder.append("：")
            val nameStart = 0
            builder.setSpan(
                ForegroundColorSpan(if (item.isAdmin) 0xFFFF9BB8.toInt() else 0xFF7FB6F2.toInt()),
                nameStart,
                name.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val bodyStart = builder.length
            builder.append(item.text)
            builder.setSpan(
                ForegroundColorSpan(0xE6FFFFFF.toInt()),
                bodyStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = builder
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
        }
        listBox.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        while (listBox.childCount > limit) listBox.removeViewAt(0)
        totalReceived++
        updateCount()
        updateEmptyState()
        if (atBottom) scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun isNearBottom(): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        val distance = child.bottom - (scrollView.height + scrollView.scrollY)
        return distance <= dp(72)
    }

    private fun updateCount() {
        val shown = listBox.childCount
        countText.text = if (shown > 0) "· $shown 条" else ""
    }

    private fun updateEmptyState() {
        emptyHint.visibility = if (listBox.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
