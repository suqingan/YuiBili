package com.yuilittle.bili

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 弹幕图标组件与全屏右侧弹幕设置抽屉（UGC / PGC 播放页共用）。
 * 风格与 YuiBili 的极简暖白/碳墨 + 玫瑰粉强调体系完全一致，无第三方依赖。
 */
internal object DanmakuUi {

    private const val ACCENT_LIGHT_ON_DARK = 0xFFF27D9E.toInt()

    /**
     * 弹幕开关图标（纯矢量 Canvas 绘制，零位图）：
     * - 居中圆角屏幕框 + 框内居中粗体「弹」字（或全屏控制栏内紧凑清晰字形）；
     * - 开启态：品牌玫瑰粉/高亮白 + 右下角绿色/玫瑰粉实心圆点与小对勾徽章；
     * - 关闭态：柔和灰/半透明白 + 右下角柔和禁用斜杠圆徽章（不糊住「弹」字本身，辨识度高）。
     *
     * @param onDarkPlayer true 表示渲染在深色视频控制栏上；false 表示渲染在竖屏简介/评论 Tab 栏上。
     */
    class ToggleIconView(
        context: Context,
        private val onDarkPlayer: Boolean
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private var danmakuEnabled = true

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val frameRect = RectF()
        private val badgePath = Path()

        fun setDanmakuEnabled(enabled: Boolean) {
            if (danmakuEnabled == enabled) return
            danmakuEnabled = enabled
            contentDescription = if (enabled) "关闭弹幕" else "开启弹幕"
            invalidate()
        }

        private fun dpf(v: Float): Float = v * density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            val cy = h * 0.5f
            val u = minOf(w, h) / 28f

            val frameW = 19.5f * u
            val frameH = 15.2f * u
            frameRect.set(
                cx - frameW * 0.5f,
                cy - frameH * 0.5f,
                cx + frameW * 0.5f,
                cy + frameH * 0.5f
            )

            val mainColor: Int
            val accentColor: Int
            val badgeBgColor: Int
            val badgeFgColor: Int
            if (onDarkPlayer) {
                accentColor = ACCENT_LIGHT_ON_DARK
                mainColor = if (danmakuEnabled) Color.WHITE else 0x8CFFFFFF.toInt()
                badgeBgColor = if (danmakuEnabled) accentColor else 0xFF565B66.toInt()
                badgeFgColor = Color.WHITE
            } else {
                val c = Ds.colors
                accentColor = c.accent
                mainColor = if (danmakuEnabled) c.accent else c.muted
                badgeBgColor = if (danmakuEnabled) c.accent else c.subtle
                badgeFgColor = Color.WHITE
            }

            // 圆角屏幕外框
            strokePaint.color = mainColor
            strokePaint.strokeWidth = 1.65f * u
            canvas.drawRoundRect(frameRect, 3.6f * u, 3.6f * u, strokePaint)

            // 框内「弹」字（略向左上方微移给右下角角标留呼吸空间）
            textPaint.color = mainColor
            textPaint.textSize = 9.4f * u
            val fm = textPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) * 0.5f - 0.3f * u
            canvas.drawText("弹", cx - 0.5f * u, textY, textPaint)

            // 右下角状态角标（实心小圆 + 对勾 / 叉号）
            val badgeR = 4.3f * u
            val bx = frameRect.right - 0.4f * u
            val by = frameRect.bottom - 0.4f * u

            // 角标底环：在浅色/深色背景上和外框切出清晰间隔
            fillPaint.color = if (onDarkPlayer) 0xCC121419.toInt() else Ds.colors.bg
            canvas.drawCircle(bx, by, badgeR + 1.1f * u, fillPaint)

            fillPaint.color = badgeBgColor
            canvas.drawCircle(bx, by, badgeR, fillPaint)

