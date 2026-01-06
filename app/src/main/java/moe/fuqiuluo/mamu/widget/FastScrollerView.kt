package moe.fuqiuluo.mamu.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * 自定义快速滚动条视图
 * 用于 RecyclerView 的快速滚动功能
 */
class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SCROLLBAR_WIDTH = 24f // 滚动条宽度（增大到 24dp）
        private const val THUMB_WIDTH = 16f // 实际滑块宽度
        private const val SCROLLBAR_MARGIN = 8f // 右边距
        private const val THUMB_MIN_HEIGHT = 48f // 滑块最小高度
        private const val THUMB_MAX_HEIGHT_RATIO = 0.4f // 滑块最大高度比例（轨道高度的40%）
        private const val TRACK_CORNER_RADIUS = 8f // 轨道圆角
        private const val THUMB_CORNER_RADIUS = 8f // 滑块圆角
        private const val FADE_DELAY = 2000L // 淡出延迟（增加到2秒）
        private const val FADE_DURATION = 300L // 淡出动画时长
        private const val TOUCH_SLOP = 24f // 触摸容差（增加触摸区域）
    }

    private var recyclerView: RecyclerView? = null
    private var isDragging = false
    private var isVisible = false
    private var alpha = 0f

    // 性能优化：节流滚动更新
    private var lastScrollY = -1f
    private var lastTargetPosition = -1
    private var scrollUpdateRunnable: Runnable? = null

    // 绘制相关
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF() // 仅用于触摸区域判断，不绘制
    private val thumbRect = RectF()

    // 滑块颜色（不透明）
    private val thumbColor = 0xFF64B5F6.toInt() // 100% 蓝色，不透明

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            updateThumbPosition()
            show()
        }
    }

    init {
        thumbPaint.color = thumbColor
        // 默认显示滚动条
        visibility = VISIBLE
        isVisible = true
        alpha = 0.8f // 默认80%透明度
    }

    /**
     * 绑定 RecyclerView
     */
    fun attachToRecyclerView(rv: RecyclerView?) {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = rv
        recyclerView?.addOnScrollListener(scrollListener)
        updateThumbPosition()
        // 绑定后立即显示滚动条
        post {
            isVisible = true
            invalidate()
        }
    }

    /**
     * 解绑 RecyclerView
     */
    fun detachFromRecyclerView() {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = null
    }

    /**
     * 更新滑块位置
     */
    private fun updateThumbPosition() {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return

        val itemCount = layoutManager.itemCount
        if (itemCount == 0) {
            isVisible = false
            visibility = GONE
            return
        }

        isVisible = true
        visibility = VISIBLE

        // 计算滚动进度
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition)
        val viewHeight = firstVisibleView?.height ?: 0

        val scrollOffset = if (viewHeight > 0 && firstVisibleView != null) {
            -firstVisibleView.top.toFloat() / viewHeight
        } else {
            0f
        }

        val scrollProgress = (firstVisiblePosition + scrollOffset) / itemCount

        // 计算轨道位置（触摸区域，宽度为 SCROLLBAR_WIDTH）
        val trackTop = paddingTop.toFloat()
        val trackBottom = height - paddingBottom.toFloat()
        val trackHeight = trackBottom - trackTop
        val trackLeft = width - SCROLLBAR_WIDTH - SCROLLBAR_MARGIN - paddingEnd
        val trackRight = width - SCROLLBAR_MARGIN - paddingEnd

        // 计算滑块位置（实际显示，宽度为 THUMB_WIDTH，居中显示）
        val thumbLeft = trackLeft + (SCROLLBAR_WIDTH - THUMB_WIDTH) / 2
        val thumbRight = thumbLeft + THUMB_WIDTH

        trackRect.set(trackLeft, trackTop, trackRight, trackBottom)

        // 计算滑块高度（根据可见内容比例，但限制最大高度）
        val visibleItemCount = layoutManager.findLastVisibleItemPosition() - firstVisiblePosition + 1
        val thumbHeightRatio = min(THUMB_MAX_HEIGHT_RATIO, visibleItemCount.toFloat() / itemCount)
        val thumbHeight = max(THUMB_MIN_HEIGHT, trackHeight * thumbHeightRatio)

        // 计算滑块位置
        val thumbTop = trackTop + (trackHeight - thumbHeight) * scrollProgress
        val thumbBottom = thumbTop + thumbHeight

        thumbRect.set(thumbLeft, thumbTop, thumbRight, thumbBottom)

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isVisible && !isDragging) return

        // 应用透明度：拖动时完全不透明，否则使用当前透明度
        val currentAlpha = if (isDragging) {
            255 // 拖动时完全不透明
        } else {
            (alpha * 255).toInt()
        }
        thumbPaint.alpha = currentAlpha

        // 只绘制滑块，不绘制导轨
        canvas.drawRoundRect(thumbRect, THUMB_CORNER_RADIUS, THUMB_CORNER_RADIUS, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 扩大触摸区域：判断是否在轨道区域内（包含左右容差）
                if (isPointInsideScrollbar(event.x, event.y)) {
                    isDragging = true
                    show()
                    scrollToPositionImmediate(event.y)
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // 性能优化：使用节流，避免频繁滚动
                    scheduleScrollUpdate(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    // 取消待处理的滚动更新
                    scrollUpdateRunnable?.let { removeCallbacks(it) }
                    scrollUpdateRunnable = null
                    lastScrollY = -1f
                    lastTargetPosition = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    hide()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 判断点击位置是否在滚动条区域内（包含触摸容差）
     */
    private fun isPointInsideScrollbar(x: Float, y: Float): Boolean {
        val expandedTrackRect = RectF(
            trackRect.left - TOUCH_SLOP,
            trackRect.top,
            trackRect.right + TOUCH_SLOP,
            trackRect.bottom
        )
        return expandedTrackRect.contains(x, y)
    }

    /**
     * 调度滚动更新（节流）
     * 使用 postOnAnimation 确保在下一帧更新，避免频繁滚动
     */
    private fun scheduleScrollUpdate(y: Float) {
        // 如果位置没变化，跳过
        if (y == lastScrollY) return
        lastScrollY = y

        // 取消之前的更新
        scrollUpdateRunnable?.let { removeCallbacks(it) }

        // 立即更新滑块视觉位置（不触发滚动）
        updateThumbVisualPosition(y)

        // 调度实际的滚动更新
        scrollUpdateRunnable = Runnable {
            scrollToPositionInternal(y)
        }
        postOnAnimation(scrollUpdateRunnable)
    }

    /**
     * 立即滚动到位置（ACTION_DOWN 使用）
     */
    private fun scrollToPositionImmediate(y: Float) {
        lastScrollY = y
        scrollToPositionInternal(y)
    }

    /**
     * 更新滑块的视觉位置（不触发 RecyclerView 滚动）
     * 用于快速响应触摸，提供流畅的视觉反馈
     */
    private fun updateThumbVisualPosition(y: Float) {
        val trackTop = paddingTop.toFloat()
        val trackBottom = height - paddingBottom.toFloat()
        val trackHeight = trackBottom - trackTop

        // 计算滚动进度
        val scrollProgress = ((y - trackTop) / trackHeight).coerceIn(0f, 1f)

        // 计算滑块位置
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        val itemCount = layoutManager.itemCount
        if (itemCount == 0) return

        val visibleItemCount = layoutManager.findLastVisibleItemPosition() -
                               layoutManager.findFirstVisibleItemPosition() + 1
        val thumbHeightRatio = min(THUMB_MAX_HEIGHT_RATIO, visibleItemCount.toFloat() / itemCount)
        val thumbHeight = max(THUMB_MIN_HEIGHT, trackHeight * thumbHeightRatio)

        val thumbTop = trackTop + (trackHeight - thumbHeight) * scrollProgress
        val thumbBottom = thumbTop + thumbHeight

        val trackLeft = width - SCROLLBAR_WIDTH - SCROLLBAR_MARGIN - paddingEnd
        val thumbLeft = trackLeft + (SCROLLBAR_WIDTH - THUMB_WIDTH) / 2
        val thumbRight = thumbLeft + THUMB_WIDTH

        thumbRect.set(thumbLeft, thumbTop, thumbRight, thumbBottom)
        invalidate()
    }

    /**
     * 内部滚动方法（实际触发 RecyclerView 滚动）
     */
    private fun scrollToPositionInternal(y: Float) {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return

        val trackTop = paddingTop.toFloat()
        val trackBottom = height - paddingBottom.toFloat()
        val trackHeight = trackBottom - trackTop

        // 计算滚动进度
        val scrollProgress = ((y - trackTop) / trackHeight).coerceIn(0f, 1f)

        // 计算目标位置
        val itemCount = layoutManager.itemCount
        val targetPosition = (scrollProgress * itemCount).toInt().coerceIn(0, itemCount - 1)

        // 如果目标位置没变，跳过滚动
        if (targetPosition == lastTargetPosition) return
        lastTargetPosition = targetPosition

        // 滚动到目标位置（不会触发 onScrolled，所以不需要额外的 updateThumbPosition）
        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
    }

    /**
     * 显示滚动条
     */
    private fun show() {
        removeCallbacks(hideRunnable)
        isVisible = true

        if (this.alpha < 1f) {
            animate()
                .alpha(1f)
                .setDuration(150)
                .withStartAction {
                    visibility = VISIBLE
                }
                .withEndAction {
                    alpha = 1f
                    postDelayed(hideRunnable, FADE_DELAY)
                }
                .start()
        } else {
            // 已经是完全显示状态，只需要重置隐藏定时器
            postDelayed(hideRunnable, FADE_DELAY)
        }
    }

    /**
     * 隐藏滚动条
     */
    private fun hide() {
        if (isDragging) return

        removeCallbacks(hideRunnable)
        animate()
            .alpha(0.8f) // 隐藏后保持80%透明度
            .setDuration(FADE_DURATION)
            .withEndAction {
                alpha = 0.8f
                isVisible = true // 保持可见，只是半透明
            }
            .start()
    }

    private val hideRunnable = Runnable {
        hide()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
        scrollUpdateRunnable?.let { removeCallbacks(it) }
        scrollUpdateRunnable = null
        detachFromRecyclerView()
    }
}
