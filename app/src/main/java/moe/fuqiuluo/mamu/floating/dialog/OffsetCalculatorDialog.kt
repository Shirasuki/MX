package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.annotation.ColorInt
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.databinding.DialogOffsetCalculatorBinding
import moe.fuqiuluo.mamu.driver.Disassembler
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.FormattedValue
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.NavigateToMemoryAddressEvent
import moe.fuqiuluo.mamu.pp.ExecutionException
import moe.fuqiuluo.mamu.pp.ExecutionResult
import moe.fuqiuluo.mamu.pp.ParseException
import moe.fuqiuluo.mamu.pp.PtrPathExecutor
import moe.fuqiuluo.mamu.pp.PtrPathParser
import moe.fuqiuluo.mamu.pp.PtrPathTokenizer
import moe.fuqiuluo.mamu.widget.BuiltinKeyboard
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 偏移量计算器对话框
 */
class OffsetCalculatorDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val clipboardManager: ClipboardManager,
    private val initialBaseAddress: Long? = null
) : BaseDialog(context) {
    private var currentFocusedInput: EditText? = null
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var executionResult: ExecutionResult? = null
    private var calculateJob: Job? = null

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun setupDialog() {
        val binding = DialogOffsetCalculatorBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 配置键盘方向
        val isPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        binding.builtinKeyboard.setScreenOrientation(isPortrait)

        // 根据配置决定是否禁用系统输入法
        val useBuiltinKeyboard = mmkv.keyboardType == 0
        if (useBuiltinKeyboard) {
            binding.inputBaseAddress.showSoftInputOnFocus = false
            binding.inputExpression.showSoftInputOnFocus = false
            binding.builtinKeyboard.visibility = View.VISIBLE
            binding.divider.visibility = View.VISIBLE
        } else {
            binding.inputBaseAddress.showSoftInputOnFocus = true
            binding.inputExpression.showSoftInputOnFocus = true
            binding.builtinKeyboard.visibility = View.GONE
            binding.divider.visibility = View.GONE
        }

        // 设置初始基址（如果提供）
        if (initialBaseAddress != null) {
            binding.inputBaseAddress.setText("%X".format(initialBaseAddress))
            binding.resultAddress.text = "结果: 0x" + initialBaseAddress.toHexString()
        } else {
            binding.resultAddress.text = "结果: 0x0"
        }

        val spannable = SpannableStringBuilder()
        spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
        spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
        spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
        spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
        spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
        spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
        spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
        spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
        spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
        spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
        binding.resultText.text = spannable

        // 设置输入框焦点监听
        setupInputFocus(binding)

        // 设置键盘监听器
        setupBuiltinKeyboard(binding)

        // 监听输入变化，实时计算
        setupTextWatchers(binding)

        // 复制按钮
        binding.btnCopy.setOnClickListener {
            copyResultToClipboard(binding)
        }

        // 跳转按钮
        binding.btnGoto.setOnClickListener {
            jumpToFinalAddress()
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun setupInputFocus(binding: DialogOffsetCalculatorBinding) {
        val inputs = listOf(
            binding.inputBaseAddress,
            binding.inputExpression
        )

        inputs.forEach { input ->
            input.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentFocusedInput = view as EditText
                }
            }
        }

        // 默认焦点在基址输入框
        currentFocusedInput = binding.inputExpression
        binding.inputExpression.requestFocus()
    }

    private fun setupBuiltinKeyboard(binding: DialogOffsetCalculatorBinding) {
        binding.builtinKeyboard.listener = object : BuiltinKeyboard.KeyboardListener {
            override fun onKeyInput(key: String) {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd
                editable.replace(selectionStart, selectionEnd, key)
            }

            override fun onDelete() {
                val input = currentFocusedInput ?: return
                val editable = input.text ?: return
                val selectionStart = input.selectionStart
                val selectionEnd = input.selectionEnd

                if (selectionStart != selectionEnd) {
                    editable.delete(selectionStart, selectionEnd)
                } else if (selectionStart > 0) {
                    editable.delete(selectionStart - 1, selectionStart)
                }
            }

            override fun onSelectAll() {
                currentFocusedInput?.selectAll()
            }

            override fun onMoveLeft() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos > 0) {
                    input.setSelection(cursorPos - 1)
                }
            }

            override fun onMoveRight() {
                val input = currentFocusedInput ?: return
                val cursorPos = input.selectionStart
                if (cursorPos < input.text.length) {
                    input.setSelection(cursorPos + 1)
                }
            }

            override fun onHistory() {
                notification.showSuccess("历史记录功能开发中")
            }

            override fun onPaste() {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    val input = currentFocusedInput ?: return
                    val editable = input.text ?: return
                    val selectionStart = input.selectionStart
                    val selectionEnd = input.selectionEnd
                    editable.replace(selectionStart, selectionEnd, text)
                }
            }
        }
    }

    /**
     * 监听输入变化，实时计算表达式
     */
    private fun setupTextWatchers(binding: DialogOffsetCalculatorBinding) {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 延迟执行，避免频繁计算
                calculateJob?.cancel()
                calculateJob = coroutineScope.launch(Dispatchers.Default) {
                    performCalculation(binding)
                }
            }
        }

        binding.inputBaseAddress.addTextChangedListener(textWatcher)
        binding.inputExpression.addTextChangedListener(textWatcher)
    }

    /**
     * 执行表达式计算
     */
    private suspend fun performCalculation(binding: DialogOffsetCalculatorBinding) {
        val baseAddressStr = binding.inputBaseAddress.text.toString().trim()
        val expressionStr = binding.inputExpression.text.toString().trim()
        val hexMode = binding.cbHexMode.isChecked

        try {
            val result = withContext(Dispatchers.IO) {
                // 解析基址
                val baseAddress = try {
                    baseAddressStr.toLong(16)
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw ParseException("基址格式错误: $baseAddressStr")
                }

                // 词法分析
                val tokens = PtrPathTokenizer.tokenize(expressionStr, hexMode)
                // 语法解析
                val ast = PtrPathParser.parse(tokens)
                // 执行
                val executor = PtrPathExecutor(baseAddress)
                executor.execute(ast)
            }

            // 显示结果
            executionResult = result
            withContext(Dispatchers.Main) {
                displayResult(binding, result)
            }
        } catch (e: ParseException) {
            withContext(Dispatchers.Main) {
                // 解析错误
                displayError(binding, "解析错误: ${e.message}")
            }
        } catch (e: ExecutionException) {
            withContext(Dispatchers.Main) {
                // 执行错误
                displayError(binding, "执行错误: ${e.message}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                // 其他错误
                displayError(binding, "错误: ${e.message}")
            }
        }
    }

    /**
     * 显示计算结果
     */
    private fun displayResult(binding: DialogOffsetCalculatorBinding, result: ExecutionResult) {
        binding.resultText.visibility = View.VISIBLE

        if (!result.success) {
            binding.resultAddress.text = "结果: " + (result.errorMessage ?: "表达式异常")
        } else {
            binding.resultAddress.text = "结果: 0x" + result.finalAddress.toHexString()
        }

        val spannable = SpannableStringBuilder()
        if (!result.success) {
            spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
            spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
            spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
            spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
            spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
            spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
            spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
            spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
            return
        }

        // 构建带颜色的结果文本
        val formats = listOf(
            MemoryDisplayFormat.HEX_BIG_ENDIAN,
            MemoryDisplayFormat.HEX_LITTLE_ENDIAN,
            MemoryDisplayFormat.STRING_EXPR,
            MemoryDisplayFormat.UTF16_LE,
            MemoryDisplayFormat.DWORD,
            MemoryDisplayFormat.FLOAT,
            MemoryDisplayFormat.DOUBLE,
            MemoryDisplayFormat.WORD,
            MemoryDisplayFormat.BYTE,
            MemoryDisplayFormat.QWORD,
        )

        val bytes = result.bytes
        if (bytes == null) {
            spannable.appendColored("-h; ", MemoryDisplayFormat.HEX_BIG_ENDIAN.textColor)
            spannable.appendColored("-r; ", MemoryDisplayFormat.HEX_LITTLE_ENDIAN.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.STRING_EXPR.textColor)
            spannable.appendColored("-; ", MemoryDisplayFormat.UTF16_LE.textColor)
            spannable.appendColored("-D; ", MemoryDisplayFormat.DWORD.textColor)
            spannable.appendColored("-F; ", MemoryDisplayFormat.FLOAT.textColor)
            spannable.appendColored("-E; ", MemoryDisplayFormat.DOUBLE.textColor)
            spannable.appendColored("-W; ", MemoryDisplayFormat.WORD.textColor)
            spannable.appendColored("-B; ", MemoryDisplayFormat.BYTE.textColor)
            spannable.appendColored("-Q; ", MemoryDisplayFormat.QWORD.textColor)
        } else run {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hexByteSize = MemoryDisplayFormat.calculateHexByteSize(formats)
            val hexBuilder = StringBuilder(hexByteSize * 2)

            formats.forEach {
                val value = parseValueFast(
                    buffer,
                    result.finalAddress,
                    it,
                    hexByteSize,
                    hexBuilder,
                    result.regions
                )
                spannable.appendColored(
                    "${value.value}${
                        if (it.appendCode) it.code else ""
                    }; ", value.color ?: it.textColor
                )
            }
        }

        binding.resultText.text = spannable
        binding.resultText.isEnabled = true
        binding.btnCopy.isEnabled = true
        binding.btnGoto.isEnabled = result.finalAddress != 0L
    }

    /**
     * 二分查找获取可执行内存区域（用于 HEX 值颜色判断）
     */
    private fun findExecutableRegionBinary(
        address: Long,
        sortedRegions: List<DisplayMemRegionEntry>
    ): DisplayMemRegionEntry? {
        if (sortedRegions.isEmpty()) return null

        var low = 0
        var high = sortedRegions.lastIndex

        while (low <= high) {
            val mid = (low + high) ushr 1
            val region = sortedRegions[mid]

            when {
                address < region.start -> high = mid - 1
                address >= region.end -> low = mid + 1
                else -> return if (region.isExecutable) region else null
            }
        }
        return null
    }

    /**
     * 快速解析内存值
     */
    private fun parseValueFast(
        buffer: ByteBuffer,
        address: Long,
        format: MemoryDisplayFormat,
        hexByteSize: Int,
        hexBuilder: StringBuilder,
        sortedRegions: List<DisplayMemRegionEntry>
    ): FormattedValue {
        val startPos = buffer.position()
        val hexChars = "0123456789ABCDEF".toCharArray()

        try {
            return when (format) {
                MemoryDisplayFormat.HEX_LITTLE_ENDIAN, MemoryDisplayFormat.HEX_BIG_ENDIAN -> {
                    if (buffer.remaining() < hexByteSize) {
                        return FormattedValue(format, "---", Color.WHITE)
                    }

                    // 快速十六进制转换
                    hexBuilder.setLength(0)
                    val bytes = ByteArray(hexByteSize)
                    buffer.get(bytes)

                    if (format == MemoryDisplayFormat.HEX_LITTLE_ENDIAN) {
                        for (b in bytes) {
                            hexBuilder.append(hexChars[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(hexChars[b.toInt() and 0x0F])
                        }
                    } else {
                        for (i in bytes.lastIndex downTo 0) {
                            val b = bytes[i]
                            hexBuilder.append(hexChars[(b.toInt() shr 4) and 0x0F])
                            hexBuilder.append(hexChars[b.toInt() and 0x0F])
                        }
                    }

                    // 将十六进制值解析为地址（快速版本）
                    buffer.position(startPos)
                    val valueAsAddress: Long? = when (hexByteSize) {
                        8 -> buffer.long.takeIf { it >= 0 }
                        4 -> buffer.int.toLong() and 0xFFFFFFFFL
                        2 -> buffer.short.toLong() and 0xFFFFL
                        else -> null
                    }

                    // 使用二分查找获取颜色
                    val color = if (valueAsAddress != null) {
                        findExecutableRegionBinary(valueAsAddress, sortedRegions)?.range?.color
                            ?: Color.WHITE
                    } else {
                        Color.WHITE
                    }

                    FormattedValue(format, hexBuilder.toString(), color)
                }

                MemoryDisplayFormat.DWORD -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.int.toString())
                }

                MemoryDisplayFormat.QWORD -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.long.toString())
                }

                MemoryDisplayFormat.WORD -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.short.toString())
                }

                MemoryDisplayFormat.BYTE -> {
                    if (buffer.remaining() < 1) return FormattedValue(format, "---")
                    FormattedValue(format, buffer.get().toString())
                }

                MemoryDisplayFormat.FLOAT -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    FormattedValue(format, "%.6f".format(buffer.float))
                }

                MemoryDisplayFormat.DOUBLE -> {
                    if (buffer.remaining() < 8) return FormattedValue(format, "---")
                    FormattedValue(format, "%.10f".format(buffer.double))
                }

                MemoryDisplayFormat.UTF16_LE -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val charValue = buffer.short.toInt().toChar()
                    val displayChar = if (charValue.isLetterOrDigit() || charValue.isWhitespace()) {
                        charValue.toString()
                    } else {
                        "."
                    }
                    FormattedValue(format, "\"$displayChar\"")
                }

                MemoryDisplayFormat.STRING_EXPR -> {
                    if (buffer.remaining() < 1) return FormattedValue(format, "---")
                    val bytes = ByteArray(min(4, buffer.remaining()))
                    buffer.get(bytes)
                    val displayString = buildString(bytes.size) {
                        for (b in bytes) {
                            append(if (b in 32..126) b.toInt().toChar() else '.')
                        }
                    }
                    FormattedValue(format, "'$displayString'")
                }

                MemoryDisplayFormat.ARM32 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM32(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.THUMB -> {
                    if (buffer.remaining() < 2) return FormattedValue(format, "---")
                    val bytes = ByteArray(2)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleThumb(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64 -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.disassembleARM64(bytes, address, count = 1)
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            FormattedValue(format, "${insn.mnemonic} ${insn.operands}")
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }

                MemoryDisplayFormat.ARM64_PSEUDO -> {
                    if (buffer.remaining() < 4) return FormattedValue(format, "---")
                    val bytes = ByteArray(4)
                    buffer.get(bytes)
                    try {
                        val results = Disassembler.generatePseudoCode(
                            Disassembler.Architecture.ARM64,
                            bytes,
                            address,
                            count = 1
                        )
                        if (results.isNotEmpty()) {
                            val insn = results[0]
                            // 优先显示伪代码，如果没有则显示汇编
                            val displayText = insn.pseudoCode ?: "${insn.mnemonic} ${insn.operands}"
                            FormattedValue(format, displayText)
                        } else {
                            FormattedValue(format, "???")
                        }
                    } catch (e: Exception) {
                        FormattedValue(format, "err")
                    }
                }
            }
        } finally {
            buffer.position(startPos)
        }
    }

    /**
     * 显示错误信息（红色）
     */
    private fun displayError(binding: DialogOffsetCalculatorBinding, errorMessage: String) {
        binding.resultText.visibility = View.VISIBLE

        val spannable = SpannableStringBuilder()
        spannable.append(errorMessage)
        spannable.setSpan(
            ForegroundColorSpan(0xFFF44336.toInt()), // 红色
            0,
            errorMessage.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.resultText.text = spannable
        binding.btnCopy.isEnabled = false
        binding.btnGoto.isEnabled = false
    }

    /**
     * 复制结果到剪贴板
     */
    private fun copyResultToClipboard(binding: DialogOffsetCalculatorBinding) {
        val result = executionResult ?: return

        val text = buildString {
            append("基址: 0x%X\n".format(result.finalAddress))
            append("表达式: ${binding.inputExpression.text}\n")
            append("最终地址: 0x%X\n".format(result.finalAddress))

            if (result.memoryValues != null) {
                append("\n内存值:\n")
                result.memoryValues.forEach { (type, value) ->
                    append("$type: $value\n")
                }
            }
        }

        val clip = ClipData.newPlainText("偏移量计算结果", text)
        clipboardManager.setPrimaryClip(clip)
        notification.showSuccess("已复制到剪贴板")
    }

    private fun SpannableStringBuilder.appendColored(
        text: String,
        @ColorInt color: Int
    ): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    /**
     * 跳转到最终地址
     */
    private fun jumpToFinalAddress() {
        val result = executionResult ?: return

        if (!result.success || result.finalAddress == 0L) {
            notification.showError("无法跳转：地址无效")
            return
        }

        coroutineScope.launch {
            FloatingEventBus.emitNavigateToMemoryAddress(
                NavigateToMemoryAddressEvent(address = result.finalAddress)
            )
        }

        dialog.dismiss()
    }

    override fun dismiss() {
        calculateJob?.cancel()
        super.dismiss()
    }
}
