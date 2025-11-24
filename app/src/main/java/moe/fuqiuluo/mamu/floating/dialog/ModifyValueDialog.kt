package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import com.google.android.material.textview.MaterialTextView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogModifyValueBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.floating.ext.floatingOpacity
import moe.fuqiuluo.mamu.floating.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog
import kotlin.math.max

private const val TAG = "ModifyValueDialog"

class ModifyValueDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val searchResultItem: SearchResultItem,
    private val onConfirm: ((newValue: String, valueType: DisplayValueType) -> Unit)? = null
) : BaseDialog(context) {

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogModifyValueBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        val address: Long
        val displayValueType: DisplayValueType
        val value: String
        when (searchResultItem) {
            is ExactSearchResultItem -> {
                address = searchResultItem.address
                displayValueType = searchResultItem.displayValueType ?: DisplayValueType.DWORD
                value = searchResultItem.value
            }
            is FuzzySearchResultItem -> {
                address = searchResultItem.address
                displayValueType = searchResultItem.displayValueType ?: DisplayValueType.DWORD
                value = searchResultItem.value
            }
            else -> {
                Log.e(TAG, "Unsupported SearchResultItem type")
                return
            }
        }

        // 设置标题：修改 [地址] 的值
        val addressHex = String.format("%X", address)
        binding.titleText.text = context.getString(R.string.modify_dialog_title) + " $addressHex"

        // 初始化值类型
        val allValueTypes = DisplayValueType.entries.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        var currentValueType = displayValueType

        fun updateSubtitleRange(type: DisplayValueType) {
            binding.subtitleRange.text = type.rangeDescription
        }

        // 设置初始值
        binding.inputValue.setText(value)
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
                    binding.btnValueType.text = currentValueType.displayName
                    updateSubtitleRange(currentValueType)
                }
            )
        }

        binding.btnConvertBase.setOnClickListener {
            notification.showSuccess(context.getString(R.string.feature_convert_base_todo))
        }

        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val currentText = binding.inputValue.text.toString()
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                val newText = if (selectionStart != selectionEnd) {
                    currentText.take(selectionStart) + key + currentText.substring(selectionEnd)
                } else {
                    currentText.take(selectionStart) + key + currentText.substring(selectionStart)
                }

                binding.inputValue.setText(newText)
                binding.inputValue.setSelection(selectionStart + key.length)
            }

            override fun onDelete() {
                val currentText = binding.inputValue.text.toString()
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                if (selectionStart != selectionEnd) {
                    val newText =
                        currentText.take(selectionStart) + currentText.substring(selectionEnd)
                    binding.inputValue.setText(newText)
                    binding.inputValue.setSelection(selectionStart)
                } else if (selectionStart > 0) {
                    val newText =
                        currentText.take(selectionStart - 1) + currentText.substring(selectionStart)
                    binding.inputValue.setText(newText)
                    binding.inputValue.setSelection(selectionStart - 1)
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
                notification.showSuccess(context.getString(R.string.feature_history_todo))
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val currentText = binding.inputValue.text.toString()
                    val cursorPos = binding.inputValue.selectionStart
                    val newText =
                        currentText.take(cursorPos) + text + currentText.substring(cursorPos)
                    binding.inputValue.setText(newText)
                    binding.inputValue.setSelection(cursorPos + text.length)
                }
            }
        }

        binding.btnGoto.setOnClickListener {
            notification.showSuccess("转到功能待实现")
        }

        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            val newValue = binding.inputValue.text.toString().trim()

            if (newValue.isEmpty()) {
                notification.showError(context.getString(R.string.error_empty_search_value))
                return@setOnClickListener
            }

            onConfirm?.invoke(newValue, currentValueType)
            dialog.dismiss()
        }
    }
}