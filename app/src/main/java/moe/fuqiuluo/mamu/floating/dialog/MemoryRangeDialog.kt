package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.ListView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.databinding.DialogMultiChoiceBinding
import moe.fuqiuluo.mamu.floating.adapter.MemoryRangeAdapter
import moe.fuqiuluo.mamu.floating.ext.floatingOpacity
import moe.fuqiuluo.mamu.floating.model.MemoryRange
import moe.fuqiuluo.mamu.floating.dialog.BaseDialog
import kotlin.math.max

class MemoryRangeDialog(
    context: Context,
    private val memoryRanges: Array<MemoryRange>,
    private val checkedItems: BooleanArray,
    private val memorySizes: Map<MemoryRange, Long>? = null,
    private val defaultCheckedItems: BooleanArray? = null,
): BaseDialog(context) {
    var onMultiChoice: ((BooleanArray) -> Unit)? = null
    private var adapter: MemoryRangeAdapter? = null

    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogMultiChoiceBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        // 设置标题
        binding.dialogTitle.text = "选择内存范围"

        adapter = MemoryRangeAdapter(context, memoryRanges, checkedItems, memorySizes)

        // 设置列表
        binding.optionList.adapter = adapter
        binding.optionList.choiceMode = ListView.CHOICE_MODE_NONE // 自定义 Adapter 自己管理选中状态

        // 显示重置按钮（如果提供了默认值）
        if (defaultCheckedItems != null) {
            binding.btnReset.visibility = android.view.View.VISIBLE
            binding.btnReset.setOnClickListener {
                resetToDefault()
            }
        }

        // 确定按钮
        binding.btnOk.setOnClickListener {
            val newCheckedItems = adapter?.getCheckedItems() ?: checkedItems
            onMultiChoice?.invoke(newCheckedItems)
            dialog.dismiss()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun resetToDefault() {
        if (defaultCheckedItems == null) return

        // 恢复到默认状态
        for (i in checkedItems.indices) {
            checkedItems[i] = defaultCheckedItems[i]
        }

        // 通知适配器刷新
        adapter?.notifyDataSetChanged()
    }
}