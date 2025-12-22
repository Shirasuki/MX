package moe.fuqiuluo.mamu.pp

/**
 * 解析异常
 */
class ParseException(
    message: String,
    val position: Int = -1
) : Exception(if (position >= 0) "$message at position $position" else message)

private class TokenIterator(private val tokens: List<Token>) {
    private var index = 0

    fun current(): Token = if (index < tokens.size) tokens[index] else tokens.last()

    fun peek(offset: Int = 1): Token? {
        val pos = index + offset
        return if (pos < tokens.size) tokens[pos] else null
    }

    fun hasNext(): Boolean = index < tokens.size && current().type != TokenType.EOF

    fun consume(): Token {
        val token = current()
        if (index < tokens.size - 1) index++
        return token
    }

    fun expect(type: TokenType): Token {
        val token = current()
        if (token.type != type) {
            throw ParseException(
                "期望 ${type}，但得到 ${token.type}: '${token.value}'",
                token.position
            )
        }
        return consume()
    }

    fun match(type: TokenType): Boolean = current().type == type

    fun matchAny(vararg types: TokenType): Boolean = types.any { match(it) }
}

/**
 * 语法解析器
 */
object PtrPathParser {

    /**
     * 解析表达式列表
     * @param tokens Token列表
     * @return AST节点列表
     */
    fun parse(tokens: List<Token>): List<ExprNode> {
        if (tokens.isEmpty() || (tokens.size == 1 && tokens[0].type == TokenType.EOF)) {
            throw ParseException("空表达式")
        }

        val iterator = TokenIterator(tokens)
        val nodes = mutableListOf<ExprNode>()

        while (iterator.hasNext()) {
            val node = parseNode(iterator)
            if (node != null) {
                nodes.add(node)
            }
        }

        if (nodes.isEmpty()) {
            throw ParseException("未解析到任何有效节点")
        }

        return nodes
    }

    /**
     * 解析单个节点
     */
    private fun parseNode(iter: TokenIterator): ExprNode? {
        val token = iter.current()

        return when (token.type) {
            // 偏移量：数字（可能带+/-）
            TokenType.PLUS, TokenType.MINUS, TokenType.NUMBER -> {
                parseOffset(iter)
            }

            // 解引用：*type
            TokenType.STAR -> {
                parseDeref(iter)
            }

            // 变量定义：identifier:
            TokenType.IDENTIFIER if iter.peek()?.type == TokenType.COLON -> {
                parseVarDef(iter)
            }

            // 变量引用：$identifier
            TokenType.DOLLAR -> {
                parseVarRef(iter)
            }

            // 下划线：_: (定义) 或 _ (引用current)
            TokenType.UNDERSCORE -> {
                parseUnderscore(iter)
            }

            // 内建操作：@skip, @null, @stop, @[index]
            TokenType.AT -> {
                parseBuiltin(iter)
            }

            // 条件表达式：需要lookahead查找?
            else -> {
                // 尝试解析条件表达式
                if (canStartCondition(iter)) {
                    parseConditional(iter)
                } else {
                    throw ParseException(
                        "无法识别的token: ${token.type} '${token.value}'",
                        token.position
                    )
                }
            }
        }
    }

    /**
     * 解析偏移量：4, +8, -0x10
     */
    private fun parseOffset(iter: TokenIterator): ExprNode.Offset {
        val token = iter.current()
        var sign = 1L

        // 处理符号
        if (token.type == TokenType.PLUS) {
            iter.consume()
        } else if (token.type == TokenType.MINUS) {
            sign = -1L
            iter.consume()
        }

        // 解析数字
        val numToken = iter.expect(TokenType.NUMBER)
        val value = parseNumber(numToken.value)

        return ExprNode.Offset(sign * value)
    }

    /**
     * 解析数字（支持十进制和0x十六进制）
     */
    private fun parseNumber(str: String): Long {
        return try {
            when {
                str.startsWith("0x") || str.startsWith("0X") -> {
                    str.substring(2).toLong(16)
                }
                // 检测是否包含十六进制字符（a-f, A-F），如果有则按十六进制解析
                str.any { it in 'a'..'f' || it in 'A'..'F' } -> {
                    str.toLong(16)
                }
                else -> str.toLong(10)
            }
        } catch (e: NumberFormatException) {
            throw ParseException("无效的数字: $str")
        }
    }

