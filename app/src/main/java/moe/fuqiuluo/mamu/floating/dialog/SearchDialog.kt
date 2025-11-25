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
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogSearchInputBinding
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.SearchProgressCallback
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.ext.floatingOpacity
import moe.fuqiuluo.mamu.floating.ext.keyboardType
import moe.fuqiuluo.mamu.floating.ext.memoryAccessMode
import moe.fuqiuluo.mamu.floating.ext.selectedMemoryRanges
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog
import kotlin.math.max

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
    private val onSearchCompleted: ((ranges: List<DisplayMemRegionEntry>) -> Unit)? = null,
    private val onRefineCompleted: (() -> Unit)? = null
) : BaseDialog(context) {
    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var searchRanges: List<DisplayMemRegionEntry>

    private inner class SearchCallback : SearchProgressCallback {
        override fun onSearchComplete(
            totalFound: Long,
            totalRegions: Int,
            elapsedMillis: Long
        ) {
            searchScope.launch(Dispatchers.Main) {
                if (!::searchRanges.isInitialized) {
                    notification.showError(context.getString(R.string.error_search_failed_unknown))
                    return@launch
                } else {
                    notification.showSuccess(
                        context.getString(
                            R.string.success_search_complete,
                            totalFound,
                            elapsedMillis
                        )
                    )
                    onSearchCompleted?.invoke(searchRanges)
                }
            }
        }
    }

    private inner class RefineSearchCallback : SearchProgressCallback {
        override fun onSearchComplete(
            totalFound: Long,
            totalRegions: Int,
            elapsedMillis: Long
        ) {
            searchScope.launch(Dispatchers.Main) {
                notification.showSuccess(
                    context.getString(
                        R.string.success_search_complete,
                        totalFound,
                        elapsedMillis
                    )
                )
                onRefineCompleted?.invoke()
            }
        }
    }

    fun release() {
        searchScope.cancel()
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogSearchInputBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 根据配置决定是否禁用系统输入法
        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            // 使用内置键盘时，禁用系统输入法弹出
            binding.inputValue.showSoftInputOnFocus = false
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            // 使用系统键盘时，允许系统输入法弹出
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

        val allValueTypes = DisplayValueType.entries.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        var currentValueType = searchDialogState.lastSelectedValueType

        fun updateSubtitleRange(type: DisplayValueType) {
            binding.subtitleRange.text = type.rangeDescription
        }

        // 恢复上次输入的值
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
                // 直接操作 Editable，避免 setText 带来的竞争条件
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                // 使用 Editable.replace() 直接替换选中的文本
                // 如果没有选中文本，selectionStart == selectionEnd，相当于插入
                editable.replace(selectionStart, selectionEnd, key)
                // 光标会自动移动到插入文本之后
            }

            override fun onDelete() {
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                if (selectionStart != selectionEnd) {
                    // 有选中文本，删除选中部分
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    // 无选中文本，删除光标前一个字符
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
                notification.showSuccess(context.getString(moe.fuqiuluo.mamu.R.string.feature_history_todo))
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val editable = binding.inputValue.text ?: return
                    val selectionStart = binding.inputValue.selectionStart
                    val selectionEnd = binding.inputValue.selectionEnd

                    // 使用 Editable.replace() 在光标位置粘贴文本
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }

        // 检查是否有搜索结果，动态设置按钮布局
        val hasResults = SearchEngine.getTotalResultCount() > 0

        if (hasResults) {
            // 有结果时：[新搜索] [取消] [改善]
            binding.btnNewSearch?.visibility = View.VISIBLE
            binding.buttonSpacer?.visibility = View.VISIBLE
            binding.btnConfirm.visibility = View.GONE
            binding.btnRefine?.visibility = View.VISIBLE
        } else {
            // 无结果时：[取消] [搜索]
            binding.btnNewSearch?.visibility = View.GONE
            binding.buttonSpacer?.visibility = View.GONE
            binding.btnConfirm.visibility = View.VISIBLE
            binding.btnRefine?.visibility = View.GONE
        }

        // 执行搜索的通用函数
        val preCheck: (String) -> Boolean = preCheck@{ expression ->
            if (expression.isEmpty()) {
                notification.showError(context.getString(R.string.error_empty_search_value))
                return@preCheck false
            }

            searchDialogState.lastInputValue = expression
            dialog.dismiss()

            return@preCheck true
        }
        val performSearch: () -> Unit = performSearch@{
            val expression = binding.inputValue.text.toString().trim()
            val valueType = currentValueType

            if (!preCheck(expression)) {
                return@performSearch
            }

            searchScope.launch {
                SearchEngine.clearSearchResults()
                val ranges = mmkv.selectedMemoryRanges

                val nativeRegions = mutableListOf<Long>()
                WuwaDriver.queryMemRegions()
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
                    // 普通搜索：在指定内存区域中搜索
                    SearchEngine.exactSearchWithCustomRange(
                        expression,
                        valueType,
                        nativeRegions.toLongArray(),
                        SearchCallback()
                    )
                }.onFailure {
                    Log.e(TAG, "搜索失败", it)
                }
            }
        }
        val refineSearch: () -> Unit = refineSearch@ {
            val expression = binding.inputValue.text.toString().trim()
            val valueType = currentValueType

            if (!preCheck(expression)) {
                return@refineSearch
            }

            searchScope.launch {
                runCatching {
                    // 改善搜索：基于上一次搜索结果进行再次搜索
                    SearchEngine.refineSearch(
                        expression,
                        valueType,
                        RefineSearchCallback()
                    )
                }.onFailure {
                    Log.e(TAG, "改善搜索失败", it)
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            searchDialogState.lastInputValue = binding.inputValue.text.toString()
            onCancel?.invoke()
            dialog.dismiss()
        }

        // 新搜索按钮：清除旧结果并进行全新搜索
        binding.btnNewSearch?.setOnClickListener {
            performSearch()
        }

        // 搜索按钮
        binding.btnConfirm.setOnClickListener {
            if (hasResults) {
                refineSearch()
            } else {
                performSearch()
            }
        }

        // 改善按钮
        binding.btnRefine?.setOnClickListener {
            refineSearch()
        }
    }
}
