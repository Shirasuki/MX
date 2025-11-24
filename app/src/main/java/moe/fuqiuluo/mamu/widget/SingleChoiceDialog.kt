package moe.fuqiuluo.mamu.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ListView
import moe.fuqiuluo.mamu.databinding.DialogSingleChoiceBinding
import moe.fuqiuluo.mamu.floating.dialog.BaseDialog

fun Context.simpleSingleChoiceDialog(
    title: String = "",
    options: Array<String>,
    onSingleChoice: (Int) -> Unit,
    onCancel: (() -> Unit)? = null,
    selected: Int = 0,
    icons: Array<Int>? = null,
    showTitle: Boolean = true,
    showRadioButton: Boolean = true,
    textColors: Array<Int>? = null,
) {
    val dialog = SimpleSingleChoiceDialog(
        context = this,
        title = title,
        options = options,
        selected = selected,
        icons = icons,
        showTitle = showTitle,
        showRadioButton = showRadioButton,
        textColors = textColors,
    )
    dialog.onSingleChoice = onSingleChoice
    dialog.onCancel = onCancel
    dialog.show()
}

class SimpleSingleChoiceDialog(
    context: Context,
    private var title: String = "",
    private var options: Array<String> = emptyArray(),
    private var selected: Int = 0,
    private val icons: Array<Int>? = null,
    private val showTitle: Boolean = true,
    private val showRadioButton: Boolean = true,
    private val textColors: Array<Int>? = null,
): BaseDialog(context) {
    var onSingleChoice: ((Int) -> Unit)? = null

    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogSingleChoiceBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        if (showTitle) {
            binding.titleLayout.visibility = View.VISIBLE
            binding.dialogTitle.text = title
        } else {
            binding.titleLayout.visibility = View.GONE
        }

        // 设置列表
        val adapter = SingleChoiceAdapter(context, options, selected, icons, showRadioButton, textColors)
        binding.optionList.adapter = adapter
        binding.optionList.choiceMode = ListView.CHOICE_MODE_SINGLE

        binding.optionList.setOnItemClickListener { _, _, position, _ ->
            adapter.setSelectedPosition(position)
            onSingleChoice?.invoke(position)
            dialog.dismiss()
        }

        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }
}