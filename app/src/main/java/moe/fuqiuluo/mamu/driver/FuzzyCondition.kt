package moe.fuqiuluo.mamu.driver

/**
 * 模糊搜索条件枚举
 */
enum class FuzzyCondition(val nativeId: Int, val displayName: String) {
    /**
     * 初始扫描（记录所有值）
     */
    INITIAL(0, "初始扫描"),

    /**
     * 值未变化
     */
    UNCHANGED(1, "值未变化"),

    /**
     * 值已变化
     */
    CHANGED(2, "值已变化"),

    /**
     * 值增加了
     */
    INCREASED(3, "值增加了"),

    /**
     * 值减少了
     */
    DECREASED(4, "值减少了"),

    /**
     * 值增加了指定数量 (param1)
     */
    INCREASED_BY(5, "值增加了"),

    /**
     * 值减少了指定数量 (param1)
     */
    DECREASED_BY(6, "值减少了"),

    /**
     * 值增加了指定范围 (param1 ~ param2)
     */
    INCREASED_BY_RANGE(7, "值增加了范围"),

    /**
     * 值减少了指定范围 (param1 ~ param2)
     */
    DECREASED_BY_RANGE(8, "值减少了范围"),

    /**
     * 值增加了指定百分比 (param1 / 100.0)
     */
    INCREASED_BY_PERCENT(9, "值增加了%"),

    /**
     * 值减少了指定百分比 (param1 / 100.0)
     */
    DECREASED_BY_PERCENT(10, "值减少了%");

    /**
     * 是否需要输入参数
     */
    fun needsParam(): Boolean {
        return when (this) {
            INCREASED_BY, DECREASED_BY, INCREASED_BY_PERCENT, DECREASED_BY_PERCENT -> true
            else -> false
        }
    }

    /**
     * 是否需要两个参数（范围）
     */
    fun needsTwoParams(): Boolean {
        return when (this) {
            INCREASED_BY_RANGE, DECREASED_BY_RANGE -> true
            else -> false
        }
    }

    companion object {
        fun fromNativeId(id: Int): FuzzyCondition? {
            return entries.firstOrNull { it.nativeId == id }
        }

        /**
         * 获取可用于细化搜索的条件列表（排除 INITIAL）
         */
        fun getRefineConditions(): List<FuzzyCondition> {
            return entries.filter { it != INITIAL }
        }
    }
}
