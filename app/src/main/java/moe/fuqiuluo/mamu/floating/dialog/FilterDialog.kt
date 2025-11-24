package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogFilterBinding
import moe.fuqiuluo.mamu.floating.ext.floatingOpacity
import moe.fuqiuluo.mamu.floating.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.multiChoiceDialog
import kotlin.math.max

class FilterDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val filterDialogState: FilterDialogState,
    private val clipboardManager: ClipboardManager,
    private val onApply: ((FilterDialogState) -> Unit)? = null
) : BaseDialog(context) {

    private var currentFocusedInput: EditText? = null

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogFilterBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 恢复状态
        binding.inputMaxDisplayCount.setText(filterDialogState.maxDisplayCount.toString())
        binding.cbEnableAddressFilter.isChecked = filterDialogState.enableAddressFilter
        binding.inputAddressStart.setText(filterDialogState.addressRangeStart)
        binding.inputAddressEnd.setText(filterDialogState.addressRangeEnd)
        binding.cbEnableDataTypeFilter.isChecked = filterDialogState.enableDataTypeFilter

        // 设置输入框焦点监听
        setupInputFocus(binding)

        // 控制地址范围过滤显示/隐藏
        binding.cbEnableAddressFilter.setOnCheckedChangeListener { _, isChecked ->
            binding.containerAddressRange.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.containerAddressRange.visibility =
            if (filterDialogState.enableAddressFilter) View.VISIBLE else View.GONE

        // 控制数据类型过滤显示/隐藏
        binding.cbEnableDataTypeFilter.setOnCheckedChangeListener { _, isChecked ->
            binding.btnSelectDataTypes.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        binding.btnSelectDataTypes.visibility =
            if (filterDialogState.enableDataTypeFilter) View.VISIBLE else View.GONE

        // 数据类型选择
        updateDataTypeButtonText(binding)
        binding.btnSelectDataTypes.setOnClickListener {
            showDataTypeSelectionDialog(binding)
        }

        // 内置键盘
        setupBuiltinKeyboard(binding)

        // 重置按钮
        binding.btnReset.setOnClickListener {
            resetToDefaults(binding)
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            saveState(binding)
            onCancel?.invoke()
            dialog.dismiss()
        }

        // 应用按钮
        binding.btnApply.setOnClickListener {
            if (validateInput(binding)) {
                saveState(binding)
                onApply?.invoke(filterDialogState)
                dialog.dismiss()
            }
        }
    }

    private fun setupInputFocus(binding: DialogFilterBinding) {
        val inputs = listOf(
            binding.inputMaxDisplayCount,
            binding.inputAddressStart,
            binding.inputAddressEnd,
        )

        inputs.forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentFocusedInput = view as EditText
                }
            }
        }

        // 默认焦点
        currentFocusedInput = binding.inputMaxDisplayCount
        binding.inputMaxDisplayCount.requestFocus()
    }

    private fun setupBuiltinKeyboard(binding: DialogFilterBinding) {
        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                val currentText = input.text.toString()
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                val newText = if (selectionStart != selectionEnd) {
                    currentText.take(selectionStart) + key + currentText.substring(selectionEnd)
                } else {
                    currentText.take(selectionStart) + key + currentText.substring(selectionStart)
                }

                input.setText(newText)
                input.setSelection(selectionStart + key.length)
            }

            override fun onDelete() {
                val input = currentFocusedInput ?: return
                val currentText = input.text.toString()
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                if (selectionStart != selectionEnd) {
                    val newText =
                        currentText.take(selectionStart) + currentText.substring(selectionEnd)
                    input.setText(newText)
                    input.setSelection(selectionStart)
                } else if (selectionStart > 0) {
                    val newText =
                        currentText.take(selectionStart - 1) + currentText.substring(selectionStart)
                    input.setText(newText)
                    input.setSelection(selectionStart - 1)
                }
            }

            override fun onSelectAll() {
                currentFocusedInput?.selectAll()
            }

            override fun onMoveLeft() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos > 0) {
                    input.setSelection(cursorPos - 1)
                }
            }

            override fun onMoveRight() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos < input.text.length) {
                    input.setSelection(cursorPos + 1)
                }
            }

            override fun onHistory() {
                notification.showSuccess("历史记录功能开发中")
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val input = currentFocusedInput ?: return
                    val currentText = input.text.toString()
                    val cursorPos = input.selectionStart
                    val newText =
                        currentText.take(cursorPos) + text + currentText.substring(cursorPos)
                    input.setText(newText)
                    input.setSelection(cursorPos + text.length)
                }
            }
        }
    }

    private fun showDataTypeSelectionDialog(binding: DialogFilterBinding) {
        val allTypes = DisplayValueType.entries.toTypedArray()
        val typeNames = allTypes.map { it.displayName }.toTypedArray()
        val checkedItems = BooleanArray(allTypes.size) { index ->
            filterDialogState.selectedDataTypes.contains(allTypes[index])
        }

        context.multiChoiceDialog(
            title = "选择数据类型",
            options = typeNames,
            checkedItems = checkedItems,
            onMultiChoice = { newCheckedItems ->
                filterDialogState.selectedDataTypes.clear()
                newCheckedItems.forEachIndexed { index, checked ->
                    if (checked) {
                        filterDialogState.selectedDataTypes.add(allTypes[index])
                    }
                }
                updateDataTypeButtonText(binding)
            }
        )
    }

    private fun updateDataTypeButtonText(binding: DialogFilterBinding) {
        val text = if (filterDialogState.selectedDataTypes.isEmpty()) {
            context.getString(R.string.button_select_data_types)
        } else {
            filterDialogState.selectedDataTypes.joinToString(", ") { it.displayName }
        }
        binding.btnSelectDataTypes.text = text
    }

    private fun resetToDefaults(binding: DialogFilterBinding) {
        // 重置为默认值
        filterDialogState.maxDisplayCount = 100
        filterDialogState.enableAddressFilter = false
        filterDialogState.addressRangeStart = ""
        filterDialogState.addressRangeEnd = ""
        filterDialogState.enableDataTypeFilter = false
        filterDialogState.selectedDataTypes.clear()

        // 更新UI
        binding.inputMaxDisplayCount.setText("100")
        binding.cbEnableAddressFilter.isChecked = false
        binding.inputAddressStart.setText("")
        binding.inputAddressEnd.setText("")
        updateDataTypeButtonText(binding)

        notification.showSuccess("已重置为默认值")
    }

    private fun validateInput(binding: DialogFilterBinding): Boolean {
        // 验证单页最大显示数量
        val maxCount = binding.inputMaxDisplayCount.text.toString().toIntOrNull()
        if (maxCount == null || maxCount <= 0) {
            notification.showError("单页最大显示数量必须是正整数")
            return false
        }

        // 如果启用了地址范围过滤，验证地址范围
        if (binding.cbEnableAddressFilter.isChecked) {
            val startAddr = binding.inputAddressStart.text.toString().trim()
            val endAddr = binding.inputAddressEnd.text.toString().trim()
            if (startAddr.isEmpty() || endAddr.isEmpty()) {
                notification.showError("地址范围不能为空")
                return false
            }
        }

        // 如果启用了数据类型过滤，验证是否选择了至少一个类型
        if (binding.cbEnableDataTypeFilter.isChecked && filterDialogState.selectedDataTypes.isEmpty()) {
            notification.showError("请至少选择一个数据类型")
            return false
        }

        return true
    }

    private fun saveState(binding: DialogFilterBinding) {
        filterDialogState.maxDisplayCount =
            binding.inputMaxDisplayCount.text.toString().toIntOrNull() ?: 100
        filterDialogState.enableAddressFilter = binding.cbEnableAddressFilter.isChecked
        filterDialogState.addressRangeStart = binding.inputAddressStart.text.toString()
        filterDialogState.addressRangeEnd = binding.inputAddressEnd.text.toString()
        filterDialogState.enableDataTypeFilter = binding.cbEnableDataTypeFilter.isChecked
    }
}