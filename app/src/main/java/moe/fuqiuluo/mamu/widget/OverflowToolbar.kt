package moe.fuqiuluo.mamu.widget

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import moe.fuqiuluo.mamu.R

class OverflowToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconContainer: LinearLayout
    private val overflowButton: ImageButton

    private var actions: List<ToolbarAction> = emptyList()
    private var visibleActions: List<ToolbarAction> = emptyList()
    private var overflowActions: List<ToolbarAction> = emptyList()

    private val iconWidth: Int
    private val overflowButtonWidth: Int

    // 调试开关
    private var debugEnabled = false
    private fun logD(msg: String) { if (debugEnabled) Log.d("OverflowToolbar", msg) }
    private fun logW(msg: String) { if (debugEnabled) Log.w("OverflowToolbar", msg) }

    // 防重复重建的“指纹”
    private var lastFingerprint: LayoutFingerprint? = null

    // 仅安排一次的 PreDraw 回调
    private var preDrawScheduled = false
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private var overflowCallback: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL

        // 从资源文件获取图标大小
        iconWidth = context.resources.getDimensionPixelSize(R.dimen.floating_icon_xl)
        overflowButtonWidth = context.resources.getDimensionPixelSize(R.dimen.floating_icon_xl)

        // 从 XML 加载布局
        LayoutInflater.from(context).inflate(R.layout.widget_overflow_toolbar, this, true)

        // 获取视图引用
        iconContainer = findViewById(R.id.icon_container)
        overflowButton = findViewById<ImageButton>(R.id.overflow_button).apply {
            setOnClickListener { invokeOverflow() }
        }

        logD("init: iconWidth=$iconWidth overflowButtonWidth=$overflowButtonWidth")
    }

    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        logD("debugEnabled=$debugEnabled")
    }

    fun setActions(actions: List<ToolbarAction>) {
        this.actions = actions.toList()
        logD("setActions(size=${actions.size}), isAttached=$isAttachedToWindow, isLaidOut=$isLaidOut, width=$width")
        scheduleComputeNextDraw("setActions")
        // 提示系统尽快测量布局
        requestLayout()
        invalidate()
    }

    fun setOverflowCallback(callback: (() -> Unit)?) {
        overflowCallback = callback
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        logD("onAttachedToWindow, width=$width, isLaidOut=$isLaidOut")
        scheduleComputeNextDraw("onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        logD("onDetachedFromWindow -> clear preDraw")
        clearPreDrawListener()
        // 避免持有过期状态
        lastFingerprint = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        logD("onVisibilityChanged: visibility=$visibility, width=$width, isLaidOut=$isLaidOut")
        if (visibility == VISIBLE) {
            scheduleComputeNextDraw("onVisibilityChanged")
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        logD("onSizeChanged: w=$w h=$h oldw=$oldw oldh=$oldh actions=${actions.size}")
        if (w != oldw || h != oldh) {
            scheduleComputeNextDraw("onSizeChanged")
        }
    }

    // 注意：不在 onLayout 里主动重建，避免过度触发
    // override fun onLayout(...) { super.onLayout(...); }
    private fun scheduleComputeNextDraw(reason: String) {
        if (preDrawScheduled) {
            logD("scheduleComputeNextDraw($reason) skipped, already scheduled")
            return
        }
        if (!viewTreeObserver.isAlive) {
            logW("scheduleComputeNextDraw($reason): vto not alive, will retry on next attach")
            // 在 onAttachedToWindow 时会再次安排
            return
        }
        preDrawScheduled = true
        preDrawListener = ViewTreeObserver.OnPreDrawListener {
            clearPreDrawListener()
            performUpdate("preDraw:$reason")
            true
        }.also {
            viewTreeObserver.addOnPreDrawListener(it)
        }
        logD("scheduleComputeNextDraw($reason) -> scheduled")
    }

    private fun clearPreDrawListener() {
        if (preDrawListener != null && viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        preDrawListener = null
        preDrawScheduled = false
    }

    private data class LayoutFingerprint(
        val availableWidth: Int,
        val visibleIds: List<Int>,
        val overflowIds: List<Int>,
        val overflowVisible: Boolean
    )

    private fun performUpdate(reason: String) {
        val availableWidth = (measuredWidth - paddingStart - paddingEnd).coerceAtLeast(0)
        logD("performUpdate($reason): measuredWidth=$measuredWidth, paddingStart=$paddingStart, paddingEnd=$paddingEnd, availableWidth=$availableWidth, actions=${actions.size}")

        if (availableWidth <= 0) {
            logW("availableWidth<=0, skip this frame")
            return
        }

        // 尝试不预留溢出按钮，能全放下就不显示溢出
        val maxNoOverflow = (availableWidth / iconWidth).coerceAtLeast(0)
        val canFitAll = actions.size <= maxNoOverflow

        val (newVisible, newOverflow) = if (canFitAll) {
            actions to emptyList()
        } else {
            // 需要溢出：预留溢出按钮后再计算
            val widthForIcons = (availableWidth - overflowButtonWidth).coerceAtLeast(0)
            val maxWithOverflow = (widthForIcons / iconWidth).coerceAtLeast(0) + 1
            val visCount = maxWithOverflow.coerceAtMost(actions.size)
            actions.take(visCount) to actions.drop(visCount)
        }

        val newVisibleIds = newVisible.map { it.id }
        val newOverflowIds = newOverflow.map { it.id }
        val newOverflowVisible = newOverflow.isNotEmpty()

        val fp = LayoutFingerprint(
            availableWidth = availableWidth,
            visibleIds = newVisibleIds,
            overflowIds = newOverflowIds,
            overflowVisible = newOverflowVisible
        )

        if (fp == lastFingerprint) {
            logD("performUpdate: skip (no effective change). fp=$fp")
            return
        }

        logD(
            "layout result: " +
                    "canFitAll=$canFitAll, " +
                    "visible=${newVisibleIds}, " +
                    "overflow=${newOverflowIds}, " +
                    "overflowVisible=$newOverflowVisible"
        )

        // 应用变更
        visibleActions = newVisible
        overflowActions = newOverflow
        lastFingerprint = fp

        rebuildIconContainer()
        overflowButton.visibility = if (newOverflowVisible) VISIBLE else GONE
        logD("applied: childCount=${iconContainer.childCount}, overflowBtn=${if (overflowButton.visibility==VISIBLE) "VISIBLE" else "GONE"}")
    }

    @SuppressLint("ResourceType")
    private fun rebuildIconContainer() {
        logD("rebuildIconContainer: removeAllViews then add ${visibleActions.size} buttons")
        iconContainer.removeAllViews()

        val padding = context.resources.getDimensionPixelSize(R.dimen.floating_spacing_sm)

        visibleActions.forEach { action ->
            val button = ImageButton(context).apply {
                layoutParams = LayoutParams(iconWidth, LayoutParams.MATCH_PARENT)
                setImageResource(action.icon)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(padding, padding, padding, padding)
                contentDescription = action.label
                setOnClickListener { action.onClick() }
            }
            iconContainer.addView(button)
        }
    }

    private fun invokeOverflow() {
        if (overflowActions.isEmpty()) return

        overflowCallback?.invoke()
    }
}