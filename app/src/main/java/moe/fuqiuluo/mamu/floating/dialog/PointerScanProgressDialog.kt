package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogSearchProgressBinding
import moe.fuqiuluo.mamu.driver.PointerScanner

/**
 * 指针扫描进度对话框
 */
class PointerScanProgressDialog(
    context: Context,
    private val onCancelClick: () -> Unit = {},
    private val onHideClick: () -> Unit = {}
) : BaseDialog(context) {

    private lateinit var binding: DialogSearchProgressBinding

    override fun setupDialog() {
        binding = DialogSearchProgressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        binding.progressTitle.text = context.getString(R.string.pointer_scan_dialog_title)
        binding.tvCounter.text = "扫描阶段:"
        binding.tvRegions.text = "就绪"
        binding.tvResults.text = "0"

        binding.btnCancel.setOnClickListener {
            onCancelClick()
            dialog.dismiss()
        }

        binding.btnHide.setOnClickListener {
            onHideClick()
            dialog.dismiss()
        }
    }

    /**
     * 更新进度显示
     */
    fun updateProgress(phase: Int, progress: Int, pointersFound: Long, chainsFound: Long) {
        binding.progressBar.progress = progress
        binding.tvProgress.text = "$progress%"

        val phaseText = when (phase) {
            PointerScanner.Phase.SCANNING_POINTERS -> "扫描指针中... ($pointersFound)"
            PointerScanner.Phase.BUILDING_CHAINS -> "构建链中..."
            PointerScanner.Phase.COMPLETED -> "完成"
            PointerScanner.Phase.CANCELLED -> "已取消"
            PointerScanner.Phase.ERROR -> "错误"
            else -> "就绪"
        }
        binding.tvRegions.text = phaseText
        binding.tvResults.text = chainsFound.toString()
    }
}
