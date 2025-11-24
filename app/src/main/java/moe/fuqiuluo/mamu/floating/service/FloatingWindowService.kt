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
import androidx.appcompat.view.ContextThemeWrapper
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

    // 缓存屏幕方向，避免重复调整布局
    private var currentOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 创建带有 Material Components 主题的 Context
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MX)

        floatingIconBinding = FloatingWindowLayoutBinding.inflate(LayoutInflater.from(themedContext))
        fullscreenBinding = FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(themedContext))

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
        currentOrientation = resources.configuration.orientation
        adjustLayoutForOrientation(currentOrientation)
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
        // 只有方向真正改变时才调整布局，避免无谓的重建
        if (currentOrientation != newConfig.orientation) {
            currentOrientation = newConfig.orientation
            adjustLayoutForOrientation(newConfig.orientation)
        }
    }

    private fun adjustLayoutForOrientation(orientation: Int) {
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
        val fullscreenIconView = fullscreenBinding.attachedAppIcon
        val floatingIconView = floatingIconBinding.appIcon

        if (process == null) {
            fullscreenIconView.setImageResource(R.mipmap.ic_launcher)
            floatingIconView.setImageResource(R.mipmap.ic_launcher)
            return
        }
        runCatching {
            val packages = packageManager.getPackagesForUid(process.uid)
            val appIcon = packages?.firstOrNull()?.let {
                packageManager.getApplicationIcon(it)
            }
            if (appIcon != null) {
                fullscreenIconView.setImageDrawable(appIcon)
                floatingIconView.setImageDrawable(appIcon)
            } else {
                fullscreenIconView.setImageResource(R.drawable.icon_android_24px)
                floatingIconView.setImageResource(R.drawable.icon_android_24px)
            }
        }.onFailure {
            fullscreenIconView.setImageResource(R.drawable.icon_android_24px)
            floatingIconView.setImageResource(R.drawable.icon_android_24px)
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