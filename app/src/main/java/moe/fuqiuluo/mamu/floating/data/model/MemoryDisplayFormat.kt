package moe.fuqiuluo.mamu.floating.data.model

/**
 * 内存预览支持的显示格式
 * 按优先级排序：h > r > S > J > A > T > A8 > PC > D > F > E > W > B > Q
 */
enum class MemoryDisplayFormat(
    val code: String,
    val displayName: String,
    val textColor: Int,
    val byteSize: Int, // 每个值占用的字节数
    val priority: Int, // 显示优先级（数字越小优先级越高）
    val appendCode: Boolean = false, // 附加code到结尾
) {
    HEX_BIG_ENDIAN(
        code = "h",
        displayName = "反向十六进制 (大端序)",
        textColor = 0xFF00CED1.toInt(),
        byteSize = Int.MAX_VALUE, // 不参与计数
        priority = 1,
        appendCode = true
    ),
    HEX_LITTLE_ENDIAN(
        code = "r",
        displayName = "十六进制 (小端序)",
        textColor = 0xFFD4CAD6.toInt(),
        byteSize = Int.MAX_VALUE, // 不参与计数
        priority = 2,
        appendCode = true
    ),
    STRING_EXPR(
        code = "S",
        displayName = "字符串表达式",
        textColor = 0xFFD4CAD6.toInt(),
        byteSize = Int.MAX_VALUE, // 不参与计数
        priority = 3
    ),
    UTF16_LE(
        code = "J",
        displayName = "UTF16-LE",
        textColor = 0xFFD4CAD6.toInt(),
        byteSize = Int.MAX_VALUE, // 不参与计数
        priority = 4
    ),
    ARM32(
        code = "A",
        displayName = "ARM 32指令",
        textColor = 0xFFF7B8F1.toInt(),
        byteSize = 4,
        priority = 5
    ),
    THUMB(
        code = "T",
        displayName = "Thumb指令",
        textColor = 0xFFF82BF5.toInt(),
        byteSize = 2,
        priority = 6
    ),
    ARM64(
        code = "A8",
        displayName = "ARM64指令",
        textColor = 0xFFE4A2E1.toInt(),
        byteSize = 4,
        priority = 7
    ),
    ARM64_PSEUDO(
        code = "PC",
        displayName = "ARM64伪代码",
        textColor = 0xFFF49152.toInt(),
        byteSize = 4,
        priority = 8
    ),
    DWORD(
        code = "D",
        displayName = "Dword",
        textColor = DisplayValueType.DWORD.textColor,
        byteSize = 4,
        priority = 9,
        appendCode = true
    ),
    FLOAT(
        code = "F",
        displayName = "Float",
        textColor = DisplayValueType.FLOAT.textColor,
        byteSize = 4,
        priority = 10,
        appendCode = true
    ),
    DOUBLE(
        code = "E",
        displayName = "Double",
        textColor = DisplayValueType.DOUBLE.textColor,
        byteSize = 8,
        priority = 11,
        appendCode = true
    ),
    WORD(
        code = "W",
        displayName = "Word",
        textColor = DisplayValueType.WORD.textColor,
        byteSize = 2,
        priority = 12,
        appendCode = true
    ),
    BYTE(
        code = "B",
        displayName = "Byte",
        textColor = DisplayValueType.BYTE.textColor,
        byteSize = 1,
        priority = 13,
        appendCode = true
    ),
    QWORD(
        code = "Q",
        displayName = "Qword",
        textColor = DisplayValueType.QWORD.textColor,
        byteSize = 8,
        priority = 14,
        appendCode = true
    );

    companion object {
        fun fromCode(code: String): MemoryDisplayFormat? {
            return entries.find { it.code == code }
        }

        /**
         * 根据选中的格式计算对齐单位（使用最小的字节数）
         */
        fun calculateAlignment(formats: List<MemoryDisplayFormat>): Int {
            return formats.minOfOrNull { it.byteSize }.let {
                if (it == Int.MAX_VALUE) 8 else it
            } ?: 4
        }

        /**
         * 根据选中的格式计算十六进制显示的字节数（使用最大的字节数，排除Int.MAX_VALUE）
         */
        fun calculateHexByteSize(formats: Collection<MemoryDisplayFormat>): Int {
            val validSizes = formats.mapNotNull {
                if (it.byteSize == Int.MAX_VALUE) null else it.byteSize
            }
            return validSizes.maxOrNull() ?: 4
        }

        /**
         * 获取默认选中的格式
         */
        fun getDefaultFormats(): List<MemoryDisplayFormat> {
            return listOf(HEX_BIG_ENDIAN, DWORD, QWORD)
        }

        /**
         * 获取所有格式按优先级排序
         */
        fun getAllFormatsSortedByPriority(): List<MemoryDisplayFormat> {
            return entries.sortedBy { it.priority }
        }
    }
}
