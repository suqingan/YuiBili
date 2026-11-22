package com.yuilittle.bili

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 平板端双列真瀑布流动态容器：
 * 左右两列各自垂直堆叠，每次增量追加按两列估算高度自动放进较短的一列，
 * 彻底消除图文高卡旁边短视频卡底部留白的大块空洞。
 * 支持滚动位置回调（用于回顶按钮）与接近底部自动触发分页加载。
 */
internal class SpaceMasonryDynamicView(
    private val activity: Activity,
    private val horizontalPaddingDp: Int = 16,
    private val columnGapDp: Int = 12,
    private val cardBottomGapDp: Int = 12
) : ScrollView(activity) {

    var onScrollYChanged: ((scrollY: Int) -> Unit)? = null
    var onNearBottom: (() -> Unit)? = null

    private val rootContainer: LinearLayout
    private val columnsRow: LinearLayout
    private val leftCol: LinearLayout
    private val rightCol: LinearLayout
    private val footerHost: LinearLayout

    private var renderedCount = 0
    private var leftEstHeight = 0
    private var rightEstHeight = 0
    private var lastHighlight = ""
    private var nearBottomDispatchedCount = -1

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        val hPad = activity.dp(horizontalPaddingDp)
        setPadding(hPad, activity.dp(10), hPad, activity.dp(24))

        rootContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        columnsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        leftCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rightCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        columnsRow.addView(leftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = activity.dp(columnGapDp)
        })
        columnsRow.addView(rightCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootContainer.addView(columnsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        footerHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rootContainer.addView(footerHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        addView(rootContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setFooter(footer: View) {
        footerHost.removeAllViews()
        footerHost.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    fun clearAll() {
        clearColumnImages(leftCol)
        clearColumnImages(rightCol)
        leftCol.removeAllViews()
        rightCol.removeAllViews()
        renderedCount = 0
        leftEstHeight = 0
        rightEstHeight = 0
        nearBottomDispatchedCount = -1
        scrollTo(0, 0)
    }

    /**
     * 同步动态列表到双列瀑布流：
     * - 增量加载时只追加新增卡片（不重建已有视图、不跳滚动位置）；
     * - 刷新/清空/关键词变化时重置两列。
     */
    fun syncItems(
        items: List<SpaceDynamic>,
        highlightKeyword: String = "",
        forceRebuild: Boolean = false,
        onOpenDetail: (SpaceDynamic) -> Unit,
        onOpenVideo: (bvid: String?, aid: Long?) -> Unit,
        onFeedback: (String) -> Unit
    ) {
        if (forceRebuild || items.size < renderedCount || highlightKeyword != lastHighlight) {
            clearAll()
        }
        lastHighlight = highlightKeyword
        if (items.isEmpty()) return

        val screenW = resources.displayMetrics.widthPixels
        val cardW = ((screenW - activity.dp(horizontalPaddingDp * 2 + columnGapDp)) / 2)
            .coerceAtLeast(activity.dp(240))
        val bottomGap = activity.dp(cardBottomGapDp)

        for (i in renderedCount until items.size) {
            val item = items[i]
            val card = SpaceDynamicCardUi.createCard(
                activity = activity,
                item = item,
                highlightKeyword = highlightKeyword,
                cardWidthPx = cardW,
                cardMode = true,
                onOpenDetail = onOpenDetail,
                onOpenVideo = onOpenVideo,
                onFeedback = onFeedback
            )
            // 使用真实已测量列高优先，未完成首帧布局时回退估算高度，确保左右列紧紧接上
            val lHeight = if (leftCol.height > 0 && rightCol.height > 0) leftCol.height else leftEstHeight
            val rHeight = if (leftCol.height > 0 && rightCol.height > 0) rightCol.height else rightEstHeight
            val targetLeft = lHeight <= rHeight
            val col = if (targetLeft) leftCol else rightCol
            col.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = bottomGap })

            val est = estimateCardHeightPx(item, cardW)
            if (targetLeft) leftEstHeight += est else rightEstHeight += est
        }
        renderedCount = items.size
    }

    private fun estimateCardHeightPx(item: SpaceDynamic, cardW: Int): Int {
        var h = activity.dp(92) // 头部 + 底部统计 + 内边距
        if (!item.title.isNullOrBlank()) h += activity.dp(34)
        if (item.text.isNotBlank()) {
            val charsPerLine = (cardW / activity.dp(15).coerceAtLeast(1)).coerceAtLeast(16)
            val lines = ((item.text.length + charsPerLine - 1) / charsPerLine).coerceIn(1, 5)
            h += lines * activity.dp(22) + activity.dp(10)
        }
        when (item.kind) {
            DynamicKind.IMAGE -> {
                val n = item.images.size
                h += when {
                    n <= 0 -> 0
                    n == 1 -> activity.dp(220)
                    n <= 4 -> activity.dp(260)
                    else -> activity.dp(320)
                }
            }
            DynamicKind.VIDEO -> {
                val pure = item.text.isBlank() && item.title.isNullOrBlank() && item.images.isEmpty()
                h += if (pure) (cardW * 9f / 16f).toInt() + activity.dp(46) else activity.dp(96)
            }
            else -> h += activity.dp(48)
        }
        return h + activity.dp(cardBottomGapDp)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollYChanged?.invoke(t)
        val contentH = rootContainer.height
        val viewH = height
        if (renderedCount > 0 && contentH > viewH && t + viewH >= contentH - activity.dp(360)) {
            if (nearBottomDispatchedCount != renderedCount) {
                nearBottomDispatchedCount = renderedCount
                onNearBottom?.invoke()
            }
        }
    }

    private fun clearColumnImages(col: LinearLayout) {
        for (i in 0 until col.childCount) {
            SpaceDynamicCardUi.clearImages(col.getChildAt(i))
        }
    }
}
