package moe.fuqiuluo.mamu.floating.data.local

import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.SearchHistoryItem

/**
 * 搜索历史存储仓库
 * 使用 MMKV 存储搜索历史记录
 */
object SearchHistoryRepository {
    private const val KEY_SEARCH_HISTORY = "search_history"
    private const val MAX_HISTORY_SIZE = 50 // 最大保存50条历史记录
    private const val SEPARATOR = "\u001F" // 字段分隔符 (Unit Separator)
    private const val RECORD_SEPARATOR = "\u001E" // 记录分隔符 (Record Separator)

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    /**
     * 添加搜索历史记录
     * 如果已存在相同的表达式和类型，则更新时间戳并移到最前面
     */
    fun addHistory(expression: String, valueType: DisplayValueType) {
        if (expression.isBlank()) return

        val history = getHistory().toMutableList()

        // 移除已存在的相同记录
        history.removeAll { it.expression == expression && it.valueType == valueType }

        // 添加新记录到最前面
        history.add(0, SearchHistoryItem(expression, valueType))

        // 限制历史记录数量
        val trimmedHistory = history.take(MAX_HISTORY_SIZE)

        saveHistory(trimmedHistory)
    }

    /**
     * 获取所有搜索历史记录
     */
    fun getHistory(): List<SearchHistoryItem> {
        val data = mmkv.decodeString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        if (data.isBlank()) return emptyList()

        return try {
            data.split(RECORD_SEPARATOR).mapNotNull { record ->
                parseRecord(record)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 删除指定的历史记录
     */
    fun deleteHistory(item: SearchHistoryItem) {
        val history = getHistory().toMutableList()
        history.removeAll { it.expression == item.expression && it.valueType == item.valueType }
        saveHistory(history)
    }

    /**
     * 清空所有历史记录
     */
    fun clearHistory() {
        mmkv.remove(KEY_SEARCH_HISTORY)
    }

    /**
     * 保存历史记录列表
     */
    private fun saveHistory(history: List<SearchHistoryItem>) {
        val data = history.joinToString(RECORD_SEPARATOR) { item ->
            "${item.expression}$SEPARATOR${item.valueType.code}$SEPARATOR${item.timestamp}"
        }
        mmkv.encode(KEY_SEARCH_HISTORY, data)
    }

    /**
     * 解析单条记录
     */
    private fun parseRecord(record: String): SearchHistoryItem? {
        val parts = record.split(SEPARATOR)
        if (parts.size < 3) return null

        val expression = parts[0]
        val valueType = DisplayValueType.fromCode(parts[1]) ?: return null
        val timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()

        return SearchHistoryItem(expression, valueType, timestamp)
    }
}