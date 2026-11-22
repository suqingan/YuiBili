package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PGC 播放页信息块：左竖版海报 + 右侧标题/标签/评分，简介完整显示。
 *
 * 布局自管理（onMeasure/onLayout）：简介从「标题+标签」下方开始，
 * 与海报在纵向上重叠；FlowMarginSpan 让海报覆盖的行缩进「海报宽 + 间距」，
 * 其后各行全宽——右侧放不下时文字自然续到海报下方。
 * 简介高度用 UNSPECIFIED 测量，始终按全文高度展开，不受父容器可用高度影响。
 */
internal class PgcIntroBlockView(context: Context) : FrameLayout(context) {

    private fun dpF(v: Float): Float = v * context.resources.displayMetrics.density

    private val posterWidthPx = context.dp(96)
    private val posterHeightPx = context.dp(128)
    private val posterGapPx = context.dp(12)
    private val descGapPx = context.dp(8)

    private val descView: TextView
    private var descFull = ""
    private var appliedSideLines = -1

    private lateinit var posterView: ImageView
    private lateinit var posterFrame: FrameLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var tagRow: LinearLayout

    init {
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val posterFrame2 = FrameLayout(context).apply {
            background = Ds.card(context, Ds.colors.cover, Ds.colors.lineSoft, Ds.Radius.chip)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        posterFrame2.addView(poster, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        posterView = poster
        posterFrame = posterFrame2
        topRow.addView(posterFrame2, LinearLayout.LayoutParams(posterWidthPx, posterHeightPx))

        rightCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(posterGapPx, 0, 0, 0)
        }
        titleView = TextView(context).apply {
            textSize = 17f
            typeface = Ds.display(bold = true)
            setTextColor(Ds.colors.text)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dpF(2f), 1f)
        }
        rightCol.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, context.dp(6), 0, 0)
        }
        rightCol.addView(tagRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topRow.addView(rightCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(topRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        descView = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Ds.colors.muted)
            includeFontPadding = false
            setLineSpacing(dpF(3f), 1f)
        }
        addView(descView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    fun bind(
        title: String,
        typeLabel: String,
        styles: String,
        score: String,
        coverUrl: String,
        descFull: String
    ) {
        this.descFull = descFull
        appliedSideLines = -1
        titleView.text = title
        tagRow.removeAllViews()
        if (typeLabel.isNotBlank()) tagRow.addView(chip(typeLabel, highlighted = true))
        styles.split("/", ",", "、").map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .forEach { tagRow.addView(chip(it, highlighted = false)) }
        if (score.isNotBlank()) {
            posterFrame.addView(TextView(context).apply {
                text = "${score}分"
                textSize = 11f
                typeface = Ds.display(bold = true)
                setTextColor(0xFFFFCA58.toInt())
                includeFontPadding = false
                fontFeatureSettings = "tnum"
                background = Ds.pill(context, 0xA614161B.toInt(), null)
                setPadding(context.dp(5), context.dp(2), context.dp(5), context.dp(2))
            }, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = context.dp(5)
                bottomMargin = context.dp(5)
            })
        }
        CoverLoader.load(posterView, coverUrl, cropThumb = false, thumbEdge = 384)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val topRow = getChildAt(0)
        topRow.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        // 简介起点 = 标题+标签块底部（与海报竖直重叠，形成绕排），不随海报高度下移
        val descTop = rightCol.measuredHeight + descGapPx
        val descWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        descView.measure(
            MeasureSpec.makeMeasureSpec(descWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        // 实际渲染行高（includeFontPadding=false 时 getLineHeight 偏大，会把行数算少一行）
        val actualLineH = measureLineHeight()
        // 与海报纵向相交的行都要缩进：向上取整，宁可多缩一行也不让文字压到海报上
        val sideLines = if (actualLineH > 0) {
            ((posterHeightPx - descTop).toFloat() / actualLineH).let {
                if (it <= 0f) 0 else kotlin.math.ceil(it).toInt()
            }.coerceIn(1, 12)
        } else 3
        if (sideLines != appliedSideLines || descView.text.isNullOrEmpty()) {
            appliedSideLines = sideLines
            post { applyDescSpan(sideLines) }
        }
        val totalH = maxOf(topRow.measuredHeight, descTop + descView.measuredHeight) +
            paddingTop + paddingBottom
        setMeasuredDimension(w, resolveSize(totalH, heightMeasureSpec))
    }

    /** 从实际排版取 baseline 间距作为真实行高；无排版结果时回退到 paint 字体度量。 */
    private fun measureLineHeight(): Float {
        val layout = descView.layout
        if (layout != null && layout.lineCount > 1) {
            val delta = layout.getLineBaseline(1) - layout.getLineBaseline(0)
            if (delta > 0) return delta.toFloat()
        }
        if (layout != null) return layout.height.toFloat().coerceAtLeast(1f)
        val fm = descView.paint.fontMetrics
        return (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1f)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = width
        val topRow = getChildAt(0)
        topRow.layout(paddingLeft, paddingTop, w - paddingRight, paddingTop + topRow.measuredHeight)
        val descTop = paddingTop + rightCol.measuredHeight + descGapPx
        descView.layout(
            paddingLeft, descTop, w - paddingRight, descTop + descView.measuredHeight
        )
    }

    private fun applyDescSpan(sideLines: Int) {
        val leading = posterWidthPx + posterGapPx
        val builder = SpannableStringBuilder(descFull)
        builder.setSpan(
            FlowMarginSpan(leading, sideLines),
            0, builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        descView.text = builder
        android.util.Log.d(
            "PgcIntro",
            "len=${descFull.length} w=$width rch=${rightCol.height} side=$sideLines " +
                "descH=${descView.height} lines=${descView.layout?.lineCount}"
        )
    }

    private fun chip(text: String, highlighted: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 10.5f
        typeface = Ds.body(bold = highlighted)
        setTextColor(if (highlighted) Ds.colors.accent else Ds.colors.muted)
        includeFontPadding = false
        setPadding(context.dp(6), context.dp(2), context.dp(6), context.dp(2))
        background = if (highlighted) {
            Ds.pill(context, Ds.colors.accentSoft, null)
        } else {
            Ds.pill(context, if (Theme.isDark) 0x1FFFFFFF else 0x12000000, null)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = context.dp(5) }
    }

    /** 前 marginLines 行缩进 leadingPx（first=true），其后全宽（first=false 返回 0）。 */
    private class FlowMarginSpan(
        private val leadingPx: Int,
        private val marginLines: Int
    ) : android.text.style.LeadingMarginSpan.LeadingMarginSpan2 {
        override fun getLeadingMargin(first: Boolean): Int = if (first) leadingPx else 0

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout
        ) = Unit

        override fun getLeadingMarginLineCount(): Int = marginLines
    }
}
