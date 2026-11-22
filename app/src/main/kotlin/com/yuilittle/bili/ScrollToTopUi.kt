package com.yuilittle.bili

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ListView
import kotlin.math.roundToInt

/**
 * 长列表平滑快速回顶驱动器（同时支持 ListView 与 GridView）。
 *
 * 系统自带的 smoothScrollToPosition(0) 在数百项的列表上常常滚几屏就停，
 * smoothScrollToPositionFromTop 在项高不均（如动态卡片）时像素估算误差大、中途会跳变。
 * 这里用两段式：
 * 1. 距顶超过 nearPosition 时先无感定位到近场锚点（省去逐项扫过数百条内容）；
 * 2. 再用 ValueAnimator 驱动 scrollListBy 逐帧向上滚，easeOut 柔和刹停。
 * 用户中途按下屏幕、View 移除或宿主取消时立刻停止，把控制权交还手势。
 */
internal class AbsListSmoothTopScroller(
    private val list: AbsListView,
    private val nearPosition: Int = 8,
    private val onStart: (() -> Unit)? = null,
    private val onSettled: (() -> Unit)? = null
) {
    private var animator: ValueAnimator? = null
    private var running = false

    private val touchCanceller = View.OnTouchListener { _, event ->
        if (running && event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
        false
    }

    init {
        list.setOnTouchListener(touchCanceller)
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = cancel()
        })
    }

    val isRunning: Boolean get() = running

    fun isAtTop(): Boolean {
        val count = list.adapter?.count ?: 0
        if (count == 0 || list.childCount == 0) return true
        if (list.firstVisiblePosition > 0) return false
        val firstTop = list.getChildAt(0)?.top ?: list.paddingTop
        return firstTop >= list.paddingTop - px(list, 2)
    }

    fun cancel() {
        running = false
        animator?.cancel()
        animator = null
    }

    /** @return true 表示确实发起了回顶；已在顶部或正在回顶时为 false。 */
    fun scrollToTop(): Boolean {
        val count = list.adapter?.count ?: 0
        if (count == 0 || isAtTop()) {
            onSettled?.invoke()
            return false
        }
        if (running) return false

        // 终止正在进行的惯性滑动
        list.smoothScrollBy(0, 0)

        if (MotionTokens.isReduced(list.context)) {
            onStart?.invoke()
            jumpToTop()
            onSettled?.invoke()
            return true
        }

        running = true
        onStart?.invoke()

        val currentFirst = list.firstVisiblePosition
        if (currentFirst > nearPosition) {
            setSelectionTop(nearPosition.coerceAtMost(count - 1))
        }

        list.post {
            if (!running || !list.isAttachedToWindow) {
                cancel()
                return@post
            }
            val estimatedPx = estimateRemainingPx().coerceAtLeast(px(list, 120))
            val durationMs = (240L + estimatedPx / list.resources.displayMetrics.density * 0.16f)
                .toLong().coerceIn(260L, 420L)

            var lastValue = 0f
            animator = ValueAnimator.ofFloat(0f, estimatedPx.toFloat()).apply {
                duration = durationMs
                interpolator = MotionTokens.easeOut
                addUpdateListener { anim ->
                    if (!running || !list.isAttachedToWindow) {
                        anim.cancel()
                        return@addUpdateListener
                    }
                    val curr = anim.animatedValue as Float
                    val delta = (curr - lastValue).roundToInt()
                    if (delta > 0) {
                        lastValue = curr
                        list.scrollListBy(-delta)
                        if (isAtTop()) {
                            running = false
                            anim.cancel()
                            jumpToTop()
                            onSettled?.invoke()
                        }
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val wasRunning = running
                        running = false
                        animator = null
                        if (wasRunning && list.isAttachedToWindow) {
                            // 动态高度估算偏小时贴顶兜底
                            if (!isAtTop()) {
                                list.scrollListBy(-list.height)
                                if (!isAtTop()) jumpToTop()
                            }
                            onSettled?.invoke()
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        animator = null
                    }
                })
                start()
            }
        }
        return true
    }

    private fun estimateRemainingPx(): Int {
        val firstPos = list.firstVisiblePosition
        val firstChild = list.getChildAt(0)
        val firstHidden = (list.paddingTop - (firstChild?.top ?: list.paddingTop)).coerceAtLeast(0)
        if (firstPos <= 0) return firstHidden

        val visibleCount = list.childCount.coerceAtLeast(1)
        val cols = (list as? GridView)?.numColumns?.coerceAtLeast(1) ?: 1
        var sum = 0
        for (i in 0 until visibleCount) sum += list.getChildAt(i)?.height ?: 0
        val avgRowH = (sum / visibleCount).coerceAtLeast(px(list, 72))
        val rowsAbove = (firstPos + cols - 1) / cols
        return ((rowsAbove * avgRowH + firstHidden) * 1.15f).roundToInt()
            .coerceAtMost(list.height * 4)
    }

    private fun setSelectionTop(position: Int) {
        when (list) {
            is ListView -> list.setSelectionFromTop(position, 0)
            else -> list.setSelection(position)
        }
    }

    private fun jumpToTop() {
        when (list) {
            is ListView -> list.setSelectionFromTop(0, 0)
            else -> list.setSelection(0)
        }
    }
}

/** 悬浮「回到顶部」按钮：中性表面 + 微描边 + 线条图标，与底部悬浮 Dock 语言一致。 */
internal class ScrollToTopButton(
    context: Context,
    onClick: () -> Unit
) : FrameLayout(context) {

    private var shown = false

    init {
        val c = Ds.colors
        val fill = if (c.isDark) 0xF521242B.toInt() else 0xF5FFFFFF.toInt()
        background = Ds.circle(fill, c.line, Ds.hairline(context))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        elevation = px(this, 4).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = "回到顶部"
        visibility = View.GONE
        alpha = 0f
        Ds.bindPressFeedback(this, 0.90f)
        addView(
            Ds.scrollTopIcon(context, c.text, 1.85f),
            LayoutParams(px(this, 18), px(this, 18)).apply { gravity = Gravity.CENTER }
        )
        setOnClickListener { onClick() }
    }

    /** 状态机切换，目标与当前一致时直接返回，避免 onScroll 每帧重启动画。 */
    fun setShown(visible: Boolean, animate: Boolean = true) {
        if (shown == visible) return
        shown = visible
        animate().cancel()
        val reduced = MotionTokens.isReduced(context)
        if (!animate || reduced) {
            alpha = if (visible) 1f else 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        if (visible) {
            if (visibility != View.VISIBLE) {
                alpha = 0f
                translationY = px(this, 12).toFloat()
                scaleX = 0.88f
                scaleY = 0.88f
                visibility = View.VISIBLE
            }
            animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(MotionTokens.easeOut)
                .start()
        } else {
            animate()
                .alpha(0f)
                .translationY(dp(10).toFloat())
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(140L)
                .setInterpolator(MotionTokens.easeOut)
                .withEndAction {
                    if (!shown) {
                        visibility = View.GONE
                        translationY = 0f
                        scaleX = 1f
                        scaleY = 1f
                    }
                }
                .start()
        }
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }
}

private fun px(view: View, value: Int): Int =
    (value * view.resources.displayMetrics.density + 0.5f).toInt()

/** 带迟滞的显隐判定：避免临界位置反复抽搐。 */
internal fun nextScrollTopVisible(
    currentlyVisible: Boolean,
    firstVisiblePosition: Int,
    showAt: Int,
    hideAt: Int
): Boolean = when {
    firstVisiblePosition >= showAt -> true
    firstVisiblePosition <= hideAt -> false
    else -> currentlyVisible
}
