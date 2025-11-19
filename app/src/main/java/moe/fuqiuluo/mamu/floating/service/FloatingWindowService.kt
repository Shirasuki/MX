package moe.fuqiuluo.mamu.floating.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.*
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding
import moe.fuqiuluo.mamu.databinding.FloatingWindowLayoutBinding
import moe.fuqiuluo.mamu.driver.ProcessDeathMonitor
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager
import moe.fuqiuluo.mamu.floating.ext.selectedMemoryRanges
import moe.fuqiuluo.mamu.floating.ext.tabSwitchAnimation
import moe.fuqiuluo.mamu.floating.ext.topMostLayer
import moe.fuqiuluo.mamu.floating.controller.*
import moe.fuqiuluo.mamu.floating.ext.applyOpacity
import moe.fuqiuluo.mamu.floating.listener.DraggableFloatingIconTouchListener
import moe.fuqiuluo.mamu.floating.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.widget.*

private const val TAG = "FloatingWindowService"

class FloatingWindowService : Service(), ProcessDeathMonitor.Callback {
    // 窗口管理器
    private lateinit var windowManager: WindowManager

    // 未展开的悬浮窗图标界面绑定
    private lateinit var floatingIconBinding: FloatingWindowLayoutBinding

    // 展开的全屏界面绑定
    private lateinit var fullscreenBinding: FloatingFullscreenLayoutBinding

    private val floatingIconView: View get() = floatingIconBinding.root
    private val fullscreenView: View get() = fullscreenBinding.root

    // 通知
    private val notification by lazy {
        NotificationOverlay(this)
    }

