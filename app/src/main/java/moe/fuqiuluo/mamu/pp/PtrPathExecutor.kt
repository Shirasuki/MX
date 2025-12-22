package moe.fuqiuluo.mamu.pp

import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRangeParallel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 执行异常
 */
class ExecutionException(message: String) : Exception(message)

/**
 * 执行引擎
 * @param baseAddress 基址（起始地址）
 */
class PtrPathExecutor(
    private val baseAddress: Long
) {
    private var currentAddress = baseAddress
    private val variables = mutableMapOf<String, Long>()
    private val steps = mutableListOf<ExecutionStep>()
    private var stepIndex = 0
    private var shouldStop = false

    /**
     * 执行表达式节点列表
     * @param nodes AST节点列表
     * @return 执行结果
     */
    suspend fun execute(nodes: List<ExprNode>): ExecutionResult {
        try {
            // 记录初始状态
            addStep("开始执行", baseAddress, currentAddress, "base=${"0x%X".format(baseAddress)}")

            // 顺序执行每个节点
            for (node in nodes) {
                if (shouldStop) {
                    break
                }
                executeNode(node)
            }

            // 如果执行成功，读取最终地址的内存值
            val memoryValues = if (currentAddress != 0L && !shouldStop) {
                readMemoryValues(currentAddress)
            } else {
                null
            }

            val bytes = WuwaDriver.readMemory(currentAddress, 8)
            val regions = WuwaDriver.queryMemRegionsWithRetry().divideToSimpleMemoryRangeParallel()
                .sortedBy { it.start }

            return ExecutionResult(
                success = true,
                finalAddress = currentAddress,
                steps = steps,
                errorMessage = null,
                memoryValues = memoryValues,
                bytes = bytes,
                regions = regions
            )
        } catch (e: Exception) {
            // 执行失败，返回部分结果
            return ExecutionResult(
                success = false,
                finalAddress = currentAddress,
                steps = steps,
                errorMessage = e.message ?: "未知错误",
                memoryValues = null,
                regions = emptyList(),
                bytes = null
            )
        }
    }

    /**
     * 执行单个节点
     */
    private fun executeNode(node: ExprNode) {
        val before = currentAddress

        when (node) {
            is ExprNode.Offset -> {
                currentAddress += node.value
                val sign = if (node.value >= 0) "+" else ""
                addStep(
                    "偏移",
                    before,
                    currentAddress,
                    "$sign${node.value} (${sign}0x%X)".format(Math.abs(node.value))
                )
            }

            is ExprNode.Deref -> {
                // 可能需要连续解引用多次
                repeat(node.count) { index ->
                    val addrBefore = currentAddress
                    val bytes = WuwaDriver.readMemory(currentAddress, node.type.byteSize)
                        ?: throw ExecutionException("无法读取地址 0x%X".format(currentAddress))

                    currentAddress = bytesToAddress(bytes, node.type)

                    val desc = if (node.count > 1) {
                        "解引用 [${index + 1}/${node.count}]"
                    } else {
                        "解引用"
                    }

                    addStep(
                        desc,
                        addrBefore,
                        currentAddress,
                        "*${node.type.code}"
                    )
                }
            }

            is ExprNode.VarDef -> {
                val varBefore = currentAddress

                // 执行变量定义的子表达式
                for (subNode in node.expr) {
                    if (shouldStop) break
                    executeNode(subNode)
                }

                // 保存当前地址到变量
                variables[node.name] = currentAddress

                addStep(
                    "变量定义",
                    varBefore,
                    currentAddress,
                    if (node.name == "_") {
                        "_: = 0x%X".format(currentAddress)
                    } else {
                        "${node.name}: = 0x%X".format(currentAddress)
                    }
                )
            }

            is ExprNode.VarRef -> {
                // 特殊处理：如果是下划线变量引用
                if (node.name == "_") {
                    // 从变量表中获取，如果不存在则使用current
                    val varValue = variables["_"] ?: currentAddress
                    if (variables.containsKey("_")) {
                        currentAddress = varValue
                        addStep(
                            "变量引用",
                            before,
                            currentAddress,
                            "\$_ = 0x%X".format(currentAddress)
                        )
                    } else {
                        // 如果_未定义，则表示使用current，不改变地址
                        addStep(
                            "当前地址",
                            before,
                            currentAddress,
                            "_ (current)"
                        )
                    }
                } else {
                    val varValue = variables[node.name]
                        ?: throw ExecutionException("未定义的变量: ${node.name}")
                    currentAddress = varValue
                    addStep(
                        "变量引用",
                        before,
                        currentAddress,
                        "\$${node.name} = 0x%X".format(currentAddress)
                    )
                }
            }

            is ExprNode.Conditional -> {
                val condResult = evaluateCondition(node.condition)
                val branch = if (condResult) node.trueBranch else node.falseBranch
                val branchName = if (condResult) "真分支" else "假分支"

                addStep(
                    "条件判断",
                    before,
                    currentAddress,
                    "条件=${condResult}, 执行$branchName"
                )

                // 执行选中的分支
                for (subNode in branch) {
                    if (shouldStop) break
                    executeNode(subNode)
                }
            }

            is ExprNode.Builtin.Skip -> {
                addStep(
                    "跳过",
                    before,
                    currentAddress,
                    "@skip (保持当前地址)"
                )
            }

            is ExprNode.Builtin.Null -> {
                currentAddress = 0L
                addStep(
                    "返回空",
                    before,
                    currentAddress,
                    "@null"
                )
            }

            is ExprNode.Builtin.Stop -> {
                shouldStop = true
                addStep(
                    "停止执行",
                    before,
                    currentAddress,
                    "@stop"
                )
            }

            is ExprNode.Builtin.ArrayAccess -> {
                val index = evaluateOperand(node.indexExpr)
                val elemSize = node.elemSize ?: 8

                currentAddress += index * elemSize

                addStep(
                    "数组访问",
                    before,
                    currentAddress,
                    "@[%d] (elem_size=%d, offset=+0x%X)".format(index, elemSize, index * elemSize)
                )
            }
        }
    }

    /**
     * 评估条件表达式
     */
    private fun evaluateCondition(condition: Condition): Boolean {
        return when (condition) {
            is Condition.Compare -> {
                val left = evaluateOperand(condition.left)
                val right = evaluateOperand(condition.right)
                when (condition.op) {
                    CompareOp.EQ -> left == right
                    CompareOp.NE -> left != right
                    CompareOp.GT -> left > right
                    CompareOp.LT -> left < right
                    CompareOp.GE -> left >= right
                    CompareOp.LE -> left <= right
                }
            }

            is Condition.Bitwise -> {
                val left = evaluateOperand(condition.left)
                val right = evaluateOperand(condition.right)
                val result = when (condition.op) {
                    BitwiseOp.AND -> left and right
                    BitwiseOp.OR -> left or right
                    BitwiseOp.XOR -> left xor right
                }
                // 位运算结果非0为真
                result != 0L
            }

            is Condition.Logical -> {
                when (condition.op) {
                    LogicalOp.AND -> {
                        evaluateCondition(condition.left) && evaluateCondition(condition.right)
                    }

                    LogicalOp.OR -> {
                        evaluateCondition(condition.left) || evaluateCondition(condition.right)
                    }
                }
            }

            is Condition.Not -> {
                !evaluateCondition(condition.condition)
            }
        }
    }

    /**
     * 评估操作数
     */
    private fun evaluateOperand(operand: Operand): Long {
        return when (operand) {
            is Operand.Current -> currentAddress

            is Operand.Variable -> {
                variables[operand.name]
                    ?: throw ExecutionException("未定义的变量: ${operand.name}")
            }

            is Operand.Constant -> operand.value
        }
    }

    /**
     * 将字节数组转换为地址值
     * @param bytes 字节数组
     * @param type 数据类型
     * @return 地址值
     */
    private fun bytesToAddress(bytes: ByteArray, type: DataType): Long {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        return when (type) {
            DataType.U8 -> buffer.get().toLong() and 0xFF
            DataType.U16 -> buffer.short.toLong() and 0xFFFF
            DataType.U32 -> buffer.int.toLong() and 0xFFFFFFFFL
            DataType.U64 -> buffer.long

            DataType.I8 -> buffer.get().toLong()
            DataType.I16 -> buffer.short.toLong()
            DataType.I32 -> buffer.int.toLong()
            DataType.I64 -> buffer.long
        }
    }

    /**
     * 读取指定地址的内存值（所有数据类型）
     * @param address 目标地址
     * @return 类型代码 -> 值字符串的映射
     */
    private fun readMemoryValues(address: Long): Map<String, String> {
        val results = mutableMapOf<String, String>()

        // 读取所有数据类型
        val types = DataType.entries

        for (type in types) {
            try {
                val bytes = WuwaDriver.readMemory(address, type.byteSize)
                if (bytes != null) {
                    val value = bytesToAddress(bytes, type)

                    // 格式化输出
                    val formatted = when (type) {
                        DataType.U8, DataType.U16, DataType.U32, DataType.U64 -> {
                            // 无符号：十进制 (十六进制)
                            "%d (0x%X)".format(value, value)
                        }

                        DataType.I8, DataType.I16, DataType.I32, DataType.I64 -> {
                            // 有符号：十进制 (十六进制)
                            "%d (0x%X)".format(value, value)
                        }
                    }

                    results[type.code] = formatted
                } else {
                    results[type.code] = "读取失败"
                }
            } catch (e: Exception) {
                results[type.code] = "错误: ${e.message}"
            }
        }

        return results
    }

    /**
     * 添加执行步骤记录
     */
    private fun addStep(
        description: String,
        addressBefore: Long,
        addressAfter: Long,
        operation: String
    ) {
        steps.add(
            ExecutionStep(
                stepIndex = stepIndex++,
                description = description,
                addressBefore = addressBefore,
                addressAfter = addressAfter,
                operation = operation,
                variables = variables.toMap()  // 拷贝当前变量状态
            )
        )
    }
}
