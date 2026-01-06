package moe.fuqiuluo.mamu.floating.controller

import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.memoryDisplayFormats
import moe.fuqiuluo.mamu.data.settings.memoryRegionCacheInterval
import moe.fuqiuluo.mamu.databinding.FloatingMemoryPreviewLayoutBinding
import moe.fuqiuluo.mamu.driver.LocalMemoryOps
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.InfiniteMemoryAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.data.model.MemoryPreviewItem
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.floating.dialog.AddressActionDialog
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ModuleListDialog
import moe.fuqiuluo.mamu.floating.event.AddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.SaveMemoryPreviewEvent
import moe.fuqiuluo.mamu.floating.event.SearchResultsUpdatedEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRangeParallel
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.multiChoiceDialog
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class MemoryPreviewController(
    context: Context,
    binding: FloatingMemoryPreviewLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingMemoryPreviewLayoutBinding>(context, binding, notification) {

    companion object {
        private val PAGE_SIZE = LocalMemoryOps.getPageSize()
        private const val TAG = "MemoryPreviewCtrl"
        private const val MEMORY_ROW_POOL_SIZE = 32
        private const val MAX_NAVIGATION_HISTORY = 100
        private const val DEFAULT_PAGE_COUNT = 3  // 默认显示3页
    }

    private lateinit var currentFormats: MutableList<MemoryDisplayFormat>
    private var currentStartAddress: Long = 0L
    private var targetAddress: Long? = null

    private val navigationHistory = mutableListOf<Long>()
    private var navigationIndex = -1
    private var isNavigating = false

    private var memoryRegions: List<DisplayMemRegionEntry> = emptyList()
    private var memoryRegionsCacheTime: Long = 0L

    private val mmkv by lazy { MMKV.defaultMMKV() }

    private lateinit var adapter: InfiniteMemoryAdapter

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun initialize() {
        currentFormats = mmkv.memoryDisplayFormats.toMutableList()

        setupAdapter()
        setupToolbar()
        setupRecyclerView()
        setupStatusBar()
        updateFormatDisplay()

        subscribeToNavigateEvents()
        loadInitialPlaceholderPage()
    }

    private fun setupAdapter() {
        adapter = InfiniteMemoryAdapter(
            onRowClick = { memoryRow -> showModifyValueDialog(memoryRow) },
            onRowLongClick = { memoryRow ->
                showAddressActionDialog(memoryRow)
                true
            },
            onSelectionChanged = { _ -> },
            onDataRequest = { pageAlignedAddress, callback ->
                requestPageData(pageAlignedAddress, callback)
            },
            onBoundaryReached = { isTop ->
                handleBoundaryReached(isTop)
            }
        )
        adapter.setFormats(currentFormats)
    }

    /**
     * 处理边界到达事件
     * @param isTop true 表示到达顶部边界，false 表示到达底部边界
     */
    private fun handleBoundaryReached(isTop: Boolean) {
        if (isTop) {
            // 向上扩展，需要保持滚动位置
            val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
            val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0
            val firstVisibleView = layoutManager?.findViewByPosition(firstVisiblePosition)
            val offset = firstVisibleView?.top ?: 0
            
            val expanded = adapter.expandTop(1)
            if (expanded) {
                // 扩展后调整滚动位置，保持视觉上的连续性
                val rowsPerPage = PAGE_SIZE / adapter.getAlignment()
                val newPosition = firstVisiblePosition + rowsPerPage
                layoutManager?.scrollToPositionWithOffset(newPosition, offset)
            }
        } else {
            // 向下扩展，不需要调整滚动位置
            adapter.expandBottom(1)
        }
    }

    /**
     * 请求页面数据（页面对齐的地址）
     * 关键：确保 native 函数读取时地址是页面对齐的
     */
    private fun requestPageData(pageAlignedAddress: Long, callback: (ByteArray?) -> Unit) {
        if (!WuwaDriver.isProcessBound) {
            callback(null)
            return
        }

        // 验证地址是否页面对齐
        if (pageAlignedAddress % PAGE_SIZE != 0L) {
            Log.e(TAG, "地址未对齐到页面边界: 0x${pageAlignedAddress.toString(16)}")
            callback(null)
            return
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val data = WuwaDriver.readMemory(pageAlignedAddress, PAGE_SIZE)
                withContext(Dispatchers.Main) {
                    callback(data)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取内存失败: 0x${pageAlignedAddress.toString(16)}", e)
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    private fun loadInitialPlaceholderPage() {
        currentStartAddress = 0L
        targetAddress = null
        val defaultRows = (DEFAULT_PAGE_COUNT * PAGE_SIZE) / adapter.getAlignment()
        adapter.setAddressRange(0L, defaultRows)
        updateEmptyState()
    }

    private fun subscribeToNavigateEvents() {
        coroutineScope.launch {
            FloatingEventBus.navigateToMemoryAddressEvents.collect { event ->
                jumpToAddress(event.address)
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.previewToolbar

        val actions = listOf(
            ToolbarAction(1, R.drawable.calculate_24px, "偏移量计算器") { showOffsetCalculator() },
            ToolbarAction(200, R.drawable.icon_arrow_left_alt_24px, "后退") { navigateBack() },
            ToolbarAction(201, R.drawable.icon_arrow_right_alt_24px, "前进") { navigateForward() },
            ToolbarAction(100, R.drawable.baseline_forward_24, "转到") { showModuleListDialog() },
            ToolbarAction(2, R.drawable.icon_save_24px, "保存") { saveSelectedToAddresses() },
            ToolbarAction(3, R.drawable.icon_edit_24px, "修改") { showBatchModifyDialog() },
            ToolbarAction(4, R.drawable.flip_to_front_24px, "交叉勾选") { crossSelectBetween() },
            ToolbarAction(5, R.drawable.search_check_24px, "选定为搜索结果") { setSelectedAsSearchResults() },
            ToolbarAction(6, R.drawable.deselect_24px, "清除选择") { adapter.clearSelection() },
            ToolbarAction(7, R.drawable.type_xor_24px, "计算偏移异或") { calculateOffsetXor() },
            ToolbarAction(8, R.drawable.select_all_24px, "全选") { adapter.selectAll() },
            ToolbarAction(9, R.drawable.flip_to_front_24px, "反选") { adapter.invertSelection() },
        )

        toolbar.setActions(actions)
        val options = actions.map { it.label }.toTypedArray()
        val icons = actions.map { it.icon }.toTypedArray()
        toolbar.setOverflowCallback {
            context.simpleSingleChoiceDialog(
                showTitle = false,
                options = options,
                icons = icons,
                showRadioButton = false,
                onSingleChoice = { which ->
                    if (which < actions.size) actions[which].onClick.invoke()
                }
            )
        }
    }

    private fun setupRecyclerView() {
        val viewPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, MEMORY_ROW_POOL_SIZE)
        }

        binding.memoryPreviewRecyclerView.apply {
            visibility = View.VISIBLE
            layoutManager = LinearLayoutManager(context)
            adapter = this@MemoryPreviewController.adapter
            setHasFixedSize(true)
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            setRecycledViewPool(viewPool)
            (layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = 20
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            
            // 添加滚动监听器，检测边界并触发扩展
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val firstVisible = lm.findFirstVisibleItemPosition()
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (firstVisible != RecyclerView.NO_POSITION && lastVisible != RecyclerView.NO_POSITION) {
                        this@MemoryPreviewController.adapter.checkBoundary(firstVisible, lastVisible)
                    }
                }
            })
        }

        binding.fastScroller.attachToRecyclerView(binding.memoryPreviewRecyclerView)
        preCreateViewHolders(viewPool)
    }

    private fun preCreateViewHolders(viewPool: RecyclerView.RecycledViewPool) {
        coroutineScope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    repeat(MEMORY_ROW_POOL_SIZE) {
                        val holder = adapter.createViewHolder(binding.memoryPreviewRecyclerView, 0)
                        viewPool.putRecycledView(holder)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "预创建 ViewHolder 失败: ${e.message}")
            }
        }
    }

    private fun setupStatusBar() {
        binding.filterStatusText.setOnClickListener {
            notification.showWarning("筛选器功能待实现")
        }
        binding.formatDisplayText.setOnClickListener { showFormatSettingsDialog() }
        binding.refreshButton.setOnClickListener { refreshCurrentView() }
    }

    private fun showFormatSettingsDialog() {
        val allFormats = MemoryDisplayFormat.getAllFormatsSortedByPriority().toTypedArray()
        val formatNames = allFormats.map { it.code + ": " + it.displayName }.toTypedArray()
        val formatColors = allFormats.map { it.textColor }.toIntArray()
        val checkedItems = BooleanArray(allFormats.size) { currentFormats.contains(allFormats[it]) }

        context.multiChoiceDialog(
            title = "选择显示数值格式",
            options = formatNames,
            checkedItems = checkedItems,
            itemColors = formatColors,
            onMultiChoice = { newCheckedItems ->
                currentFormats.clear()
                newCheckedItems.forEachIndexed { index, isChecked ->
                    if (isChecked) currentFormats.add(allFormats[index])
                }
                if (currentFormats.isEmpty()) {
                    currentFormats.add(MemoryDisplayFormat.DWORD)
                    notification.showWarning("至少需要选择一个显示格式")
                }
                mmkv.memoryDisplayFormats = currentFormats
                updateFormatDisplay()
                adapter.setFormats(currentFormats)
                refreshCurrentView()
            }
        )
    }

    private fun updateFormatDisplay() {
        val spanBuilder = SpannableStringBuilder()
        currentFormats.forEachIndexed { index, format ->
            if (index > 0) spanBuilder.append(",")
            val start = spanBuilder.length
            spanBuilder.append(format.code)
            spanBuilder.setSpan(
                ForegroundColorSpan(format.textColor), start, spanBuilder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.formatDisplayText.text = spanBuilder
    }

    private fun jumpToAddress(requestedAddress: Long) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val pageStartAddress = (requestedAddress / PAGE_SIZE) * PAGE_SIZE
        val isPageAligned = requestedAddress == pageStartAddress

        if (!isNavigating) {
            val currentHistoryAddress = if (navigationIndex >= 0 && navigationIndex < navigationHistory.size) {
                navigationHistory[navigationIndex]
            } else -1L
            if (pageStartAddress != currentHistoryAddress) {
                addToNavigationHistory(pageStartAddress)
            }
        }

        currentStartAddress = pageStartAddress
        targetAddress = requestedAddress

        updateMemoryRegionsCache()

        // 计算三页的行数
        val defaultRows = (DEFAULT_PAGE_COUNT * PAGE_SIZE) / adapter.getAlignment()
        
        // 根据是否页面对齐决定起始地址
        val startAddress = if (isPageAligned) {
            // 页面对齐：从当前页开始，显示当前页+后两页
            pageStartAddress
        } else {
            // 非页面对齐：从前一页开始，目标地址在中间
            if (pageStartAddress >= PAGE_SIZE) pageStartAddress - PAGE_SIZE else 0L
        }
        
        adapter.setAddressRange(startAddress, defaultRows)
        adapter.setHighlightAddress(requestedAddress)
        adapter.setMemoryRegions(memoryRegions)

        scrollToAddress(requestedAddress, isPageAligned)
        notification.showSuccess("已跳转到地址: ${String.format("%X", requestedAddress)}")
    }

    private fun scrollToAddress(address: Long, showAtTop: Boolean = false) {
        val alignment = adapter.getAlignment()
        val baseAddress = adapter.getBaseAddress()
        val targetRow = ((address - baseAddress) / alignment).toInt()

        val layoutManager = binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && targetRow >= 0) {
            val offset = if (showAtTop) {
                0  // 显示在顶部
            } else {
                binding.memoryPreviewRecyclerView.height / 2  // 显示在中间
            }
            layoutManager.scrollToPositionWithOffset(targetRow, offset)
        }
    }

    private fun updateMemoryRegionsCache() {
        val cacheInterval = mmkv.memoryRegionCacheInterval.toLong()
        val now = System.currentTimeMillis()
        val cacheValid = cacheInterval > 0 && memoryRegions.isNotEmpty() &&
                (now - memoryRegionsCacheTime) < cacheInterval

        if (!cacheValid) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val regions = WuwaDriver.queryMemRegionsWithRetry()
                        .divideToSimpleMemoryRangeParallel()
                        .sortedBy { it.start }
                    withContext(Dispatchers.Main) {
                        memoryRegions = regions
                        memoryRegionsCacheTime = System.currentTimeMillis()
                        adapter.setMemoryRegions(regions)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "更新内存区域缓存失败", e)
                }
            }
        }
    }

    private fun addToNavigationHistory(address: Long) {
        if (navigationIndex >= 0 && navigationIndex < navigationHistory.size - 1) {
            navigationHistory.subList(navigationIndex + 1, navigationHistory.size).clear()
        }
        if (navigationHistory.isEmpty() || navigationHistory.last() != address) {
            navigationHistory.add(address)
            navigationIndex = navigationHistory.size - 1
            while (navigationHistory.size > MAX_NAVIGATION_HISTORY) {
                navigationHistory.removeAt(0)
                navigationIndex--
            }
        }
    }

    private fun navigateBack() {
        if (navigationHistory.isEmpty() || navigationIndex <= 0) {
            notification.showWarning("已经是最早的记录")
            return
        }
        isNavigating = true
        navigationIndex--
        jumpToAddress(navigationHistory[navigationIndex])
        isNavigating = false
        notification.showSuccess("后退 (${navigationIndex + 1}/${navigationHistory.size})")
    }

    private fun navigateForward() {
        if (navigationHistory.isEmpty() || navigationIndex >= navigationHistory.size - 1) {
            notification.showWarning("已经是最新的记录")
            return
        }
        isNavigating = true
        navigationIndex++
        jumpToAddress(navigationHistory[navigationIndex])
        isNavigating = false
        notification.showSuccess("前进 (${navigationIndex + 1}/${navigationHistory.size})")
    }

    private fun refreshCurrentView() {
        adapter.refreshAll()
        notification.showSuccess("已刷新")
    }

    private fun showAddressActionDialog(memoryRow: MemoryPreviewItem.MemoryRow) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val defaultType = DisplayValueType.DWORD

        coroutineScope.launch {
            val currentValue = withContext(Dispatchers.IO) {
                try {
                    val bytes = WuwaDriver.readMemory(memoryRow.address, defaultType.memorySize.toInt())
                    if (bytes != null) ValueTypeUtils.bytesToDisplayValue(bytes, defaultType) else "?"
                } catch (e: Exception) { "?" }
            }

            AddressActionDialog(
                context = context,
                notification = notification,
                clipboardManager = clipboardManager,
                address = memoryRow.address,
                value = currentValue,
                valueType = defaultType,
                coroutineScope = coroutineScope,
                callbacks = object : AddressActionDialog.Callbacks {
                    override fun onShowOffsetCalculator(address: Long) {
                        coroutineScope.launch {
                            FloatingEventBus.emitUIAction(
                                UIActionEvent.ShowOffsetCalculatorDialog(initialBaseAddress = address)
                            )
                        }
                    }
                    override fun onJumpToAddress(address: Long) { jumpToAddress(address) }
                }
            ).show()
        }
    }

    private fun showModifyValueDialog(memoryRow: MemoryPreviewItem.MemoryRow) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val defaultType = DisplayValueType.DWORD

        coroutineScope.launch {
            val currentValue = withContext(Dispatchers.IO) {
                try {
                    val bytes = WuwaDriver.readMemory(memoryRow.address, defaultType.memorySize.toInt())
                    if (bytes != null) ValueTypeUtils.bytesToDisplayValue(bytes, defaultType) else ""
                } catch (e: Exception) { "" }
            }

            ModifyValueDialog(
                context = context,
                notification = notification,
                clipboardManager = clipboardManager,
                address = memoryRow.address,
                currentValue = currentValue,
                defaultType = defaultType,
                onConfirm = { addr, oldValue, newValue, valueType ->
                    try {
                        val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)
                        MemoryBackupManager.saveBackup(addr, oldValue, valueType)
                        val success = WuwaDriver.writeMemory(addr, dataBytes)
                        if (success) {
                            coroutineScope.launch {
                                FloatingEventBus.emitAddressValueChanged(
                                    AddressValueChangedEvent(addr, newValue, valueType.nativeId,
                                        AddressValueChangedEvent.Source.MEMORY_PREVIEW)
                                )
                            }
                            val pageAddress = (addr / PAGE_SIZE) * PAGE_SIZE
                            adapter.refreshPage(pageAddress)
                            notification.showSuccess(context.getString(R.string.modify_success_message, String.format("%X", addr)))
                        } else {
                            notification.showError(context.getString(R.string.modify_failed_message, String.format("%X", addr)))
                        }
                    } catch (e: IllegalArgumentException) {
                        notification.showError(context.getString(R.string.error_invalid_value_format, e.message ?: "Unknown error"))
                    } catch (e: Exception) {
                        notification.showError(context.getString(R.string.error_modify_failed, e.message ?: "Unknown error"))
                    }
                }
            ).show()
        }
    }

    private fun showOffsetCalculator() {
        val initialBaseAddress = if (adapter.getSelectedCount() > 0) {
            adapter.getSelectedAddresses().first()
        } else currentStartAddress

        coroutineScope.launch {
            FloatingEventBus.emitUIAction(UIActionEvent.ShowOffsetCalculatorDialog(initialBaseAddress))
        }
    }

    private fun showModuleListDialog() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val currentPid = WuwaDriver.currentBindPid
        if (currentPid <= 0 || !WuwaDriver.isProcessAlive(currentPid)) {
            notification.showError("目标进程已退出")
            return
        }

        coroutineScope.launch {
            try {
                val regions = withContext(Dispatchers.IO) { WuwaDriver.queryMemRegionsWithRetry(currentPid) }
                val modules = regions.divideToSimpleMemoryRange().sortedBy { it.start }

                if (modules.isEmpty()) {
                    notification.showWarning("未找到任何模块")
                    return@launch
                }

                ModuleListDialog(
                    context = context,
                    modules = modules,
                    notification = notification,
                    onModuleSelected = { selectedModule ->
                        jumpToAddress(selectedModule.start)
                        notification.showSuccess("已跳转到: ${selectedModule.name.substringAfterLast("/")}")
                    },
                    onGoto = { address -> jumpToAddress(address) }
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "加载模块列表失败", e)
                notification.showError("加载模块列表失败: ${e.message ?: "未知错误"}")
            }
        }
    }

    private fun saveSelectedToAddresses() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        coroutineScope.launch {
            FloatingEventBus.emitSaveMemoryPreview(SaveMemoryPreviewEvent(selectedRows, memoryRegions))
            notification.showSuccess("已保存 ${selectedRows.size} 个地址")
        }
    }

    private fun showBatchModifyDialog() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        val tempAddresses = selectedRows.map { row ->
            SavedAddress(row.address, "0x${row.address.toString(16).uppercase()}",
                DisplayValueType.DWORD.nativeId, "", false, row.memoryRange ?: MemoryRange.O)
        }

        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        BatchModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            savedAddresses = tempAddresses,
            onConfirm = { items, newValue, valueType ->
                batchModifyMemoryValues(items.map { it.address }, newValue, valueType)
            }
        ).show()
    }

    private fun batchModifyMemoryValues(addresses: List<Long>, newValue: String, valueType: DisplayValueType) {
        coroutineScope.launch {
            try {
                val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)
                val results = withContext(Dispatchers.IO) {
                    WuwaDriver.batchWriteMemory(addresses.toLongArray(), Array(addresses.size) { dataBytes })
                }
                val successCount = results.count { it }
                val failureCount = results.size - successCount
                if (failureCount == 0) {
                    notification.showSuccess("成功修改 $successCount 个地址")
                    adapter.refreshAll()
                } else {
                    notification.showWarning("成功: $successCount, 失败: $failureCount")
                }
            } catch (e: IllegalArgumentException) {
                notification.showError("值格式错误: ${e.message}")
            } catch (e: Exception) {
                notification.showError("批量修改失败: ${e.message}")
            }
        }
    }

    private fun crossSelectBetween() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.size < 2) {
            notification.showWarning("请至少选择2个项目以使用交叉勾选")
            return
        }

        val minAddress = selectedAddresses.minOrNull() ?: return
        val maxAddress = selectedAddresses.maxOrNull() ?: return
        val alignment = adapter.getAlignment()
        val addressesToSelect = mutableListOf<Long>()
        var addr = minAddress
        while (addr <= maxAddress) {
            addressesToSelect.add(addr)
            addr += alignment
        }
        adapter.selectAddresses(addressesToSelect)
        notification.showSuccess("已交叉选中 ${addressesToSelect.size} 个地址")
    }

    private fun setSelectedAsSearchResults() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        coroutineScope.launch {
            val types = Array(selectedRows.size) { DisplayValueType.DWORD }
            val success = withContext(Dispatchers.IO) {
                SearchEngine.addResultsFromAddresses(selectedRows.map { it.address }, types)
            }
            if (success) {
                val totalCount = SearchEngine.getTotalResultCount()
                notification.showSuccess("已将 ${selectedRows.size} 个地址设为搜索结果")
                val ranges = selectedRows.map { row ->
                    DisplayMemRegionEntry(row.address, row.address + DisplayValueType.DWORD.memorySize,
                        0x03, row.memoryRange?.displayName ?: "Unknown", row.memoryRange ?: MemoryRange.O)
                }
                FloatingEventBus.emitSearchResultsUpdated(SearchResultsUpdatedEvent(totalCount, ranges))
            } else {
                notification.showError("设置搜索结果失败")
            }
        }
    }

    private fun calculateOffsetXor() {
        val selectedAddresses = adapter.getSelectedAddresses()
        if (selectedAddresses.size < 2) {
            notification.showWarning("请至少选择 2 个地址")
            return
        }

        val selectedRows = adapter.getSelectedRows()
        val tempAddresses = selectedRows.map { row ->
            SavedAddress(row.address, "0x${row.address.toString(16).uppercase()}",
                DisplayValueType.DWORD.nativeId, "", false, row.memoryRange ?: MemoryRange.O)
        }
        coroutineScope.launch {
            FloatingEventBus.emitUIAction(UIActionEvent.ShowOffsetXorDialog(tempAddresses))
        }
    }

    private fun updateEmptyState() {
        val hasData = adapter.itemCount > 0
        binding.emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.memoryPreviewRecyclerView.visibility = if (hasData) View.VISIBLE else View.GONE
    }

    override fun cleanup() {
        super.cleanup()
        binding.fastScroller.detachFromRecyclerView()
        coroutineScope.cancel()
    }
}
