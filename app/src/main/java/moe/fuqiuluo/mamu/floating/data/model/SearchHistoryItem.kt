package moe.fuqiuluo.mamu.floating.data.model

/**
 * 搜索历史记录项
 * @param expression 搜索表达式
 * @param valueType 值类型
 * @param timestamp 搜索时间戳
 */
data class SearchHistoryItem(
    val expression: String,
    val valueType: DisplayValueType,
    val timestamp: Long = System.currentTimeMillis()
)