    /**
     * 解析解引用：*u64, **u32, ***i32
     */
    private fun parseDeref(iter: TokenIterator): ExprNode.Deref {
        // 统计连续的*数量
        var count = 0
        while (iter.match(TokenType.STAR)) {
            count++
            iter.consume()
        }

        if (count == 0) {
            throw ParseException("解引用操作需要至少一个*")
        }

        // 解析数据类型
        val typeToken = iter.expect(TokenType.DATA_TYPE)
        val dataType = DataType.fromCode(typeToken.value)
            ?: throw ParseException("无效的数据类型: ${typeToken.value}", typeToken.position)

        return ExprNode.Deref(dataType, count)
    }

    /**
     * 解析变量定义：name: expr1 expr2 ...
     * 注意：变量定义会消费后续的表达式直到遇到不能解析的token或特殊分隔符
     */
    private fun parseVarDef(iter: TokenIterator): ExprNode.VarDef {
        val nameToken = iter.expect(TokenType.IDENTIFIER)
        iter.expect(TokenType.COLON)

        // 解析变量定义的子表达式
        // 子表达式可以是：偏移、解引用、变量引用等（但不能是另一个变量定义）
        val subExprs = mutableListOf<ExprNode>()

        // 至少需要一个子表达式
        while (iter.hasNext() && canStartVarDefSubExpr(iter)) {
            val subNode = parseVarDefSubNode(iter)
            if (subNode != null) {
                subExprs.add(subNode)
            } else {
                break
            }
        }

        if (subExprs.isEmpty()) {
            throw ParseException("变量定义 '${nameToken.value}' 缺少子表达式", nameToken.position)
        }

        return ExprNode.VarDef(nameToken.value, subExprs)
    }

    /**
     * 判断是否可以开始变量定义的子表达式
     */
    private fun canStartVarDefSubExpr(iter: TokenIterator): Boolean {
        val token = iter.current()
        return when (token.type) {
            TokenType.PLUS, TokenType.MINUS, TokenType.NUMBER,
            TokenType.STAR, TokenType.DOLLAR, TokenType.UNDERSCORE,
            TokenType.AT -> true
            // 不允许嵌套变量定义
            TokenType.IDENTIFIER -> iter.peek()?.type != TokenType.COLON
            else -> false
        }
    }

    /**
     * 解析变量定义的子节点（不包括变量定义和条件表达式）
     */
    private fun parseVarDefSubNode(iter: TokenIterator): ExprNode? {
        val token = iter.current()

        return when {
            token.type == TokenType.PLUS || token.type == TokenType.MINUS || token.type == TokenType.NUMBER -> {
                parseOffset(iter)
            }

            token.type == TokenType.STAR -> {
                parseDeref(iter)
            }

            token.type == TokenType.DOLLAR -> {
                parseVarRef(iter)
            }

            token.type == TokenType.UNDERSCORE -> {
                parseUnderscore(iter)
            }

            token.type == TokenType.AT -> {
                parseBuiltin(iter)
            }

            else -> null
        }
    }

    /**
     * 解析变量引用：$name
     */
    private fun parseVarRef(iter: TokenIterator): ExprNode.VarRef {
        iter.expect(TokenType.DOLLAR)
        val nameToken = iter.expect(TokenType.IDENTIFIER)
        return ExprNode.VarRef(nameToken.value)
    }

    /**
     * 解析下划线：
     * - _: expr... (变量定义，特殊变量名"_")
     * - _ (引用current，但这里作为特殊处理返回VarRef("_"))
     */
    private fun parseUnderscore(iter: TokenIterator): ExprNode {
        iter.expect(TokenType.UNDERSCORE)

        // 检查是否是 _:
        if (iter.match(TokenType.COLON)) {
            iter.consume() // 消费:

            // 解析子表达式
            val subExprs = mutableListOf<ExprNode>()
            while (iter.hasNext() && canStartVarDefSubExpr(iter)) {
                val subNode = parseVarDefSubNode(iter)
                if (subNode != null) {
                    subExprs.add(subNode)
                } else {
                    break
                }
            }

            if (subExprs.isEmpty()) {
                throw ParseException("下划线变量定义 '_:' 缺少子表达式")
            }

            return ExprNode.VarDef("_", subExprs)
        }

        // 单独的 _ 表示引用下划线变量（或current）
        // 这里统一作为 VarRef("_") 处理，执行器会特殊处理
        return ExprNode.VarRef("_")
    }

