package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.saveListUpdateInterval
import moe.fuqiuluo.mamu.databinding.FloatingSavedAddressesLayoutBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.SavedAddressAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.local.SavedAddressRepository
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.ModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetXorDialog
import moe.fuqiuluo.mamu.floating.dialog.RemoveOptionsDialog
import moe.fuqiuluo.mamu.floating.event.AddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.BatchAddressValueChangedEvent
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.SearchResultsUpdatedEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SavedAddressController(
    context: Context,
    binding: FloatingSavedAddressesLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingSavedAddressesLayoutBinding>(context, binding, notification) {
    // 保存的地址列表（内存中）
    private val savedAddresses = mutableListOf<SavedAddress>()

    // 地址数量 badge views (支持多个，用于顶部工具栏和侧边栏)
    private val addressCountBadgeViews = mutableListOf<TextView>()

    // 列表适配器
    private val adapter: SavedAddressAdapter = SavedAddressAdapter(
        onItemClick = { address, position ->
            showModifyValueDialog(address)
        },
        onFreezeToggle = { address, isFrozen ->
            // 切换冻结状态
            val index = savedAddresses.indexOfFirst { it.address == address.address }
            if (index >= 0) {
                savedAddresses[index] = savedAddresses[index].copy(isFrozen = isFrozen)
                notification.showSuccess(if (isFrozen) "已冻结" else "已解除冻结")
            }
        },
        onItemDelete = { address ->
            deleteAddress(address.address)
        }
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 自动更新协程任务
    private var autoUpdateJob: Job? = null

    override fun initialize() {
        setupToolbar()
        setupRecyclerView()
        setupRefreshButton()
        updateProcessDisplay(null)
        updateEmptyState()
        updateAddressCountBadge()

        subscribeToAddressEvents()
        subscribeToProcessStateEvents()
        subscribeToSaveSearchResultsEvents()
        subscribeToSaveMemoryPreviewEvents()
    }

    /**
     * 订阅地址值变更事件
     * 当搜索结果界面修改值时，同步更新保存地址界面的显示
     */
    private fun subscribeToAddressEvents() {
        // 订阅单个地址变更事件
        coroutineScope.launch {
            FloatingEventBus.addressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SAVED_ADDRESS }
                .collect { event ->
                    updateAddressValueByAddress(event.address, event.newValue)
                }
        }

        // 订阅批量地址变更事件
        coroutineScope.launch {
            FloatingEventBus.batchAddressValueChangedEvents
                .filter { it.source != AddressValueChangedEvent.Source.SAVED_ADDRESS }
                .collect { event ->
                    event.changes.forEach { change ->
                        updateAddressValueByAddress(change.address, change.newValue)
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
                        // 绑定新进程时，先停止旧的更新，清空地址，再启动新的更新
                        stopAutoUpdate()
                        clearAll()
                        updateProcessDisplay(event.process)
                        startAutoUpdate()
                    }

                    ProcessStateEvent.Type.UNBOUND,
                    ProcessStateEvent.Type.DIED -> {
                        // 进程解绑或死亡时，立即停止更新并清空
                        stopAutoUpdate()
                        clearAll()
                        updateProcessDisplay(null)
                    }
                }
            }
        }
    }

    /**
     * 订阅保存搜索结果事件
     */
    private fun subscribeToSaveSearchResultsEvents() {
        coroutineScope.launch {
            FloatingEventBus.saveSearchResultsEvents.collect { event ->
                // 将搜索结果转换为 SavedAddress 并保存
                val savedAddresses = event.selectedItems.mapNotNull { item ->
                    when (item) {
                        is ExactSearchResultItem -> {
                            // 查找对应的内存范围
                            val range = event.ranges?.find { range ->
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
                            val range = event.ranges?.find { range ->
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
                saveAddresses(savedAddresses)
            }
        }
    }

    /**
     * 订阅保存内存预览事件
     */
    private fun subscribeToSaveMemoryPreviewEvents() {
        coroutineScope.launch {
            FloatingEventBus.saveMemoryPreviewEvents.collect { event ->
                // 对ranges进行排序（如果存在），以便使用二分查找
                val sortedRanges = event.ranges?.sortedBy { it.start }

                // 转换MemoryRow为SavedAddress
                val addresses = event.selectedItems.map { row ->
                    // 使用二分查找从ranges中找到匹配的range
                    val range = findRangeForAddress(row.address, sortedRanges)
                        ?: row.memoryRange
                        ?: MemoryRange.O

                    SavedAddress(
                        address = row.address,
                        name = "Var #${String.format("%X", row.address)}",
                        valueType = DisplayValueType.DWORD.nativeId,
                        value = "",
                        isFrozen = false,
                        range = range
                    )
                }

                saveAddresses(addresses)
            }
        }
    }

    /**
     * 使用二分查找在排序的ranges中找到包含指定address的range
     * @param address 要查找的内存地址
     * @param sortedRanges 已按start排序的内存范围列表
     * @return 找到的MemoryRange，如果未找到返回null
     */
    private fun findRangeForAddress(
        address: Long,
        sortedRanges: List<DisplayMemRegionEntry>?
    ): MemoryRange? {
        if (sortedRanges.isNullOrEmpty()) return null

        // 使用二分查找
        val index = sortedRanges.binarySearch { range ->
            when {
                address < range.start -> 1  // address在range之前，继续向左查找
                address >= range.end -> -1  // address在range之后，继续向右查找
                else -> 0  // address在range内，找到了
            }
        }

        return if (index >= 0) sortedRanges[index].range else null
    }

    /**
     * 根据地址更新值（用于事件同步）
     */
    private fun updateAddressValueByAddress(address: Long, newValue: String) {
        val index = savedAddresses.indexOfFirst { it.address == address }
        if (index >= 0) {
            savedAddresses[index] = savedAddresses[index].copy(value = newValue)
            adapter.updateAddress(savedAddresses[index])
        }
    }

    fun setAddressCountBadgeView(vararg badges: TextView) {
        addressCountBadgeViews.clear()
        addressCountBadgeViews.addAll(badges)
        updateAddressCountBadge()
    }

    private fun updateAddressCountBadge() {
        val count = savedAddresses.size
        addressCountBadgeViews.forEach { badge ->
            if (count > 0) {
                badge.text = if (count > 99) "99+" else count.toString()
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.savedToolbar

        val actions = listOf(
            ToolbarAction(
                id = 1,
                icon = R.drawable.select_all_24px,
                label = "全选"
            ) {
                adapter.selectAll()
            },
            ToolbarAction(
                id = 2,
                icon = R.drawable.flip_to_front_24px,
                label = "反选"
            ) {
                adapter.invertSelection()
            },
            ToolbarAction(
                id = 3,
                icon = R.drawable.icon_edit_24px,
                label = "编辑所选值"
            ) {
                showBatchModifyDialog()
            },
            ToolbarAction(
                id = 4,
                icon = R.drawable.icon_delete_24px,
                label = "删除"
            ) {
                showRemoveDialog()
            },
            ToolbarAction(
                id = 5,
                icon = R.drawable.icon_save_24px,
                label = "保存地址到文件"
            ) {
                exportAddresses()
            },
            ToolbarAction(
                id = 6,
                icon = R.drawable.undo_24px,
                label = "恢复"
            ) {
                restoreSelectedValues()
            },
            ToolbarAction(
                id = 7,
                icon = R.drawable.icon_list_24px,
                label = "从文件载入地址"
            ) {
                showLoadAddressesDialog()
            },
            ToolbarAction(
                id = 8,
                icon = R.drawable.search_check_24px,
                label = "选定为搜索结果"
            ) {
                setSelectedAsSearchResults()
            },
            ToolbarAction(
                id = 9,
                icon = R.drawable.type_xor_24px,
                label = "计算偏移异或"
            ) {
                calculateOffsetXor()
            },
            ToolbarAction(
                id = 10,
                icon = R.drawable.type_auto_24px,
                label = "更改所选类型"
            ) {
                showChangeTypeDialog()
            },
            ToolbarAction(
                id = 11,
                icon = R.drawable.deselect_24px,
                label = "清除选择"
            ) {
                adapter.deselectAll()
            },
            ToolbarAction(
                id = 12,
                icon = R.drawable.calculate_24px,
                label = "偏移量计算器"
            ) {
                showOffsetCalculator()
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
                    if (which < actions.size) {
                        actions[which].onClick.invoke()
                    }
                }
            )
        }
    }

    private fun setupRecyclerView() {
        binding.savedAddressesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SavedAddressController.adapter

            setHasFixedSize(true)
            if (itemAnimator != null && itemAnimator is SimpleItemAnimator) {
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            }
        }

        // 绑定快速滚动条
        binding.savedFastScroller.attachToRecyclerView(binding.savedAddressesRecyclerView)
    }

    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            refreshAddresses()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateProcessDisplay(process: DisplayProcessInfo?) {
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

    /**
     * 保存单个地址
     */
    private fun saveAddress(address: SavedAddress) {
        val existingIndex = savedAddresses.indexOfFirst { it.address == address.address }
        if (existingIndex >= 0) {
            savedAddresses[existingIndex] = address
            adapter.updateAddress(address)
        } else {
            savedAddresses.add(address)
            adapter.addAddress(address)
        }
        updateEmptyState()
        updateAddressCountBadge()
    }

    /**
     * 批量保存地址
     */
    private fun saveAddresses(addresses: List<SavedAddress>) {
        if (addresses.isEmpty()) {
            return
        }

        addresses.forEach { newAddr ->
            val existingIndex = savedAddresses.indexOfFirst { it.address == newAddr.address }
            if (existingIndex >= 0) {
                savedAddresses[existingIndex] = newAddr
            } else {
                savedAddresses.add(newAddr)
            }
        }
        adapter.setAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()

        notification.showSuccess("已保存 ${addresses.size} 个地址")
    }

    /**
     * 删除地址
     */
    private fun deleteAddress(address: Long) {
        savedAddresses.removeIf { it.address == address }
        adapter.setAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()
        notification.showSuccess("已删除")
    }

    /**
     * 清空所有地址（进程切换或死亡时调用）
     */
    fun clearAll() {
        savedAddresses.clear()
        adapter.setAddresses(emptyList())
        updateEmptyState()
        updateAddressCountBadge()
    }

    /**
     * 刷新所有地址的值（使用批量读取提高效率）
     */
    private fun refreshAddresses() {
        if (savedAddresses.isEmpty()) {
            notification.showWarning("没有保存的地址")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        coroutineScope.launch {
            var successCount = 0
            var failCount = 0

            val addrs = mutableListOf<Long>()
            val sizes = mutableListOf<Int>()

            for (address in savedAddresses) {
                addrs.add(address.address)
                val valueType = address.displayValueType ?: DisplayValueType.DWORD
                sizes.add(valueType.memorySize.toInt())
            }

            val results = withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs.toLongArray(), sizes.toIntArray())
            }

            // 更新 UI
            results.forEachIndexed { index, bytes ->
                val address = savedAddresses[index]
                val valueType = address.displayValueType ?: DisplayValueType.DWORD

                if (bytes != null) {
                    try {
                        val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, valueType)
                        savedAddresses[index] = address.copy(value = newValue)
                        adapter.updateAddress(savedAddresses[index])
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                } else {
                    failCount++
                }
            }

            if (failCount == 0) {
                notification.showSuccess("已刷新 $successCount 个地址")
            } else {
                notification.showWarning("成功: $successCount, 失败: $failCount")
            }
        }
    }

    /**
     * 显示修改单个地址值的对话框
     */
    private fun showModifyValueDialog(address: SavedAddress) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = ModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            savedAddress = address,
            onConfirm = { addr, oldValue, newValue, valueType ->
                try {
                    val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                    // 保存备份
                    MemoryBackupManager.saveBackup(addr, oldValue, valueType)

                    val success = WuwaDriver.writeMemory(addr, dataBytes)
                    if (success) {
                        // 更新内存中的地址值
                        val index = savedAddresses.indexOfFirst { it.address == addr }
                        if (index >= 0) {
                            savedAddresses[index] = savedAddresses[index].copy(value = newValue)
                            adapter.updateAddress(savedAddresses[index])
                        }

                        // 发送事件通知其他界面同步更新
                        coroutineScope.launch {
                            FloatingEventBus.emitAddressValueChanged(
                                AddressValueChangedEvent(
                                    address = addr,
                                    newValue = newValue,
                                    valueType = valueType.nativeId,
                                    source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                                )
                            )
                        }

                        notification.showSuccess(
                            context.getString(
                                R.string.modify_success_message,
                                String.format("%X", addr)
                            )
                        )
                    } else {
                        notification.showError(
                            context.getString(
                                R.string.modify_failed_message,
                                String.format("%X", addr)
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

    /**
     * 显示批量修改对话框
     */
    private fun showBatchModifyDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = BatchModifyValueDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            savedAddresses = selectedItems,
            onConfirm = { items, newValue, valueType ->
                batchModifyValues(items, newValue, valueType)
            }
        )

        dialog.show()
    }

    /**
     * 批量修改值（使用批量写入接口提高效率）
     */
    private fun batchModifyValues(
        items: List<SavedAddress>,
        newValue: String,
        valueType: DisplayValueType
    ) {
        coroutineScope.launch {
            try {
                val dataBytes = ValueTypeUtils.parseExprToBytes(newValue, valueType)

                // 保存备份
                items.forEach { item ->
                    MemoryBackupManager.saveBackup(item.address, item.value, valueType)
                }

                // 准备批量写入参数
                val addrs = items.map { it.address }.toLongArray()
                val dataArray = Array(items.size) { dataBytes }

                // 批量写入内存
                val results = withContext(Dispatchers.IO) {
                    WuwaDriver.batchWriteMemory(addrs, dataArray)
                }

                // 统计结果并更新 UI
                var successCount = 0
                var failureCount = 0
                val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

                results.forEachIndexed { index, success ->
                    if (success) {
                        val item = items[index]
                        val addrIndex = savedAddresses.indexOfFirst { it.address == item.address }
                        if (addrIndex >= 0) {
                            savedAddresses[addrIndex] = item.copy(value = newValue)
                            adapter.updateAddress(savedAddresses[addrIndex])
                        }
                        successfulChanges.add(
                            BatchAddressValueChangedEvent.AddressChange(
                                address = item.address,
                                newValue = newValue,
                                valueType = valueType.nativeId
                            )
                        )
                        successCount++
                    } else {
                        failureCount++
                    }
                }

                // 发送批量事件通知其他界面同步更新
                if (successfulChanges.isNotEmpty()) {
                    FloatingEventBus.emitBatchAddressValueChanged(
                        BatchAddressValueChangedEvent(
                            changes = successfulChanges,
                            source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                        )
                    )
                }

                if (failureCount == 0) {
                    notification.showSuccess("成功修改 $successCount 个地址")
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

    /**
     * 显示删除对话框
     */
    private fun showRemoveDialog() {
        val selectedCount = adapter.getSelectedItems().size
        val totalCount = savedAddresses.size

        if (totalCount == 0) {
            notification.showWarning("没有可删除的地址")
            return
        }

        val dialog = RemoveOptionsDialog(
            context = context,
            selectedCount = selectedCount
        )

        dialog.onRemoveAll = {
            clearAll()
            notification.showSuccess("已清空所有地址")
        }

        dialog.onRestoreAndRemove = {
            restoreAndRemoveSelected()
        }

        dialog.onRemoveSelected = {
            removeSelectedAddresses()
        }

        dialog.show()
    }

    /**
     * 恢复并移除选中的地址
     */
    private fun restoreAndRemoveSelected() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        coroutineScope.launch {
            var restoreCount = 0
            var failCount = 0
            val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

            withContext(Dispatchers.IO) {
                selectedItems.forEach { item ->
                    val backup = MemoryBackupManager.getBackup(item.address)
                    if (backup != null) {
                        try {
                            val dataBytes = ValueTypeUtils.parseExprToBytes(
                                backup.originalValue,
                                backup.originalType
                            )
                            if (WuwaDriver.writeMemory(item.address, dataBytes)) {
                                successfulChanges.add(
                                    BatchAddressValueChangedEvent.AddressChange(
                                        address = item.address,
                                        newValue = backup.originalValue,
                                        valueType = backup.originalType.nativeId
                                    )
                                )
                                MemoryBackupManager.removeBackup(item.address)
                                restoreCount++
                            } else {
                                failCount++
                            }
                        } catch (e: Exception) {
                            failCount++
                        }
                    }
                }
            }

            // 发送批量事件通知其他界面同步更新
            if (successfulChanges.isNotEmpty()) {
                FloatingEventBus.emitBatchAddressValueChanged(
                    BatchAddressValueChangedEvent(
                        changes = successfulChanges,
                        source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                    )
                )
            }

            // 移除地址
            selectedItems.forEach { item ->
                savedAddresses.removeIf { it.address == item.address }
            }
            adapter.setAddresses(savedAddresses)
            updateEmptyState()
            updateAddressCountBadge()

            if (failCount == 0) {
                notification.showSuccess("已恢复并移除 $restoreCount 个地址")
            } else {
                notification.showWarning("恢复: $restoreCount, 失败: $failCount")
            }
        }
    }

    /**
     * 移除选中的地址
     */
    private fun removeSelectedAddresses() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        selectedItems.forEach { item ->
            savedAddresses.removeIf { it.address == item.address }
        }
        adapter.setAddresses(savedAddresses)
        updateEmptyState()
        updateAddressCountBadge()

        notification.showSuccess("已移除 ${selectedItems.size} 个地址")
    }

    /**
     * 导出地址到文件
     */
    private fun exportAddresses() {
        if (savedAddresses.isEmpty()) {
            notification.showWarning("没有可导出的地址")
            return
        }

        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                SavedAddressRepository.saveAddresses(context, savedAddresses)
            }

            if (success) {
                notification.showSuccess("已保存 ${savedAddresses.size} 个地址到文件")
            } else {
                notification.showError("保存失败")
            }
        }
    }

    /**
     * 恢复选中地址的原始值
     */
    private fun restoreSelectedValues() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        coroutineScope.launch {
            var restoreCount = 0
            var noBackupCount = 0
            var failCount = 0
            val successfulChanges = mutableListOf<BatchAddressValueChangedEvent.AddressChange>()

            withContext(Dispatchers.IO) {
                selectedItems.forEach { item ->
                    val backup = MemoryBackupManager.getBackup(item.address)
                    if (backup != null) {
                        try {
                            val dataBytes = ValueTypeUtils.parseExprToBytes(
                                backup.originalValue,
                                backup.originalType
                            )
                            if (WuwaDriver.writeMemory(item.address, dataBytes)) {
                                withContext(Dispatchers.Main) {
                                    val index =
                                        savedAddresses.indexOfFirst { it.address == item.address }
                                    if (index >= 0) {
                                        savedAddresses[index] =
                                            item.copy(value = backup.originalValue)
                                        adapter.updateAddress(savedAddresses[index])
                                    }
                                }
                                successfulChanges.add(
                                    BatchAddressValueChangedEvent.AddressChange(
                                        address = item.address,
                                        newValue = backup.originalValue,
                                        valueType = backup.originalType.nativeId
                                    )
                                )
                                MemoryBackupManager.removeBackup(item.address)
                                restoreCount++
                            } else {
                                failCount++
                            }
                        } catch (e: Exception) {
                            failCount++
                        }
                    } else {
                        noBackupCount++
                    }
                }
            }

            // 发送批量事件通知其他界面同步更新
            if (successfulChanges.isNotEmpty()) {
                FloatingEventBus.emitBatchAddressValueChanged(
                    BatchAddressValueChangedEvent(
                        changes = successfulChanges,
                        source = AddressValueChangedEvent.Source.SAVED_ADDRESS
                    )
                )
            }

            when {
                restoreCount > 0 && failCount == 0 && noBackupCount == 0 -> {
                    notification.showSuccess("已恢复 $restoreCount 个地址")
                }

                noBackupCount > 0 -> {
                    notification.showWarning("恢复: $restoreCount, 无备份: $noBackupCount")
                }

                else -> {
                    notification.showWarning("恢复: $restoreCount, 失败: $failCount")
                }
            }
        }
    }

    /**
     * 显示载入地址对话框
     */
    private fun showLoadAddressesDialog() {
        coroutineScope.launch {
            val savedLists = withContext(Dispatchers.IO) {
                SavedAddressRepository.getSavedListNames(context)
            }

            if (savedLists.isEmpty()) {
                notification.showWarning("没有已保存的地址列表")
                return@launch
            }

            context.simpleSingleChoiceDialog(
                title = "选择地址列表",
                options = savedLists.toTypedArray(),
                showRadioButton = false,
                onSingleChoice = { which ->
                    loadAddressesFromFile(savedLists[which])
                }
            )
        }
    }

    /**
     * 从文件加载地址
     */
    private fun loadAddressesFromFile(fileName: String) {
        coroutineScope.launch {
            val loadedAddresses = withContext(Dispatchers.IO) {
                SavedAddressRepository.loadAddresses(context, fileName)
            }

            if (loadedAddresses.isNotEmpty()) {
                saveAddresses(loadedAddresses)
                notification.showSuccess("已载入 ${loadedAddresses.size} 个地址")
            } else {
                notification.showError("载入失败或文件为空")
            }
        }
    }

    /**
     * 将选中的地址设为搜索结果
     */
    private fun setSelectedAsSearchResults() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        coroutineScope.launch {
            val addresses = selectedItems.map { it.address }
            val types = selectedItems.map {
                it.displayValueType ?: DisplayValueType.DWORD
            }.toTypedArray()

            val success = withContext(Dispatchers.IO) {
                SearchEngine.addResultsFromAddresses(addresses, types)
            }

            if (success) {
                val totalCount = SearchEngine.getTotalResultCount()
                notification.showSuccess("已将 ${selectedItems.size} 个地址设为搜索结果")

                // 为每个地址创建独立的 DisplayMemRegionEntry，避免不连续地址的问题
                val ranges = selectedItems.map { item ->
                    val size = item.displayValueType?.memorySize ?: 4
                    DisplayMemRegionEntry(
                        start = item.address,
                        end = item.address + size,
                        type = 0x03, // r/w
                        name = item.range.displayName,
                        range = item.range
                    )
                }

                // 发送搜索结果更新事件
                coroutineScope.launch {
                    FloatingEventBus.emitSearchResultsUpdated(
                        SearchResultsUpdatedEvent(totalCount, ranges)
                    )
                }
            } else {
                notification.showError("设置搜索结果失败")
            }
        }
    }

    /**
     * 计算选中地址的偏移异或（通过Service显示对话框）
     */
    private fun calculateOffsetXor() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.size < 2) {
            notification.showWarning("请至少选择 2 个地址")
            return
        }

        coroutineScope.launch {
            FloatingEventBus.emitUIAction(
                UIActionEvent.ShowOffsetXorDialog(selectedItems)
            )
        }
    }

    /**
     * 显示更改类型对话框
     */
    private fun showChangeTypeDialog() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.isEmpty()) {
            notification.showWarning("未选择任何项目")
            return
        }

        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        context.simpleSingleChoiceDialog(
            title = "选择新类型",
            options = valueTypeNames,
            textColors = valueTypeColors,
            showRadioButton = false,
            onSingleChoice = { which ->
                val newType = allValueTypes[which]
                changeSelectedAddressTypes(selectedItems, newType)
            }
        )
    }

    /**
     * 更改选中地址的类型并刷新内存值
     */
    private fun changeSelectedAddressTypes(items: List<SavedAddress>, newType: DisplayValueType) {
        if (!WuwaDriver.isProcessBound) {
            // 未绑定进程时，只更改类型不刷新值
            items.forEach { item ->
                val index = savedAddresses.indexOfFirst { it.address == item.address }
                if (index >= 0) {
                    savedAddresses[index] = item.copy(valueType = newType.nativeId)
                    adapter.updateAddress(savedAddresses[index])
                }
            }
            notification.showWarning("已更改类型，但未绑定进程无法刷新值")
            return
        }

        coroutineScope.launch {
            // 准备批量读取参数
            val addrs = items.map { it.address }.toLongArray()
            val sizes = IntArray(items.size) { newType.memorySize.toInt() }

            // 批量读取内存
            val results = withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs, sizes)
            }

            // 更新类型和值
            var successCount = 0
            var failCount = 0

            results.forEachIndexed { index, bytes ->
                val item = items[index]
                val addrIndex = savedAddresses.indexOfFirst { it.address == item.address }

                if (addrIndex >= 0) {
                    if (bytes != null) {
                        try {
                            val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, newType)
                            savedAddresses[addrIndex] = item.copy(
                                valueType = newType.nativeId,
                                value = newValue
                            )
                            adapter.updateAddress(savedAddresses[addrIndex])
                            successCount++
                        } catch (e: Exception) {
                            // 转换失败，只更新类型
                            savedAddresses[addrIndex] = item.copy(valueType = newType.nativeId)
                            adapter.updateAddress(savedAddresses[addrIndex])
                            failCount++
                        }
                    } else {
                        // 读取失败，只更新类型
                        savedAddresses[addrIndex] = item.copy(valueType = newType.nativeId)
                        adapter.updateAddress(savedAddresses[addrIndex])
                        failCount++
                    }
                }
            }

            if (failCount == 0) {
                notification.showSuccess("已更改 $successCount 个地址的类型为 ${newType.code}")
            } else {
                notification.showWarning("成功: $successCount, 读取失败: $failCount")
            }
        }
    }

    /**
     * 显示偏移量计算器
     */
    private fun showOffsetCalculator() {
        val selectedItems = adapter.getSelectedItems()
        var initialBaseAddress: Long? = null
        if (selectedItems.isNotEmpty()) {
            initialBaseAddress = selectedItems.firstOrNull()?.address
        }

        coroutineScope.launch {
            FloatingEventBus.emitUIAction(
                UIActionEvent.ShowOffsetCalculatorDialog(
                    initialBaseAddress = initialBaseAddress
                )
            )
        }
    }

    private fun updateEmptyState() {
        if (savedAddresses.isEmpty()) {
            binding.emptyStateView.visibility = View.VISIBLE
            binding.savedAddressesRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateView.visibility = View.GONE
            binding.savedAddressesRecyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * 启动自动更新（只更新可见部分）
     */
    fun startAutoUpdate() {
        // 如果已有任务在运行，先停止
        stopAutoUpdate()

        autoUpdateJob = coroutineScope.launch {
            while (isActive) {
                val interval = MMKV.defaultMMKV().saveListUpdateInterval.toLong()
                delay(interval)
                updateVisibleAddresses()
            }
        }
    }

    /**
     * 停止自动更新
     */
    fun stopAutoUpdate() {
        autoUpdateJob?.cancel()
        autoUpdateJob = null
    }

    /**
     * 只更新可见范围的地址值（优化性能）
     */
    private suspend fun updateVisibleAddresses() {
        // 记录当前进程 PID（窗口期保护）
        val currentPid = WuwaDriver.currentBindPid
        if (savedAddresses.isEmpty() || currentPid <= 0) {
            return
        }

        // 获取可见范围
        val layoutManager = binding.savedAddressesRecyclerView.layoutManager as? LinearLayoutManager
            ?: return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()

        if (firstVisible < 0 || lastVisible < 0 || firstVisible > lastVisible) {
            return
        }

        // 安全的边界检查
        val safeFirst = firstVisible.coerceIn(0, savedAddresses.size - 1)
        val safeLast = lastVisible.coerceIn(0, savedAddresses.size - 1)

        if (safeFirst > safeLast) return

        // 创建快照（防止并发修改）
        val snapshot = try {
            savedAddresses.subList(safeFirst, safeLast + 1).toList()
        } catch (e: Exception) {
            return
        }

        // 准备批量读取参数
        val addrs = snapshot.map { it.address }.toLongArray()
        val sizes = snapshot.map {
            it.displayValueType?.memorySize?.toInt() ?: DisplayValueType.DWORD.memorySize.toInt()
        }.toIntArray()

        val results = try {
            withContext(Dispatchers.IO) {
                WuwaDriver.batchReadMemory(addrs, sizes)
            }
        } catch (e: Exception) {
            return
        }

        // 关键检查：进程是否还是同一个？
        if (WuwaDriver.currentBindPid != currentPid) {
            // 进程已切换，丢弃这批数据
            return
        }

        // 安全更新 UI（通过地址查找，即使列表变化也不会崩溃）
        results.forEachIndexed { index, bytes ->
            try {
                val snapshotItem = snapshot[index]
                val currentIndex =
                    savedAddresses.indexOfFirst { it.address == snapshotItem.address }

                if (currentIndex >= 0 && bytes != null) {
                    val valueType = savedAddresses[currentIndex].displayValueType
                        ?: DisplayValueType.DWORD
                    val newValue = ValueTypeUtils.bytesToDisplayValue(bytes, valueType)

                    // 只在值变化时更新，避免无意义的刷新
                    if (savedAddresses[currentIndex].value != newValue) {
                        savedAddresses[currentIndex] =
                            savedAddresses[currentIndex].copy(value = newValue)
                        adapter.updateAddress(savedAddresses[currentIndex])
                    }
                }
            } catch (e: Exception) {
                // 忽略单个地址的错误，继续处理其他地址
            }
        }
    }

    override fun cleanup() {
        super.cleanup()
        binding.savedFastScroller.detachFromRecyclerView()
        stopAutoUpdate()
        coroutineScope.cancel()
    }
}