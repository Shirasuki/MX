package moe.fuqiuluo.mamu.pp

/**
 * Token类型
 */
enum class TokenType {
    // 字面量和标识符
    NUMBER,        // 123, 0x10
    IDENTIFIER,    // ptr, base
    DATA_TYPE,     // u64, i32

    // 符号
    DOLLAR,        // $
    UNDERSCORE,    // _
    COLON,         // :
    STAR,          // *
    PLUS,          // +
    MINUS,         // -
    QUESTION,      // ?
    AT,            // @
    LBRACKET,      // [
    RBRACKET,      // ]
    LPAREN,        // (
    RPAREN,        // )
    COMMA,         // ,

    // 比较运算符
    EQ,            // ==
    NE,            // !=
    GT,            // >
    LT,            // <
    GE,            // >=
    LE,            // <=

    // 位运算符
    AND,           // &
    OR,            // |
    XOR,           // ^

    // 逻辑运算符
    LAND,          // &&
    LOR,           // ||
    NOT,           // !

    // 特殊
    EOF            // 文件结束
}

/**
 * Token数据类
 */
data class Token(
    val type: TokenType,
    val value: String,
    val position: Int
)

/**
 * 词法分析器
 */
object PtrPathTokenizer {

    // 数据类型关键字
    private val dataTypes = setOf("u8", "u16", "u32", "u64", "i8", "i16", "i32", "i64")

    // 内建关键字
    private val builtinKeywords = setOf("skip", "null", "stop", "current")

    /**
     * 词法分析主函数
     * @param input 输入字符串
     * @param hexMode 十六进制模式（true时所有数字按十六进制解析）
     * @return Token列表
     */
    fun tokenize(input: String, hexMode: Boolean = false): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        while (pos < input.length) {
            val ch = input[pos]

            // 跳过空白字符
            if (ch.isWhitespace()) {
                pos++
                continue
            }

            // 数字
            if (ch.isDigit() || (hexMode && ch in 'a'..'f') || (hexMode && ch in 'A'..'F')) {
                val (token, newPos) = scanNumber(input, pos, hexMode)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 十六进制数字（0x前缀）
            if (ch == '0' && pos + 1 < input.length && input[pos + 1] in "xX") {
                val (token, newPos) = scanHexNumber(input, pos)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 标识符或关键字
            if (ch.isLetter() || ch == '_') {
                val (token, newPos) = scanIdentifier(input, pos)
                tokens.add(token)
                pos = newPos
                continue
            }

            // 美元符号 $
            if (ch == '$') {
                tokens.add(Token(TokenType.DOLLAR, "$", pos))
                pos++
                continue
            }

            // 双字符运算符
            if (pos + 1 < input.length) {
                val twoChar = input.substring(pos, pos + 2)
                val tokenType = when (twoChar) {
                    "==" -> TokenType.EQ
                    "!=" -> TokenType.NE
                    ">=" -> TokenType.GE
                    "<=" -> TokenType.LE
                    "&&" -> TokenType.LAND
                    "||" -> TokenType.LOR
                    else -> null
                }

                if (tokenType != null) {
                    tokens.add(Token(tokenType, twoChar, pos))
                    pos += 2
                    continue
                }
            }

            // 单字符符号
            val tokenType = when (ch) {
                ':' -> TokenType.COLON
                '*' -> TokenType.STAR
                '+' -> TokenType.PLUS
                '-' -> TokenType.MINUS
                '?' -> TokenType.QUESTION
                '@' -> TokenType.AT
                '[' -> TokenType.LBRACKET
                ']' -> TokenType.RBRACKET
                '(' -> TokenType.LPAREN
                ')' -> TokenType.RPAREN
                ',' -> TokenType.COMMA
                '>' -> TokenType.GT
                '<' -> TokenType.LT
                '&' -> TokenType.AND
                '|' -> TokenType.OR
                '^' -> TokenType.XOR
                '!' -> TokenType.NOT
                else -> null
            }

            if (tokenType != null) {
                tokens.add(Token(tokenType, ch.toString(), pos))
                pos++
                continue
            }

            // 无法识别的字符
            throw IllegalArgumentException("无法识别的字符 '$ch' 在位置 $pos")
        }

        tokens.add(Token(TokenType.EOF, "", pos))
        return tokens
    }

    /**
     * 扫描数字
     */
    private fun scanNumber(input: String, start: Int, hexMode: Boolean): Pair<Token, Int> {
        var pos = start
        val sb = StringBuilder()

        while (pos < input.length) {
            val ch = input[pos]
            if (hexMode) {
                if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') {
                    sb.append(ch)
                    pos++
                } else {
                    break
                }
            } else {
                if (ch.isDigit()) {
                    sb.append(ch)
                    pos++
                } else {
                    break
                }
            }
        }

        return Token(TokenType.NUMBER, sb.toString(), start) to pos
    }

    /**
     * 扫描十六进制数字（0x前缀）
     */
    private fun scanHexNumber(input: String, start: Int): Pair<Token, Int> {
        var pos = start + 2  // 跳过 "0x"
        val sb = StringBuilder("0x")

        while (pos < input.length) {
            val ch = input[pos]
            if (ch.isDigit() || ch in 'a'..'f' || ch in 'A'..'F') {
                sb.append(ch)
                pos++
            } else {
                break
            }
        }

        if (sb.length == 2) {
            throw IllegalArgumentException("无效的十六进制数字在位置 $start")
        }

        return Token(TokenType.NUMBER, sb.toString(), start) to pos
    }

    /**
     * 扫描标识符或关键字
     */
    private fun scanIdentifier(input: String, start: Int): Pair<Token, Int> {
        var pos = start
        val sb = StringBuilder()

        // 第一个字符必须是字母或下划线
        if (input[pos].isLetter() || input[pos] == '_') {
            sb.append(input[pos])
            pos++
        }

        // 后续字符可以是字母、数字或下划线
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
            sb.append(input[pos])
            pos++
        }

        val identifier = sb.toString()

        // 判断是否是数据类型关键字
        val tokenType = when {
            identifier in dataTypes -> TokenType.DATA_TYPE
            else -> TokenType.IDENTIFIER
        }

        return Token(tokenType, identifier, start) to pos
    }
}
