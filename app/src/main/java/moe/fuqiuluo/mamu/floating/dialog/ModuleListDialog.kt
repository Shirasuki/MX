package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.databinding.DialogModuleListBinding
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard

/**
 * 模块内存列表对话框
 * 用于显示进程的模块内存列表，点击后跳转到对应地址
 */
class ModuleListDialog(
    context: Context,
    private val modules: List<DisplayMemRegionEntry>,
    private val onModuleSelected: (DisplayMemRegionEntry) -> Unit
) : BaseDialog(context) {

    private var currentFocusedInput: EditText? = null

    // 历史记录（静态保存，跨实例共享）
    companion object {
        private val addressHistory = mutableListOf<HistoryEntry>()
        private const val MAX_HISTORY_SIZE = 50
    }

    data class HistoryEntry(
        val address: Long,
        val moduleName: String,
        val rangeType: MemoryRange
    )

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogModuleListBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 配置键盘方向
        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 根据配置决定是否禁用系统输入法
        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.inputAddress.showSoftInputOnFocus = false
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.inputAddress.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        // 设置输入框焦点
        currentFocusedInput = binding.inputAddress
        binding.inputAddress.requestFocus()

        // 设置展示按钮（跳转到地址对应模块）
        binding.btnShowModule.setOnClickListener {
            val input = binding.inputAddress.text.toString().trim()
            if (input.isNotEmpty()) {
                showModuleAtAddress(input)
            }
        }

        // 设置历史按钮
        binding.btnHistory.setOnClickListener {
            showHistoryPopup()
        }

        // 设置类型筛选按钮
        setupTypeFilterButtons(binding)

        // 设置ALL按钮
        binding.btnAll.setOnClickListener {
            showAllModulesPopup()
        }

        // 设置键盘监听器
        setupBuiltinKeyboard(binding)

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }

        // 转到按钮
        binding.btnGoto.setOnClickListener {
            val input = binding.inputAddress.text.toString().trim()
            if (input.isNotEmpty()) {
                gotoAddress(input)
            }
        }
    }

    private fun setupTypeFilterButtons(binding: DialogModuleListBinding) {
        val filterButtons = mapOf(
            binding.btnFilterCd to MemoryRange.Cd,
            binding.btnFilterCb to MemoryRange.Cb,
            binding.btnFilterPs to MemoryRange.Ps,
            binding.btnFilterXa to MemoryRange.Xa
        )

        filterButtons.forEach { (button, range) ->
            button.setOnClickListener {
                showFilteredModulesPopup(range)
            }
        }
    }

    private fun showFilteredModulesPopup(range: MemoryRange) {
        val filteredModules = modules.filter { it.range == range }
        val title = "${range.code} - ${range.displayName}"
        
        ModuleListPopupDialog(context, title, filteredModules) { selectedModule ->
            addToHistory(selectedModule)
            onModuleSelected(selectedModule)
            dialog.dismiss()
        }.show()
    }

    private fun showAllModulesPopup() {
        ModuleListPopupDialog(context, "全部模块", modules) { selectedModule ->
            addToHistory(selectedModule)
            onModuleSelected(selectedModule)
            dialog.dismiss()
        }.show()
    }

    private fun showHistoryPopup() {
        if (addressHistory.isEmpty()) {
            return
        }

        // 将历史记录转换为模块列表
        val historyModules = addressHistory.mapNotNull { entry ->
            modules.find { it.start <= entry.address && entry.address < it.end }
        }.distinctBy { it.start }

        if (historyModules.isEmpty()) {
            return
        }

        ModuleListPopupDialog(context, "历史记录", historyModules) { selectedModule ->
            addToHistory(selectedModule)
            onModuleSelected(selectedModule)
            dialog.dismiss()
        }.show()
    }

    private fun showModuleAtAddress(input: String) {
        try {
            val address = input.toLong(16)
            val targetModule = modules.find { it.start <= address && address < it.end }
            if (targetModule != null) {
                // 显示该模块附近的模块列表
                val targetIndex = modules.indexOf(targetModule)
                val startIndex = (targetIndex - 10).coerceAtLeast(0)
                val endIndex = (targetIndex + 10).coerceAtMost(modules.size)
                val nearbyModules = modules.subList(startIndex, endIndex)
                
                ModuleListPopupDialog(context, "地址 ${input.uppercase()} 附近", nearbyModules) { selectedModule ->
                    addToHistory(selectedModule)
                    onModuleSelected(selectedModule)
                    dialog.dismiss()
                }.show()
            }
        } catch (e: Exception) {
            // 地址格式错误
        }
    }

    private fun gotoAddress(input: String) {
        try {
            val address = input.toLong(16)
            val targetModule = modules.find { it.start <= address && address < it.end }
            if (targetModule != null) {
                addToHistory(targetModule)
                onModuleSelected(targetModule)
                dialog.dismiss()
            } else {
                // 创建虚拟模块用于跳转
                val virtualModule = DisplayMemRegionEntry(
                    start = address,
                    end = address + 0x1000,
                    type = 0,
                    name = "直接跳转",
                    range = MemoryRange.O
                )
                onModuleSelected(virtualModule)
                dialog.dismiss()
            }
        } catch (e: Exception) {
            // 地址格式错误
        }
    }

    private fun addToHistory(module: DisplayMemRegionEntry) {
        val entry = HistoryEntry(module.start, module.name, module.range)
        addressHistory.removeAll { it.address == entry.address }
        addressHistory.add(0, entry)
        if (addressHistory.size > MAX_HISTORY_SIZE) {
            addressHistory.removeAt(addressHistory.lastIndex)
        }
    }

    private fun setupBuiltinKeyboard(binding: DialogModuleListBinding) {
        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd
                editable.replace(selectionStart, selectionEnd, key)
            }

            override fun onDelete() {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                if (selectionStart != selectionEnd) {
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    editable.delete(selectionStart - 1, selectionStart)
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
                showHistoryPopup()
            }

            override fun onPaste() {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboardManager?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val input = currentFocusedInput ?: return
                    val editable = input.text ?: return
                    val selectionStart = input.selectionStart
                    val selectionEnd = input.selectionEnd
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }
    }
}
