package moe.fuqiuluo.mamu.service

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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.MainActivity
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.filterLinuxProcess
import moe.fuqiuluo.mamu.data.settings.filterSystemProcess
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.data.settings.tabSwitchAnimation
import moe.fuqiuluo.mamu.data.settings.topMostLayer
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding
import moe.fuqiuluo.mamu.databinding.FloatingWindowLayoutBinding
import moe.fuqiuluo.mamu.driver.ProcessDeathMonitor
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager
import moe.fuqiuluo.mamu.floating.adapter.ProcessListAdapter
import moe.fuqiuluo.mamu.floating.controller.*
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.dialog.MemoryRangeDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetCalculatorDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetXorDialog
import moe.fuqiuluo.mamu.floating.dialog.customDialog
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.applyOpacity
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.listener.DraggableFloatingIconTouchListener
import moe.fuqiuluo.mamu.utils.ApplicationUtils
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import moe.fuqiuluo.mamu.utils.onError
import moe.fuqiuluo.mamu.utils.onSuccess
import moe.fuqiuluo.mamu.widget.*

private const val TAG = "FloatingWindowService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "floating_window_service"

class FloatingWindowService : Service(), ProcessDeathMonitor.Callback {
    // 协程作用域
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    // Tab 索引常量
    private companion object TabIndices {
        const val TAB_SETTINGS = 0
        const val TAB_SEARCH = 1
        const val TAB_SAVED_ADDRESSES = 2
        const val TAB_MEMORY_PREVIEW = 3
        const val TAB_BREAKPOINTS = 4
    }

    // 标记是否正在程序化切换tab（避免递归回调）
    private var isProgrammaticTabSwitch = false

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

        floatingIconBinding =
            FloatingWindowLayoutBinding.inflate(LayoutInflater.from(themedContext))
        fullscreenBinding =
            FloatingFullscreenLayoutBinding.inflate(LayoutInflater.from(themedContext))

        setupFloatingIcon()
        setupFullscreenView()

        if (!WuwaDriver.loaded) {
            Toast.makeText(this, "驱动载入异常，请重新启动!", Toast.LENGTH_SHORT).show()
            throw RuntimeException("WuwaDriver is not loaded")
        }

        initializeControllers()
        subscribeToUIActionEvents()
        subscribeToMemoryRangeChangedEvents()
        subscribeToProcessStateEvents()

