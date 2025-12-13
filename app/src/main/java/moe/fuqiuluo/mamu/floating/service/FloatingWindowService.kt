package moe.fuqiuluo.mamu.floating.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import androidx.appcompat.view.ContextThemeWrapper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.view.isVisible
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding
import moe.fuqiuluo.mamu.databinding.FloatingWindowLayoutBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.ProcessDeathMonitor
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.data.settings.tabSwitchAnimation
import moe.fuqiuluo.mamu.data.settings.topMostLayer
import moe.fuqiuluo.mamu.floating.controller.*
import moe.fuqiuluo.mamu.floating.ext.applyOpacity
import moe.fuqiuluo.mamu.floating.listener.DraggableFloatingIconTouchListener
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.widget.*

private const val TAG = "FloatingWindowService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "floating_window_service"

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

    // 预创建动画对象，避免在动画期间频繁分配内存
    // Android 官方文档: "请在初始化期间或动画之间分配对象。切勿在动画运行期间进行分配。"
    private val sharedInterpolator = AccelerateDecelerateInterpolator()

    // Tab 指示器动画 (100ms)
    private val indicatorFadeIn by lazy {
        AlphaAnimation(0f, 1f).apply {
            duration = 100
            interpolator = sharedInterpolator
        }
    }
    private val indicatorFadeOut by lazy {
        AlphaAnimation(1f, 0f).apply {
            duration = 100
            interpolator = sharedInterpolator
        }
    }

    // 内容切换动画 (80ms)
    private val contentFadeIn by lazy {
        AlphaAnimation(0f, 1f).apply {
            duration = 80
            interpolator = sharedInterpolator
        }
    }
    private val contentFadeOut by lazy {
        AlphaAnimation(1f, 0f).apply {
            duration = 80
            interpolator = sharedInterpolator
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // 创建前台服务通知
        createNotificationChannel()
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 创建带有 Material Components 主题的 Context
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MX)

        floatingIconBinding = FloatingWindowLayoutBinding.inflate(LayoutInflater.from(themedContext))
        fullscreenBinding = FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(themedContext))

        setupFloatingIcon()
        setupFullscreenView()

        if (!WuwaDriver.loaded) {
            Toast.makeText(this, "驱动载入异常，请重新启动!", Toast.LENGTH_SHORT).show()
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
            // 启用硬件加速以提升渲染性能
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
            // 启用硬件加速以提升渲染性能
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
            hideFullscreen() // fullscreenBinding.btnCloseFullscreen
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
        // 先初始化 savedAddressController，因为 searchController 需要引用它
        savedAddressController = SavedAddressController(
            context = this,
            binding = fullscreenBinding.contentSavedAddresses,
            notification = notification
        )

        // 设置 badge view
        savedAddressController.setAddressCountBadgeView(fullscreenBinding.badgeSavedAddresses)

        searchController = SearchController(
            context = this,
            binding = fullscreenBinding.contentSearch,
            notification = notification,
            onShowSearchDialog = {
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                searchController.showSearchDialog(clipboardManager)
            },
            onSaveSelectedAddresses = { selectedItems ->
                // 将搜索结果转换为 SavedAddress 并保存
                val ranges = searchController.getRanges()
                val savedAddresses = selectedItems.mapNotNull { item ->
                    when (item) {
                        is ExactSearchResultItem -> {
                            // 查找对应的内存范围
                            val range = ranges?.find { range ->
                                item.address >= range.start && item.address < range.end
                            }?.range ?: return@mapNotNull null

                            SavedAddress(
                                address = item.address,
                                name = "Var #${String.format("%X", item.address)}",
                                valueType = item.valueType,
                                value = item.value,
                                isFrozen = false,
                                range = range
                            )
                        }
                        is FuzzySearchResultItem -> {
                            // 查找对应的内存范围
                            val range = ranges?.find { range ->
                                item.address >= range.start && item.address < range.end
                            }?.range ?: return@mapNotNull null

                            SavedAddress(
                                address = item.address,
                                name = "Var #${String.format("%X", item.address)}",
                                valueType = item.valueType,
                                value = item.value,
                                isFrozen = false,
                                range = range
                            )
                        }
                        else -> null
                    }
                }
                savedAddressController.saveAddresses(savedAddresses)
            },
            onExitFullscreen = {
                // 退出全屏：隐藏搜索进度对话框但保持搜索状态
                hideFullscreen() // SearchController called
            }
        )

        settingsController = SettingsController(
            context = this,
            binding = fullscreenBinding.contentSettings,
            notification = notification,
            packageManager = packageManager,
            onUpdateTopIcon = ::updateTopIcon,
            onUpdateSearchProcessDisplay = {
                searchController.updateSearchProcessDisplay(it)
                savedAddressController.updateProcessDisplay(it)
            },
            onUpdateMemoryRangeSummary = ::updateBottomInfoBar,
            onApplyOpacity = { fullscreenBinding.applyOpacity() },
            processDeathCallback = this,
            onBoundProcessChanged = {
                // 绑定的进程改变，无效化
                searchController.clearSearchResults()
                savedAddressController.clearAll()
            }
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
        // 隐藏搜索进度对话框（如果正在搜索）
        searchController.hideSearchProgressIfNeeded()

        fullscreenView.visibility = View.GONE
        floatingIconView.visibility = View.VISIBLE
    }

    private fun showFullscreen() {
        fullscreenView.visibility = View.VISIBLE
        floatingIconView.visibility = View.GONE

        // 重新显示搜索进度对话框（如果正在搜索）
        searchController.showSearchProgressIfNeeded()
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
        if (::searchController.isInitialized) {
            searchController.adjustLayoutForOrientation(orientation)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, moe.fuqiuluo.mamu.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
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
                            // 复用预创建的动画对象
                            indicatorFadeIn.reset()
                            indicator.visibility = View.VISIBLE
                            indicator.startAnimation(indicatorFadeIn)
                        }
                    } else {
                        if (indicator.isVisible) {
                            // 使用 animate() API 代替 Animation，更高效且自动管理生命周期
                            indicator.animate()
                                .alpha(0f)
                                .setDuration(100)
                                .setInterpolator(sharedInterpolator)
                                .withEndAction {
                                    indicator.visibility = View.GONE
                                    indicator.alpha = 1f  // 重置 alpha 供下次使用
                                }
                                .start()
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
                // 使用 View.animate() API，更高效且无需频繁创建对象
                current.animate()
                    .alpha(0f)
                    .setDuration(80)
                    .setInterpolator(sharedInterpolator)
                    .withEndAction {
                        current.visibility = View.GONE
                        current.alpha = 1f  // 重置 alpha

                        targetView.alpha = 0f
                        targetView.visibility = View.VISIBLE
                        targetView.animate()
                            .alpha(1f)
                            .setDuration(80)
                            .setInterpolator(sharedInterpolator)
                            .start()
                    }
                    .start()
            } ?: run {
                targetView.alpha = 0f
                targetView.visibility = View.VISIBLE
                targetView.animate()
                    .alpha(1f)
                    .setDuration(80)
                    .setInterpolator(sharedInterpolator)
                    .start()
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