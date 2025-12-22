package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.databinding.DialogOffsetXorBinding
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.widget.NotificationOverlay

/**
 * 偏移量计算对话框
 */
class OffsetXorDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val selectedAddresses: List<SavedAddress>
) : BaseDialog(context) {

    // 计算结果
    private var baseAddress: Long = 0L
    private var xorResult: Long = 0L
    private var offsetEntries: List<OffsetEntry> = emptyList()

    data class OffsetEntry(
        val address: Long,
        val rangeName: String,
        val offset: Long,
        val delta: Long?  // 第一个地址没有增量
    )

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogOffsetXorBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 计算偏移量
        calculateOffsets()

        // 显示基址
        binding.textBaseAddress.text = "0x${baseAddress.toString(16).uppercase()}"

        // 显示异或结果
        binding.textXorResult.text = "0x${xorResult.toString(16).uppercase()}"

        // 显示统计信息
        val rangeCount = offsetEntries.map { it.rangeName }.distinct().size
        binding.textStats.text = "共 ${offsetEntries.size} 个地址，分布在 $rangeCount 个内存范围"

        // 设置 RecyclerView
        binding.offsetList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = OffsetAdapter(offsetEntries)
            setHasFixedSize(true)
            // 禁用动画提高性能
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        // 复制按钮
        binding.btnCopy.setOnClickListener {
            copyResultsToClipboard()
        }

        // 关闭按钮
        binding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun calculateOffsets() {
        if (selectedAddresses.isEmpty()) return

        // 按地址排序
        val sortedAddresses = selectedAddresses.sortedBy { it.address }

        // 基址是最小的地址
        baseAddress = sortedAddresses.first().address

        // 计算偏移量和增量
        val entries = mutableListOf<OffsetEntry>()
        var previousAddress = baseAddress

        sortedAddresses.forEachIndexed { index, addr ->
            val offset = addr.address - baseAddress
            val delta = if (index == 0) null else addr.address - previousAddress

            entries.add(
                OffsetEntry(
                    address = addr.address,
                    rangeName = addr.range.code,
                    offset = offset,
                    delta = delta
                )
            )
            previousAddress = addr.address
        }

        offsetEntries = entries

        // 计算所有偏移量的异或值（跳过第一个，因为它的偏移是0）
        xorResult = entries.drop(1).fold(0L) { acc, entry -> acc xor entry.offset }
    }

    /**
     * RecyclerView Adapter
     */
    private inner class OffsetAdapter(
        private val entries: List<OffsetEntry>
    ) : RecyclerView.Adapter<OffsetAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val rangeText: TextView = itemView.findViewWithTag("range")
            val addressText: TextView = itemView.findViewWithTag("address")
            val offsetText: TextView = itemView.findViewWithTag("offset")
            val deltaText: TextView = itemView.findViewWithTag("delta")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(4, 8, 4, 8)
            }

            // 范围名称
            val rangeText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
                textSize = 11f
                setTextColor(0xFF81C784.toInt())
                tag = "range"
            }
            itemLayout.addView(rangeText)

            // 地址
            val addressText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                textSize = 12f
                setTextColor(0xFF64B5F6.toInt())
                typeface = Typeface.MONOSPACE
                tag = "address"
            }
            itemLayout.addView(addressText)

            // 偏移量
            val offsetText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.MONOSPACE
                tag = "offset"
            }
            itemLayout.addView(offsetText)

            // 增量
            val deltaText = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                tag = "delta"
            }
            itemLayout.addView(deltaText)

            return ViewHolder(itemLayout)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            // 范围
            holder.rangeText.text = entry.rangeName
            holder.rangeText.setOnClickListener {
                copyToClipboard(entry.rangeName, "范围")
            }

            // 地址
            val addressValue = "0x${entry.address.toString(16).uppercase()}"
            holder.addressText.text = addressValue
            holder.addressText.setOnClickListener {
                copyToClipboard(addressValue, "地址")
            }

            // 偏移量
            val offsetValue = if (entry.offset == 0L) "0" else "0x${entry.offset.toString(16).uppercase()}"
            holder.offsetText.text = if (entry.offset == 0L) "0" else "+0x${entry.offset.toString(16).uppercase()}"
            holder.offsetText.setOnClickListener {
                copyToClipboard(offsetValue, "偏移量")
            }

            // 增量
            val deltaValue = entry.delta?.let { "0x${it.toString(16).uppercase()}" }
            holder.deltaText.text = entry.delta?.let { "+0x${it.toString(16).uppercase()}" } ?: "-"
            holder.deltaText.setTextColor(
                if (entry.delta != null) 0xFFFFAB00.toInt() else 0xFF888888.toInt()
            )
            holder.deltaText.setOnClickListener(
                if (deltaValue != null) {
                    View.OnClickListener { copyToClipboard(deltaValue, "增量") }
                } else null
            )
        }

        override fun getItemCount(): Int = entries.size
    }

    private fun copyToClipboard(value: String, label: String) {
        val clip = ClipData.newPlainText(label, value)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("已复制$label: $value")
    }

    private fun copyResultsToClipboard() {
        val sb = StringBuilder()
        sb.appendLine("=== 偏移量计算结果 ===")
        sb.appendLine("基址: 0x${baseAddress.toString(16).uppercase()}")
        sb.appendLine("偏移XOR: 0x${xorResult.toString(16).uppercase()}")
        sb.appendLine()
        sb.appendLine("范围\t\t地址\t\t\t偏移量\t\t增量")
        sb.appendLine("-".repeat(60))

        offsetEntries.forEach { entry ->
            val addr = "0x${entry.address.toString(16).uppercase()}"
            val offset = if (entry.offset == 0L) "0" else "+0x${entry.offset.toString(16).uppercase()}"
            val delta = entry.delta?.let { "+0x${it.toString(16).uppercase()}" } ?: "-"
            sb.appendLine("${entry.rangeName}\t\t$addr\t\t$offset\t\t$delta")
        }

        val clip = ClipData.newPlainText("offset_xor_result", sb.toString())
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("已复制到剪贴板")
    }
}