            badgeStrokePaint.color = badgeFgColor
            badgeStrokePaint.strokeWidth = 1.45f * u
            badgePath.rewind()
            if (danmakuEnabled) {
                badgePath.moveTo(bx - 1.9f * u, by + 0.1f * u)
                badgePath.lineTo(bx - 0.5f * u, by + 1.55f * u)
                badgePath.lineTo(bx + 2.0f * u, by - 1.45f * u)
            } else {
                val arm = 1.55f * u
                badgePath.moveTo(bx - arm, by - arm)
                badgePath.lineTo(bx + arm, by + arm)
                badgePath.moveTo(bx + arm, by - arm)
                badgePath.lineTo(bx - arm, by + arm)
            }
            canvas.drawPath(badgePath, badgeStrokePaint)
        }
    }

    /**
     * 竖屏 Tab 栏右侧的轻量弹幕开关胶囊（紧贴 Tab 栏最右侧，对应用户红笔标出的位置）：
     * 含自定义「弹」字状态图标 + 「弹幕开 / 弹幕关」短标签，开启时玫瑰粉柔底，关闭时中性灰底。
     */
    class PortraitTabToggle(context: Context) : LinearLayout(context) {
        private val icon = ToggleIconView(context, onDarkPlayer = false)
        private val label = TextView(context)
        private var currentEnabled = true

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            val padH = Ds.dp(context, 10).toInt()
            val padV = Ds.dp(context, 4).toInt()
            setPadding(padH, padV, padH, padV)

            val iconSize = Ds.dp(context, 20).toInt()
            addView(icon, LayoutParams(iconSize, iconSize))

            label.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
                typeface = Ds.body(bold = true)
                includeFontPadding = false
                maxLines = 1
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ds.dp(context, 4).toInt()
            })

            Ds.bindPressFeedback(this, 0.93f)
            updateState(DanmakuSettingsStore.snapshot().enabled)
        }

        fun updateState(enabled: Boolean) {
            currentEnabled = enabled
            icon.setDanmakuEnabled(enabled)
            val c = Ds.colors
            label.text = if (enabled) "弹幕" else "弹幕关"
            label.setTextColor(if (enabled) c.accent else c.muted)
            background = Ds.pill(
                context,
                fill = if (enabled) c.accentSoft else c.surfaceRaised,
                border = if (enabled) (c.accent and 0x55FFFFFF) else c.lineSoft,
                borderPx = Ds.hairline(context)
            )
            contentDescription = if (enabled) "关闭弹幕" else "开启弹幕"
        }
    }

    /**
     * 全屏控制栏的「弹幕设置」图标（圆角屏幕框内带双滑轨+圆点，或弹字+小齿轮的极简矢量）。
     * 采用极简两条弹幕轨迹线 + 右下角精致调参齿轮，与控制栏的线性图标风格统一。
     */
    class SettingsIconView(context: Context) : View(context) {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val frameRect = RectF()

        init {
            contentDescription = "弹幕设置"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            val cy = h * 0.5f
            val u = minOf(w, h) / 28f

            val frameW = 19.5f * u
            val frameH = 15.2f * u
            frameRect.set(
                cx - frameW * 0.5f,
                cy - frameH * 0.5f,
                cx + frameW * 0.5f,
                cy + frameH * 0.5f
            )

            // 屏幕外框
            strokePaint.color = 0xF2FFFFFF.toInt()
            strokePaint.strokeWidth = 1.65f * u
            canvas.drawRoundRect(frameRect, 3.6f * u, 3.6f * u, strokePaint)

            // 框内两条错落弹幕流光条
            val line1Y = cy - 2.6f * u
            val line2Y = cy + 2.3f * u
            strokePaint.strokeWidth = 1.55f * u
            canvas.drawLine(frameRect.left + 3.6f * u, line1Y, frameRect.right - 4.0f * u, line1Y, strokePaint)
            canvas.drawLine(frameRect.left + 3.6f * u, line2Y, frameRect.left + 10.6f * u, line2Y, strokePaint)

            // 右下角精致调参齿轮角标
            val gx = frameRect.right - 0.6f * u
            val gy = frameRect.bottom - 0.6f * u
            val outerR = 4.6f * u
            fillPaint.color = 0xCC121419.toInt()
            canvas.drawCircle(gx, gy, outerR + 1.1f * u, fillPaint)

            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = 1.35f * u
            // 6 个均匀短齿
            val toothInner = 2.6f * u
            val toothOuter = 4.1f * u
            for (k in 0 until 6) {
                val rad = Math.toRadians(k * 60.0)
                val cos = kotlin.math.cos(rad).toFloat()
                val sin = kotlin.math.sin(rad).toFloat()
                canvas.drawLine(
                    gx + cos * toothInner, gy + sin * toothInner,
                    gx + cos * toothOuter, gy + sin * toothOuter,
                    strokePaint
                )
            }
            fillPaint.color = 0xCC121419.toInt()
            canvas.drawCircle(gx, gy, 2.4f * u, fillPaint)
            canvas.drawCircle(gx, gy, 2.2f * u, strokePaint)
        }
    }

    /**
     * 全屏右侧「弹幕设置」抽屉控制器，视觉结构与合集抽屉（seasonDrawerOverlay）完全对齐：
     * - 半透明深碳圆角卡片（0xD414161B + 1px 微描边），右侧滑入；
     * - 顶部标题「弹幕设置」+「恢复默认」+ 关闭键；
     * - 连续调节滑块：透明度、字号、速度；
     * - 显示区域四档胶囊：1/4 屏、半屏、3/4 屏、全屏；
     * - 屏蔽类型四项切换：滚动、顶部、底部、彩色。
     */
    class FullscreenSettingsDrawer(
        private val context: Context,
        private val reducedMotion: Boolean,
        private val onBeforeOpen: () -> Unit
    ) {
        val overlay: FrameLayout
        private val panel: LinearLayout
        private var isOpen = false
        private var animating = false

        private lateinit var opacitySlider: CompactSliderRow
        private lateinit var sizeSlider: CompactSliderRow
        private lateinit var speedSlider: CompactSliderRow
        private val areaChips = ArrayList<Pair<Float, TextView>>()
        private lateinit var blockScrollChip: TextView
        private lateinit var blockTopChip: TextView
        private lateinit var blockBottomChip: TextView
        private lateinit var blockColorChip: TextView

        val opened: Boolean get() = isOpen

        private fun dp(v: Int): Int = Ds.dp(context, v).toInt()

        init {
            overlay = FrameLayout(context).apply {
                setBackgroundColor(0x1F000000)
                visibility = View.GONE
                isClickable = true
                setOnClickListener { close() }
            }

            panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.plate(
                    0xD814161B.toInt(),
                    0x2EFFFFFF,
                    Ds.hairline(context),
                    Ds.dp(context, 18)
                )
                isClickable = true
                Ds.clipRounded(this, 18)
            }

            // 顶部栏
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(10), dp(11))
            }
            header.addView(TextView(context).apply {
                text = "弹幕设置"
                textSize = 13.5f
                typeface = Ds.display(bold = true)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            header.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            header.addView(TextView(context).apply {
                text = "恢复默认"
                textSize = 11.5f
                typeface = Ds.body(bold = true)
                setTextColor(ACCENT_LIGHT_ON_DARK)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                isClickable = true
                Ds.bindPressFeedback(this, 0.92f)
                setOnClickListener {
                    val next = DanmakuSettingsStore.resetToDefault()
                    syncUi(next)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) })
            header.addView(Ds.closeIcon(context, 0xB3FFFFFF.toInt(), 1.7f).apply {
                isClickable = true
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(28), dp(28)))

            panel.addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            panel.addView(View(context).apply { setBackgroundColor(0x1FFFFFFF) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ds.hairline(context)))

            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }

            // ── 1. 滑块参数：不透明度 / 字体大小 / 弹幕速度 ──
            opacitySlider = CompactSliderRow(
                context = context,
                title = "不透明度",
                min = 0.15f,
                max = 1.00f,
                formatValue = { "${(it * 100f).roundToInt()}%" },
                onChange = { v, finished ->
                    DanmakuSettingsStore.update(debouncePersist = !finished) { it.copy(opacity = v) }
                }
            )
            body.addView(opacitySlider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            sizeSlider = CompactSliderRow(
                context = context,
                title = "字体大小",
                min = 0.60f,
                max = 1.60f,
                formatValue = { "${(it * 100f).roundToInt()}%" },
                onChange = { v, finished ->
                    DanmakuSettingsStore.update(debouncePersist = !finished) { it.copy(textScale = v) }
                }
            )
            body.addView(sizeSlider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })

            speedSlider = CompactSliderRow(
                context = context,
                title = "滚动速度",
                min = 0.60f,
                max = 1.60f,
                formatValue = { String.format(java.util.Locale.US, "%.1fx", it) },
                onChange = { v, finished ->
                    DanmakuSettingsStore.update(debouncePersist = !finished) { it.copy(speedScale = v) }
                }
            )
            body.addView(speedSlider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })

            // ── 2. 显示区域 ──
            body.addView(buildSectionTitle("显示区域"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(8) })

            val areaRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val areaOptions = listOf(
                0.25f to "1/4 屏",
                0.50f to "半屏",
                0.75f to "3/4 屏",
                1.00f to "全屏"
            )
            areaOptions.forEachIndexed { idx, (fraction, title) ->
                val chip = buildOptionChip(title) {
                    val next = DanmakuSettingsStore.update { it.copy(areaFraction = fraction) }
                    syncUi(next)
                }
                areaChips.add(fraction to chip)
                areaRow.addView(chip, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                })
            }
            body.addView(areaRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // ── 3. 按类型屏蔽 ──
            body.addView(buildSectionTitle("屏蔽弹幕类型"), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(8) })

            val blockRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            blockScrollChip = buildOptionChip("滚动") {
                val next = DanmakuSettingsStore.update { it.copy(blockScroll = !it.blockScroll) }
                syncUi(next)
            }
            blockTopChip = buildOptionChip("顶部") {
                val next = DanmakuSettingsStore.update { it.copy(blockTop = !it.blockTop) }
                syncUi(next)
            }
            blockBottomChip = buildOptionChip("底部") {
                val next = DanmakuSettingsStore.update { it.copy(blockBottom = !it.blockBottom) }
                syncUi(next)
            }
            blockColorChip = buildOptionChip("彩色") {
                val next = DanmakuSettingsStore.update { it.copy(blockColor = !it.blockColor) }
                syncUi(next)
            }
            listOf(blockScrollChip, blockTopChip, blockBottomChip, blockColorChip).forEachIndexed { idx, chip ->
                blockRow.addView(chip, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx > 0) leftMargin = dp(6)
                })
            }
            body.addView(blockRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            scroll.addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            panel.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            overlay.addView(panel, FrameLayout.LayoutParams(
                computePanelWidth(),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                topMargin = dp(12); bottomMargin = dp(12); rightMargin = dp(12)
            })
        }

        fun open() {
            if (isOpen || animating) return
            onBeforeOpen()
            syncUi(DanmakuSettingsStore.snapshot())
            val panelWidth = computePanelWidth()
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                width = panelWidth
            }
            panel.requestLayout()

            isOpen = true
            overlay.visibility = View.VISIBLE
            overlay.alpha = if (reducedMotion) 1f else 0f
            panel.translationX = if (reducedMotion) 0f else panelWidth.toFloat()
            if (reducedMotion) {
                animating = false
            } else {
                animating = true
                overlay.animate().alpha(1f).setDuration(MotionTokens.panelMs)
                    .setInterpolator(MotionTokens.easeOut).start()
                panel.animate().translationX(0f).setDuration(MotionTokens.panelMs)
                    .setInterpolator(MotionTokens.drawer)
                    .withEndAction { animating = false }
                    .start()
            }
        }

        fun close(immediate: Boolean = false) {
            if (!isOpen) return
            isOpen = false
            DanmakuSettingsStore.flushPersist()
            if (immediate || reducedMotion) {
                overlay.animate().cancel()
                panel.animate().cancel()
                overlay.visibility = View.GONE
                overlay.alpha = 1f
                panel.translationX = 0f
                animating = false
                return
            }
            animating = true
            val panelWidth = computePanelWidth()
            panel.animate().translationX(panelWidth.toFloat()).setDuration(MotionTokens.smallMs)
                .setInterpolator(MotionTokens.easeOut).start()
            overlay.animate().alpha(0f).setDuration(MotionTokens.smallMs)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                    panel.translationX = 0f
                    animating = false
                }.start()
        }

        fun syncUi(s: DanmakuSettingsStore.Snapshot) {
            opacitySlider.setValue(s.opacity)
            sizeSlider.setValue(s.textScale)
            speedSlider.setValue(s.speedScale)
            areaChips.forEach { (fraction, chip) ->
                styleChip(chip, active = kotlin.math.abs(s.areaFraction - fraction) < 0.05f)
            }
            styleChip(blockScrollChip, active = s.blockScroll)
            styleChip(blockTopChip, active = s.blockTop)
            styleChip(blockBottomChip, active = s.blockBottom)
            styleChip(blockColorChip, active = s.blockColor)
        }

        private fun buildSectionTitle(title: String): TextView = TextView(context).apply {
            text = title
            textSize = 11.5f
            typeface = Ds.body(bold = true)
            setTextColor(0xA6FFFFFF.toInt())
        }

        private fun buildOptionChip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Ds.body(bold = true)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            isClickable = true
            Ds.bindPressFeedback(this, 0.94f)
            setOnClickListener { onClick() }
        }

        private fun styleChip(chip: TextView, active: Boolean) {
            if (active) {
                chip.background = Ds.card(
                    context,
                    fill = 0x33F27D9E,
                    border = ACCENT_LIGHT_ON_DARK,
                    radiusDp = Ds.Radius.chip,
                    borderPx = Ds.hairline(context)
                )
                chip.setTextColor(ACCENT_LIGHT_ON_DARK)
            } else {
                chip.background = Ds.card(
                    context,
                    fill = 0x16FFFFFF,
                    border = 0x24FFFFFF,
                    radiusDp = Ds.Radius.chip,
                    borderPx = Ds.hairline(context)
                )
                chip.setTextColor(0xDCFFFFFF.toInt())
            }
        }

        private fun computePanelWidth(): Int {
            val dm = context.resources.displayMetrics
            return (dm.widthPixels * 0.42f).toInt().coerceIn(dp(276), dp(348))
        }
    }

    /** 紧凑自定义进度滑块行（标题 + 数值 + 细轨道玫瑰粉滑块），无 SeekBar 兼容主题杂色。 */
    private class CompactSliderRow(
        context: Context,
        title: String,
        private val min: Float,
        private val max: Float,
        private val formatValue: (Float) -> String,
        private val onChange: (value: Float, finished: Boolean) -> Unit
    ) : LinearLayout(context) {

        private val valueText: TextView
        private val track: SliderTrackView
        private var currentValue: Float = min

        init {
            orientation = VERTICAL
            val topRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(context).apply {
                text = title
                textSize = 12f
                typeface = Ds.body(bold = true)
                setTextColor(0xE6FFFFFF.toInt())
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            valueText = TextView(context).apply {
                textSize = 11.5f
                typeface = Ds.body(bold = true)
                fontFeatureSettings = "tnum"
                setTextColor(ACCENT_LIGHT_ON_DARK)
            }
            topRow.addView(valueText, LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            ))
            addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

            track = SliderTrackView(context) { fraction, finished ->
                val v = min + (max - min) * fraction
                currentValue = v
                valueText.text = formatValue(v)
                onChange(v, finished)
            }
            addView(track, LayoutParams(LayoutParams.MATCH_PARENT, Ds.dp(context, 28).toInt()).apply {
                topMargin = Ds.dp(context, 2).toInt()
            })
        }

        fun setValue(value: Float) {
            val clamped = value.coerceIn(min, max)
            currentValue = clamped
            valueText.text = formatValue(clamped)
            val frac = if (max > min) (clamped - min) / (max - min) else 0f
            track.setFraction(frac)
        }
    }

    private class SliderTrackView(
        context: Context,
        private val onScrub: (fraction: Float, finished: Boolean) -> Unit
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x36FFFFFF
            style = Paint.Style.FILL
        }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_LIGHT_ON_DARK
            style = Paint.Style.FILL
        }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_LIGHT_ON_DARK
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
        private val barRect = RectF()
        private var fraction = 0.5f
        private var dragging = false

        fun setFraction(f: Float) {
            val clamped = f.coerceIn(0f, 1f)
            if (fraction != clamped) {
                fraction = clamped
                invalidate()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val pad = 7f * density
            val usableW = (width - pad * 2f).coerceAtLeast(1f)
            val next = ((event.x - pad) / usableW).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = true
                    fraction = next
                    invalidate()
                    onScrub(fraction, false)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    fraction = next
                    invalidate()
                    onScrub(fraction, false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    fraction = next
                    invalidate()
                    onScrub(fraction, true)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cy = height * 0.5f
            val pad = 7f * density
            val left = pad
            val right = (width - pad).coerceAtLeast(left)
            val trackH = 3.2f * density
            val r = trackH * 0.5f

            barRect.set(left, cy - r, right, cy + r)
            canvas.drawRoundRect(barRect, r, r, trackPaint)

            val thumbX = left + (right - left) * fraction
            barRect.set(left, cy - r, thumbX, cy + r)
            canvas.drawRoundRect(barRect, r, r, activePaint)

            val thumbR = if (dragging) 6.6f * density else 5.6f * density
            canvas.drawCircle(thumbX, cy, thumbR, thumbPaint)
            canvas.drawCircle(thumbX, cy, thumbR, thumbRingPaint)
        }
    }
}
