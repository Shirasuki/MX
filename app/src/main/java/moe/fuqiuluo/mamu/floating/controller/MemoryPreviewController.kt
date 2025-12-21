package moe.fuqiuluo.mamu.floating.controller

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.memoryDisplayFormats
import moe.fuqiuluo.mamu.data.settings.memoryRegionCacheInterval
import moe.fuqiuluo.mamu.databinding.FloatingMemoryPreviewLayoutBinding
import moe.fuqiuluo.mamu.driver.Disassembler
import moe.fuqiuluo.mamu.driver.LocalMemoryOps
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.MemoryPreviewAdapter
import moe.fuqiuluo.mamu.floating.data.model.*
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRangeParallel
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.ToolbarAction
import moe.fuqiuluo.mamu.widget.multiChoiceDialog
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class MemoryPreviewController(
    context: Context,
    binding: FloatingMemoryPreviewLayoutBinding,
    notification: NotificationOverlay
) : FloatingController<FloatingMemoryPreviewLayoutBinding>(context, binding, notification) {

    companion object {
        private val PAGE_SIZE = LocalMemoryOps.getPageSize()
        private const val TAG = "MemoryPreviewCtrl"
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

        // ViewHolder 预创建数量
        private const val MEMORY_ROW_POOL_SIZE = 32
        private const val NAVIGATION_POOL_SIZE = 2
    }

    // 当前显示的格式列表
    private lateinit var currentFormats: MutableList<MemoryDisplayFormat>

    // 当前地址范围
    private var currentStartAddress: Long = 0L

    // 当前跳转的目标地址（用于高亮显示）
    private var targetAddress: Long? = null

    // 缓存的内存区域列表（已分类和排序）
    private var memoryRegions: List<DisplayMemRegionEntry> = emptyList()
    private var memoryRegionsCacheTime: Long = 0L

    // MMKV 实例
    private val mmkv by lazy { MMKV.defaultMMKV() }

    // 列表适配器
    private val adapter = MemoryPreviewAdapter(
        onRowClick = { address ->
            // 点击时已经在 adapter 中切换了选中状态，这里可以做额外操作
            // TODO: 可以显示编辑对话框或其他操作
        },
        onNavigationClick = { targetAddress, isNext ->
            // 翻页时清除高亮，直接跳转到页头
            this.targetAddress = null
            jumpToPage(targetAddress)
        },
        onSelectionChanged = { selectedCount ->
            // 选中状态变化时的回调
        }
    )

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun initialize() {
        // 从 MMKV 加载显示格式
        currentFormats = mmkv.memoryDisplayFormats.toMutableList()

        setupToolbar()
        setupRecyclerView()
        setupStatusBar()
        updateFormatDisplay()

        subscribeToNavigateEvents()

        // 加载初始占位页面，提前创建所有 ViewHolder
        loadInitialPlaceholderPage()
    }

    /**
     * 加载初始占位页面
     * 从 0x0 开始显示占位符内容，提前创建所有 ViewHolder
     */
    private fun loadInitialPlaceholderPage() {
        currentStartAddress = 0L
        targetAddress = null

        // 计算对齐和格式
        val alignment = MemoryDisplayFormat.calculateAlignment(currentFormats)
        val maxRows = PAGE_SIZE / alignment

        // 构建占位符列表
        val items = mutableListOf<MemoryPreviewItem>()

        // 添加占位符行
        var address = 0L
        repeat(maxRows) {
            val placeholderValues = currentFormats.map { format ->
                FormattedValue(format, "?", format.textColor)
            }
            items.add(
                MemoryPreviewItem.MemoryRow(
                    address = address,
                    formattedValues = placeholderValues,
                    memoryRange = MemoryRange.O,
                    isHighlighted = false
                )
            )
            address += alignment
        }

        // 添加下一页导航
        items.add(MemoryPreviewItem.PageNavigation(PAGE_SIZE.toLong(), isNext = true))

        adapter.setItems(items)
        updateEmptyState()
    }

    /**
     * 订阅导航到内存地址事件
     */
    private fun subscribeToNavigateEvents() {
        coroutineScope.launch {
            FloatingEventBus.navigateToMemoryAddressEvents.collect { event ->
                // 跳转到指定地址
                jumpToPage(event.address)
                notification.showSuccess("已跳转到地址: ${String.format("%X", event.address)}")
            }
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.previewToolbar

        val actions = listOf(
            ToolbarAction(
                id = 1,
                icon = R.drawable.icon_search_24px,
                label = "跳转到地址"
            ) {
                // TODO: 显示跳转对话框
                notification.showWarning("跳转功能待实现")
            },
            ToolbarAction(
                id = 2,
                icon = R.drawable.icon_list_24px,
                label = "选择内存范围"
            ) {
                // TODO: 显示选择内存范围对话框
                notification.showWarning("选择内存范围功能待实现")
            }
        )

        toolbar.setActions(actions)
        val options = actions.map { it.label }.toTypedArray()
        val icons = actions.map { it.icon }.toTypedArray()
        toolbar.setOverflowCallback {
            context.simpleSingleChoiceDialog(
                showTitle = false,
                options = options,
                icons = icons,
                showRadioButton = false,
                onSingleChoice = { which ->
                    if (which < actions.size) {
                        actions[which].onClick.invoke()
                    }
                }
            )
        }
    }

    private fun setupRecyclerView() {
        // 创建共享的 ViewPool 并设置预创建数量
        val viewPool = RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(MemoryPreviewAdapter.VIEW_TYPE_MEMORY_ROW, MEMORY_ROW_POOL_SIZE)
            setMaxRecycledViews(MemoryPreviewAdapter.VIEW_TYPE_NAVIGATION, NAVIGATION_POOL_SIZE)
        }

        binding.memoryPreviewRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@MemoryPreviewController.adapter

            setHasFixedSize(true)
            if (itemAnimator != null && itemAnimator is SimpleItemAnimator) {
                (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            }

            // 设置共享 ViewPool
            setRecycledViewPool(viewPool)

            // 增加预取数量
            (layoutManager as? LinearLayoutManager)?.apply {
                initialPrefetchItemCount = 20
            }

            // 添加分界线
            val divider = DividerItemDecoration(
                context,
                DividerItemDecoration.VERTICAL
            )
            addItemDecoration(divider)
        }

        // 绑定快速滚动条
        binding.fastScroller.attachToRecyclerView(binding.memoryPreviewRecyclerView)

        // 后台预创建 ViewHolder
        preCreateViewHolders(viewPool)
    }

    /**
     * 后台预创建 ViewHolder，减少首次加载时的创建开销
     */
    private fun preCreateViewHolders(viewPool: RecyclerView.RecycledViewPool) {
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val recyclerView = binding.memoryPreviewRecyclerView
                val adapter = this@MemoryPreviewController.adapter

                // 切到主线程创建 ViewHolder（必须在主线程操作 View）
                withContext(Dispatchers.Main) {
                    // 预创建 MemoryRow ViewHolder
                    repeat(MEMORY_ROW_POOL_SIZE) {
                        val holder = adapter.createViewHolder(
                            recyclerView,
                            MemoryPreviewAdapter.VIEW_TYPE_MEMORY_ROW
                        )
                        viewPool.putRecycledView(holder)
                    }

                    // 预创建 Navigation ViewHolder
                    repeat(NAVIGATION_POOL_SIZE) {
                        val holder = adapter.createViewHolder(
                            recyclerView,
                            MemoryPreviewAdapter.VIEW_TYPE_NAVIGATION
                        )
                        viewPool.putRecycledView(holder)
                    }

//                    Log.d(
//                        TAG,
//                        "预创建 ViewHolder 完成: MemoryRow=$MEMORY_ROW_POOL_SIZE, Navigation=$NAVIGATION_POOL_SIZE"
//                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "预创建 ViewHolder 失败: ${e.message}")
            }
        }
    }

    private fun setupStatusBar() {
        // 点击筛选器状态 - 打开筛选器对话框
        binding.filterStatusText.setOnClickListener {
            // TODO: 显示筛选器对话框
            notification.showWarning("筛选器功能待实现")
        }

        // 点击格式显示 - 打开格式设置对话框
        binding.formatDisplayText.setOnClickListener {
            showFormatSettingsDialog()
        }

        // 刷新按钮
        binding.refreshButton.setOnClickListener {
            refreshCurrentPage()
        }
    }

    /**
     * 显示格式设置对话框
     */
    private fun showFormatSettingsDialog() {
        val allFormats = MemoryDisplayFormat.getAllFormatsSortedByPriority().toTypedArray()
        val formatNames = allFormats.map { it.code + ": " + it.displayName }.toTypedArray()
        val formatColors = allFormats.map { it.textColor }.toIntArray()

        // 创建当前选中状态的数组
        val checkedItems = BooleanArray(allFormats.size) { index ->
            currentFormats.contains(allFormats[index])
        }

        context.multiChoiceDialog(
            title = "选择显示数值格式",
            options = formatNames,
            checkedItems = checkedItems,
            itemColors = formatColors,
            onMultiChoice = { newCheckedItems ->
                // 更新选中的格式
                currentFormats.clear()
                newCheckedItems.forEachIndexed { index, isChecked ->
                    if (isChecked) {
                        currentFormats.add(allFormats[index])
                    }
                }

                // 至少要选中一个格式
                if (currentFormats.isEmpty()) {
                    currentFormats.add(MemoryDisplayFormat.DWORD)
                    notification.showWarning("至少需要选择一个显示格式")
                }

                // 保存到 MMKV
                mmkv.memoryDisplayFormats = currentFormats

                updateFormatDisplay()
                // 格式改变时保持目标地址高亮
                refreshCurrentPage()
            }
        )
    }

    /**
     * 更新格式显示文本（带颜色）
     */
    private fun updateFormatDisplay() {
        val spanBuilder = SpannableStringBuilder()

        currentFormats.forEachIndexed { index, format ->
            if (index > 0) {
                spanBuilder.append(",")
            }

            val start = spanBuilder.length
            spanBuilder.append(format.code)
            val end = spanBuilder.length

            spanBuilder.setSpan(
                ForegroundColorSpan(format.textColor),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        binding.formatDisplayText.text = spanBuilder
    }

    /**
     * 跳转到指定地址
     * @param requestedAddress 用户请求跳转的地址（可能不对齐）
     */
    private fun jumpToPage(requestedAddress: Long) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        // 计算页头地址（向下对齐到页边界，固定4KB页大小）
        val pageStartAddress = (requestedAddress / PAGE_SIZE) * PAGE_SIZE
        currentStartAddress = pageStartAddress

        // 记录目标地址用于高亮
        targetAddress = requestedAddress

        // 加载页面
        loadPage(pageStartAddress, requestedAddress)
    }

    /**
     * 加载指定地址开始的一页数据
     * @param pageStartAddress 页头地址（必须已对齐到 PAGE_SIZE）
     * @param highlightAddress 要高亮的地址（可选）
     */
    private fun loadPage(pageStartAddress: Long, highlightAddress: Long? = null) {
//        val startTime = System.currentTimeMillis()
//        Log.d(TAG, "━━━━━ loadPage 开始 ━━━━━")
//        Log.d(
//            TAG,
//            "地址: 0x${
//                pageStartAddress.toString(16).uppercase()
//            }, 高亮: ${highlightAddress?.let { "0x${it.toString(16).uppercase()}" } ?: "无"}")

        // 检查页头地址是否对齐
        if (pageStartAddress % PAGE_SIZE != 0L) {
            throw IllegalArgumentException("pageStartAddress must be aligned to PAGE_SIZE ($PAGE_SIZE bytes)")
        }

        if (!WuwaDriver.isProcessBound) {
            notification.showError("未绑定进程")
            return
        }

        // 预计算固定值（在循环外计算一次）
        val alignment = MemoryDisplayFormat.calculateAlignment(currentFormats)
        val hexByteSize = MemoryDisplayFormat.calculateHexByteSize(currentFormats)
        val formats = currentFormats
//        val t1 = System.currentTimeMillis()
//        Log.d(
//            TAG,
//            "[1] 计算对齐: alignment=$alignment, hexByteSize=$hexByteSize, 耗时=${t1 - startTime}ms"
//        )

        // 整个流程在 Default 线程执行，减少调度开销
        coroutineScope.launch(Dispatchers.Default) {
//            val t2 = System.currentTimeMillis()
//            Log.d(TAG, "[2] 协程启动, 耗时=${t2 - t1}ms")

            // 检查内存区域缓存是否有效
            val cacheInterval = mmkv.memoryRegionCacheInterval.toLong()
            val now = System.currentTimeMillis()
            val cacheValid = cacheInterval > 0 &&
                    memoryRegions.isNotEmpty() &&
                    (now - memoryRegionsCacheTime) < cacheInterval

            // 并行：查询内存区域（如果缓存失效）+ 读取内存
            val regionsDeferred = if (!cacheValid) {
                async {
                    WuwaDriver.queryMemRegions().divideToSimpleMemoryRangeParallel()
                        .sortedBy { it.start }
                }
            } else null

            val memoryDeferred = async(Dispatchers.IO) {
                WuwaDriver.readMemory(pageStartAddress, PAGE_SIZE)
            }

            val sortedRegions = if (regionsDeferred != null) {
                val regions = regionsDeferred.await()
                memoryRegions = regions
                memoryRegionsCacheTime = now
                regions
            } else {
                memoryRegions
            }
//            val t3 = System.currentTimeMillis()
//            Log.d(
//                TAG,
//                "[3] 查询内存区域: 区域数=${sortedRegions.size}, 缓存=${cacheValid}, 耗时=${t3 - t2}ms"
//            )

            val memoryData = memoryDeferred.await()
//            val t4 = System.currentTimeMillis()
//            Log.d(TAG, "[4] 读取内存: 大小=${memoryData?.size ?: 0}bytes, 耗时=${t4 - t3}ms")

            // 构建最终列表
            val items = mutableListOf<MemoryPreviewItem>()
            val maxRows = PAGE_SIZE / alignment
            var highlightedRowIndex = -1

            // 添加上一页导航（如果不是第一页）
            if (pageStartAddress > 0) {
                val prevAddress = (pageStartAddress - PAGE_SIZE).coerceAtLeast(0)
                items.add(MemoryPreviewItem.PageNavigation(prevAddress, isNext = false))
            }

            if (memoryData == null) {
                // 内存读取失败，显示占位符
                var address = pageStartAddress
                repeat(maxRows) {
                    val isHighlighted = highlightAddress != null &&
                            highlightAddress >= address &&
                            highlightAddress < address + alignment
                    if (isHighlighted) {
                        highlightedRowIndex = items.size
                    }

                    val placeholderValues = formats.map { format ->
                        FormattedValue(format, "?", format.textColor)
                    }
                    items.add(
                        MemoryPreviewItem.MemoryRow(
                            address = address,
                            formattedValues = placeholderValues,
                            memoryRange = findMemoryRangeBinary(address, sortedRegions),
                            isHighlighted = isHighlighted
                        )
                    )
                    address += alignment
                }
            } else {
                // 解析内存数据（已经在 Default 线程）
                val parseResult = parseMemoryData(
                    memoryData = memoryData,
                    pageStartAddress = pageStartAddress,
                    highlightAddress = highlightAddress,
                    alignment = alignment,
                    hexByteSize = hexByteSize,
                    formats = formats,
                    sortedRegions = sortedRegions
                )
                highlightedRowIndex = if (pageStartAddress > 0) {
                    parseResult.highlightedRowIndex + 1 // 加上上一页导航的位置
                } else {
                    parseResult.highlightedRowIndex
                }
                items.addAll(parseResult.rows)
            }
//            val t5 = System.currentTimeMillis()
//            Log.d(TAG, "[5] 解析内存完成: 解析${items.size}行, 耗时=${t5 - t4}ms")

            // 添加下一页导航
            val nextAddress = pageStartAddress + PAGE_SIZE
            items.add(MemoryPreviewItem.PageNavigation(nextAddress, isNext = true))

            // 切回主线程更新 UI
            withContext(Dispatchers.Main) {
                adapter.setItems(items)
                updateEmptyState()
//                val t6 = System.currentTimeMillis()
//                Log.d(TAG, "[6] 更新UI完成: 耗时=${t6 - t5}ms")

                // 滚动到高亮的行，放在视觉中间位置
                if (highlightedRowIndex >= 0) {
                    val layoutManager =
                        binding.memoryPreviewRecyclerView.layoutManager as? LinearLayoutManager
                    if (layoutManager != null) {
                        // 计算偏移量使目标居中
                        val recyclerHeight = binding.memoryPreviewRecyclerView.height
                        val offset = recyclerHeight / 2
                        layoutManager.scrollToPositionWithOffset(highlightedRowIndex, offset)
                    }
                }

//                val totalTime = System.currentTimeMillis() - startTime
//                Log.d(TAG, "━━━━━ loadPage 完成: 总耗时=${totalTime}ms ━━━━━\n")
            }
        }
    }

    /**
     * 内存解析结果
     */
    private data class ParseResult(
        val rows: List<MemoryPreviewItem.MemoryRow>,
        val rowCount: Int,
        val highlightedRowIndex: Int
    )

    private fun parseMemoryData(
        memoryData: ByteArray,
        pageStartAddress: Long,
        highlightAddress: Long?,
        alignment: Int,
        hexByteSize: Int,
        formats: List<MemoryDisplayFormat>,
        sortedRegions: List<DisplayMemRegionEntry>
    ): ParseResult {
        val rows = mutableListOf<MemoryPreviewItem.MemoryRow>()
        val buffer = ByteBuffer.wrap(memoryData).order(ByteOrder.LITTLE_ENDIAN)
        val maxRows = PAGE_SIZE / alignment
        var highlightedRowIndex = -1
        var currentAddress = pageStartAddress

        // 预分配 StringBuilder 用于十六进制转换
        val hexBuilder = StringBuilder(hexByteSize * 2)

        while (buffer.remaining() >= alignment && rows.size < maxRows) {
            // 记录当前行的起始位置
            val rowStartPos = buffer.position()
            val formattedValues = ArrayList<FormattedValue>(formats.size)

            // 为每种格式解析值
            for (format in formats) {
                val formattedValue = parseValueFast(
                    buffer, currentAddress, format, hexByteSize, hexBuilder, sortedRegions
                )
                formattedValues.add(formattedValue)
            }

            // 使用二分查找获取内存区域
            val memoryRange = findMemoryRangeBinary(currentAddress, sortedRegions)

            // 检查该行是否包含高亮地址
            val isHighlighted = highlightAddress != null &&
                    highlightAddress >= currentAddress &&
                    highlightAddress < currentAddress + alignment

            if (isHighlighted) {
                highlightedRowIndex = rows.size
            }

            rows.add(
                MemoryPreviewItem.MemoryRow(
                    currentAddress,
                    formattedValues,
                    memoryRange,
                    isHighlighted
                )
            )

            // 移动 buffer 到下一行
            buffer.position(rowStartPos + alignment)
            currentAddress += alignment
        }

        return ParseResult(rows, rows.size, highlightedRowIndex)
    }

    /**
     * 快速解析内存值
     */
    private fun parseValueFast(
        buffer: ByteBuffer,
        address: Long,
        format: MemoryDisplayFormat,
        hexByteSize: Int,
        hexBuilder: StringBuilder,
        sortedRegions: List<DisplayMemRegionEntry>
    ): FormattedValue {
        val startPos = buffer.position()

        try {
            return when (format) {
                MemoryDisplayFormat.HEX_LITTLE_ENDIAN, MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                    if (buffer.remaining() < hexByteSize) {
                        return FormattedValue(format, "---", Color.WHITE)
                    }

                    // 快速十六进制转换
                    hexBuilder.setLength(0)
                    val bytes = ByteArray(hexByteSize)
                    buffer.get(bytes)

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

                    // 将十六进制值解析为地址（快速版本）
                    buffer.position(startPos)
                    val valueAsAddress: Long? = when (hexByteSize) {
                        8 -> buffer.long.takeIf { it >= 0 }
                        4 -> buffer.int.toLong() and 0xFFFFFFFFL
                        2 -> buffer.short.toLong() and 0xFFFFL
                        else -> null
                    }

                    // 使用二分查找获取颜色
                    val color = if (valueAsAddress != null) {
                        findExecutableRegionBinary(valueAsAddress, sortedRegions)?.range?.color
                            ?: Color.WHITE
                    } else {
                        Color.WHITE
                    }

                    FormattedValue(format, hexBuilder.toString(), color)
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
                    FormattedValue(format, "%.6f".format(buffer.float))
                }

                MemoryDisplayFormat.DOUBLE -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, "%.10f".format(buffer.double))
                }

                MemoryDisplayFormat.UTF16_LE -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val charValue = buffer.short.toInt().toChar()
                    val displayChar = if (charValue.isLetterOrDigit() || charValue.isWhitespace()) {
                        charValue.toString()
                    } else {
                        "."
                    }
                    FormattedValue(format, "\"$displayChar\"")
                }

                MemoryDisplayFormat.STRING_EXPR -> {
                    if (buffer.remaining() < 1) return FormattedValue(format, "---")
                    val bytes = ByteArray(min(4, buffer.remaining()))
                    buffer.get(bytes)
                    val displayString = buildString(bytes.size) {
                        for (b in bytes) {
                            append(if (b in 32..126) b.toInt().toChar() else '.')
                        }
                    }
                    FormattedValue(format, "'$displayString'")
                }

                MemoryDisplayFormat.ARM32 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM32(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.THUMB -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val bytes = ByteArray(2)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleThumb(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM64(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64_PSEUDO -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.generatePseudoCode(
                            Disassembler.Architecture.ARM64,
                            bytes,
                            address,
                            count = 1
                        )
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            // 优先显示伪代码，如果没有则显示汇编
                            val displayText = insn.pseudoCode ?: "${insn.mnemonic} ${insn.operands}"
                            FormattedValue(format, displayText)
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                else -> FormattedValue(format, "TODO(${format.code})")
            }
        } finally {
            buffer.position(startPos)
        }
    }

    /**
     * 二分查找获取地址所属的内存区域
     */
    private fun findMemoryRangeBinary(
        address: Long,
        sortedRegions: List<DisplayMemRegionEntry>
    ): MemoryRange? {
        if (sortedRegions.isEmpty()) return null

        var low = 0
        var high = sortedRegions.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = sortedRegions[mid]

            when {
                address < region.start -> high = mid - 1
                address >= region.end -> low = mid + 1
                else -> return region.range // 找到包含该地址的区域
            }
        }
        return null
    }

    /**
     * 二分查找获取可执行内存区域（用于 HEX 值颜色判断）
     */
    private fun findExecutableRegionBinary(
        address: Long,
        sortedRegions: List<DisplayMemRegionEntry>
    ): DisplayMemRegionEntry? {
        if (sortedRegions.isEmpty()) return null

        var low = 0
        var high = sortedRegions.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = sortedRegions[mid]

            when {
                address < region.start -> high = mid - 1
                address >= region.end -> low = mid + 1
                else -> return if (region.isExecutable) region else null
            }
        }
        return null
    }

    /**
     * 刷新当前页
     */
    private fun refreshCurrentPage() {
        if (currentStartAddress > 0) {
            // 刷新时保持当前的页头地址和高亮地址
            loadPage(currentStartAddress, targetAddress)
        } else {
            notification.showWarning("请先选择起始地址")
        }
    }

    private fun updateEmptyState() {
        val hasData = adapter.itemCount > 0
        binding.emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.memoryPreviewRecyclerView.visibility = if (hasData) View.VISIBLE else View.GONE
    }

    override fun cleanup() {
        super.cleanup()
        binding.fastScroller.detachFromRecyclerView()
        coroutineScope.cancel()
    }
}
