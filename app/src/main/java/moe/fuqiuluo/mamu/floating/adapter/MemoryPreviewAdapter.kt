package moe.fuqiuluo.mamu.floating.adapter

import android.annotation.SuppressLint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import moe.fuqiuluo.mamu.databinding.ItemMemoryPreviewBinding
import moe.fuqiuluo.mamu.databinding.ItemMemoryPreviewNavigationBinding
import moe.fuqiuluo.mamu.floating.data.model.MemoryPreviewItem

class MemoryPreviewAdapter(
    private val onRowClick: (Long) -> Unit = {},
    private val onNavigationClick: (Long, Boolean) -> Unit = { _, _ -> },
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<MemoryPreviewItem>()

    // 选中的地址集合（多选）- 使用 fastutil 优化内存
    private val selectedAddresses = LongOpenHashSet()

    companion object {
        const val VIEW_TYPE_MEMORY_ROW = 0
        const val VIEW_TYPE_NAVIGATION = 1

        // 结构性变化阈值
        private const val STRUCTURAL_CHANGE_RATIO = 0.1 // 20%
        private const val STRUCTURAL_CHANGE_MIN_DIFF = 200

        private const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }

    init {
        setHasStableIds(true)
    }

    /**
     * 切换某个地址的选中状态
     */
    private fun toggleSelection(address: Long) {
        if (selectedAddresses.contains(address)) {
            selectedAddresses.remove(address)
        } else {
            selectedAddresses.add(address)
        }
        onSelectionChanged(selectedAddresses.size)
    }

    /**
     * 检查某个地址是否被选中
     */
    private fun isAddressSelected(address: Long): Boolean {
        return selectedAddresses.contains(address)
    }

    /**
     * 清空选中状态
     */
    fun clearSelection() {
        if (selectedAddresses.isEmpty()) return
        selectedAddresses.clear()
        notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(0)
    }

    /**
     * 获取选中的地址列表
     */
    fun getSelectedAddresses(): LongArray {
        return selectedAddresses.toLongArray()
    }

    /**
     * 获取选中数量
     */
    fun getSelectedCount(): Int = selectedAddresses.size

    fun setItems(newItems: List<MemoryPreviewItem>) {
        val oldSize = items.size
        val newSize = newItems.size
        val sizeDiff = kotlin.math.abs(newSize - oldSize)

        // 检测结构性变化：行数变化超过 20% 或超过 200 行
        // 这种情况下 DiffUtil 计算成本高但复用率低，直接全量刷新更快
        val isStructuralChange = oldSize == 0 ||
                sizeDiff > oldSize * STRUCTURAL_CHANGE_RATIO ||
                sizeDiff > STRUCTURAL_CHANGE_MIN_DIFF

        if (isStructuralChange) {
            items.clear()
            items.addAll(newItems)
            // 清空选中状态（因为地址可能已经变了）
            selectedAddresses.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
            return
        }

        // 小变化用 DiffUtil 增量更新
        val diffCallback = MemoryPreviewDiffCallback(items, newItems)
        val diffResult = DiffUtil.calculateDiff(diffCallback, false)
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is MemoryPreviewItem.MemoryRow -> VIEW_TYPE_MEMORY_ROW
            is MemoryPreviewItem.PageNavigation -> VIEW_TYPE_NAVIGATION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MEMORY_ROW -> {
                val binding = ItemMemoryPreviewBinding.inflate(inflater, parent, false)
                MemoryRowViewHolder(binding)
            }

            VIEW_TYPE_NAVIGATION -> {
                val binding = ItemMemoryPreviewNavigationBinding.inflate(inflater, parent, false)
                NavigationViewHolder(binding)
            }

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MemoryPreviewItem.MemoryRow -> {
                (holder as MemoryRowViewHolder).bind(item)
            }

            is MemoryPreviewItem.PageNavigation -> {
                (holder as NavigationViewHolder).bind(item)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            for (payload in payloads) {
                if (payload == PAYLOAD_SELECTION_CHANGED) {
                    if (holder is MemoryRowViewHolder) {
                        val item = items[position] as? MemoryPreviewItem.MemoryRow
                        item?.let { holder.updateSelection(it.address) }
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        return when (val item = items[position]) {
            is MemoryPreviewItem.MemoryRow -> item.address
            is MemoryPreviewItem.PageNavigation -> {
                if (item.isNext) Long.MAX_VALUE else Long.MIN_VALUE
            }
        }
    }

    inner class MemoryRowViewHolder(
        private val binding: ItemMemoryPreviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // 每个 ViewHolder 复用自己的 SpannableStringBuilder
        private val spanBuilder = SpannableStringBuilder()

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = items[position] as? MemoryPreviewItem.MemoryRow
                    item?.let {
                        onRowClick(it.address)
                    }
                }
            }
        }

        fun bind(item: MemoryPreviewItem.MemoryRow) {
            // 复用 spanBuilder 构建显示文本
            spanBuilder.clear()
            spanBuilder.clearSpans()

            // 添加地址（至少8个字符，不够用0填充）
            val addressStart = 0
            spanBuilder.append(item.address.toString(16).uppercase().padStart(8, '0'))
            val addressEnd = spanBuilder.length

            spanBuilder.setSpan(
                ForegroundColorSpan(0xFF57D05B.toInt()),
                addressStart,
                addressEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 分隔符
            spanBuilder.append("  ")

            // 添加值
            item.formattedValues.forEachIndexed { index, formattedValue ->
                if (index > 0) {
                    spanBuilder.append("; ")
                }

                val start = spanBuilder.length
                spanBuilder.append(formattedValue.value)
                if (formattedValue.format.appendCode) {
                    spanBuilder.append(formattedValue.format.code)
                }
                val end = spanBuilder.length

                val color = formattedValue.color ?: formattedValue.format.textColor
                spanBuilder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            binding.contentText.text = spanBuilder

            // 显示内存范围标识和颜色
            if (item.memoryRange != null) {
                binding.rangeText.text = item.memoryRange.code
                binding.rangeText.setTextColor(item.memoryRange.color)
            } else {
                binding.rangeText.text = ""
            }

            // Checkbox 选中状态
            val isSelected = isAddressSelected(item.address)
            binding.checkbox.apply {
                setOnCheckedChangeListener(null) // 先移除监听器避免触发
                isChecked = isSelected
                setOnCheckedChangeListener { _, isChecked ->
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                        val currentItem = items.getOrNull(pos) as? MemoryPreviewItem.MemoryRow
                        currentItem?.let {
                            toggleSelection(it.address)
                            updateItemBackground(isChecked)
                        }
                    }
                }
            }

            // 设置高亮背景（选中优先级高于跳转高亮）
            if (isSelected) {
                binding.itemContainer.setBackgroundColor(0x33448AFF)
            } else if (item.isHighlighted) {
                binding.itemContainer.setBackgroundColor(0x50b1d3b0)
            } else {
                binding.itemContainer.background = null
            }
        }

        fun updateSelection(address: Long) {
            val isSelected = isAddressSelected(address)
            binding.checkbox.apply {
                setOnCheckedChangeListener(null)
                isChecked = isSelected
                setOnCheckedChangeListener { _, isChecked ->
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                        val currentItem = items.getOrNull(pos) as? MemoryPreviewItem.MemoryRow
                        currentItem?.let {
                            toggleSelection(it.address)
                            updateItemBackground(isChecked)
                        }
                    }
                }
            }
            updateItemBackground(isSelected)
        }

        private fun updateItemBackground(isSelected: Boolean) {
            val item = items.getOrNull(bindingAdapterPosition) as? MemoryPreviewItem.MemoryRow
            // 选中优先级高于跳转高亮
            if (isSelected) {
                binding.itemContainer.setBackgroundColor(0x33448AFF)
            } else if (item?.isHighlighted == true) {
                binding.itemContainer.setBackgroundColor(0x50b1d3b0)
            } else {
                binding.itemContainer.background = null
            }
        }
    }

    inner class NavigationViewHolder(
        private val binding: ItemMemoryPreviewNavigationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = items[position] as? MemoryPreviewItem.PageNavigation
                    item?.let { onNavigationClick(it.targetAddress, it.isNext) }
                }
            }
        }

        @SuppressLint("SetTextI18n")
        fun bind(item: MemoryPreviewItem.PageNavigation) {
            val formattedAddress = item.targetAddress.toString(16).uppercase().padStart(8, '0')
            if (item.isNext) {
                binding.navigationText.text = "下一页 → $formattedAddress"
            } else {
                binding.navigationText.text = "← 上一页 $formattedAddress"
            }
        }
    }

    private class MemoryPreviewDiffCallback(
        private val oldList: List<MemoryPreviewItem>,
        private val newList: List<MemoryPreviewItem>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem is MemoryPreviewItem.MemoryRow && newItem is MemoryPreviewItem.MemoryRow ->
                    oldItem.address == newItem.address

                oldItem is MemoryPreviewItem.PageNavigation && newItem is MemoryPreviewItem.PageNavigation ->
                    oldItem.isNext == newItem.isNext

                else -> false
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return when {
                oldItem is MemoryPreviewItem.MemoryRow && newItem is MemoryPreviewItem.MemoryRow ->
                    oldItem.address == newItem.address &&
                            oldItem.isHighlighted == newItem.isHighlighted &&
                            oldItem.memoryRange == newItem.memoryRange &&
                            oldItem.formattedValues == newItem.formattedValues

                oldItem is MemoryPreviewItem.PageNavigation && newItem is MemoryPreviewItem.PageNavigation ->
                    oldItem.targetAddress == newItem.targetAddress

                else -> false
            }
        }
    }
}
