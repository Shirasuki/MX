package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogSearchInputBinding
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.floating.data.local.SearchHistoryRepository
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.formatElapsedTime
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

data class SearchDialogState(
    var lastSelectedValueType: DisplayValueType = DisplayValueType.DWORD,
    var lastInputValue: String = ""
)

private const val TAG = "SearchDialog"

class SearchDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val searchDialogState: SearchDialogState,
    private val clipboardManager: ClipboardManager,
    private val onSearchCompleted: ((ranges: List<DisplayMemRegionEntry>, totalFound: Long) -> Unit)? = null,
    private val onRefineCompleted: ((totalFound: Long) -> Unit)? = null
) : BaseDialog(context) {
    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var searchRanges: List<DisplayMemRegionEntry>

    // 进度相关
    private var progressDialog: SearchProgressDialog? = null

    // 搜索状态标志
    var isSearching = false
    private var currentIsRefineSearch = false

    // 搜索开始时间
    private var searchStartTime = 0L

    /**
     * 读取进度数据从共享缓冲区
     */
    private fun readProgressData(): SearchProgressData {
        return SearchProgressData(
            currentProgress = SearchEngine.getProgress(),
            regionsOrAddrsSearched = SearchEngine.getRegionsDone(),
            totalFound = SearchEngine.getFoundCount(),
            heartbeat = SearchEngine.getHeartbeat()
        )
    }

    /**
     * 启动进度监控协程（轮询检查状态）
     */
    private fun startProgressMonitoring(isRefineSearch: Boolean) {
        searchScope.launch(Dispatchers.Main) {
            while (isActive && isSearching) {
                val status = SearchEngine.getStatus()
                val data = readProgressData()

                // 更新进度 UI
                progressDialog?.updateProgress(data)

                // 检查搜索状态
                when (status) {
                    SearchEngine.Status.COMPLETED -> {
                        val elapsed = System.currentTimeMillis() - searchStartTime
                        onSearchFinished(isRefineSearch, data.totalFound, elapsed)
                        break
                    }
                    SearchEngine.Status.CANCELLED -> {
                        onSearchCancelled()
                        break
                    }
                    SearchEngine.Status.ERROR -> {
                        onSearchError(SearchEngine.getErrorCode())
                        break
                    }
                }

                delay(100)
            }
        }
    }

    /**
     * 搜索完成回调
     */
    private fun onSearchFinished(isRefineSearch: Boolean, totalFound: Long, elapsedMillis: Long) {
        cleanupProgressTracking()

        notification.showSuccess(
            context.getString(
                R.string.success_search_complete,
                totalFound,
                formatElapsedTime(elapsedMillis)
            )
        )

        if (isRefineSearch) {
            onRefineCompleted?.invoke(totalFound)
        } else {
            if (::searchRanges.isInitialized) {
                onSearchCompleted?.invoke(searchRanges, totalFound)
            }
        }
    }

    /**
     * 搜索取消回调
     */
    private fun onSearchCancelled() {
        cleanupProgressTracking()
        notification.showWarning(context.getString(R.string.search_cancelled))
    }

    /**
     * 搜索错误回调
     */
    private fun onSearchError(errorCode: Int) {
        cleanupProgressTracking()
        val errorMessage = when (errorCode) {
            SearchEngine.ErrorCode.NOT_INITIALIZED -> "搜索引擎未初始化"
            SearchEngine.ErrorCode.INVALID_QUERY -> "无效的搜索表达式"
            SearchEngine.ErrorCode.MEMORY_READ_FAILED -> "内存读取失败"
            SearchEngine.ErrorCode.ALREADY_SEARCHING -> "搜索正在进行中"
            else -> "搜索出错 (code: $errorCode)"
        }
        notification.showError(errorMessage)
    }

    /**
     * 取消当前搜索
     */
    private fun cancelSearch() {
        if (isSearching) {
            // 通过共享内存请求取消（零延迟）
            SearchEngine.requestCancelViaBuffer()
            // 也通过 CancellationToken 请求取消
            SearchEngine.requestCancel()
        }
    }

    /**
     * 初始化进度追踪
     */
    private fun setupProgressTracking(isRefineSearch: Boolean) {
        isSearching = true
        currentIsRefineSearch = isRefineSearch
        searchStartTime = System.currentTimeMillis()

        // 显示进度对话框
        progressDialog = SearchProgressDialog(
            context = context,
            isRefineSearch = isRefineSearch,
            onCancelClick = {
                cancelSearch()
            },
            onHideClick = {
                // 发送隐藏悬浮窗事件
                searchScope.launch {
                    FloatingEventBus.emitUIAction(UIActionEvent.HideFloatingWindow)
                }
            }
        ).apply {
            show()
        }

        // 启动进度监控
        startProgressMonitoring(isRefineSearch)
    }

    /**
     * 清理进度追踪
     */
    private fun cleanupProgressTracking() {
        progressDialog?.dismiss()
        progressDialog = null
        isSearching = false
    }

    fun release() {
        cleanupProgressTracking()
        searchScope.cancel()
    }

    /**
     * 隐藏进度对话框（但保持搜索状态）
     */
    fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    /**
     * 如果正在搜索，重新显示进度对话框
     */
    fun showProgressDialogIfSearching() {
        if (isSearching && progressDialog == null) {
            progressDialog = SearchProgressDialog(
                context = context,
                isRefineSearch = currentIsRefineSearch,
                onCancelClick = {
                    cancelSearch()
                },
                onHideClick = {
                    // 发送隐藏悬浮窗事件
                    searchScope.launch {
                        FloatingEventBus.emitUIAction(UIActionEvent.HideFloatingWindow)
                    }
                }
            ).apply {
                show()
                updateProgress(readProgressData())
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        val binding = DialogSearchInputBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.inputValue.showSoftInputOnFocus = false
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.inputValue.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        val operators = arrayOf("=", "≠", "<", ">", "≤", "≥")
        var currentOperator = "="

        binding.btnOperator.setOnClickListener {
            context.simpleSingleChoiceDialog(
                title = context.getString(moe.fuqiuluo.mamu.R.string.dialog_select_operator),
                options = operators,
                selected = operators.indexOf(currentOperator),
                showTitle = true,
                showRadioButton = false,
                textColors = null,
                onSingleChoice = { which ->
                    currentOperator = operators[which]
                    binding.btnOperator.text = currentOperator
                }
            )
        }

        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        var currentValueType = searchDialogState.lastSelectedValueType

        fun updateSubtitleRange(type: DisplayValueType) {
            binding.subtitleRange.text = type.rangeDescription
        }

        binding.inputValue.setText(searchDialogState.lastInputValue)
        binding.btnValueType.text = currentValueType.displayName
        updateSubtitleRange(currentValueType)

        binding.btnValueType.setOnClickListener {
            context.simpleSingleChoiceDialog(
                options = valueTypeNames,
                selected = allValueTypes.indexOf(currentValueType),
                showTitle = false,
                showRadioButton = false,
                textColors = valueTypeColors,
                onSingleChoice = { which ->
                    currentValueType = allValueTypes[which]
                    searchDialogState.lastSelectedValueType = currentValueType
                    binding.btnValueType.text = currentValueType.displayName
                    updateSubtitleRange(currentValueType)
                }
            )
        }

        binding.btnConvertBase.setOnClickListener {
            notification.showSuccess(context.getString(moe.fuqiuluo.mamu.R.string.feature_convert_base_todo))
        }

        binding.btnSearchAllMemory.setOnClickListener {
            notification.showSuccess(context.getString(moe.fuqiuluo.mamu.R.string.feature_select_memory_range_todo))
        }

        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd
                editable.replace(selectionStart, selectionEnd, key)
            }

            override fun onDelete() {
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                if (selectionStart != selectionEnd) {
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    editable.delete(selectionStart - 1, selectionStart)
                }
            }

            override fun onSelectAll() {
                binding.inputValue.selectAll()
            }

            override fun onMoveLeft() {
                val cursorPos = binding.inputValue.selectionStart
                if (cursorPos > 0) {
                    binding.inputValue.setSelection(cursorPos - 1)
                }
            }

            override fun onMoveRight() {
                val cursorPos = binding.inputValue.selectionStart
                if (cursorPos < binding.inputValue.text.length) {
                    binding.inputValue.setSelection(cursorPos + 1)
                }
            }

            override fun onHistory() {
                // 显示搜索历史对话框
                SearchHistoryDialog(
                    context = context,
                    notification = notification,
                    onHistorySelected = { expression, valueType ->
                        // 填充选中的历史记录到输入框
                        binding.inputValue.setText(expression)
                        binding.inputValue.setSelection(expression.length)
                        currentValueType = valueType
                        searchDialogState.lastSelectedValueType = valueType
                        binding.btnValueType.text = valueType.displayName
                        updateSubtitleRange(valueType)
                    }
                ).show()
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val editable = binding.inputValue.text ?: return
                    val selectionStart = binding.inputValue.selectionStart
                    val selectionEnd = binding.inputValue.selectionEnd
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }

        val hasResults = SearchEngine.getTotalResultCount() > 0

        if (hasResults) {
            binding.btnNewSearch.visibility = View.VISIBLE
            binding.buttonSpacer.visibility = View.VISIBLE
            binding.btnConfirm.visibility = View.GONE
            binding.btnRefine.visibility = View.VISIBLE
        } else {
            binding.btnNewSearch.visibility = View.GONE
            binding.buttonSpacer.visibility = View.GONE
            binding.btnConfirm.visibility = View.VISIBLE
            binding.btnRefine.visibility = View.GONE
        }

        val preCheck: (String, DisplayValueType) -> Boolean = preCheck@{ expression, valueType ->
            if (expression.isEmpty()) {
                notification.showError(context.getString(R.string.error_empty_search_value))
                return@preCheck false
            }

            // 保存搜索历史
            SearchHistoryRepository.addHistory(expression, valueType)

            searchDialogState.lastInputValue = expression
            dialog.dismiss()

            return@preCheck true
        }

        // 执行异步搜索
        val performSearch: () -> Unit = performSearch@{
            val expression = binding.inputValue.text.toString().trim()
            val valueType = currentValueType

            if (!preCheck(expression, valueType)) {
                return@performSearch
            }

            searchScope.launch {
                SearchEngine.clearSearchResults()
                val ranges = mmkv.selectedMemoryRanges

                val nativeRegions = mutableListOf<Long>()
                WuwaDriver.queryMemRegionsWithRetry()
                    .divideToSimpleMemoryRange()
                    .also {
                        searchRanges = it
                    }
                    .filter { ranges.contains(it.range) }
                    .forEach {
                        nativeRegions.add(it.start)
                        nativeRegions.add(it.end)
                    }

                runCatching {
                    // 使用异步 API 启动搜索
                    // IMPORTANT: Start search FIRST, then start monitoring
                    // This ensures the monitoring coroutine sees SEARCHING status, not old COMPLETED
                    val started = SearchEngine.startSearchAsyncWithCustomRange(
                        expression,
                        valueType,
                        nativeRegions.toLongArray(),
                        useDeepSearch = binding.cbIsDeeplySearch.isChecked
                    )
                    if (started) {
                        // Start monitoring AFTER search has started (status is now SEARCHING)
                        withContext(Dispatchers.Main) {
                            setupProgressTracking(false)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            notification.showError("启动搜索失败")
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "搜索失败", it)
                    withContext(Dispatchers.Main) {
                        cleanupProgressTracking()
                        notification.showError("搜索失败: ${it.message}")
                    }
                }
            }
        }

        // 执行异步改善搜索
        val refineSearch: () -> Unit = refineSearch@{
            val expression = binding.inputValue.text.toString().trim()
            val valueType = currentValueType

            if (!preCheck(expression, valueType)) {
                return@refineSearch
            }

            searchScope.launch {
                runCatching {
                    // IMPORTANT: Start search FIRST, then start monitoring
                    val started = SearchEngine.startRefineAsync(expression, valueType)
                    if (started) {
                        // Start monitoring AFTER search has started (status is now SEARCHING)
                        withContext(Dispatchers.Main) {
                            setupProgressTracking(true)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            notification.showError("启动改善搜索失败")
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "改善搜索失败", it)
                    withContext(Dispatchers.Main) {
                        cleanupProgressTracking()
                        notification.showError("改善搜索失败: ${it.message}")
                    }
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            searchDialogState.lastInputValue = binding.inputValue.text.toString()
            onCancel?.invoke()
            dialog.dismiss()
        }

        binding.btnNewSearch.setOnClickListener {
            performSearch()
        }

        binding.btnConfirm.setOnClickListener {
            if (hasResults) {
                refineSearch()
            } else {
                performSearch()
            }
        }

        binding.btnRefine.setOnClickListener {
            refineSearch()
        }
    }
}
