package moe.fuqiuluo.mamu.pp

import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry

/**
 * 数据类型
 */
enum class DataType(val byteSize: Int, val code: String) {
    U8(1, "u8"),
    U16(2, "u16"),
    U32(4, "u32"),
    U64(8, "u64"),
    I8(1, "i8"),
    I16(2, "i16"),
    I32(4, "i32"),
    I64(8, "i64");

    companion object {
        fun fromCode(code: String): DataType? {
            return entries.find { it.code == code }
        }
    }
}

/**
 * AST节点基类
 */
sealed class ExprNode {
    /**
     * 偏移操作：4, +8, -0x10
     */
    data class Offset(val value: Long) : ExprNode()

    /**
     * 解引用操作：*u64, **u32
     * @param type 数据类型
     * @param count 连续解引用次数（**u64则count=2）
     */
    data class Deref(val type: DataType, val count: Int = 1) : ExprNode()

    /**
     * 变量定义：name:expr
     * @param name 变量名
     * @param expr 子表达式列表
     */
    data class VarDef(val name: String, val expr: List<ExprNode>) : ExprNode()

    /**
     * 变量引用：$name
     * @param name 变量名
     */
    data class VarRef(val name: String) : ExprNode()

    /**
     * 条件表达式：cond? true : false
     */
    data class Conditional(
        val condition: Condition,
        val trueBranch: List<ExprNode>,
        val falseBranch: List<ExprNode>
    ) : ExprNode()

    /**
     * 内建操作符
     */
    sealed class Builtin : ExprNode() {
        /**
         * @skip - 跳过，保持当前地址不变
         */
        object Skip : Builtin()

        /**
         * @null - 返回0地址
         */
        object Null : Builtin()

        /**
         * @stop - 停止执行
         */
        object Stop : Builtin()

        /**
         * @[index] 或 @[index,elemSize] - 数组访问
         * @param indexExpr 索引表达式（可以是常量或变量引用）
         * @param elemSize 元素大小（字节），null则默认为8
         */
        data class ArrayAccess(
            val indexExpr: Operand,
            val elemSize: Int? = null
        ) : Builtin()
    }
}

/**
 * 条件表达式
 */
sealed class Condition {
    /**
     * 比较操作：==, !=, >, <, >=, <=
     */
    data class Compare(
        val left: Operand,
        val op: CompareOp,
        val right: Operand
    ) : Condition()

    /**
     * 位运算：&, |, ^
     */
    data class Bitwise(
        val left: Operand,
        val op: BitwiseOp,
        val right: Operand
    ) : Condition()

    /**
     * 逻辑运算：&&, ||
     */
    data class Logical(
        val left: Condition,
        val op: LogicalOp,
        val right: Condition
    ) : Condition()

    /**
     * 逻辑非：!condition
     */
    data class Not(val condition: Condition) : Condition()
}

/**
 * 比较运算符
 */
enum class CompareOp(val symbol: String) {
    EQ("=="),
    NE("!="),
    GT(">"),
    LT("<"),
    GE(">="),
    LE("<=")
}

/**
 * 位运算符
 */
enum class BitwiseOp(val symbol: String) {
    AND("&"),
    OR("|"),
    XOR("^")
}

/**
 * 逻辑运算符
 */
enum class LogicalOp(val symbol: String) {
    AND("&&"),
    OR("||")
}

/**
 * 条件操作数
 */
sealed class Operand {
    /**
     * 当前地址：_
     */
    object Current : Operand()

    /**
     * 变量引用：$name
     */
    data class Variable(val name: String) : Operand()

    /**
     * 常量：0x100
     */
    data class Constant(val value: Long) : Operand()
}

/**
 * 执行步骤记录
 */
data class ExecutionStep(
    val stepIndex: Int,
    val description: String,
    val addressBefore: Long,
    val addressAfter: Long,
    val operation: String,
    val variables: Map<String, Long> = emptyMap()
)

/**
 * 执行结果
 */
data class ExecutionResult(
    val success: Boolean,
    val finalAddress: Long,
    val steps: List<ExecutionStep>,
    val bytes: ByteArray? = null,
    val regions: List<DisplayMemRegionEntry>,
    val errorMessage: String? = null,
    val memoryValues: Map<String, String>? = null  // 类型代码 -> 值字符串
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExecutionResult

        if (success != other.success) return false
        if (finalAddress != other.finalAddress) return false
        if (steps != other.steps) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (regions != other.regions) return false
        if (errorMessage != other.errorMessage) return false
        if (memoryValues != other.memoryValues) return false

        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + finalAddress.hashCode()
        result = 31 * result + steps.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        result = 31 * result + regions.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (memoryValues?.hashCode() ?: 0)
        return result
    }
}