        // 通知悬浮窗已启动
        FloatingWindowStateManager.setActive(true)
    }

    /**
     * 订阅 UI 操作请求事件
     */
    private fun subscribeToUIActionEvents() {
        coroutineScope.launch {
            FloatingEventBus.uiActionEvents.collect { event ->
                when (event) {
                    is UIActionEvent.ShowProcessSelectionDialog -> showProcessSelectionDialog()

                    is UIActionEvent.ShowMemoryRangeDialog -> showMemoryRangeDialog()

                    is UIActionEvent.ShowOffsetCalculatorDialog -> showOffsetCalculatorDialog(event.initialBaseAddress)

                    is UIActionEvent.ShowOffsetXorDialog -> {
                        showOffsetXorDialog(event.selectedAddresses)
                    }

                    is UIActionEvent.BindProcessRequest -> handleBindProcess(event.process)

                    is UIActionEvent.UnbindProcessRequest -> handleUnbindProcess(isUserInitiated = true)

                    is UIActionEvent.ExitOverlayRequest -> stopSelf()

                    is UIActionEvent.ApplyOpacityRequest -> fullscreenBinding.applyOpacity()

                    is UIActionEvent.HideFloatingWindow -> hideFullscreen()

                    is UIActionEvent.SwitchToSettingsTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_SETTINGS))
                        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_settings
                        updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, R.id.navigation_settings)
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SETTINGS)
                    }

                    is UIActionEvent.SwitchToSearchTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_SEARCH))
                        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_search
                        updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, R.id.navigation_search)
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SEARCH)
                    }

                    is UIActionEvent.SwitchToSavedAddressesTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_SAVED_ADDRESSES))
                        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_saved_addresses
                        updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, R.id.navigation_saved_addresses)
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_SAVED_ADDRESSES)
                    }

                    is UIActionEvent.SwitchToMemoryPreviewTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_MEMORY_PREVIEW))
                        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_memory_preview
                        updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, R.id.navigation_memory_preview)
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_MEMORY_PREVIEW)
                    }

                    is UIActionEvent.SwitchToBreakpointsTab -> {
                        isProgrammaticTabSwitch = true
                        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_BREAKPOINTS))
                        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_breakpoints
                        updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, R.id.navigation_breakpoints)
                        isProgrammaticTabSwitch = false
                        switchToTab(TAB_BREAKPOINTS)
                    }
                }
            }
        }
    }

    /**
     * 订阅内存范围配置变更事件
     */
    private fun subscribeToMemoryRangeChangedEvents() {
        coroutineScope.launch {
            FloatingEventBus.memoryRangeChangedEvents.collect {
                updateBottomInfoBar()
            }
        }
    }

    /**
     * 订阅进程状态变更事件（用于更新顶部图标）
     */
    private fun subscribeToProcessStateEvents() {
        coroutineScope.launch {
            FloatingEventBus.processStateEvents.collect { event ->
                when (event.type) {
                    ProcessStateEvent.Type.BOUND -> {
                        updateTopIcon(event.process)
                    }

                    ProcessStateEvent.Type.UNBOUND, ProcessStateEvent.Type.DIED -> {
                        updateTopIcon(null)
                    }
                }
            }
        }
    }

    /**
     * 处理进程绑定请求
     */
    private fun handleBindProcess(process: DisplayProcessInfo) {
        if (!WuwaDriver.isAllowedBindProc(process.packageName ?: process.cmdline)) {
            notification.showError("该进程禁止绑定!")
            return
        }

        // 如果当前有绑定的进程，先解绑
        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
            ProcessDeathMonitor.stop()
        }

        runCatching {
            val success = WuwaDriver.bindProcess(process.pid)
            if (!success) {
                notification.showError(getString(R.string.error_bind_process_failed))
                return
            }

            // 启动进程死亡监控
            ProcessDeathMonitor.start(process.pid, this)
        }.onFailure {
            it.printStackTrace()
            notification.showError(
                getString(R.string.error_bind_process_failed_with_reason, it.message.orEmpty())
            )
        }.onSuccess {
            notification.showSuccess(getString(R.string.success_process_selected, process.name))

            // 发布进程绑定成功事件
            coroutineScope.launch {
                FloatingEventBus.emitProcessState(
                    ProcessStateEvent(ProcessStateEvent.Type.BOUND, process)
                )
            }
        }
    }

    /**
     * 处理进程解绑
     * @param isUserInitiated 是否由用户主动触发（终止进程按钮）
     */
    private fun handleUnbindProcess(isUserInitiated: Boolean) {
        if (!WuwaDriver.isProcessBound) {
            if (isUserInitiated) {
                notification.showError(getString(R.string.error_no_bound_process))
            }
            return
        }

        val pid = WuwaDriver.currentBindPid

        if (isUserInitiated) {
            // 用户主动终止进程
            RootShellExecutor.exec(
                suCmd = RootConfigManager.getCustomRootCommand(), "kill -9 $pid", 1000
            ).onSuccess {
                notification.showSuccess(getString(R.string.success_process_terminated))
            }.onError {
                notification.showError(getString(R.string.error_terminate_failed))
            }
        }

        WuwaDriver.unbindProcess()
        ProcessDeathMonitor.stop()

        // 发布进程解绑事件
        coroutineScope.launch {
            FloatingEventBus.emitProcessState(
                ProcessStateEvent(ProcessStateEvent.Type.UNBOUND, null)
            )
        }
    }

    /**
     * 处理进程死亡
     */
    private fun handleProcessDied(pid: Int) {
        notification.showError(getString(R.string.error_process_died, pid))

        if (WuwaDriver.isProcessBound) {
            WuwaDriver.unbindProcess()
        }
        ProcessDeathMonitor.stop()

        // 发布进程死亡事件
        coroutineScope.launch {
            FloatingEventBus.emitProcessState(
                ProcessStateEvent(ProcessStateEvent.Type.DIED, null)
            )
        }
    }

    /**
     * 显示进程选择对话框
     */
    @SuppressLint("SetTextI18n")
    private fun showProcessSelectionDialog() {
        coroutineScope.launch {
            runCatching {
                val mmkv = MMKV.defaultMMKV()
                val filterSystem = mmkv.filterSystemProcess
                val filterLinux = mmkv.filterLinuxProcess

                val processList = withContext(Dispatchers.IO) {
                    WuwaDriver.listProcessesWithInfo().filter { process ->
                        when {
                            filterSystem && ApplicationUtils.isSystemApp(
                                this@FloatingWindowService, process.uid
                            ) -> false

                            filterLinux && process.uid < 1000 -> false
                            else -> true
                        }
                    }.map { process ->
                        when {
                            process.name.isEmpty() || ApplicationUtils.isSystemApp(
                                this@FloatingWindowService, process.uid
                            ) -> {
                                DisplayProcessInfo(
                                    icon = ApplicationUtils.getAndroidIcon(this@FloatingWindowService),
                                    name = process.name,
                                    packageName = null,
                                    pid = process.pid,
                                    uid = process.uid,
                                    prio = 1,
                                    rss = process.rss,
                                    cmdline = process.name
                                )
                            }

                            else -> {
                                val packageName = process.name.split(":").first()
                                var prio = 3

                                val appIcon = ApplicationUtils.getAppIconByPackageName(
                                    this@FloatingWindowService, packageName
                                ) ?: ApplicationUtils.getAppIconByUid(
                                    this@FloatingWindowService, process.uid
                                ) ?: ApplicationUtils.getAndroidIcon(this@FloatingWindowService)
                                    .also { prio-- }

                                val appName = ApplicationUtils.getAppNameByPackageName(
                                    this@FloatingWindowService, packageName
                                ) ?: ApplicationUtils.getAppNameByUid(
                                    this@FloatingWindowService, process.uid
                                ) ?: process.name.also { prio-- }

                                DisplayProcessInfo(
                                    icon = appIcon,
                                    name = appName,
                                    packageName = packageName,
                                    pid = process.pid,
                                    uid = process.uid,
                                    prio = prio,
                                    rss = process.rss,
                                    cmdline = process.name
                                )
                            }
                        }
                    }.sortedByDescending { it.prio }
                }

                val adapter = ProcessListAdapter(this@FloatingWindowService, processList)
                this@FloatingWindowService.customDialog(
                    title = getString(R.string.settings_select_process),
                    adapter = adapter,
                    onItemClick = { position ->
                        val selectedProcess = processList[position]
                        // 发送绑定进程请求事件，由 Service 统一处理绑定逻辑
                        coroutineScope.launch {
                            FloatingEventBus.emitUIAction(
                                UIActionEvent.BindProcessRequest(
                                    selectedProcess
                                )
                            )
                        }
                    })
            }.onFailure {
                Log.e(TAG, it.stackTraceToString())
                notification.showError("加载进程列表失败: ${it.message}")
            }
        }
    }

    /**
     * 显示内存范围选择对话框
     */
    private fun showMemoryRangeDialog() {
        val mmkv = MMKV.defaultMMKV()
        val allRanges = MemoryRange.entries.toTypedArray()
        val selectedRanges = mmkv.selectedMemoryRanges
        val checkedItems = allRanges.map { selectedRanges.contains(it) }.toBooleanArray()

        // 默认选中的内存范围
        val defaultRanges = setOf(
            MemoryRange.Jh,
            MemoryRange.Ch,
            MemoryRange.Ca,
            MemoryRange.Cd,
            MemoryRange.Cb,
            MemoryRange.Ps,
            MemoryRange.An
        )
        val defaultCheckedItems = allRanges.map { defaultRanges.contains(it) }.toBooleanArray()

        val memorySizes = if (WuwaDriver.isProcessBound) runCatching {
            val regions = WuwaDriver.queryMemRegions().divideToSimpleMemoryRange()
            regions.groupBy { it.range }.mapValues { (_, entries) ->
                entries.sumOf { it.end - it.start }
            }
        }.getOrNull() else {
            null
        }

        val dialog = MemoryRangeDialog(
            context = this,
            memoryRanges = allRanges,
            checkedItems = checkedItems,
            memorySizes = memorySizes,
            defaultCheckedItems = defaultCheckedItems
        )

        dialog.onMultiChoice = { newCheckedItems ->
            val newRanges = allRanges.filterIndexed { index, _ -> newCheckedItems[index] }.toSet()
            mmkv.selectedMemoryRanges = newRanges
            notification.showSuccess(getString(R.string.success_memory_range_saved))

            // 发送内存范围变更事件
            coroutineScope.launch {
                FloatingEventBus.emitMemoryRangeChanged()
            }
        }

        dialog.show()
    }

    /**
     * 显示偏移量计算器对话框
     */
    private fun showOffsetCalculatorDialog(initialBaseAddress: Long?) {
        val dialog = OffsetCalculatorDialog(
            context = this,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
            initialBaseAddress = initialBaseAddress
        )

        dialog.show()
    }

    /**
     * 显示偏移异或计算对话框
     */
    private fun showOffsetXorDialog(selectedAddresses: List<moe.fuqiuluo.mamu.floating.data.model.SavedAddress>) {
        val dialog = OffsetXorDialog(
            context = this,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
            selectedAddresses = selectedAddresses
        )
        dialog.show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingIcon() {
        val preferTopMost = MMKV.defaultMMKV().topMostLayer

        var layoutFlag = if (preferTopMost) {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val iconSizePx = resources.getDimensionPixelSize(R.dimen.overlay_icon_size)
        val params = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            layoutFlag,
            // 启用硬件加速以提升渲染性能
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
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
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            // 启用硬件加速以提升渲染性能
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
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

        // 默认显示搜索tab (使用 TabLayout 和 NavigationRail API)
        isProgrammaticTabSwitch = true
        fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(TAB_SEARCH))
        fullscreenBinding.sidebarNavigationRail.selectedItemId = R.id.navigation_search
        isProgrammaticTabSwitch = false
        switchToTab(TAB_SEARCH)

        // 初始化布局方向（根据当前屏幕方向）
        currentOrientation = resources.configuration.orientation
        adjustLayoutForOrientation(currentOrientation)
    }

    private fun setupTopBar() {
        // 顶部工具栏应用图标
        fullscreenBinding.attachedAppIcon.setOnClickListener {
            showProcessSelectionDialog()
        }

        // 关闭按钮
        fullscreenBinding.btnCloseFullscreen.setOnClickListener {
            hideFullscreen()
        }

        // 侧边栏应用图标
        fullscreenBinding.sidebarAppIcon.setOnClickListener {
            showProcessSelectionDialog()
        }

        // 侧边栏关闭按钮
        fullscreenBinding.sidebarBtnClose.setOnClickListener {
            hideFullscreen()
        }

        // 设置 TabLayout（顶部工具栏）
        fullscreenBinding.tabLayout.apply {
            removeAllTabs()
            addTab(newTab().setIcon(R.drawable.icon_settings_24px).setContentDescription(getString(R.string.tab_settings)))
            addTab(newTab().setIcon(R.drawable.icon_search_24px).setContentDescription(getString(R.string.tab_search)))
            addTab(newTab().setIcon(R.drawable.icon_save_24px).setContentDescription(getString(R.string.tab_saved_addresses)))
            addTab(newTab().setIcon(R.drawable.icon_list_24px).setContentDescription(getString(R.string.tab_memory_preview)))
            addTab(newTab().setIcon(R.drawable.icon_bug_report_24px).setContentDescription(getString(R.string.tab_breakpoints)))

            addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                    if (isProgrammaticTabSwitch) return
                    tab?.let {
                        switchToTab(it.position)
                        // 同步侧边栏 NavigationRail
                        val itemId = getNavigationItemIdByIndex(it.position)
                        if (fullscreenBinding.sidebarNavigationRail.selectedItemId != itemId) {
                            isProgrammaticTabSwitch = true
                            fullscreenBinding.sidebarNavigationRail.selectedItemId = itemId
                            updateNavigationRailIndicator(fullscreenBinding.sidebarNavigationRail, itemId)
                            isProgrammaticTabSwitch = false
                        }
                    }
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            })
        }

        // 设置 NavigationRail（侧边栏）
        fullscreenBinding.sidebarNavigationRail.apply {
            setOnItemSelectedListener { item ->
                if (isProgrammaticTabSwitch) return@setOnItemSelectedListener true

                val tabIndex = getTabIndexByNavigationItemId(item.itemId)
                switchToTab(tabIndex)

                // 更新自定义指示器
                updateNavigationRailIndicator(this, item.itemId)

                // 同步顶部 TabLayout
                if (fullscreenBinding.tabLayout.selectedTabPosition != tabIndex) {
                    isProgrammaticTabSwitch = true
                    fullscreenBinding.tabLayout.selectTab(fullscreenBinding.tabLayout.getTabAt(tabIndex))
                    isProgrammaticTabSwitch = false
                }
                true
            }

            // 添加自定义左侧指示器
            post {
                addCustomIndicatorToNavigationRail(this)
            }
        }
    }

    /**
     * 为 NavigationRail 添加自定义左侧指示器
     */
    private fun addCustomIndicatorToNavigationRail(navigationRail: com.google.android.material.navigationrail.NavigationRailView) {
        try {
            val menuView = navigationRail.getChildAt(0) as? ViewGroup ?: return

            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue

                // 创建左侧指示器
                val indicator = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        dp(4), // 宽度 4dp
                        dp(32) // 高度 32dp
                    ).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }
                    setBackgroundColor(resources.getColor(R.color.floating_primary, null))
                    visibility = View.GONE // 默认隐藏
                    tag = "custom_indicator"
                }

                // 添加指示器到 item
                if (itemView is FrameLayout) {
                    itemView.addView(indicator, 0) // 添加到最底层
                }
            }

            // 更新指示器显示状态
            updateNavigationRailIndicator(navigationRail, navigationRail.selectedItemId)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add custom indicator to NavigationRail", e)
        }
    }

    /**
     * 更新 NavigationRail 指示器显示
     */
    private fun updateNavigationRailIndicator(
        navigationRail: com.google.android.material.navigationrail.NavigationRailView,
        selectedItemId: Int
    ) {
        try {
            val menuView = navigationRail.getChildAt(0) as? ViewGroup ?: return

            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue
                val indicator = itemView.findViewWithTag<View>("custom_indicator") ?: continue

                val menuItem = navigationRail.menu.getItem(i)
                indicator.visibility = if (menuItem.itemId == selectedItemId) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update NavigationRail indicator", e)
        }
    }

    /**
     * 根据 tab 索引获取 NavigationRail 的 item ID
     */
    private fun getNavigationItemIdByIndex(index: Int): Int {
        return when (index) {
            TAB_SETTINGS -> R.id.navigation_settings
            TAB_SEARCH -> R.id.navigation_search
            TAB_SAVED_ADDRESSES -> R.id.navigation_saved_addresses
            TAB_MEMORY_PREVIEW -> R.id.navigation_memory_preview
            TAB_BREAKPOINTS -> R.id.navigation_breakpoints
            else -> R.id.navigation_search
        }
    }

    /**
     * 根据 NavigationRail 的 item ID 获取 tab 索引
     */
    private fun getTabIndexByNavigationItemId(itemId: Int): Int {
        return when (itemId) {
            R.id.navigation_settings -> TAB_SETTINGS
            R.id.navigation_search -> TAB_SEARCH
            R.id.navigation_saved_addresses -> TAB_SAVED_ADDRESSES
            R.id.navigation_memory_preview -> TAB_MEMORY_PREVIEW
            R.id.navigation_breakpoints -> TAB_BREAKPOINTS
            else -> TAB_SEARCH
        }
    }

    /**
     * 根据 tab 索引切换内容视图
     */
    private fun switchToTab(tabIndex: Int) {
        val contentId = when (tabIndex) {
            TAB_SETTINGS -> R.id.content_settings
            TAB_SEARCH -> R.id.content_search
            TAB_SAVED_ADDRESSES -> R.id.content_saved_addresses
            TAB_MEMORY_PREVIEW -> R.id.content_memory_preview
            TAB_BREAKPOINTS -> R.id.content_breakpoints
            else -> R.id.content_search
        }

        val contentContainer = fullscreenBinding.contentContainer

        // 隐藏所有内容视图
        for (i in 0 until contentContainer.childCount) {
            contentContainer.getChildAt(i).visibility = View.GONE
        }

        // 显示选中的内容视图
        contentContainer.findViewById<View>(contentId)?.visibility = View.VISIBLE
    }

    private fun initializeControllers() {
        // 先初始化 savedAddressController，因为 searchController 需要引用它
        savedAddressController = SavedAddressController(
            context = this,
            binding = fullscreenBinding.contentSavedAddresses,
            notification = notification
        )

        // TODO: 重新添加 badge views 到新的 TabLayout
        // 设置 badge views (顶部工具栏和侧边栏)
        // savedAddressController.setAddressCountBadgeView(
        //     fullscreenBinding.badgeSavedAddresses, fullscreenBinding.sidebarBadgeSavedAddresses
        // )

        searchController = SearchController(
            context = this,
            binding = fullscreenBinding.contentSearch,
            notification = notification,
            clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager,
        )

        settingsController = SettingsController(
            context = this, binding = fullscreenBinding.contentSettings, notification = notification
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
    }

    private fun initializeBottomInfoBar() {
        updateBottomInfoBar()

        fullscreenBinding.tvSelectedMemoryRanges.setOnClickListener {
            // 发送显示内存范围对话框事件
            coroutineScope.launch {
                FloatingEventBus.emitUIAction(UIActionEvent.ShowMemoryRangeDialog)
            }
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
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

        // 切换顶部工具栏和侧边栏的可见性
        fullscreenBinding.toolbarContainer.visibility = if (isLandscape) View.GONE else View.VISIBLE
        fullscreenBinding.sidebarContainer.visibility = if (isLandscape) View.VISIBLE else View.GONE

        // 更新内容区域的约束
        val contentContainer = fullscreenBinding.contentContainer
        val layoutParams =
            contentContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isLandscape) {
            // 横屏：内容区域从侧边栏右侧开始
            layoutParams.topToBottom =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topToTop =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.startToEnd = R.id.sidebar_container
            layoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            // 竖屏：内容区域从顶部工具栏下方开始
            layoutParams.topToBottom = R.id.toolbar_container
            layoutParams.topToTop =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.startToEnd =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        contentContainer.layoutParams = layoutParams

        // 更新底部信息栏的约束
        val bottomInfoBar = fullscreenBinding.bottomInfoBar
        val bottomLayoutParams =
            bottomInfoBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

        if (isLandscape) {
            bottomLayoutParams.startToEnd = R.id.sidebar_container
            bottomLayoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        } else {
            bottomLayoutParams.startToEnd =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            bottomLayoutParams.startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        bottomInfoBar.layoutParams = bottomLayoutParams

        // TabLayout 自动管理指示器状态，不需要手动同步

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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher).setContentIntent(pendingIntent).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
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

        // 取消协程
        coroutineScope.cancel()

        // 通知悬浮窗已关闭
        FloatingWindowStateManager.setActive(false)
    }

    private fun updateTopIcon(process: DisplayProcessInfo?) {
        val fullscreenIconView = fullscreenBinding.attachedAppIcon
        val sidebarIconView = fullscreenBinding.sidebarAppIcon
        val floatingIconView = floatingIconBinding.appIcon

        if (process == null) {
            fullscreenIconView.setImageResource(R.mipmap.ic_launcher)
            sidebarIconView.setImageResource(R.mipmap.ic_launcher)
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
                sidebarIconView.setImageDrawable(appIcon)
                floatingIconView.setImageDrawable(appIcon)
            } else {
                fullscreenIconView.setImageResource(R.drawable.icon_android_24px)
                sidebarIconView.setImageResource(R.drawable.icon_android_24px)
                floatingIconView.setImageResource(R.drawable.icon_android_24px)
            }
        }.onFailure {
            fullscreenIconView.setImageResource(R.drawable.icon_android_24px)
            sidebarIconView.setImageResource(R.drawable.icon_android_24px)
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
                ForegroundColorSpan(range.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = end + 1
        }

        tvSelectedMemoryRanges.text = spannable
    }

    override fun onProcessDied(pid: Int) {
        handleProcessDied(pid)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}