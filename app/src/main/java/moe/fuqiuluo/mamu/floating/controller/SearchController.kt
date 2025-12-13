package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.FloatingSearchLayoutBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
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
import moe.fuqiuluo.mamu.floating.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SearchController(
    context: Context,
    binding: FloatingSearchLayoutBinding,
    notification: NotificationOverlay,
    private val onShowSearchDialog: () -> Unit,
    private val onSaveSelectedAddresses: ((List<SearchResultItem>) -> Unit)? = null,
    private val onExitFullscreen: (() -> Unit)? = null
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

    override fun initialize() {
        setupToolbar()
        setupRecyclerView()
        setupRefreshButton()
        updateSearchProcessDisplay(null)
        updateSearchResultCount(0, null)
        updateFilterStatusUI()
        showEmptyState(true)
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
                onShowSearchDialog()
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
            },
            ToolbarAction(
                id = 13,
                icon = R.drawable.calculate_24px,
                label = "偏移量计算器"
            ) {
            },
            ToolbarAction(
                id = 14,
                icon = R.drawable.icon_filter_list_24px,
                label = "过滤"
            ) {
                showFilterDialog()
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
                notification.showSuccess(
                    "长按了第 $position 个"
                )
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
                    SearchEngine.getResults(offset, limit).toList()
                }

                if (addresses.isEmpty()) {
                    if (offset == 0) {
                        searchResultAdapter.clearResults()
                        showEmptyState(true)
                        notification.showError("没有找到结果")
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

    fun onSearchCompleted(ranges: List<DisplayMemRegionEntry>) {
        val totalCount = SearchEngine.getTotalResultCount().toInt()

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

    fun showSearchDialog(clipboardManager: ClipboardManager) {
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
                onSearchCompleted = {
                    onSearchCompleted(it)
                    allSearchComplete()
                },
                onRefineCompleted = {
                    onRefineCompleted()
                    allSearchComplete()
                }
            ).apply {
                // 设置退出全屏回调
                onExitFullscreen = this@SearchController.onExitFullscreen
            }
        }
        searchDialog?.show()
    }

    /**
     * 隐藏搜索进度对话框（如果正在搜索）
     * 用于退出全屏时隐藏进度 UI，但后台搜索继续
     */
    fun hideSearchProgressIfNeeded() {
        searchDialog?.hideProgressDialog()
    }

    /**
     * 如果正在搜索，重新显示搜索进度对话框
     * 用于重新进入全屏时恢复进度 UI
     */
    fun showSearchProgressIfNeeded() {
        searchDialog?.showProgressDialogIfSearching()
    }

    private fun onRefineCompleted() {
        val totalCount = SearchEngine.getTotalResultCount().toInt()

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

    fun clearSearchResults() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                SearchEngine.clearSearchResults()
            }
            searchResultAdapter.clearResults()
            showEmptyState(true)
            updateSearchResultCount(0, null) // 清空结果
        }
    }

    fun getRanges(): List<DisplayMemRegionEntry>? {
        return searchResultAdapter.getRanges()
    }

    private fun saveSelectedAddresses() {
        val selectedItems = searchResultAdapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        onSaveSelectedAddresses?.invoke(selectedItems)
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
                                    successCount++
                                } else {
                                    failureCount++
                                }
                            } catch (e: Exception) {
                                failureCount++
                            }
                        }
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

    fun adjustLayoutForOrientation(orientation: Int) {
        if (searchDialog == null || searchDialog?.isSearching == false) {
            searchDialog = null
        }
    }

    override fun cleanup() {
        super.cleanup()
        coroutineScope.cancel()
    }
}