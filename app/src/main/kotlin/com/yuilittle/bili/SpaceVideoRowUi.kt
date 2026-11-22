package com.yuilittle.bili

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 个人主页「投稿」与空间搜索「视频」共用的单列横排视频卡片（以及对应的列表骨架屏）。
 * 相比首页双列瀑布卡，空间内按时间/播放/收藏浏览 UP 主投稿时，左图右文的横排列表
 * 可完整展示长标题（2 行不截断大半）、清晰的时间/播放/弹幕数据，阅读密度和呼吸感更好。
 */
internal object SpaceVideoRowUi {

    data class Holder(
        val cover: ImageView,
        val durationText: TextView,
        val titleText: TextView,
        val metaPrimaryText: TextView,
        val metaSecondaryText: TextView,
        var boundKey: String? = null
    )

    fun createRow(context: Context): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.control)
            setPadding(context.dp(10), context.dp(10), context.dp(12), context.dp(10))
            isClickable = true
            isFocusable = true
            Ds.bindPressFeedback(this, 0.985f)
        }

        // 左侧 16:10 封面 + 右下角柔和时长角标
        val coverW = context.dp(134)
        val coverH = context.dp(84)
        val coverBox = FrameLayout(context).apply {
            setBackgroundColor(COLOR_COVER)
            Ds.clipRounded(this, Ds.Radius.chip)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(COLOR_COVER)
        }
        coverBox.addView(
            cover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        coverBox.addView(
            View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x66000000)
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                context.dp(24),
                Gravity.BOTTOM
            )
        )
        val durationText = TextView(context).apply {
            textSize = 10f
            typeface = Ds.body(bold = true)
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            background = rounded(0x99000000.toInt(), context.dp(4))
            setPadding(context.dp(5), context.dp(2), context.dp(5), context.dp(2))
            includeFontPadding = false
        }
        coverBox.addView(
            durationText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = context.dp(5)
                bottomMargin = context.dp(5)
            }
        )
        row.addView(coverBox, LinearLayout.LayoutParams(coverW, coverH))

        // 右侧：两行标题 + 底部轻盈元信息（播放 / 弹幕 + 发布时间）
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(1), 0, context.dp(1))
        }
        val titleText = TextView(context).apply {
            textSize = 14f
            typeface = Ds.body(bold = true)
            setTextColor(COLOR_INK)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(context.dp(2).toFloat(), 1f)
            includeFontPadding = false
        }
        infoCol.addView(
            titleText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        infoCol.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(
            MiniPlayIcon(context),
            LinearLayout.LayoutParams(context.dp(12), context.dp(12)).apply {
                rightMargin = context.dp(4)
            }
        )
        val metaPrimaryText = TextView(context).apply {
            textSize = 11.5f
            typeface = Ds.body()
            fontFeatureSettings = "tnum"
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        metaRow.addView(
            metaPrimaryText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val metaSecondaryText = TextView(context).apply {
            textSize = 11f
            typeface = Ds.body()
            fontFeatureSettings = "tnum"
            setTextColor(Ds.colors.subtle)
            maxLines = 1
            includeFontPadding = false
            setPadding(context.dp(6), 0, 0, 0)
        }
        metaRow.addView(
            metaSecondaryText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        infoCol.addView(
            metaRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(8) }
        )

        row.addView(infoCol, LinearLayout.LayoutParams(0, coverH, 1f))
        row.tag = Holder(cover, durationText, titleText, metaPrimaryText, metaSecondaryText)
        return row
    }

    fun bind(
        row: LinearLayout,
        holder: Holder,
        video: SpaceVideo,
        highlightKeyword: String = ""
    ) {
        val key = "${video.bvid}|${video.aid}|${video.title}|$highlightKeyword"
        if (holder.boundKey != key) {
            holder.boundKey = key
            holder.titleText.text = SpaceDynamicCardUi.applyKeywordHighlight(video.title, highlightKeyword)
            val duration = video.duration?.trim().orEmpty()
            if (duration.isNotEmpty()) {
                holder.durationText.visibility = View.VISIBLE
                holder.durationText.text = duration
            } else {
                holder.durationText.visibility = View.GONE
            }

            val parts = ArrayList<String>(2)
            video.play?.let { parts += "${BiliSpaceApi.formatNullableCount(it)}播放" }
            video.danmaku?.takeIf { it > 0L }?.let { parts += "${BiliSpaceApi.formatNullableCount(it)}弹幕" }
            holder.metaPrimaryText.text = if (parts.isNotEmpty()) parts.joinToString(" · ") else "投稿视频"

            val dateStr = formatPublishTime(video.publishedAt)
            holder.metaSecondaryText.text = dateStr
            holder.metaSecondaryText.visibility = if (dateStr.isNotEmpty()) View.VISIBLE else View.GONE

            row.contentDescription = listOf(video.title, holder.metaPrimaryText.text, dateStr)
                .filter { it.isNotBlank() }
                .joinToString("，")
            row.animate().cancel()
            row.alpha = 1f
            row.scaleX = 1f
            row.scaleY = 1f
        }
        val coverUrl = video.cover?.trim().orEmpty()
        if (coverUrl.isNotEmpty()) {
            CoverLoader.load(
                holder.cover,
                SpaceDynamicCardUi.buildSpaceVideoCoverThumbUrl(coverUrl),
                cropThumb = false,
                animate = !holder.cover.isAttachedToWindow || holder.cover.drawable == null
            )
        } else {
            CoverLoader.clear(holder.cover)
            holder.cover.setImageDrawable(null)
        }
    }

    fun clearImages(view: View?) {
        val row = (view as? FrameLayout)?.getChildAt(0) as? LinearLayout ?: view as? LinearLayout ?: return
        (row.tag as? Holder)?.let { CoverLoader.clear(it.cover) }
    }

    fun createSkeletonLayout(context: Context, hostOverlay: FrameLayout): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(10), context.dp(14), 0)
        }
        val coverW = context.dp(134)
        val coverH = context.dp(84)
        val placeholderBg = COLOR_COVER
        val placeholderSoft = Ds.colors.lineSoft
        repeat(6) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.card(context, COLOR_CARD, Ds.colors.lineSoft, Ds.Radius.control)
                setPadding(context.dp(10), context.dp(10), context.dp(12), context.dp(10))
            }
            row.addView(
                View(context).apply {
                    background = rounded(placeholderBg, context.dp(Ds.Radius.chip))
                },
                LinearLayout.LayoutParams(coverW, coverH)
            )
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(12), context.dp(2), 0, context.dp(2))
            }
            col.addView(
                View(context).apply { background = rounded(placeholderSoft, context.dp(4)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(13))
            )
            col.addView(
                View(context).apply { background = rounded(placeholderSoft, context.dp(4)) },
                LinearLayout.LayoutParams(context.dp(120), context.dp(13)).apply { topMargin = context.dp(7) }
            )
            col.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            col.addView(
                View(context).apply { background = rounded(placeholderSoft, context.dp(4)) },
                LinearLayout.LayoutParams(context.dp(92), context.dp(10))
            )
            row.addView(col, LinearLayout.LayoutParams(0, coverH, 1f))
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = context.dp(8) }
            )
        }
        if (!MotionTokens.isReduced(context)) {
            val pulse = ObjectAnimator.ofFloat(container, View.ALPHA, 1f, 0.52f).apply {
                duration = 750L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = MotionTokens.easeOut
            }
            container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (hostOverlay.visibility == View.VISIBLE) pulse.start()
                }

                override fun onViewDetachedFromWindow(v: View) {
                    pulse.cancel()
                }
            })
            container.tag = pulse
        }
        return container
    }

    private fun formatPublishTime(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0L) return ""
        val full = formatPublishedDate(epochSeconds)
        if (full.length < 10) return full
        val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        return if (full.startsWith("$yearNow-")) full.substring(5) else full
    }

    private class MiniPlayIcon(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.15f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()
        private val tri = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val color = Ds.colors.subtle
            borderPaint.color = color
            fillPaint.color = color
            val pad = 0.8f * density
            rect.set(pad, pad * 1.4f, width - pad, height - pad * 1.4f)
            canvas.drawRoundRect(rect, 2f * density, 2f * density, borderPaint)
            val cx = width * 0.52f
            val cy = height / 2f
            val r = minOf(width, height) * 0.2f
            tri.rewind()
            tri.moveTo(cx - r * 0.55f, cy - r * 0.88f)
            tri.lineTo(cx + r * 0.88f, cy)
            tri.lineTo(cx - r * 0.55f, cy + r * 0.88f)
            tri.close()
            canvas.drawPath(tri, fillPaint)
        }
    }
}
