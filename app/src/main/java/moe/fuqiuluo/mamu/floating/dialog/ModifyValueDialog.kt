package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogModifyValueBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.FuzzySearchResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
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
    private val onConfirm: ((address: Long, oldValue: String, newValue: String, valueType: DisplayValueType) -> Unit)? = null
) : BaseDialog(context) {
    lateinit var value: String
    var address: Long = Long.MIN_VALUE

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

        val displayValueType: DisplayValueType
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
                // 直接操作 Editable，避免 setText 带来的竞争条件
                val editable = binding.inputValue.text ?: return
                val selectionStart = binding.inputValue.selectionStart
                val selectionEnd = binding.inputValue.selectionEnd

                // 使用 Editable.replace() 直接替换选中的文本
                editable.replace(selectionStart, selectionEnd, key)
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
                notification.showSuccess(context.getString(R.string.feature_history_todo))
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

            onConfirm?.invoke(address, value, newValue, currentValueType)
            dialog.dismiss()
        }
    }
}