    /**
     * 解析内建操作：@skip, @null, @stop, @[index]
     */
    private fun parseBuiltin(iter: TokenIterator): ExprNode.Builtin {
        iter.expect(TokenType.AT)

        val token = iter.current()

        // @[index] 或 @[index,elemSize]
        if (token.type == TokenType.LBRACKET) {
            return parseArrayAccess(iter)
        }

        // @keyword
        if (token.type != TokenType.IDENTIFIER) {
            throw ParseException("期望内建操作符标识符，但得到 ${token.type}", token.position)
        }

        return when (val keyword = iter.consume().value) {
            "skip" -> ExprNode.Builtin.Skip
            "null" -> ExprNode.Builtin.Null
            "stop" -> ExprNode.Builtin.Stop
            else -> throw ParseException("未知的内建操作符: @$keyword", token.position)
        }
    }

    /**
     * 解析数组访问：@[index] 或 @[index,elemSize]
     */
    private fun parseArrayAccess(iter: TokenIterator): ExprNode.Builtin.ArrayAccess {
        iter.expect(TokenType.LBRACKET)

        // 解析索引表达式（可以是常量、变量引用或current）
        val indexOperand = parseOperand(iter)

        var elemSize: Int? = null

        // 检查是否有逗号和元素大小
        if (iter.match(TokenType.COMMA)) {
            iter.consume()
            val sizeToken = iter.expect(TokenType.NUMBER)
            elemSize = parseNumber(sizeToken.value).toInt()
        }

        iter.expect(TokenType.RBRACKET)

        return ExprNode.Builtin.ArrayAccess(indexOperand, elemSize)
    }

    /**
     * 判断当前位置是否可以开始一个条件表达式
     * 通过lookahead查找?符号
     */
    private fun canStartCondition(iter: TokenIterator): Boolean {
        // 简单的lookahead：扫描直到找到?或遇到不可能的token
        var offset = 0
        while (true) {
            val token = iter.peek(offset) ?: return false
            when (token.type) {
                TokenType.QUESTION -> return true
                TokenType.EOF, TokenType.COLON -> return false  // 不可能在条件中
                else -> offset++
            }
            if (offset > 50) return false  // 防止无限循环
        }
    }

    /**
     * 解析条件表达式：condition ? trueBranch : falseBranch
     */
    private fun parseConditional(iter: TokenIterator): ExprNode.Conditional {
        // 解析条件
        val condition = parseCondition(iter)

        // 期望?
        iter.expect(TokenType.QUESTION)

        // 解析true分支
        val trueBranch = parseBranch(iter, stopAtColon = true)

        // 期望:
        iter.expect(TokenType.COLON)

        // 解析false分支
        val falseBranch = parseBranch(iter, stopAtColon = false)

        return ExprNode.Conditional(condition, trueBranch, falseBranch)
    }

    /**
     * 解析分支表达式（true或false分支）
     */
    private fun parseBranch(iter: TokenIterator, stopAtColon: Boolean): List<ExprNode> {
        val nodes = mutableListOf<ExprNode>()

        while (iter.hasNext()) {
            val token = iter.current()

            // 停止条件
            if (stopAtColon && token.type == TokenType.COLON) {
                break
            }

            // 如果不能开始新节点，停止
            if (!canStartBranchNode(iter)) {
                break
            }

            val node = parseBranchNode(iter)
            if (node != null) {
                nodes.add(node)
            } else {
                break
            }
        }

        if (nodes.isEmpty()) {
            throw ParseException("条件分支不能为空")
        }

        return nodes
    }

    /**
     * 判断是否可以开始分支节点
     */
    private fun canStartBranchNode(iter: TokenIterator): Boolean {
        val token = iter.current()
        return when (token.type) {
            TokenType.PLUS, TokenType.MINUS, TokenType.NUMBER,
            TokenType.STAR, TokenType.DOLLAR, TokenType.UNDERSCORE,
            TokenType.AT -> true

            TokenType.IDENTIFIER -> iter.peek()?.type != TokenType.COLON  // 不允许变量定义
            else -> false
        }
    }

