package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.floating.event.AddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.BatchAddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.SaveSearchResultsEvent
import moe.fuqiuluo.mamu.floating.event.SearchResultsUpdatedEvent
import moe.fuqiuluo.mamu.databinding.FloatingSearchLayoutBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzyCondition
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.data.settings.searchPageSize
import moe.fuqiuluo.mamu.floating.adapter.SearchResultAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.FilterDialog
import moe.fuqiuluo.mamu.floating.dialog.FilterDialogState
import moe.fuqiuluo.mamu.floating.dialog.ModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.RemoveOptionsDialog
import moe.fuqiuluo.mamu.floating.dialog.SearchDialog
import moe.fuqiuluo.mamu.floating.dialog.SearchDialogState
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryBackupRecord
import moe.fuqiuluo.mamu.floating.dialog.AddressActionDialog
import moe.fuqiuluo.mamu.floating.dialog.ExportAddressDialog
import moe.fuqiuluo.mamu.floating.dialog.FuzzySearchDialog
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SearchController(
    context: Context,
    binding: FloatingSearchLayoutBinding,
    notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager
) : FloatingController<FloatingSearchLayoutBinding>(context, binding, notification) {
    // 搜索结果列表适配器
    private lateinit var searchResultAdapter: SearchResultAdapter

    // 搜索对话框状态
    private val searchDialogState = SearchDialogState()

    // 过滤对话框状态
    private val filterDialogState = FilterDialogState()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 持久化的 SearchDialog 实例
    private var searchDialog: SearchDialog? = null

    // 持久化的 FuzzySearchDialog 实例
    private var fuzzySearchDialog: FuzzySearchDialog? = null

    override fun initialize() {
        setupToolbar()
        setupRecyclerView()
        setupRefreshButton()
        updateSearchProcessDisplay(null)
        updateSearchResultCount(0, null)
        updateFilterStatusUI()
        showEmptyState(true)
        subscribeToAddressEvents()
        subscribeToProcessStateEvents()
        subscribeToSearchResultsUpdatedEvents()
    }

    /**
     * 订阅地址值变更事件
     * 当保存地址界面修改值时，同步更新搜索结果界面的显示
     */
    private fun subscribeToAddressEvents() {
        // 订阅单个地址变更事件
        coroutineScope.launch {
            FloatingEventBus.addressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SEARCH }
                .collect { event ->
                    searchResultAdapter.updateItemValueByAddress(event.address, event.newValue)
                }
        }

        // 订阅批量地址变更事件
        coroutineScope.launch {
            FloatingEventBus.batchAddressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SEARCH }
                .collect { event ->
                    event.changes.forEach { change ->
                        searchResultAdapter.updateItemValueByAddress(change.address, change.newValue)
                    }
                }
        }
    }

    /**
     * 订阅进程状态变更事件
     */
    private fun subscribeToProcessStateEvents() {
        coroutineScope.launch {
            FloatingEventBus.processStateEvents.collect { event ->
                when (event.type) {
                    ProcessStateEvent.Type.BOUND -> {
                        // 绑定新进程时，先清理旧数据再更新显示
                        clearSearchResults()
                        updateSearchProcessDisplay(event.process)
                    }
                    ProcessStateEvent.Type.UNBOUND,
                    ProcessStateEvent.Type.DIED -> {
                        clearSearchResults()
                        updateSearchProcessDisplay(null)
                    }
                }
            }
        }
    }

    /**
     * 订阅搜索结果更新事件（从保存地址界面搜索后）
     */
    private fun subscribeToSearchResultsUpdatedEvents() {
        coroutineScope.launch {
            FloatingEventBus.searchResultsUpdatedEvents.collect { event ->
                val totalCount = event.totalCount.toInt()

                val mmkv = MMKV.defaultMMKV()
                if (totalCount > 0) {
                    val itemCountPerPage = mmkv.searchPageSize
                    val limit = itemCountPerPage.coerceAtMost(totalCount)
                    updateSearchResultCount(limit, totalCount)
                    searchResultAdapter.setRanges(event.ranges)
                    loadSearchResults(limit = limit)
                } else {
                    searchResultAdapter.clearResults()
                    updateSearchResultCount(0, null)
                    showEmptyState(true)
                }
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.searchToolbar

        val actions = listOf(
            ToolbarAction(
                id = 9,
                icon = R.drawable.select_all_24px,
                label = "全选"
            ) {
                searchResultAdapter.selectAll()
            },
            ToolbarAction(
                id = 10,
                icon = R.drawable.flip_to_front_24px,
                label = "反选"
            ) {
                searchResultAdapter.invertSelection()
            },
            ToolbarAction(
                id = 2,
                icon = R.drawable.icon_save_24px,
                label = "保存所选"
            ) {
                saveSelectedAddresses()
            },
            ToolbarAction(
                id = 3,
                icon = R.drawable.saved_search_24px,
                label = "模糊搜索"
            ) {
                showFuzzySearchDialog()
            },
            ToolbarAction(
                id = 4,
                icon = R.drawable.icon_delete_24px,
                label = "移除"
            ) {
                showRemoveDialog()
            },
            ToolbarAction(
                id = 5,
                icon = R.drawable.icon_edit_24px,
                label = "编辑所选"
            ) {
                showBatchModifyValueDialog()
            },
            ToolbarAction(
                id = 6,
                icon = R.drawable.icon_search_24px,
                label = "搜索数据"
            ) {
                showSearchDialog(clipboardManager)
            },
            ToolbarAction(
                id = 7,
                icon = R.drawable.icon_play_arrow_24px,
                label = "执行脚本"
            ) {
            },
            ToolbarAction(
                id = 8,
                icon = R.drawable.history_24px,
                label = "恢复已选项"
            ) {
                restoreSelectedItems()
            },
            ToolbarAction(
                id = 11,
                icon = R.drawable.deselect_24px,
                label = "取消选择"
            ) {
                searchResultAdapter.deselectAll()
            },
            ToolbarAction(
                id = 12,
                icon = R.drawable.search_check_24px,
                label = "选定为搜索结果"
            ) {
                setSelectedAsSearchResults()
            },
            ToolbarAction(
                id = 13,
                icon = R.drawable.calculate_24px,
                label = "偏移量计算器"
            ) {
                showOffsetCalculator()
            },
            ToolbarAction(
                id = 14,
                icon = R.drawable.icon_filter_list_24px,
                label = "过滤"
            ) {
                showFilterDialog()
            },
            ToolbarAction(
                id = 15,
                icon = R.drawable.icon_save_24px,
                label = "导出选择项"
            ) {
                exportSelectedAddresses()
            },
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
                    if (which >= actions.size) {
                        return@simpleSingleChoiceDialog
                    }
                    actions[which].onClick.invoke()
                }
            )
        }
    }

    private fun setupRecyclerView() {
        searchResultAdapter = SearchResultAdapter(
            onItemClick = { result, position ->
                showModifyValueDialog(result)
            },
            onItemLongClick = { result, position ->
                showAddressActionDialog(result)
                true
            },
            onSelectionChanged = { selectedCount ->
                // 不需要执行任何操作
            },
            onItemDelete = { item ->
                // 异步调用 native 删除
                coroutineScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        SearchEngine.removeResult(item.nativePosition.toInt())
                    }

                    if (success) {
                        // 重新加载搜索结果
                        val totalCount = SearchEngine.getTotalResultCount().toInt()
                        if (totalCount > 0) {
                            val limit = searchResultAdapter.itemCount.coerceAtMost(totalCount)
                            loadSearchResults(limit = limit)
                        } else {
                            searchResultAdapter.clearResults()
                            showEmptyState(true)
                        }
                        updateSearchResultCount(searchResultAdapter.itemCount, totalCount)
                    } else {
                        notification.showError("删除失败")
                    }
                }
            }
        )

        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchResultAdapter

            // 性能优化：增加ViewHolder缓存池大小
            setItemViewCacheSize(20) // 默认是2，提高到20可以减少rebind

            // 性能优化：设置固定大小，避免每次数据变化都重新测量
            setHasFixedSize(true)

            // 性能优化：禁用变化动画，避免全选/反选时的闪烁和卡顿
            // 参考: https://github.com/wasabeef/recyclerview-animators/issues/47
            if (itemAnimator != null && itemAnimator is SimpleItemAnimator) {
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            }
        }

        // 绑定快速滚动条
        binding.searchFastScroller.attachToRecyclerView(binding.resultsRecyclerView)
    }

    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            loadSearchResults(
                limit = MMKV.defaultMMKV().searchPageSize
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSearchProcessDisplay(process: DisplayProcessInfo?) {
        process?.let {
            val memoryB = (it.rss * 4096)
            binding.processInfoText.text =
                "[${it.pid}] ${it.name} [${formatBytes(memoryB, 0)}]"
            binding.processStatusIcon.setIconResource(R.drawable.icon_pause_24px)
        } ?: run {
            binding.processInfoText.text = "未选择进程"
            binding.processStatusIcon.setIconResource(R.drawable.icon_play_arrow_24px)
        }
    }

    @SuppressLint("SetTextI18n", "DefaultLocale")
    fun updateSearchResultCount(current: Int, total: Int?) {
        binding.searchCountText.text = if (total == null || total == 0) {
            "($current)"
        } else {
            val totalFormatted = String.format("%,d", total)
            "($current/$totalFormatted)"
        }

        // 设置点击监听器，弹出过滤配置dialog
        binding.searchCountText.setOnClickListener {
            showFilterDialog()
        }
    }

    private fun loadSearchResults(
        offset: Int = 0,
        limit: Int,
    ) {
        coroutineScope.launch {
            runCatching {
                val addresses = withContext(Dispatchers.IO) {
                    //println("77777777777777777777777")
                    SearchEngine.getResults(offset, limit).toList()
                }

                if (addresses.isEmpty()) {
                    if (offset == 0) {
                        searchResultAdapter.clearResults()
                        showEmptyState(true)
                        notification.showError("没有找到结果($limit)")
                    }
                    return@launch
                }

                if (offset == 0) {
                    searchResultAdapter.setResults(addresses)
                    showEmptyState(false)
                } else {
                    searchResultAdapter.addResults(addresses)
                }
            }.onFailure { e ->
                notification.showError("加载结果失败: ${e.message}")
            }
        }
    }

    fun onSearchCompleted(ranges: List<DisplayMemRegionEntry>, totalFound: Long) {
        val totalCount = totalFound.toInt()

        val mmkv = MMKV.defaultMMKV()
        if (totalCount > 0) {
            val itemCountPerPage = mmkv.searchPageSize
            val limit = itemCountPerPage.coerceAtMost(totalCount)
            updateSearchResultCount(itemCountPerPage, totalCount) // 搜索完成
            searchResultAdapter.setRanges(ranges)
            loadSearchResults(limit = limit)
        } else {
            searchResultAdapter.clearResults()
            updateSearchResultCount(0, null) // 清空结果
            showEmptyState(true)
        }
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyStateView.visibility = if (show) View.VISIBLE else View.GONE
        binding.resultsRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showSearchDialog(clipboardManager: ClipboardManager) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("请先选择进程")
            return
        }

        // 搜索结束移除单例
        val allSearchComplete = {
            searchDialog = null
        }
        // 单例模式：复用 SearchDialog 实例
        if (searchDialog == null) {
            searchDialog = SearchDialog(
                context = context,
                notification = notification,
                searchDialogState = searchDialogState,
                clipboardManager = clipboardManager,
                onSearchCompleted = { ranges, totalFound ->
                    onSearchCompleted(ranges, totalFound)
                    allSearchComplete()
                },
                onRefineCompleted = { totalFound ->
                    onRefineCompleted(totalFound)
                    allSearchComplete()
                }
            )
        }
        searchDialog?.show()
    }

    private fun showFuzzySearchDialog() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("请先选择进程")
            return
        }

        // 搜索结束移除单例
        val allSearchComplete = {
            fuzzySearchDialog = null
        }

        // 单例模式：复用 FuzzySearchDialog 实例
        if (!SearchEngine.isSearching() && SearchEngine.getTotalResultCount() <= 0) {
            fuzzySearchDialog = null
        }
        if (fuzzySearchDialog == null) {
            fuzzySearchDialog = FuzzySearchDialog(
                context = context,
                notification = notification,
                onSearchCompleted = { ranges, totalFound ->
                    onSearchCompleted(ranges, totalFound)
                    allSearchComplete()
                },
                onRefineCompleted = { totalFound ->
                    onRefineCompleted(totalFound)
                    allSearchComplete()
                }
            )
        }
        fuzzySearchDialog?.show()
    }

    /**
     * 隐藏搜索进度对话框（如果正在搜索）
     * 用于退出全屏时隐藏进度 UI，但后台搜索继续
     */
    fun hideSearchProgressIfNeeded() {
        searchDialog?.hideProgressDialog()
        fuzzySearchDialog?.hideProgressDialog()
    }

    /**
     * 如果正在搜索，重新显示搜索进度对话框
     * 用于重新进入全屏时恢复进度 UI
     */
    fun showSearchProgressIfNeeded() {
        searchDialog?.showProgressDialogIfSearching()
        fuzzySearchDialog?.showProgressDialogIfSearching()
    }

    /**
     * 如果模糊搜索已完成且有结果，重新显示模糊搜索对话框
     * 用于重新进入全屏时恢复对话框，让用户继续细化
     */
    fun showFuzzySearchDialogIfCompleted() {
        fuzzySearchDialog?.showDialogIfSearchCompleted()
    }

    private fun onRefineCompleted(totalFound: Long) {
        val totalCount = totalFound.toInt()

        val mmkv = MMKV.defaultMMKV()
        if (totalCount > 0) {
            val itemCountPerPage = mmkv.searchPageSize
            val limit = itemCountPerPage.coerceAtMost(totalCount)
            updateSearchResultCount(itemCountPerPage, totalCount)
            loadSearchResults(limit = limit)
        } else {
            searchResultAdapter.clearResults()
            updateSearchResultCount(0, null)
            showEmptyState(true)
        }
    }

    private fun showFilterDialog() {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        filterDialogState.maxDisplayCount = MMKV.defaultMMKV().searchPageSize
        val dialog = FilterDialog(
            context = context,
            notification = notification,
            filterDialogState = filterDialogState,
            clipboardManager = clipboardManager,
            onApply = { state ->
                applyFilter(state)
            }
        )

        dialog.show()
    }

    private fun applyFilter(state: FilterDialogState) {
        // 应用单页最大显示数量
        coroutineScope.launch {
            // 设置native层过滤
            withContext(Dispatchers.IO) {
                if (state.isFilterEnabled()) {
                    val addressStart = state.addressRangeStart.toLongOrNull(16) ?: 0L
                    val addressEnd = state.addressRangeEnd.toLongOrNull(16) ?: Long.MAX_VALUE
                    val typeIds = state.selectedDataTypes.map { it.nativeId }.toIntArray()

                    SearchEngine.setFilter(
                        enableAddressFilter = state.enableAddressFilter,
                        addressStart = addressStart,
                        addressEnd = addressEnd,
                        enableTypeFilter = state.enableDataTypeFilter,
                        typeIds = typeIds,
                    )
                } else {
                    SearchEngine.clearFilter()
                }
            }

            // 更新UI显示过滤状态
            updateFilterStatusUI()

            // 如果有搜索结果，重新加载应用过滤
            val totalCount = SearchEngine.getTotalResultCount().toInt()
            if (totalCount > 0) {
                val mmkv = MMKV.defaultMMKV()
                mmkv.searchPageSize = state.maxDisplayCount
                val limit = state.maxDisplayCount.coerceAtMost(totalCount)
                loadSearchResults(limit = limit)
                updateSearchResultCount(limit, totalCount)
            }

            notification.showSuccess("过滤配置已应用" + if (state.isFilterEnabled()) ": ${state.getFilterDescription()}" else "")
        }
    }

    private fun updateFilterStatusUI() {
        // 显示或隐藏过滤状态图标
        if (filterDialogState.isFilterEnabled()) {
            binding.filterStatusIcon.visibility = View.VISIBLE
        } else {
            binding.filterStatusIcon.visibility = View.GONE
        }
    }

    private fun showRemoveDialog() {
        val selectedCount = searchResultAdapter.getSelectedItems().size
        val totalCount = searchResultAdapter.itemCount

        if (totalCount == 0) {
            notification.showWarning("没有可移除的结果")
            return
        }

        val dialog = RemoveOptionsDialog(
            context = context,
            selectedCount = selectedCount
        )

        dialog.onRemoveAll = {
            clearSearchResults()
        }

        dialog.onRestoreAndRemove = {
            restoreAndRemoveSelected()
        }

        dialog.onRemoveSelected = {
            removeSelected()
        }

        dialog.show()
    }

    private fun restoreAndRemoveSelected() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        notification.showWarning("恢复并移除功能开发中")
    }

    private fun restoreSelectedItems() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未选中任何进程")
            return
        }

        coroutineScope.launch {
            var successCount = 0
            var failureCount = 0
            var noBackupCount = 0
            val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

            withContext(Dispatchers.IO) {
                selectedItems.forEach { item ->
                    val address = when (item) {
                        is ExactSearchResultItem -> item.address
                        is FuzzySearchResultItem -> item.address
                        else -> return@forEach
                    }

                    // 获取备份记录
                    val backup = MemoryBackupManager.getBackup(address)
                    if (backup == null) {
                        noBackupCount++
                        return@forEach
                    }

                    try {
                        // 将备份值转换为字节
                        val dataBytes = ValueTypeUtils.parseExprToBytes(
                            backup.originalValue,
                            backup.originalType
                        )

                        // 写回内存
                        val success = WuwaDriver.writeMemory(address, dataBytes)
                        if (success) {
                            // 更新UI（需要在主线程）
                            withContext(Dispatchers.Main) {
                                searchResultAdapter.updateItemValueByAddress(
                                    address,
                                    backup.originalValue
                                )
                            }
                            successfulChanges.add(
                                BatchAddressValueChangedEvent.AddressChange(
                                    address = address,
                                    newValue = backup.originalValue,
                                    valueType = backup.originalType.nativeId
                                )
                            )

                            // 恢复成功后删除备份记录
                            MemoryBackupManager.removeBackup(address)
                            successCount++
                        } else {
                            failureCount++
                        }
                    } catch (e: Exception) {
                        failureCount++
                    }
                }
            }

            // 发送批量事件通知其他界面同步更新
            if (successfulChanges.isNotEmpty()) {
                FloatingEventBus.emitBatchAddressValueChanged(
                    BatchAddressValueChangedEvent(
                        changes = successfulChanges,
                        source = AddressValueChangedEvent.Source.SEARCH
                    )
                )
            }

            // 显示结果统计
            when {
                selectedItems.size == noBackupCount -> {
                    notification.showWarning("所选项目均无备份记录")
                }
                failureCount == 0 && noBackupCount == 0 -> {
                    notification.showSuccess("成功恢复 $successCount 个地址")
                }
                else -> {
                    val message = buildString {
                        if (successCount > 0) append("成功: $successCount")
                        if (failureCount > 0) {
                            if (isNotEmpty()) append(", ")
                            append("失败: $failureCount")
                        }
                        if (noBackupCount > 0) {
                            if (isNotEmpty()) append(", ")
                            append("无备份: $noBackupCount")
                        }
                    }
                    notification.showWarning(message)
                }
            }
        }
    }

    private fun removeSelected() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        coroutineScope.launch {
            val nativePositions = searchResultAdapter.getNativePositions()
            val indices = nativePositions.map { it.toInt() }.toIntArray()

            val success = withContext(Dispatchers.IO) {
                SearchEngine.removeResults(indices)
            }

            if (success) {
                notification.showSuccess("已移除 ${selectedItems.size} 个结果")

                // 重新加载搜索结果
                val totalCount = SearchEngine.getTotalResultCount().toInt()
                if (totalCount > 0) {
                    val limit = searchResultAdapter.itemCount.coerceAtMost(totalCount)
                    loadSearchResults(limit = limit)
                } else {
                    searchResultAdapter.clearResults()
                    showEmptyState(true)
                }
                updateSearchResultCount(searchResultAdapter.itemCount, totalCount)
            } else {
                notification.showError("批量删除失败")
            }
        }
    }

    private fun setSelectedAsSearchResults() {
        val totalCount = SearchEngine.getTotalResultCount().toInt()
        if (totalCount == 0) {
            notification.showWarning("没有可操作的结果")
            return
        }

        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        coroutineScope.launch {
            // 获取选中项的 native positions
            val selectedPositions = searchResultAdapter.getNativePositions()

            // 直接调用 keepOnlyResults，保留选中的项，删除其他所有项
            val indices = selectedPositions.map { it.toInt() }.toIntArray()
            val success = withContext(Dispatchers.IO) {
                SearchEngine.keepOnlyResults(indices)
            }

            if (success) {
                notification.showSuccess("已将 ${selectedItems.size} 个选中项设为搜索结果")

                // 重新加载搜索结果
                val newTotalCount = SearchEngine.getTotalResultCount().toInt()
                if (newTotalCount > 0) {
                    val limit = newTotalCount.coerceAtMost(MMKV.defaultMMKV().searchPageSize)
                    loadSearchResults(limit = limit)
                    updateSearchResultCount(limit, newTotalCount)
                } else {
                    searchResultAdapter.clearResults()
                    showEmptyState(true)
                    updateSearchResultCount(0, null)
                }

                // 清空选择状态（因为索引位置已经变化）
                searchResultAdapter.deselectAll()
            } else {
                notification.showError("操作失败")
            }
        }
    }

    private fun clearSearchResults() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                SearchEngine.clearSearchResults()
            }
            searchResultAdapter.clearResults()
            showEmptyState(true)
            updateSearchResultCount(0, null) // 清空结果
        }
    }

    private fun getRanges(): List<DisplayMemRegionEntry>? {
        return searchResultAdapter.getRanges()
    }

    private fun saveSelectedAddresses() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        // 发送保存搜索结果事件（包含 ranges 信息）
        coroutineScope.launch {
            FloatingEventBus.emitSaveSearchResults(
                SaveSearchResultsEvent(
                    selectedItems = selectedItems,
                    ranges = getRanges()
                )
            )
        }
    }

    /**
     * 导出选中的地址到文件
     */
    private fun exportSelectedAddresses() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.size < 2) {
            notification.showWarning("请至少选择 2 个地址")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        // 获取当前进程的包名作为默认文件名
        val processInfo = WuwaDriver.getProcessInfo(WuwaDriver.currentBindPid)
        val defaultFileName = processInfo?.name ?: "export_${System.currentTimeMillis()}"

        val dialog = ExportAddressDialog(
            context = context,
            notification = notification,
            coroutineScope = coroutineScope,
            selectedItems = selectedItems,
            ranges = getRanges(),
            defaultFileName = defaultFileName
        )

        dialog.show()
    }

    /**
     * 显示地址操作对话框
     */
    private fun showAddressActionDialog(result: SearchResultItem) {
        val address = when (result) {
            is ExactSearchResultItem -> result.address
            is FuzzySearchResultItem -> result.address
            else -> {
                notification.showError("无效的搜索结果类型")
                return
            }
        }

        val value = when (result) {
            is ExactSearchResultItem -> result.value
            is FuzzySearchResultItem -> result.value
            else -> ""
        }

        val valueType = result.displayValueType ?: DisplayValueType.DWORD

        val dialog = AddressActionDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            address = address,
            value = value,
            valueType = valueType,
            coroutineScope = coroutineScope,
            callbacks = object : AddressActionDialog.Callbacks {
                override fun onShowOffsetCalculator(address: Long) {
                    // 调用偏移量计算器，传入当前地址作为初始基址
                    coroutineScope.launch {
                        FloatingEventBus.emitUIAction(
                            UIActionEvent.ShowOffsetCalculatorDialog(
                                initialBaseAddress = address
                            )
                        )
                    }
                }

                override fun onJumpToAddress(address: Long) {
                    // 发送跳转到内存预览的事件
                    coroutineScope.launch {
                        FloatingEventBus.emitUIAction(
                            UIActionEvent.JumpToMemoryPreview(address)
                        )
                    }
                }
            }
        )

        dialog.show()
    }

    private fun showModifyValueDialog(result: SearchResultItem) {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = ModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            searchResultItem = result,
            onConfirm = { address, oldValue, newValue, valueType ->
                if (!WuwaDriver.isProcessBound) {
                    notification.showError("未选中任何进程")
                    return@ModifyValueDialog
                }

                try {
                    val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                    // 有没有备份都覆盖，主要是就是拿上一次的值而已
                    MemoryBackupManager.saveBackup(address, oldValue, valueType)

                    val success = WuwaDriver.writeMemory(address, dataBytes)
                    if (success) {
                        // ViewHolder will automatically display backup value if exists
                        searchResultAdapter.updateItemValueByAddress(address, newValue)

                        // 发送事件通知其他界面同步更新
                        coroutineScope.launch {
                            FloatingEventBus.emitAddressValueChanged(
                                AddressValueChangedEvent(
                                    address = address,
                                    newValue = newValue,
                                    valueType = valueType.nativeId,
                                    source = AddressValueChangedEvent.Source.SEARCH
                                )
                            )
                        }

                        notification.showSuccess(
                            context.getString(
                                R.string.modify_success_message,
                                String.format("%X", address)
                            )
                        )
                    } else {
                        notification.showError(
                            context.getString(
                                R.string.modify_failed_message,
                                String.format("%X", address)
                            )
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    notification.showError(
                        context.getString(
                            R.string.error_invalid_value_format,
                            e.message ?: "Unknown error"
                        )
                    )
                } catch (e: Exception) {
                    notification.showError(
                        context.getString(
                            R.string.error_modify_failed,
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        )

        dialog.show()
    }

    private fun showBatchModifyValueDialog() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未选中任何进程")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        fun batchModifyValues(
            items: List<SearchResultItem>,
            newValue: String,
            valueType: DisplayValueType
        ) {
            coroutineScope.launch {
                var successCount = 0
                var failureCount = 0
                val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

                try {
                    val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                    // 异步批量写入
                    withContext(Dispatchers.IO) {
                        items.forEach { item ->
                            val address = when (item) {
                                is ExactSearchResultItem -> item.address
                                is FuzzySearchResultItem -> item.address
                                else -> return@forEach
                            }

                            val oldValue = when (item) {
                                is ExactSearchResultItem -> item.value
                                is FuzzySearchResultItem -> item.value
                                else -> ""
                            }

                            try {
                                // 保存备份
                                MemoryBackupManager.saveBackup(address, oldValue, valueType)

                                // 写入内存
                                val success = WuwaDriver.writeMemory(address, dataBytes)
                                if (success) {
                                    // 更新UI (需要在主线程)
                                    withContext(Dispatchers.Main) {
                                        searchResultAdapter.updateItemValueByAddress(
                                            address,
                                            newValue
                                        )
                                    }
                                    successfulChanges.add(
                                        BatchAddressValueChangedEvent.AddressChange(
                                            address = address,
                                            newValue = newValue,
                                            valueType = valueType.nativeId
                                        )
                                    )
                                    successCount++
                                } else {
                                    failureCount++
                                }
                            } catch (e: Exception) {
                                failureCount++
                            }
                        }
                    }

                    // 发送批量事件通知其他界面同步更新
                    if (successfulChanges.isNotEmpty()) {
                        FloatingEventBus.emitBatchAddressValueChanged(
                            BatchAddressValueChangedEvent(
                                changes = successfulChanges,
                                source = AddressValueChangedEvent.Source.SEARCH
                            )
                        )
                    }

                    // 显示结果统计
                    if (failureCount == 0) {
                        notification.showSuccess("成功修改 $successCount 个地址")
                    } else {
                        notification.showWarning("成功: $successCount, 失败: $failureCount")
                    }
                } catch (e: IllegalArgumentException) {
                    notification.showError(
                        context.getString(
                            R.string.error_invalid_value_format,
                            e.message ?: "Unknown error"
                        )
                    )
                } catch (e: Exception) {
                    notification.showError(
                        "批量修改失败: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }

        val dialog = BatchModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            searchResultItems = selectedItems,
            onConfirm = { items, newValue, valueType ->
                batchModifyValues(items, newValue, valueType)
            }
        )

        dialog.show()
    }

    /**
     * 显示偏移量计算器
     */
    private fun showOffsetCalculator() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        var initialBaseAddress: Long? = null
        if (selectedItems.isNotEmpty()) {
            // 获取第一个选中项的地址作为初始基址
            initialBaseAddress = when (val item = selectedItems.firstOrNull()) {
                is ExactSearchResultItem -> item.address
                is FuzzySearchResultItem -> item.address
                else -> null
            }
        }

        coroutineScope.launch {
            FloatingEventBus.emitUIAction(
                UIActionEvent.ShowOffsetCalculatorDialog(
                    initialBaseAddress = initialBaseAddress
                )
            )
        }
    }

    fun adjustLayoutForOrientation(orientation: Int) {
        if (searchDialog == null || searchDialog?.isSearching == false) {
            searchDialog = null
        }
        if (fuzzySearchDialog == null || fuzzySearchDialog?.isSearching == false) {
            fuzzySearchDialog = null
        }
    }

    override fun cleanup() {
        super.cleanup()
        binding.searchFastScroller.detachFromRecyclerView()
        coroutineScope.cancel()
    }
}