    // 功能控制器
    private lateinit var searchController: SearchController
    private lateinit var settingsController: SettingsController
    private lateinit var savedAddressController: SavedAddressController
    private lateinit var memoryPreviewController: MemoryPreviewController
    private lateinit var breakpointController: BreakpointController

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingIconBinding = FloatingWindowLayoutBinding.inflate(LayoutInflater.from(this))
        fullscreenBinding = FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(this))

        setupFloatingIcon()
        setupFullscreenView()

        if (!WuwaDriver.loaded) {
            throw RuntimeException("WuwaDriver is not loaded")
        }

        initializeControllers()

        // 通知悬浮窗已启动
        FloatingWindowStateManager.setActive(true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingIcon() {
        val preferTopMost = MMKV.defaultMMKV().topMostLayer

        var layoutFlag = if (preferTopMost) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val iconSizePx = resources.getDimensionPixelSize(R.dimen.overlay_icon_size)
        val params = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = dp(100)

        runCatching {
            windowManager.addView(floatingIconView, params)
        }.onFailure {
            if (preferTopMost) {
                Log.w(
                    TAG,
                    "Failed to create TYPE_SYSTEM_ALERT window, falling back to TYPE_APPLICATION_OVERLAY",
                    it
                )
                layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                params.type = layoutFlag
                windowManager.addView(floatingIconView, params)
                notification.showError(getString(R.string.error_topmost_fallback))
            } else {
                throw it
            }
        }

        floatingIconView.setOnTouchListener(
            DraggableFloatingIconTouchListener(
                floatingIconView = floatingIconView,
                params = params,
                windowManager = windowManager,
                touchSlop = ViewConfiguration.get(this).scaledTouchSlop,
                showFullscreen = ::showFullscreen
            )
        )
    }

    private fun setupFullscreenView() {
        fullscreenView.visibility = View.GONE

        val preferTopMost = MMKV.defaultMMKV().topMostLayer

        var layoutFlag = if (preferTopMost) {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        runCatching {
            windowManager.addView(fullscreenView, params)
        }.onFailure {
            if (preferTopMost) {
                Log.w(
                    TAG,
                    "Failed to create TYPE_SYSTEM_ALERT fullscreen window, falling back to TYPE_APPLICATION_OVERLAY",
                    it
                )
                layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                params.type = layoutFlag
                windowManager.addView(fullscreenView, params)
            } else {
                throw it
            }
        }

        fullscreenBinding.applyOpacity()

        setupTopBar()

        initializeBottomInfoBar()

        // 默认显示搜索tab
        changeCurrentContent(R.id.content_search, R.id.indicator_search)
        // 初始化布局方向（根据当前屏幕方向）
        adjustLayoutForOrientation(resources.configuration.orientation)
    }

    private fun setupTopBar() {
        fullscreenBinding.attachedAppIcon.setOnClickListener {
            settingsController.showProcessSelectionDialog()
        }

        fullscreenBinding.btnCloseFullscreen.setOnClickListener {
            hideFullscreen()
        }

        fullscreenBinding.tabSettings.setOnClickListener {
            changeCurrentContent(R.id.content_settings, R.id.indicator_settings)
        }

        fullscreenBinding.tabSearch.setOnClickListener {
            changeCurrentContent(R.id.content_search, R.id.indicator_search)
        }

        fullscreenBinding.tabSavedAddresses.setOnClickListener {
            changeCurrentContent(R.id.content_saved_addresses, R.id.indicator_saved_addresses)
        }

        fullscreenBinding.tabMemoryPreview.setOnClickListener {
            changeCurrentContent(R.id.content_memory_preview, R.id.indicator_memory_preview)
        }

        fullscreenBinding.tabBreakpoints.setOnClickListener {
            changeCurrentContent(R.id.content_breakpoints, R.id.indicator_breakpoints)
        }
    }

    private fun initializeControllers() {
        searchController = SearchController(
            context = this,
            binding = fullscreenBinding.contentSearch,
            notification = notification,
            onShowSearchDialog = {
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                searchController.showSearchDialog(clipboardManager)
            }
        )

        settingsController = SettingsController(
            context = this,
            binding = fullscreenBinding.contentSettings,
            notification = notification,
            packageManager = packageManager,
            onUpdateTopIcon = ::updateTopIcon,
            onUpdateSearchProcessDisplay = searchController::updateSearchProcessDisplay,
            onClearSearchResults = searchController::clearSearchResults,
            onUpdateMemoryRangeSummary = ::updateBottomInfoBar,
            onApplyOpacity = { fullscreenBinding.applyOpacity() },
            processDeathCallback = this
        )

        savedAddressController = SavedAddressController(
            context = this,
            binding = fullscreenBinding.contentSavedAddresses,
            notification = notification
        )

        memoryPreviewController = MemoryPreviewController(
            context = this,
            binding = fullscreenBinding.contentMemoryPreview,
            notification = notification
        )

        breakpointController = BreakpointController(
            context = this,
            binding = fullscreenBinding.contentBreakpoints,
            notification = notification
        )

        // 初始化所有控制器
        searchController.initialize()
        settingsController.initialize()
        savedAddressController.initialize()
        memoryPreviewController.initialize()
        breakpointController.initialize()

        // 处理退出按钮
        fullscreenBinding.contentSettings.btnExitOverlay.setOnClickListener {
            if (settingsController.requestExitOverlay()) {
                stopSelf()
            }
        }
    }

    private fun initializeBottomInfoBar() {
        updateBottomInfoBar()

        fullscreenBinding.tvSelectedMemoryRanges.setOnClickListener {
            settingsController.showMemoryRangeDialog()
        }
    }

    private fun hideFullscreen() {
        fullscreenView.visibility = View.GONE
        floatingIconView.visibility = View.VISIBLE
    }

    private fun showFullscreen() {
        fullscreenView.visibility = View.VISIBLE
        floatingIconView.visibility = View.GONE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustLayoutForOrientation(newConfig.orientation)
    }

    private fun adjustLayoutForOrientation(orientation: Int) {
        val rootLayout = fullscreenBinding.rootLayout
        val toolbarContainer = fullscreenBinding.toolbarContainer
        val tabbarContainer = fullscreenBinding.tabbarContainer
        val contentContainer = fullscreenBinding.contentContainer
        val bottomInfoBar = fullscreenBinding.bottomInfoBar
        val attachedAppIcon = fullscreenBinding.attachedAppIcon
        val closeButton = fullscreenBinding.btnCloseFullscreen

        val tabFrames = listOf(
            R.id.tab_frame_settings,
            R.id.tab_frame_search,
            R.id.tab_frame_saved_addresses,
            R.id.tab_frame_memory_preview,
            R.id.tab_frame_breakpoints
        )

        when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                // 横屏：左侧垂直TabBar
                rootLayout.orientation = LinearLayout.HORIZONTAL

                toolbarContainer.orientation = LinearLayout.VERTICAL
                toolbarContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = dp(80)
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                toolbarContainer.gravity = Gravity.CENTER_HORIZONTAL

                // 调整图标
                attachedAppIcon.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = dp(48)
                    height = dp(48)
                    marginEnd = 0
                    setMargins(0, 0, 0, dp(16))
                }

                // 调整 tabbarContainer 的 layoutParams（在垂直的 toolbarContainer 中）
                tabbarContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 1f
                }

                // 获取现有的 scroll 和 tabbarLayout
                val oldScroll = tabbarContainer.findViewById<ViewGroup>(R.id.tabbar_scroll)
                val tabbarLayout = tabbarContainer.findViewById<LinearLayout>(R.id.tabbar_layout)

                if (oldScroll != null && tabbarLayout != null) {
                    // 从旧的 scroll 中移除 tabbarLayout
                    oldScroll.removeView(tabbarLayout)
                    // 移除旧的 scroll
                    tabbarContainer.removeView(oldScroll)

                    // 创建新的纵向ScrollView
                    val newScroll = ScrollView(this).apply {
                        id = R.id.tabbar_scroll
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isVerticalScrollBarEnabled = false
                    }

                    // 设置 tabbarLayout 的方向和参数
                    tabbarLayout.orientation = LinearLayout.VERTICAL
                    tabbarLayout.gravity = Gravity.CENTER_HORIZONTAL

                    // 调整每个 tab frame
                    tabFrames.forEach { frameId ->
                        fullscreenView.findViewById<FrameLayout>(frameId)?.apply {
                            layoutParams = LinearLayout.LayoutParams(
                                dp(48),
                                dp(60)
                            )
                        }
                        // 调整indicator为右侧显示
                        val indicator = when (frameId) {
                            R.id.tab_frame_settings -> fullscreenBinding.indicatorSettings
                            R.id.tab_frame_search -> fullscreenBinding.indicatorSearch
                            R.id.tab_frame_saved_addresses -> fullscreenBinding.indicatorSavedAddresses
                            R.id.tab_frame_memory_preview -> fullscreenBinding.indicatorMemoryPreview
                            R.id.tab_frame_breakpoints -> fullscreenBinding.indicatorBreakpoints
                            else -> null
                        }
                        indicator?.apply {
                            layoutParams = FrameLayout.LayoutParams(
                                dp(3),
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Gravity.END or Gravity.CENTER_VERTICAL
                            )
                        }
                    }

                    // 添加到新的 scroll
                    newScroll.addView(
                        tabbarLayout, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    tabbarContainer.addView(newScroll)
                }

                // 调整关闭按钮
                closeButton.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = dp(48)
                    height = dp(48)
                    setMargins(0, dp(8), 0, 0)
                }

                // 调整内容区域（在横向 rootLayout 中，占据剩余空间）
                contentContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = 0
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 1f
                }

                // 横屏时隐藏底部信息栏（屏幕高度有限）
                bottomInfoBar.visibility = View.GONE
            }

            else -> {
                // 竖屏：顶部横向TabBar
                rootLayout.orientation = LinearLayout.VERTICAL

                toolbarContainer.orientation = LinearLayout.HORIZONTAL
                toolbarContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = dp(65)
                }
                toolbarContainer.gravity = Gravity.CENTER_VERTICAL

                // 调整图标
                attachedAppIcon.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = dp(40)
                    height = dp(40)
                    marginEnd = dp(8)
                    setMargins(0, 0, dp(8), 0)
                }

                // 调整 tabbarContainer 的 layoutParams（在横向的 toolbarContainer 中）
                tabbarContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = 0
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 1f
                }

                // 获取现有的 scroll 和 tabbarLayout
                val oldScroll = tabbarContainer.findViewById<ViewGroup>(R.id.tabbar_scroll)
                val tabbarLayout = tabbarContainer.findViewById<LinearLayout>(R.id.tabbar_layout)

                if (oldScroll != null && tabbarLayout != null) {
                    // 从旧的 scroll 中移除 tabbarLayout
                    oldScroll.removeView(tabbarLayout)
                    // 移除旧的 scroll
                    tabbarContainer.removeView(oldScroll)

                    // 创建新的横向ScrollView
                    val newScroll = HorizontalScrollView(this).apply {
                        id = R.id.tabbar_scroll
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isHorizontalScrollBarEnabled = false
                    }

                    // 设置 tabbarLayout 的方向
                    tabbarLayout.orientation = LinearLayout.HORIZONTAL

                    // 调整每个 tab frame
                    tabFrames.forEach { frameId ->
                        fullscreenView.findViewById<FrameLayout>(frameId)?.apply {
                            layoutParams = LinearLayout.LayoutParams(
                                dp(40),
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        // 调整indicator为底部显示
                        val indicator = when (frameId) {
                            R.id.tab_frame_settings -> fullscreenBinding.indicatorSettings
                            R.id.tab_frame_search -> fullscreenBinding.indicatorSearch
                            R.id.tab_frame_saved_addresses -> fullscreenBinding.indicatorSavedAddresses
                            R.id.tab_frame_memory_preview -> fullscreenBinding.indicatorMemoryPreview
                            R.id.tab_frame_breakpoints -> fullscreenBinding.indicatorBreakpoints
                            else -> null
                        }
                        indicator?.apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(3),
                                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            )
                        }
                    }

                    // 添加到新的 scroll
                    newScroll.addView(
                        tabbarLayout, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    tabbarContainer.addView(newScroll)
                }

                // 调整关闭按钮
                closeButton.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = dp(40)
                    height = dp(40)
                    setMargins(0, 0, 0, 0)
                }

                // 调整内容区域（在垂直 rootLayout 中，占据剩余空间）
                contentContainer.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 1f
                }

                // 竖屏时显示底部信息栏
                bottomInfoBar.visibility = View.VISIBLE
                bottomInfoBar.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        notification.destroy()
        windowManager.removeView(floatingIconView)
        windowManager.removeView(fullscreenView)

        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
            ProcessDeathMonitor.stop()
        }

        // 清理控制器
        searchController.cleanup()
        settingsController.cleanup()
        savedAddressController.cleanup()
        memoryPreviewController.cleanup()
        breakpointController.cleanup()

        // 通知悬浮窗已关闭
        FloatingWindowStateManager.setActive(false)
    }

    private fun changeCurrentContent(contentId: Int, indicatorId: Int) {
        val mmkv = MMKV.defaultMMKV()
        val enableAnimation = mmkv.tabSwitchAnimation

        fun updateTabIndicator(activeIndicatorId: Int) {
            val indicators = mapOf(
                R.id.indicator_settings to fullscreenBinding.indicatorSettings,
                R.id.indicator_search to fullscreenBinding.indicatorSearch,
                R.id.indicator_saved_addresses to fullscreenBinding.indicatorSavedAddresses,
                R.id.indicator_memory_preview to fullscreenBinding.indicatorMemoryPreview,
                R.id.indicator_breakpoints to fullscreenBinding.indicatorBreakpoints
            )

            if (enableAnimation) {
                indicators.forEach { (indicatorId, indicator) ->
                    if (indicatorId == activeIndicatorId) {
                        if (indicator.visibility != View.VISIBLE) {
                            val fadeIn = AlphaAnimation(0f, 1f).apply {
                                duration = 100
                                interpolator = AccelerateDecelerateInterpolator()
                            }
                            indicator.visibility = View.VISIBLE
                            indicator.startAnimation(fadeIn)
                        }
                    } else {
                        if (indicator.isVisible) {
                            val fadeOut = AlphaAnimation(1f, 0f).apply {
                                duration = 100
                                interpolator = AccelerateDecelerateInterpolator()
                                setAnimationListener(object : Animation.AnimationListener {
                                    override fun onAnimationStart(animation: Animation?) {}
                                    override fun onAnimationEnd(animation: Animation?) {
                                        indicator.visibility = View.GONE
                                    }

                                    override fun onAnimationRepeat(animation: Animation?) {}
                                })
                            }
                            indicator.startAnimation(fadeOut)
                        }
                    }
                }
            } else {
                indicators.forEach { (indicatorId, indicator) ->
                    indicator.visibility = if (indicatorId == activeIndicatorId) View.VISIBLE else View.GONE
                }
            }
        }

        val contentContainer = fullscreenBinding.contentContainer

        var currentVisibleView: View? = null
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child.isVisible) {
                currentVisibleView = child
                break
            }
        }

        val targetView = contentContainer.findViewById<View>(contentId) ?: return

        if (currentVisibleView == targetView) {
            updateTabIndicator(indicatorId)
            return
        }

        if (enableAnimation) {
            currentVisibleView?.let { current ->
                val fadeOut = AlphaAnimation(1f, 0f).apply {
                    duration = 80
                    interpolator = AccelerateDecelerateInterpolator()
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(animation: Animation?) {}
                        override fun onAnimationEnd(animation: Animation?) {
                            current.visibility = View.GONE

                            val fadeIn = AlphaAnimation(0f, 1f).apply {
                                duration = 80
                                interpolator = AccelerateDecelerateInterpolator()
                            }
                            targetView.visibility = View.VISIBLE
                            targetView.startAnimation(fadeIn)
                        }

                        override fun onAnimationRepeat(animation: Animation?) {}
                    })
                }
                current.startAnimation(fadeOut)
            } ?: run {
                val fadeIn = AlphaAnimation(0f, 1f).apply {
                    duration = 80
                    interpolator = AccelerateDecelerateInterpolator()
                }
                targetView.visibility = View.VISIBLE
                targetView.startAnimation(fadeIn)
            }
        } else {
            currentVisibleView?.visibility = View.GONE
            targetView.visibility = View.VISIBLE
        }

        updateTabIndicator(indicatorId)
    }

    private fun updateTopIcon(process: DisplayProcessInfo?) {
        val iconView = fullscreenBinding.attachedAppIcon
        if (process == null) {
            iconView.setImageResource(R.mipmap.ic_launcher)
            return
        }
        runCatching {
            val packages = packageManager.getPackagesForUid(process.uid)
            val appIcon = packages?.firstOrNull()?.let {
                packageManager.getApplicationIcon(it)
            }
            if (appIcon != null) {
                iconView.setImageDrawable(appIcon)
            } else {
                iconView.setImageResource(R.drawable.icon_android_24px)
            }
        }.onFailure {
            iconView.setImageResource(R.drawable.icon_android_24px)
        }
    }

    private fun updateBottomInfoBar() {
        val mmkv = MMKV.defaultMMKV()
        val selectedRanges = mmkv.selectedMemoryRanges
        val tvSelectedMemoryRanges = fullscreenBinding.tvSelectedMemoryRanges

        if (selectedRanges.isEmpty()) {
            tvSelectedMemoryRanges.text = getString(R.string.memory_range_unselected)
            return
        }

        val sortedRanges = selectedRanges.sortedBy { it.ordinal }
        val rangeText = sortedRanges.joinToString(",") { it.code }
        val spannable = SpannableString(rangeText)

        var start = 0
        sortedRanges.forEach { range ->
            val end = start + range.code.length
            spannable.setSpan(
                ForegroundColorSpan(range.color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = end + 1
        }

        tvSelectedMemoryRanges.text = spannable
    }

    override fun onProcessDied(pid: Int) {
        notification.showError(getString(R.string.error_process_died, pid))

        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
        }
        ProcessDeathMonitor.stop()

        settingsController.updateCurrentProcessDisplay(null)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}