    /**
     * 解析分支节点（不包括变量定义和嵌套条件）
     */
    private fun parseBranchNode(iter: TokenIterator): ExprNode? {
        val token = iter.current()

        return when {
            token.type == TokenType.PLUS || token.type == TokenType.MINUS || token.type == TokenType.NUMBER -> {
                parseOffset(iter)
            }

            token.type == TokenType.STAR -> {
                parseDeref(iter)
            }

            token.type == TokenType.DOLLAR -> {
                parseVarRef(iter)
            }

            token.type == TokenType.UNDERSCORE -> {
                parseUnderscore(iter)
            }

            token.type == TokenType.AT -> {
                parseBuiltin(iter)
            }

            else -> null
        }
    }

    /**
     * 解析条件（支持逻辑运算）
     * condition ::= logicalOr
     */
    private fun parseCondition(iter: TokenIterator): Condition {
        return parseLogicalOr(iter)
    }

    /**
     * 解析逻辑或：condition || condition
     */
    private fun parseLogicalOr(iter: TokenIterator): Condition {
        var left = parseLogicalAnd(iter)

        while (iter.match(TokenType.LOR)) {
            iter.consume()
            val right = parseLogicalAnd(iter)
            left = Condition.Logical(left, LogicalOp.OR, right)
        }

        return left
    }

    /**
     * 解析逻辑与：condition && condition
     */
    private fun parseLogicalAnd(iter: TokenIterator): Condition {
        var left = parseLogicalNot(iter)

        while (iter.match(TokenType.LAND)) {
            iter.consume()
            val right = parseLogicalNot(iter)
            left = Condition.Logical(left, LogicalOp.AND, right)
        }

        return left
    }

    /**
     * 解析逻辑非：!condition
     */
    private fun parseLogicalNot(iter: TokenIterator): Condition {
        if (iter.match(TokenType.NOT)) {
            iter.consume()
            val condition = parseLogicalNot(iter)  // 支持多重否定
            return Condition.Not(condition)
        }

        return parseComparison(iter)
    }

    /**
     * 解析比较或位运算：operand op operand
     */
    private fun parseComparison(iter: TokenIterator): Condition {
        // 可能需要括号支持
        if (iter.match(TokenType.LPAREN)) {
            iter.consume()
            val condition = parseCondition(iter)
            iter.expect(TokenType.RPAREN)
            return condition
        }

        // 解析左操作数
        val left = parseOperand(iter)

        val opToken = iter.current()

        // 比较运算符
        if (opToken.type in listOf(
                TokenType.EQ, TokenType.NE, TokenType.GT,
                TokenType.LT, TokenType.GE, TokenType.LE
            )
        ) {
            iter.consume()
            val op = when (opToken.type) {
                TokenType.EQ -> CompareOp.EQ
                TokenType.NE -> CompareOp.NE
                TokenType.GT -> CompareOp.GT
                TokenType.LT -> CompareOp.LT
                TokenType.GE -> CompareOp.GE
                TokenType.LE -> CompareOp.LE
                else -> throw ParseException("不应该到达此处")
            }
            val right = parseOperand(iter)
            return Condition.Compare(left, op, right)
        }

        // 位运算符
        if (opToken.type in listOf(TokenType.AND, TokenType.OR, TokenType.XOR)) {
            iter.consume()
            val op = when (opToken.type) {
                TokenType.AND -> BitwiseOp.AND
                TokenType.OR -> BitwiseOp.OR
                TokenType.XOR -> BitwiseOp.XOR
                else -> throw ParseException("不应该到达此处")
            }
            val right = parseOperand(iter)
            return Condition.Bitwise(left, op, right)
        }

        throw ParseException("期望比较或位运算符，但得到 ${opToken.type}", opToken.position)
    }

    /**
     * 解析操作数：_, $var, 常量
     */
    private fun parseOperand(iter: TokenIterator): Operand {
        val token = iter.current()

        return when (token.type) {
            TokenType.UNDERSCORE -> {
                iter.consume()
                Operand.Current
            }

            TokenType.DOLLAR -> {
                iter.consume()
                val nameToken = iter.expect(TokenType.IDENTIFIER)
                Operand.Variable(nameToken.value)
            }

            TokenType.NUMBER -> {
                val numToken = iter.consume()
                val value = parseNumber(numToken.value)
                Operand.Constant(value)
            }

            else -> throw ParseException(
                "期望操作数（_, \$var, 或常量），但得到 ${token.type}",
                token.position
            )
        }
    }
}
