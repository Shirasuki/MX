package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.ImageViewCompat
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.databinding.DialogRemoveOptionsBinding
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import kotlin.math.max

class RemoveOptionsDialog(
    context: Context,
    private val selectedCount: Int,
    private val showTitleIcon: Boolean = false
) : BaseDialog(context) {

    var onRemoveAll: (() -> Unit)? = null
    var onRestoreAndRemove: (() -> Unit)? = null
    var onRemoveSelected: (() -> Unit)? = null

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        // 使用 dialog.context 确保使用正确的主题
        val binding = DialogRemoveOptionsBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.floatingOpacity
        binding.rootContainer.background?.alpha = (max(opacity, 0.85f) * 255).toInt()

        if (showTitleIcon) {
            binding.iconTitle.visibility = View.VISIBLE
            // 设置标题图标颜色
            ImageViewCompat.setImageTintList(
                binding.iconTitle,
                ColorStateList.valueOf(0xFFFFFFFF.toInt())
            )
        } else {
            binding.iconTitle.visibility = View.GONE
        }

        // 移除全部选项 - 始终显示
        binding.optionRemoveAll.setOnClickListener {
            onRemoveAll?.invoke()
            dialog.dismiss()
        }

        binding.optionRestoreAndRemove.visibility = View.VISIBLE
        binding.textRestoreAndRemove.text = "恢复并移除 ($selectedCount)"

        binding.optionRemoveSelected.visibility = View.VISIBLE
        binding.textRemoveSelected.text = "移除 ($selectedCount)"

        binding.optionRestoreAndRemove.setOnClickListener {
            onRestoreAndRemove?.invoke()
            dialog.dismiss()
        }

        binding.optionRemoveSelected.setOnClickListener {
            onRemoveSelected?.invoke()
            dialog.dismiss()
        }

        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }
}