package moe.fuqiuluo.mamu.driver

import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

interface SearchResultItem {
    val nativePosition: Long // 原始索引，目的是方便

    val displayValueType: DisplayValueType?
}

data class ExactSearchResultItem(
    override val nativePosition: Long,
    val address: Long,
    val valueType: Int,
    val value: String,
): SearchResultItem {
    override val displayValueType: DisplayValueType?
        get() = DisplayValueType.fromNativeId(valueType)
}

data class FuzzySearchResultItem(
    override val nativePosition: Long,
    val address: Long,
    val value: String,
    val valueType: Int
): SearchResultItem {
    override val displayValueType: DisplayValueType?
        get() = DisplayValueType.fromNativeId(valueType)
}

data class PointerChainResultItem(
    override val nativePosition: Long,
    val address: Long,  // 指针地址
    val chainString: String,  // 完整的指针链字符串 (e.g., "[libil2cpp.so+0x1234]->0x10->0x20")
    val moduleName: String,  // 模块名
    val depth: Int  // 指针链深度
): SearchResultItem {
    override val displayValueType: DisplayValueType
        get() = DisplayValueType.QWORD  // 指针总是 QWORD 类型

    val value: String
        get() = "0x${address.toString(16).uppercase()}"
}