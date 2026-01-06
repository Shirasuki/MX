package moe.fuqiuluo.mamu.ui.tutorial

/**
 * 教程关卡定义
 */
enum class TutorialLevel(
    val title: String,
    val description: String
) {
    /**
     * 第一关：单值搜索
     */
    SINGLE_VALUE_SEARCH(
        title = "单值搜索练习",
        description = "学习基本的内存搜索和修改技巧"
    ),

    /**
     * 第二关：指针链搜索
     */
    POINTER_CHAIN_SEARCH(
        title = "指针链搜索练习",
        description = "学习高级的指针链扫描技术"
    );

    /**
     * 获取下一关，如果是最后一关则返回 null
     */
    fun next(): TutorialLevel? {
        val values = entries.toTypedArray()
        val currentIndex = values.indexOf(this)
        return if (currentIndex < values.size - 1) {
            values[currentIndex + 1]
        } else {
            null
        }
    }

    /**
     * 获取上一关，如果是第一关则返回 null
     */
    fun previous(): TutorialLevel? {
        val values = entries.toTypedArray()
        val currentIndex = values.indexOf(this)
        return if (currentIndex > 0) {
            values[currentIndex - 1]
        } else {
            null
        }
    }

    /**
     * 是否是第一关
     */
    val isFirst: Boolean
        get() = this == entries.toTypedArray()[0]

    /**
     * 是否是最后一关
     */
    val isLast: Boolean
        get() = this == entries.toTypedArray()[entries.size - 1]

    companion object {
        /**
         * 获取第一关
         */
        fun first(): TutorialLevel = entries[0]

        /**
         * 获取最后一关
         */
        fun last(): TutorialLevel = entries[entries.size - 1]
    }
}
