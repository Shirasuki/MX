package moe.fuqiuluo.mamu.floating.event

import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.MemoryPreviewItem

/**
 * 保存内存预览到地址列表事件
 */
data class SaveMemoryPreviewEvent(
    val selectedItems: List<MemoryPreviewItem.MemoryRow>,
    val ranges: List<DisplayMemRegionEntry>?
)
