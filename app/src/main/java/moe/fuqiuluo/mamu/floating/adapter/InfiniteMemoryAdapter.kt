package moe.fuqiuluo.mamu.floating.adapter

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import moe.fuqiuluo.mamu.databinding.ItemMemoryPreviewBinding
import moe.fuqiuluo.mamu.databinding.ItemMemoryPreviewNavigationBinding
import moe.fuqiuluo.mamu.driver.Disassembler
import moe.fuqiuluo.mamu.driver.LocalMemoryOps
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.FormattedValue
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.data.model.MemoryPreviewItem
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 无限滚动内存适配器
 * 
 * 核心设计：
 * 1. 虚拟化显示：根据位置计算地址
 * 2. 页面对齐：native 读取时地址必须对齐到 PAGE_SIZE
 * 3. 缓存机制：缓存已读取的页面数据（最多4页）
 * 4. 边界扩展：滚动到边界时自动扩展地址范围（仅无限滚动模式）
 * 5. 预加载：跳转时预加载前、中、后三页
 * 6. 固定页面模式：只显示一页内存，禁用边界扩展
 */
class InfiniteMemoryAdapter(
    private val onRowClick: (MemoryPreviewItem.MemoryRow) -> Unit = {},
    private val onRowLongClick: (MemoryPreviewItem.MemoryRow) -> Boolean = { false },
    private val onSelectionChanged: (Int) -> Unit = {},
    private val onDataRequest: (pageAlignedAddress: Long, callback: (ByteArray?) -> Unit) -> Unit,
    private val onBoundaryReached: ((isTop: Boolean) -> Unit)? = null,
    private val onNavigationClick: (targetAddress: Long, isNext: Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        val PAGE_SIZE = LocalMemoryOps.getPageSize()
        private const val TAG = "InfiniteMemoryAdapter"
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

        private const val MAX_CACHED_PAGES = 4
        private const val BOUNDARY_THRESHOLD = 20  // 距离边界多少行时触发扩展
        private const val BOUNDARY_DEBOUNCE_MS = 200L  // 边界触发防抖时间

        const val VIEW_TYPE_MEMORY_ROW = 0
        const val VIEW_TYPE_NAVIGATION = 1

        const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
        const val PAYLOAD_DATA_UPDATED = "data_updated"
    }

    // 当前显示的起始地址（页面对齐）
    private var baseAddress: Long = 0L
    
    // 当前显示的格式列表
    private var currentFormats: List<MemoryDisplayFormat> = MemoryDisplayFormat.getDefaultFormats()
    
    // 每行的字节对齐
    private var alignment: Int = 4
    
    // 十六进制显示的字节数
    private var hexByteSize: Int = 4
    
    // 总行数
    private var totalRows: Int = 0
    
    // 高亮的地址
    private var highlightAddress: Long? = null
    
    // 选中的地址集合
    private val selectedAddresses = LongOpenHashSet()
    
    // 内存区域列表
    private var memoryRegions: List<DisplayMemRegionEntry> = emptyList()
    
    // 页面数据缓存（LRU）
    private val pageCache = object : LinkedHashMap<Long, ByteArray?>(MAX_CACHED_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray?>): Boolean {
            return size > MAX_CACHED_PAGES
        }
    }
    
    // 正在加载的页面集合
    private val loadingPages = HashSet<Long>()
    
    // 边界触发防抖
    private var lastBoundaryTriggerTime = 0L
    private var isExpandingTop = false
    private var isExpandingBottom = false
    
    // 是否启用无限滚动模式
    private var infiniteScrollEnabled: Boolean = true

    init {
        setHasStableIds(true)
    }

    fun setFormats(formats: List<MemoryDisplayFormat>) {
        currentFormats = formats
        alignment = MemoryDisplayFormat.calculateAlignment(formats)
        hexByteSize = MemoryDisplayFormat.calculateHexByteSize(formats)
        notifyDataSetChanged()
    }

    /**
     * 设置是否启用无限滚动模式
     * @param enabled true 为无限滚动模式，false 为固定页面模式
     */
    fun setInfiniteScrollEnabled(enabled: Boolean) {
        infiniteScrollEnabled = enabled
    }

    /**
     * 获取当前是否启用无限滚动模式
     */
    fun isInfiniteScrollEnabled(): Boolean = infiniteScrollEnabled

    /**
     * 设置地址范围并预加载数据
     */
    fun setAddressRange(address: Long, rowCount: Int) {
        baseAddress = alignToPage(address)
        totalRows = rowCount
        clearCache()
        notifyDataSetChanged()
        
        // 预加载当前范围内的所有页面
        val endAddress = baseAddress + rowCount.toLong() * alignment
        var pageAddr = baseAddress
        while (pageAddr < endAddress) {
            loadPageIfNeeded(pageAddr)
            pageAddr += PAGE_SIZE
        }
    }

    /**
     * 预加载指定地址的前、中、后三页
     */
    fun preloadPages(centerAddress: Long) {
        val centerPage = alignToPage(centerAddress)
        val prevPage = if (centerPage >= PAGE_SIZE) centerPage - PAGE_SIZE else 0L
        val nextPage = centerPage + PAGE_SIZE
        
        // 按顺序加载：当前页优先，然后前后页
        loadPageIfNeeded(centerPage)
        if (prevPage >= 0 && prevPage != centerPage) {
            loadPageIfNeeded(prevPage)
        }
        loadPageIfNeeded(nextPage)
    }

    // 用于延迟通知的 Handler
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 关联的 RecyclerView 引用
    private var recyclerView: RecyclerView? = null
    
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 如果页面未缓存则加载
     */
    private fun loadPageIfNeeded(pageAddress: Long) {
        if (pageAddress < 0) return
        if (pageCache.containsKey(pageAddress)) return
        if (loadingPages.contains(pageAddress)) return
        
        loadingPages.add(pageAddress)
        onDataRequest(pageAddress) { data ->
            loadingPages.remove(pageAddress)
            pageCache[pageAddress] = data
            
            // 延迟通知数据更新，避免在 RecyclerView 布局期间调用
            safeNotifyPageDataChanged(pageAddress)
        }
    }
    
    /**
     * 安全地通知页面数据变化
     * 如果 RecyclerView 正在布局或滚动，则延迟到下一帧执行
     */
    private fun safeNotifyPageDataChanged(pageAddress: Long) {
        val rv = recyclerView
        if (rv != null && (rv.isComputingLayout || rv.isAnimating)) {
            handler.post { notifyPageDataChanged(pageAddress) }
        } else {
            notifyPageDataChanged(pageAddress)
        }
    }

    private fun notifyPageDataChanged(pageAddress: Long) {
        val rowsPerPage = PAGE_SIZE / alignment
        val pageIndex = ((pageAddress - baseAddress) / PAGE_SIZE).toInt()
        val startRow = pageIndex * rowsPerPage
        val endRow = minOf(startRow + rowsPerPage, totalRows)
        
        if (startRow in 0 until totalRows && endRow > startRow) {
            notifyItemRangeChanged(startRow, endRow - startRow, PAYLOAD_DATA_UPDATED)
        }
    }

    /**
     * 向上扩展地址范围（滚动到顶部时调用）
     */
    fun expandTop(pageCount: Int = 1): Boolean {
        if (baseAddress <= 0) return false
        
        val expandSize = PAGE_SIZE.toLong() * pageCount
        val newBaseAddress = maxOf(0L, baseAddress - expandSize)
        if (newBaseAddress == baseAddress) return false
        
        val addedBytes = baseAddress - newBaseAddress
        val addedRows = (addedBytes / alignment).toInt()
        
        baseAddress = newBaseAddress
        totalRows += addedRows
        
        // 预加载新的页面
        for (i in 0 until pageCount) {
            val pageAddr = newBaseAddress + PAGE_SIZE * i
            loadPageIfNeeded(pageAddr)
        }
        
        notifyItemRangeInserted(0, addedRows)
        return true
    }

    /**
     * 向下扩展地址范围（滚动到底部时调用）
     */
    fun expandBottom(pageCount: Int = 1): Boolean {
        val expandSize = PAGE_SIZE.toLong() * pageCount
        val addedRows = (expandSize / alignment).toInt()
        val oldTotalRows = totalRows
        
        totalRows += addedRows
        
        // 预加载新的页面
        val endAddress = baseAddress + oldTotalRows.toLong() * alignment
        for (i in 0 until pageCount) {
            val pageAddr = alignToPage(endAddress) + PAGE_SIZE * i
            loadPageIfNeeded(pageAddr)
        }
        
        notifyItemRangeInserted(oldTotalRows, addedRows)
        return true
    }

    /**
     * 检查是否接近边界，如果是则触发扩展
     * 包含防抖机制，避免频繁触发
     * 注意：固定页面模式下不触发边界扩展
     */
    fun checkBoundary(firstVisiblePosition: Int, lastVisiblePosition: Int) {
        // 固定页面模式下不触发边界扩展
        if (!infiniteScrollEnabled) return
        
        val now = System.currentTimeMillis()
        if (now - lastBoundaryTriggerTime < BOUNDARY_DEBOUNCE_MS) {
            return
        }
        
        // 接近顶部
        if (firstVisiblePosition < BOUNDARY_THRESHOLD && baseAddress > 0 && !isExpandingTop) {
            lastBoundaryTriggerTime = now
            isExpandingTop = true
            onBoundaryReached?.invoke(true)
            isExpandingTop = false
        }
        // 接近底部
        else if (lastVisiblePosition > totalRows - BOUNDARY_THRESHOLD && !isExpandingBottom) {
            lastBoundaryTriggerTime = now
            isExpandingBottom = true
            onBoundaryReached?.invoke(false)
            isExpandingBottom = false
        }
    }

    fun setHighlightAddress(address: Long?) {
        val oldHighlight = highlightAddress
        highlightAddress = address
        
        if (oldHighlight != null) {
            val oldRow = addressToRow(oldHighlight)
            if (oldRow in 0 until totalRows) {
                notifyItemChanged(oldRow, PAYLOAD_DATA_UPDATED)
            }
        }
        if (address != null) {
            val newRow = addressToRow(address)
            if (newRow in 0 until totalRows) {
                notifyItemChanged(newRow, PAYLOAD_DATA_UPDATED)
            }
        }
    }

    fun setMemoryRegions(regions: List<DisplayMemRegionEntry>) {
        memoryRegions = regions
    }

    fun clearCache() {
        pageCache.clear()
        loadingPages.clear()
    }

    fun refreshPage(pageAddress: Long) {
        pageCache.remove(pageAddress)
        loadingPages.remove(pageAddress)
        loadPageIfNeeded(pageAddress)
    }

    fun refreshAll() {
        clearCache()
        notifyDataSetChanged()
    }

    private fun alignToPage(address: Long): Long = (address / PAGE_SIZE) * PAGE_SIZE

    fun rowToAddress(row: Int): Long = baseAddress + row.toLong() * alignment

    fun addressToRow(address: Long): Int = ((address - baseAddress) / alignment).toInt()

    private fun getPageAddress(address: Long): Long = alignToPage(address)

    private fun getPageData(pageAddress: Long): ByteArray? {
        if (pageCache.containsKey(pageAddress)) {
            return pageCache[pageAddress]
        }
        
        if (!loadingPages.contains(pageAddress)) {
            loadPageIfNeeded(pageAddress)
        }
        
        return null
    }

    // ==================== 选择相关方法 ====================

    fun toggleSelection(address: Long) {
        if (selectedAddresses.contains(address)) {
            selectedAddresses.remove(address)
        } else {
            selectedAddresses.add(address)
        }
        onSelectionChanged(selectedAddresses.size)
    }

    fun isAddressSelected(address: Long): Boolean = selectedAddresses.contains(address)

    fun clearSelection() {
        if (selectedAddresses.isEmpty()) return
        selectedAddresses.clear()
        notifyItemRangeChanged(0, totalRows, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(0)
    }

    fun getSelectedAddresses(): LongArray = selectedAddresses.toLongArray()

    fun getSelectedCount(): Int = selectedAddresses.size

    fun selectAddresses(addresses: List<Long>) {
        selectedAddresses.clear()
        selectedAddresses.addAll(addresses)
        notifyItemRangeChanged(0, totalRows, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(selectedAddresses.size)
    }

    fun selectAll() {
        for (i in 0 until totalRows) {
            selectedAddresses.add(rowToAddress(i))
        }
        notifyItemRangeChanged(0, totalRows, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(selectedAddresses.size)
    }

    fun invertSelection() {
        for (i in 0 until totalRows) {
            val address = rowToAddress(i)
            if (selectedAddresses.contains(address)) {
                selectedAddresses.remove(address)
            } else {
                selectedAddresses.add(address)
            }
        }
        notifyItemRangeChanged(0, totalRows, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(selectedAddresses.size)
    }

    // ==================== RecyclerView.Adapter ====================

    override fun getItemCount(): Int {
        return if (infiniteScrollEnabled) {
            totalRows
        } else {
            // 固定页面模式：顶部导航 + 内存行 + 底部导航
            totalRows + 2
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (!infiniteScrollEnabled) {
            when (position) {
                0 -> return VIEW_TYPE_NAVIGATION  // 上一页
                itemCount - 1 -> return VIEW_TYPE_NAVIGATION  // 下一页
            }
        }
        return VIEW_TYPE_MEMORY_ROW
    }

    override fun getItemId(position: Int): Long {
        if (!infiniteScrollEnabled) {
            when (position) {
                0 -> return Long.MIN_VALUE  // 上一页导航
                itemCount - 1 -> return Long.MAX_VALUE  // 下一页导航
            }
            // 内存行位置需要偏移1（因为位置0是上一页导航）
            return rowToAddress(position - 1)
        }
        return rowToAddress(position)
    }

    /**
     * 将adapter位置转换为实际的内存行索引
     * 在非无限滚动模式下需要考虑导航项的偏移
     */
    private fun positionToRowIndex(position: Int): Int {
        return if (infiniteScrollEnabled) {
            position
        } else {
            position - 1  // 减去顶部导航项
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_NAVIGATION -> {
                val binding = ItemMemoryPreviewNavigationBinding.inflate(inflater, parent, false)
                NavigationViewHolder(binding)
            }
            else -> {
                val binding = ItemMemoryPreviewBinding.inflate(inflater, parent, false)
                MemoryRowViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NavigationViewHolder -> {
                val isNext = position == itemCount - 1
                holder.bind(isNext)
            }
            is MemoryRowViewHolder -> {
                val rowIndex = positionToRowIndex(position)
                holder.bind(rowIndex)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            if (holder is MemoryRowViewHolder) {
                val rowIndex = positionToRowIndex(position)
                for (payload in payloads) {
                    when (payload) {
                        PAYLOAD_SELECTION_CHANGED -> holder.updateSelectionOnly(rowIndex)
                        PAYLOAD_DATA_UPDATED -> holder.updateDataOnly(rowIndex)
                    }
                }
            }
        }
    }

    // ==================== ViewHolder ====================

    inner class MemoryRowViewHolder(
        private val binding: ItemMemoryPreviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val spanBuilder = SpannableStringBuilder()
        private var currentAddress: Long = 0L

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onRowClick(createMemoryRow(position))
                }
            }

            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onRowLongClick(createMemoryRow(position))
                } else false
            }
        }

        @SuppressLint("SetTextI18n")
        fun bind(position: Int) {
            currentAddress = rowToAddress(position)
            val pageAddress = getPageAddress(currentAddress)
            val pageData = getPageData(pageAddress)
            
            spanBuilder.clear()
            spanBuilder.clearSpans()

            // 地址
            val addressStart = 0
            spanBuilder.append(currentAddress.toString(16).uppercase().padStart(8, '0'))
            spanBuilder.setSpan(
                ForegroundColorSpan(0xFF57D05B.toInt()),
                addressStart, spanBuilder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spanBuilder.append("  ")

            // 值
            if (pageData != null) {
                val offsetInPage = (currentAddress - pageAddress).toInt()
                val requiredBytes = hexByteSize
                val availableInCurrentPage = pageData.size - offsetInPage
                
                // 检查是否需要跨页读取
                val dataBuffer: ByteArray? = if (offsetInPage >= 0 && availableInCurrentPage >= requiredBytes) {
                    // 当前页面有足够数据
                    pageData.copyOfRange(offsetInPage, offsetInPage + requiredBytes)
                } else if (offsetInPage >= 0 && availableInCurrentPage > 0) {
                    // 需要跨页读取：从当前页和下一页合并数据
                    val nextPageAddress = pageAddress + PAGE_SIZE
                    val nextPageData = getPageData(nextPageAddress)
                    if (nextPageData != null) {
                        val combined = ByteArray(requiredBytes)
                        // 从当前页复制可用数据
                        System.arraycopy(pageData, offsetInPage, combined, 0, availableInCurrentPage)
                        // 从下一页复制剩余数据
                        val remainingBytes = requiredBytes - availableInCurrentPage
                        if (remainingBytes <= nextPageData.size) {
                            System.arraycopy(nextPageData, 0, combined, availableInCurrentPage, remainingBytes)
                            combined
                        } else null
                    } else null
                } else null
                
                if (dataBuffer != null) {
                    val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
                    
                    currentFormats.forEachIndexed { index, format ->
                        if (index > 0) spanBuilder.append("; ")
                        
                        val start = spanBuilder.length
                        val formattedValue = parseValue(buffer, format)
                        spanBuilder.append(formattedValue.value)
                        if (format.appendCode) spanBuilder.append(format.code)
                        
                        spanBuilder.setSpan(
                            ForegroundColorSpan(formattedValue.color ?: format.textColor),
                            start, spanBuilder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        buffer.position(0)
                    }
                } else {
                    appendPlaceholder()
                }
            } else {
                appendPlaceholder()
            }

            binding.contentText.text = spanBuilder

            // 内存范围标识
            val memoryRange = findMemoryRange(currentAddress)
            if (memoryRange != null) {
                binding.rangeText.text = memoryRange.code
                binding.rangeText.setTextColor(memoryRange.color)
            } else {
                binding.rangeText.text = ""
            }

            updateSelectionAndHighlight(position)
        }

        private fun appendPlaceholder() {
            currentFormats.forEachIndexed { index, format ->
                if (index > 0) spanBuilder.append("; ")
                val start = spanBuilder.length
                spanBuilder.append("?")
                if (format.appendCode) spanBuilder.append(format.code)
                spanBuilder.setSpan(
                    ForegroundColorSpan(format.textColor),
                    start, spanBuilder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        /**
         * 只更新选中状态（用于 PAYLOAD_SELECTION_CHANGED）
         * 不重新绑定数据，避免竞态条件
         */
        fun updateSelectionOnly(rowIndex: Int) {
            currentAddress = rowToAddress(rowIndex)
            val isSelected = isAddressSelected(currentAddress)
            val isHighlighted = highlightAddress?.let {
                it >= currentAddress && it < currentAddress + alignment
            } ?: false

            // 更新 checkbox 状态，但不触发监听器
            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = isSelected
            setupCheckboxListener()

            updateBackground(isSelected, isHighlighted)
        }

        /**
         * 只更新数据显示（用于 PAYLOAD_DATA_UPDATED）
         * 不触碰 checkbox，避免竞态条件
         */
        fun updateDataOnly(rowIndex: Int) {
            currentAddress = rowToAddress(rowIndex)
            val pageAddress = getPageAddress(currentAddress)
            val pageData = getPageData(pageAddress)

            spanBuilder.clear()
            spanBuilder.clearSpans()

            // 地址
            val addressStart = 0
            spanBuilder.append(currentAddress.toString(16).uppercase().padStart(8, '0'))
            spanBuilder.setSpan(
                ForegroundColorSpan(0xFF57D05B.toInt()),
                addressStart, spanBuilder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spanBuilder.append("  ")

            // 值
            if (pageData != null) {
                val offsetInPage = (currentAddress - pageAddress).toInt()
                val requiredBytes = hexByteSize
                val availableInCurrentPage = pageData.size - offsetInPage

                val dataBuffer: ByteArray? = if (offsetInPage >= 0 && availableInCurrentPage >= requiredBytes) {
                    pageData.copyOfRange(offsetInPage, offsetInPage + requiredBytes)
                } else if (offsetInPage >= 0 && availableInCurrentPage > 0) {
                    val nextPageAddress = pageAddress + PAGE_SIZE
                    val nextPageData = getPageData(nextPageAddress)
                    if (nextPageData != null) {
                        val combined = ByteArray(requiredBytes)
                        System.arraycopy(pageData, offsetInPage, combined, 0, availableInCurrentPage)
                        val remainingBytes = requiredBytes - availableInCurrentPage
                        if (remainingBytes <= nextPageData.size) {
                            System.arraycopy(nextPageData, 0, combined, availableInCurrentPage, remainingBytes)
                            combined
                        } else null
                    } else null
                } else null

                if (dataBuffer != null) {
                    val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
                    currentFormats.forEachIndexed { index, format ->
                        if (index > 0) spanBuilder.append("; ")
                        val start = spanBuilder.length
                        val formattedValue = parseValue(buffer, format)
                        spanBuilder.append(formattedValue.value)
                        if (format.appendCode) spanBuilder.append(format.code)
                        spanBuilder.setSpan(
                            ForegroundColorSpan(formattedValue.color ?: format.textColor),
                            start, spanBuilder.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        buffer.position(0)
                    }
                } else {
                    appendPlaceholder()
                }
            } else {
                appendPlaceholder()
            }

            binding.contentText.text = spanBuilder

            // 内存范围标识
            val memoryRange = findMemoryRange(currentAddress)
            if (memoryRange != null) {
                binding.rangeText.text = memoryRange.code
                binding.rangeText.setTextColor(memoryRange.color)
            } else {
                binding.rangeText.text = ""
            }

            // 只更新背景高亮状态，不触碰 checkbox
            val isSelected = isAddressSelected(currentAddress)
            val isHighlighted = highlightAddress?.let {
                it >= currentAddress && it < currentAddress + alignment
            } ?: false
            updateBackground(isSelected, isHighlighted)
        }

        /**
         * 设置 checkbox 监听器（使用正确的位置转换）
         */
        private fun setupCheckboxListener() {
            binding.checkbox.setOnCheckedChangeListener { _, _ ->
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    val rowIndex = positionToRowIndex(pos)
                    toggleSelection(rowToAddress(rowIndex))
                    // 立即更新当前项的背景
                    val isSelected = isAddressSelected(rowToAddress(rowIndex))
                    val isHighlighted = highlightAddress?.let {
                        it >= currentAddress && it < currentAddress + alignment
                    } ?: false
                    updateBackground(isSelected, isHighlighted)
                }
            }
        }

        /**
         * 更新背景颜色
         */
        private fun updateBackground(isSelected: Boolean, isHighlighted: Boolean) {
            when {
                isSelected -> binding.itemContainer.setBackgroundColor(0x33448AFF)
                isHighlighted -> binding.itemContainer.setBackgroundColor(0x50b1d3b0)
                else -> binding.itemContainer.background = null
            }
        }

        private fun updateSelectionAndHighlight(rowIndex: Int) {
            val isSelected = isAddressSelected(currentAddress)
            val isHighlighted = highlightAddress?.let {
                it >= currentAddress && it < currentAddress + alignment
            } ?: false

            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = isSelected
            setupCheckboxListener()

            updateBackground(isSelected, isHighlighted)
        }

        private fun createMemoryRow(adapterPosition: Int): MemoryPreviewItem.MemoryRow {
            // adapterPosition 在固定页面模式下包含顶部导航项，需要转换为实际行索引
            val rowIndex = positionToRowIndex(adapterPosition)
            val address = rowToAddress(rowIndex)
            val pageAddress = getPageAddress(address)
            val pageData = pageCache[pageAddress]
            
            val formattedValues = if (pageData != null) {
                val offsetInPage = (address - pageAddress).toInt()
                val requiredBytes = hexByteSize
                val availableInCurrentPage = pageData.size - offsetInPage
                
                // 检查是否需要跨页读取
                val dataBuffer: ByteArray? = if (offsetInPage >= 0 && availableInCurrentPage >= requiredBytes) {
                    pageData.copyOfRange(offsetInPage, offsetInPage + requiredBytes)
                } else if (offsetInPage >= 0 && availableInCurrentPage > 0) {
                    val nextPageAddress = pageAddress + PAGE_SIZE
                    val nextPageData = pageCache[nextPageAddress]
                    if (nextPageData != null) {
                        val combined = ByteArray(requiredBytes)
                        System.arraycopy(pageData, offsetInPage, combined, 0, availableInCurrentPage)
                        val remainingBytes = requiredBytes - availableInCurrentPage
                        if (remainingBytes <= nextPageData.size) {
                            System.arraycopy(nextPageData, 0, combined, availableInCurrentPage, remainingBytes)
                            combined
                        } else null
                    } else null
                } else null
                
                if (dataBuffer != null) {
                    val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
                    currentFormats.map { format ->
                        val value = parseValue(buffer, format)
                        buffer.position(0)
                        value
                    }
                } else {
                    currentFormats.map { FormattedValue(it, "?", it.textColor) }
                }
            } else {
                currentFormats.map { FormattedValue(it, "?", it.textColor) }
            }
            
            return MemoryPreviewItem.MemoryRow(
                address = address,
                formattedValues = formattedValues,
                memoryRange = findMemoryRange(address),
                isHighlighted = highlightAddress?.let { it >= address && it < address + alignment } ?: false
            )
        }

        private fun parseValue(buffer: ByteBuffer, format: MemoryDisplayFormat): FormattedValue {
            val startPos = buffer.position()
            try {
                return when (format) {
                    MemoryDisplayFormat.HEX_LITTLE_ENDIAN, MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                        if (buffer.remaining() < hexByteSize) return FormattedValue(format, "---", Color.WHITE)
                        val bytes = ByteArray(hexByteSize)
                        buffer.get(bytes)
                        val hexBuilder = StringBuilder(hexByteSize * 2)
                        if (format == MemoryDisplayFormat.HEX_LITTLE_ENDIAN) {
                            for (b in bytes) {
                                hexBuilder.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                                hexBuilder.append(HEX_CHARS[b.toInt() and 0x0F])
                            }
                        } else {
                            for (i in bytes.lastIndex downTo 0) {
                                val b = bytes[i]
                                hexBuilder.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                                hexBuilder.append(HEX_CHARS[b.toInt() and 0x0F])
                            }
                        }
                        FormattedValue(format, hexBuilder.toString(), Color.WHITE)
                    }
                    MemoryDisplayFormat.DWORD -> {
                        if (buffer.remaining() < 4) return FormattedValue(format, "---")
                        FormattedValue(format, buffer.int.toString())
                    }
                    MemoryDisplayFormat.QWORD -> {
                        if (buffer.remaining() < 8) return FormattedValue(format, "---")
                        FormattedValue(format, buffer.long.toString())
                    }
                    MemoryDisplayFormat.WORD -> {
                        if (buffer.remaining() < 2) return FormattedValue(format, "---")
                        FormattedValue(format, buffer.short.toString())
                    }
                    MemoryDisplayFormat.BYTE -> {
                        if (buffer.remaining() < 1) return FormattedValue(format, "---")
                        FormattedValue(format, buffer.get().toString())
                    }
                    MemoryDisplayFormat.FLOAT -> {
                        if (buffer.remaining() < 4) return FormattedValue(format, "---")
                        FormattedValue(format, "%.6g".format(buffer.float))
                    }
                    MemoryDisplayFormat.DOUBLE -> {
                        if (buffer.remaining() < 8) return FormattedValue(format, "---")
                        FormattedValue(format, "%.10g".format(buffer.double))
                    }
                    MemoryDisplayFormat.UTF16_LE -> {
                        if (buffer.remaining() < 2) return FormattedValue(format, "---")
                        val charValue = buffer.short.toInt().toChar()
                        val displayChar = if (charValue.isLetterOrDigit() || charValue.isWhitespace()) charValue.toString() else "."
                        FormattedValue(format, "\"$displayChar\"")
                    }
                    MemoryDisplayFormat.STRING_EXPR -> {
                        if (buffer.remaining() < 1) return FormattedValue(format, "---")
                        val bytes = ByteArray(min(4, buffer.remaining()))
                        buffer.get(bytes)
                        val displayString = buildString(bytes.size) {
                            for (b in bytes) append(if (b in 32..126) b.toInt().toChar() else '.')
                        }
                        FormattedValue(format, "'$displayString'")
                    }
                    MemoryDisplayFormat.ARM32 -> {
                        if (buffer.remaining() < 4) return FormattedValue(format, "---")
                        val bytes = ByteArray(4)
                        buffer.get(bytes)
                        try {
                            val results = Disassembler.disassembleARM32(bytes, currentAddress, count = 1)
                            if (results.isNotEmpty()) FormattedValue(format, "${results[0].mnemonic} ${results[0].operands}")
                            else FormattedValue(format, "???")
                        } catch (e: Exception) { FormattedValue(format, "err") }
                    }
                    MemoryDisplayFormat.THUMB -> {
                        if (buffer.remaining() < 2) return FormattedValue(format, "---")
                        val bytes = ByteArray(2)
                        buffer.get(bytes)
                        try {
                            val results = Disassembler.disassembleThumb(bytes, currentAddress, count = 1)
                            if (results.isNotEmpty()) FormattedValue(format, "${results[0].mnemonic} ${results[0].operands}")
                            else FormattedValue(format, "???")
                        } catch (e: Exception) { FormattedValue(format, "err") }
                    }
                    MemoryDisplayFormat.ARM64 -> {
                        if (buffer.remaining() < 4) return FormattedValue(format, "---")
                        val bytes = ByteArray(4)
                        buffer.get(bytes)
                        try {
                            val results = Disassembler.disassembleARM64(bytes, currentAddress, count = 1)
                            if (results.isNotEmpty()) FormattedValue(format, "${results[0].mnemonic} ${results[0].operands}")
                            else FormattedValue(format, "???")
                        } catch (e: Exception) { FormattedValue(format, "err") }
                    }
                    MemoryDisplayFormat.ARM64_PSEUDO -> {
                        if (buffer.remaining() < 4) return FormattedValue(format, "---")
                        val bytes = ByteArray(4)
                        buffer.get(bytes)
                        try {
                            val results = Disassembler.generatePseudoCode(Disassembler.Architecture.ARM64, bytes, currentAddress, count = 1)
                            if (results.isNotEmpty()) {
                                val text = results[0].pseudoCode ?: "${results[0].mnemonic} ${results[0].operands}"
                                FormattedValue(format, text)
                            } else FormattedValue(format, "???")
                        } catch (e: Exception) { FormattedValue(format, "err") }
                    }
                }
            } finally {
                buffer.position(startPos)
            }
        }

        private fun findMemoryRange(address: Long): MemoryRange? {
            if (memoryRegions.isEmpty()) return null
            var low = 0
            var high = memoryRegions.lastIndex
            while (low <= high) {
                val mid = (low + high) ushr 1
                val region = memoryRegions[mid]
                when {
                    address < region.start -> high = mid - 1
                    address >= region.end -> low = mid + 1
                    else -> return region.range
                }
            }
            return null
        }
    }

    // ==================== NavigationViewHolder ====================

    @SuppressLint("SetTextI18n")
    inner class NavigationViewHolder(
        private val binding: ItemMemoryPreviewNavigationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isNextPage: Boolean = false

        init {
            binding.root.setOnClickListener {
                val targetAddress = if (isNextPage) {
                    // 下一页：当前基地址 + 一页大小
                    baseAddress + PAGE_SIZE
                } else {
                    // 上一页：当前基地址 - 一页大小（最小为0）
                    if (baseAddress >= PAGE_SIZE) baseAddress - PAGE_SIZE else 0L
                }
                onNavigationClick(targetAddress, isNextPage)
            }
        }

        fun bind(isNext: Boolean) {
            isNextPage = isNext
            val targetAddress = if (isNext) {
                baseAddress + PAGE_SIZE
            } else {
                if (baseAddress >= PAGE_SIZE) baseAddress - PAGE_SIZE else 0L
            }
            val formattedAddress = targetAddress.toString(16).uppercase().padStart(8, '0')
            if (isNext) {
                binding.navigationText.text = "下一页 → $formattedAddress"
            } else {
                binding.navigationText.text = "← 上一页 $formattedAddress"
            }
        }
    }

    // ==================== 公共方法 ====================

    fun getSelectedRows(): List<MemoryPreviewItem.MemoryRow> {
        val result = mutableListOf<MemoryPreviewItem.MemoryRow>()
        for (address in selectedAddresses.toLongArray()) {
            val row = addressToRow(address)
            if (row in 0 until totalRows) {
                val pageAddress = getPageAddress(address)
                val pageData = pageCache[pageAddress]
                
                val formattedValues = if (pageData != null) {
                    val offsetInPage = (address - pageAddress).toInt()
                    val requiredBytes = hexByteSize
                    val availableInCurrentPage = pageData.size - offsetInPage
                    
                    // 检查是否需要跨页读取
                    val dataBuffer: ByteArray? = if (offsetInPage >= 0 && availableInCurrentPage >= requiredBytes) {
                        pageData.copyOfRange(offsetInPage, offsetInPage + requiredBytes)
                    } else if (offsetInPage >= 0 && availableInCurrentPage > 0) {
                        val nextPageAddress = pageAddress + PAGE_SIZE
                        val nextPageData = pageCache[nextPageAddress]
                        if (nextPageData != null) {
                            val combined = ByteArray(requiredBytes)
                            System.arraycopy(pageData, offsetInPage, combined, 0, availableInCurrentPage)
                            val remainingBytes = requiredBytes - availableInCurrentPage
                            if (remainingBytes <= nextPageData.size) {
                                System.arraycopy(nextPageData, 0, combined, availableInCurrentPage, remainingBytes)
                                combined
                            } else null
                        } else null
                    } else null
                    
                    if (dataBuffer != null) {
                        val buffer = ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN)
                        currentFormats.map { format ->
                            val value = parseValueStatic(buffer, format, address)
                            buffer.position(0)
                            value
                        }
                    } else {
                        currentFormats.map { FormattedValue(it, "?", it.textColor) }
                    }
                } else {
                    currentFormats.map { FormattedValue(it, "?", it.textColor) }
                }
                
                result.add(MemoryPreviewItem.MemoryRow(
                    address = address,
                    formattedValues = formattedValues,
                    memoryRange = findMemoryRangeStatic(address),
                    isHighlighted = false
                ))
            }
        }
        return result
    }

    private fun parseValueStatic(buffer: ByteBuffer, format: MemoryDisplayFormat, address: Long): FormattedValue {
        val startPos = buffer.position()
        try {
            return when (format) {
                MemoryDisplayFormat.HEX_LITTLE_ENDIAN, MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                    if (buffer.remaining() < hexByteSize) return FormattedValue(format, "---", Color.WHITE)
                    val bytes = ByteArray(hexByteSize)
                    buffer.get(bytes)
                    val hexBuilder = StringBuilder(hexByteSize * 2)
                    if (format == MemoryDisplayFormat.HEX_LITTLE_ENDIAN) {
                        for (b in bytes) {
                            hexBuilder.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(HEX_CHARS[b.toInt() and 0x0F])
                        }
                    } else {
                        for (i in bytes.lastIndex downTo 0) {
                            val b = bytes[i]
                            hexBuilder.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(HEX_CHARS[b.toInt() and 0x0F])
                        }
                    }
                    FormattedValue(format, hexBuilder.toString(), Color.WHITE)
                }
                MemoryDisplayFormat.DWORD -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.int.toString())
                }
                MemoryDisplayFormat.QWORD -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.long.toString())
                }
                else -> FormattedValue(format, "?")
            }
        } finally {
            buffer.position(startPos)
        }
    }

    private fun findMemoryRangeStatic(address: Long): MemoryRange? {
        if (memoryRegions.isEmpty()) return null
        var low = 0
        var high = memoryRegions.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = memoryRegions[mid]
            when {
                address < region.start -> high = mid - 1
                address >= region.end -> low = mid + 1
                else -> return region.range
            }
        }
        return null
    }

    fun getBaseAddress(): Long = baseAddress
    fun getAlignment(): Int = alignment
    fun getTotalRows(): Int = totalRows
}
