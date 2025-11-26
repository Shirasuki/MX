package moe.fuqiuluo.mamu.floating.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import moe.fuqiuluo.mamu.databinding.ItemSearchResultBinding
import moe.fuqiuluo.mamu.driver.ExactSearchResultItem
import moe.fuqiuluo.mamu.driver.SearchResultItem
import moe.fuqiuluo.mamu.floating.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.model.MemoryRange

class SearchResultAdapter(
    // 点击事件回调
    private val onItemClick: (SearchResultItem, Int) -> Unit = { _, _ -> },
    // 长按事件回调
    private val onItemLongClick: (SearchResultItem, Int) -> Boolean = { _, _ -> false },
    // 选中状态变化回调
    private val onSelectionChanged: (Int) -> Unit = {},
    // 删除项回调
    private val onItemDelete: (SearchResultItem) -> Unit = { _ -> }
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {
    // 搜索结果列表
    private val results = mutableListOf<SearchResultItem>()
    // 选中位置集合
    private val selectedPositions = mutableSetOf<Int>()
    // 内存范围列表 (保存主要是为了显示内存范围简称和颜色)
    private var ranges: List<DisplayMemRegionEntry>? = null

    init {
        // 启用稳定ID，提升RecyclerView刷新性能
        setHasStableIds(true)
    }

    /**
     * 设置搜索结果列表
     * @param newResults 新的搜索结果列表
     */
    fun setResults(newResults: List<SearchResultItem>) {
        val oldSize = results.size
        results.clear()
        selectedPositions.clear()

        if (oldSize > 0) {
            // 执行一个带动画的移除动画？有没有必要呢？
            notifyItemRangeRemoved(0, oldSize)
        }

        results.addAll(newResults)
        if (newResults.isNotEmpty()) {
            notifyItemRangeInserted(0, newResults.size)
        }

        onSelectionChanged(0) // 通知选中状态变化
    }

    /**
     * 获取所有选中项的原生地址数组
     * @return 选中项的原生地址数组
     */
    fun getNativePositions(): LongArray {
        return selectedPositions.map { results[it].nativePosition }.toLongArray()
    }

    /**
     * 设置内存范围列表
     * @param newRanges 新的内存范围列表
     */
    fun setRanges(newRanges: List<DisplayMemRegionEntry>?) {
        ranges = newRanges
    }

    /**
     * 获取内存范围列表
     * @return 内存范围列表
     */
    fun getRanges(): List<DisplayMemRegionEntry>? {
        return ranges
    }

    /**
     * 添加搜索结果
     * @param newResults 新的搜索结果列表
     */
    fun addResults(newResults: List<SearchResultItem>) {
        val oldSize = results.size
        results.addAll(newResults)
        notifyItemRangeInserted(oldSize, newResults.size)
    }

    /**
     * 清空搜索结果
     */
    fun clearResults() {
        val oldSize = results.size
        results.clear()
        selectedPositions.clear()
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }
        onSelectionChanged(0)
    }

    /**
     * 获取所有选中项
     * @return 选中项列表
     */
    fun getSelectedItems(): List<SearchResultItem> {
        return selectedPositions.map { results[it] }
    }

    /**
     * 获取所有选中位置
     * @return 选中位置集合
     */
    fun getSelectedPositions(): Set<Int> {
        return selectedPositions.toSet()
    }

    /**
     * 全选 - 使用notifyDataSetChanged避免遍历大量item
     */
    fun selectAll() {
        if (selectedPositions.size == results.size) {
            // 已经全选，无需操作
            return
        }

        selectedPositions.clear()
        selectedPositions.addAll(results.indices)

        // 大数据量时，notifyDataSetChanged只刷新可见区域，反而比notifyItemRangeChanged更快
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
    }

    /**
     * 全不选 - 使用notifyDataSetChanged避免遍历大量item
     */
    fun deselectAll() {
        if (selectedPositions.isEmpty()) {
            // 已经是全不选状态，直接返回
            return
        }

        selectedPositions.clear()
        // 大数据量时，notifyDataSetChanged只刷新可见区域，反而比notifyItemRangeChanged更快
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /**
     * 反选 - 优化版本，避免创建大型临时集合
     */
    fun invertSelection() {
        val totalSize = results.size

        // 避免创建 (0 until totalSize).toSet() 这样的大集合
        // 遍历所有索引，在集合中的删除，不在的添加
        val newSelection = hashSetOf<Int>()
        for (i in 0 until totalSize) {
            if (i !in selectedPositions) {
                newSelection.add(i)
            }
        }

        selectedPositions.clear()
        selectedPositions.addAll(newSelection)

        // 大数据量时，notifyDataSetChanged只刷新可见区域，反而比notifyItemRangeChanged更快
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
    }

    companion object {
        private const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(results[position], position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            for (payload in payloads) {
                if (payload == PAYLOAD_SELECTION_CHANGED) {
                    holder.updateSelection(position)
                }
            }
        }
    }

    override fun getItemCount(): Int = results.size

    override fun getItemId(position: Int): Long {
        // 使用nativePosition作为稳定ID，帮助RecyclerView优化刷新性能
        return results[position].nativePosition
    }

    inner class ViewHolder(
        private val binding: ItemSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SearchResultItem, position: Int) {
            when (item) {
                is ExactSearchResultItem -> {
                    binding.apply {
                        // 地址
                        addressText.text = item.address.toString(16).uppercase()

                        // 当前值
                        valueText.text = item.value

                        // 类型简称和颜色
                        val valueType = item.displayValueType ?: DisplayValueType.DWORD
                        typeText.apply {
                            text = valueType.code
                            setTextColor(valueType.textColor)
                        }

                        // 内存范围简称和颜色
                        val memoryRange = ranges?.firstOrNull { it.containsAddress(item.address) }?.range
                            ?: MemoryRange.O
                        rangeText.apply {
                            text = memoryRange.code
                            setTextColor(memoryRange.color)
                        }
                    }
                }

                else -> {
                    binding.apply {
                        addressText.text = "0"
                        valueText.text = "0"
                        typeText.text = "?"
                        rangeText.text = "?"
                    }
                }
            }

            // Checkbox选中状态
            val isSelected = position in selectedPositions
            binding.checkbox.apply {
                setOnCheckedChangeListener(null) // 先移除监听器避免触发
                isChecked = isSelected
                setOnCheckedChangeListener { _, isChecked ->
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                        if (isChecked) selectedPositions.add(pos) else selectedPositions.remove(pos)
                        updateItemBackground(isChecked)
                        onSelectionChanged(selectedPositions.size)
                    }
                }
            }
            updateItemBackground(isSelected)

            // 删除按钮
            binding.deleteButton.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    onItemDelete(results[pos])
                }
            }

            // Item点击事件
            binding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    onItemClick(results[pos], pos)
                }
            }

            // Item长按事件
            binding.root.setOnLongClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    onItemLongClick(results[pos], pos)
                } ?: false
            }
        }

        fun updateSelection(position: Int) {
            val isSelected = position in selectedPositions
            binding.checkbox.apply {
                setOnCheckedChangeListener(null)
                isChecked = isSelected
                updateItemBackground(isSelected)
                setOnCheckedChangeListener { _, isChecked ->
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                        if (isChecked) selectedPositions.add(pos) else selectedPositions.remove(pos)
                        updateItemBackground(isChecked)
                        onSelectionChanged(selectedPositions.size)
                    }
                }
            }
        }

        private fun updateItemBackground(isSelected: Boolean) {
            binding.itemContainer.setBackgroundColor(
                if (isSelected) 0x33448AFF.toInt() else 0x00000000
            )
        }
    }
}