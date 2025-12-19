package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.databinding.FloatingSavedAddressesLayoutBinding
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.SavedAddressAdapter
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.local.SavedAddressRepository
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.dialog.BatchModifyValueDialog
import moe.fuqiuluo.mamu.floating.dialog.OffsetXorDialog
import moe.fuqiuluo.mamu.floating.dialog.RemoveOptionsDialog
import moe.fuqiuluo.mamu.utils.ValueTypeUtils
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SavedAddressController(
    context: Context,
    binding: FloatingSavedAddressesLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingSavedAddressesLayoutBinding>(context, binding, notification) {
    // 搜索结果更新回调
    var onSearchResultsUpdated: ((totalCount: Long, ranges: List<DisplayMemRegionEntry>) -> Unit)? = null
    // 保存的地址列表（内存中）
    private val savedAddresses = mutableListOf<SavedAddress>()

    // 地址数量
    private var addressCountBadgeView: TextView? = null

    // 列表适配器
    private val adapter: SavedAddressAdapter = SavedAddressAdapter(
        onItemClick = { address, position ->
            notification.showWarning("点击了 ${address.name}")
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

    override fun initialize() {
        setupToolbar()
        setupRecyclerView()
        setupRefreshButton()
        updateProcessDisplay(null)
        updateEmptyState()
        updateAddressCountBadge()
    }

    fun setAddressCountBadgeView(badge: TextView) {
        addressCountBadgeView = badge
        updateAddressCountBadge()
    }

    private fun updateAddressCountBadge() {
        addressCountBadgeView?.let { badge ->
            val count = savedAddresses.size
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
    fun saveAddress(address: SavedAddress) {
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
    fun saveAddresses(addresses: List<SavedAddress>) {
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

                results.forEachIndexed { index, success ->
                    if (success) {
                        val item = items[index]
                        val addrIndex = savedAddresses.indexOfFirst { it.address == item.address }
                        if (addrIndex >= 0) {
                            savedAddresses[addrIndex] = item.copy(value = newValue)
                            adapter.updateAddress(savedAddresses[addrIndex])
                        }
                        successCount++
                    } else {
                        failureCount++
                    }
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

                onSearchResultsUpdated?.invoke(totalCount, ranges)
            } else {
                notification.showError("设置搜索结果失败")
            }
        }
    }

    /**
     * 计算选中地址的偏移异或
     */
    private fun calculateOffsetXor() {
        val selectedItems = adapter.getSelectedItems()
        if (selectedItems.size < 2) {
            notification.showWarning("请至少选择 2 个地址")
            return
        }

        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val dialog = OffsetXorDialog(
            context = context,
            notification = notification,
            clipboardManager = clipboardManager,
            selectedAddresses = selectedItems
        )

        dialog.show()
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

        val allValueTypes = DisplayValueType.entries.filter {
            it != DisplayValueType.AUTO &&
                    it != DisplayValueType.HEX_MIXED &&
                    it != DisplayValueType.ARM &&
                    it != DisplayValueType.ARM64
        }.toTypedArray()
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
        // todo 显示偏移量计算器
//        val clipboardManager =
//            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
//
//        val dialog = OffsetCalculatorDialog(
//            context = context,
//            notification = notification,
//            clipboardManager = clipboardManager
//        )
//        dialog.show()
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

    override fun cleanup() {
        super.cleanup()
        coroutineScope.cancel()
    }
}