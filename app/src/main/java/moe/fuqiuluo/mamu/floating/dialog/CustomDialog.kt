package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.databinding.DialogProcessSelectionBinding
import moe.fuqiuluo.mamu.floating.adapter.ProcessListAdapter
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import kotlin.math.max

class CustomDialog(
    context: Context,
    private val title: String = "",
    private val adapter: RecyclerView.Adapter<*>,
): BaseDialog(context) {
    var onItemClick: ((Int) -> Unit)? = null

    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogProcessSelectionBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        // 设置标题
        binding.dialogTitle.text = title

        // 设置 RecyclerView 适配器
        binding.processList.adapter = adapter

        // 设置点击事件（如果是 ProcessListAdapter）
        if (adapter is ProcessListAdapter) {
            adapter.onItemClick = { position ->
                onItemClick?.invoke(position)
                dialog.dismiss()
            }
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }
}

fun Context.customDialog(
    title: String,
    adapter: RecyclerView.Adapter<*>,
    onItemClick: (Int) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val dialog = CustomDialog(
        context = this,
        title = title,
        adapter = adapter,
    )
    dialog.onItemClick = onItemClick
    dialog.onCancel = onCancel
    dialog.show()
}
