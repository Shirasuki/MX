@file:Suppress("KotlinJniMissingFunction")

package moe.fuqiuluo.mamu.driver

/**
 * Disassembly result item.
 * @param address Instruction address.
 * @param bytes Instruction bytes (hex string).
 * @param mnemonic Instruction mnemonic (e.g., "ldr", "mov").
 * @param operands Instruction operands (e.g., "x0, [x1, #8]").
 * @param pseudoCode Simplified pseudo-code representation (optional).
 */
data class DisassemblyResult(
    val address: Long,
    val bytes: String,
    val mnemonic: String,
    val operands: String,
    val pseudoCode: String?
)

/**
 * ARM instruction disassembler using Capstone engine.
 * Supports ARM32, Thumb, and ARM64 architectures.
 */
object Disassembler {
    init {
        System.loadLibrary("mamu_core")
    }

    /**
     * Architecture modes for disassembly.
     */
    object Architecture {
        const val ARM32 = 0
        const val THUMB = 1
        const val ARM64 = 2
    }

    /**
     * Disassembles ARM32 instructions.
     * @param bytes Instruction bytes to disassemble.
     * @param address Starting address for the instructions.
     * @param count Maximum number of instructions to disassemble (0 = all).
     * @return Array of disassembly results.
     */
    fun disassembleARM32(
        bytes: ByteArray,
        address: Long = 0,
        count: Int = 0
    ): Array<DisassemblyResult> {
        return nativeDisassemble(Architecture.ARM32, bytes, address, count)
    }

    /**
     * Disassembles Thumb instructions.
     * @param bytes Instruction bytes to disassemble.
     * @param address Starting address for the instructions.
     * @param count Maximum number of instructions to disassemble (0 = all).
     * @return Array of disassembly results.
     */
    fun disassembleThumb(
        bytes: ByteArray,
        address: Long = 0,
        count: Int = 0
    ): Array<DisassemblyResult> {
        return nativeDisassemble(Architecture.THUMB, bytes, address, count)
    }

    /**
     * Disassembles ARM64 instructions.
     * @param bytes Instruction bytes to disassemble.
     * @param address Starting address for the instructions.
     * @param count Maximum number of instructions to disassemble (0 = all).
     * @return Array of disassembly results.
     */
    fun disassembleARM64(
        bytes: ByteArray,
        address: Long = 0,
        count: Int = 0
    ): Array<DisassemblyResult> {
        return nativeDisassemble(Architecture.ARM64, bytes, address, count)
    }

    /**
     * Generates pseudo-code for ARM instructions.
     * This provides a simplified, high-level representation of the instruction's operation.
     * @param architecture Architecture mode (ARM32, THUMB, or ARM64).
     * @param bytes Instruction bytes.
     * @param address Starting address for the instructions.
     * @param count Maximum number of instructions to process (0 = all).
     * @return Array of disassembly results with pseudo-code.
     */
    fun generatePseudoCode(
        architecture: Int,
        bytes: ByteArray,
        address: Long = 0,
        count: Int = 0
    ): Array<DisassemblyResult> {
        return nativeGeneratePseudoCode(architecture, bytes, address, count)
    }

    /**
     * Reads memory from current bound process and disassembles.
     * @param architecture Architecture mode.
     * @param address Memory address to read from.
     * @param size Number of bytes to read.
     * @return Array of disassembly results, or empty array if read fails.
     */
    fun disassembleFromMemory(
        architecture: Int,
        address: Long,
        size: Int
    ): Array<DisassemblyResult> {
        val bytes = WuwaDriver.readMemory(address, size) ?: return emptyArray()
        return nativeDisassemble(architecture, bytes, address, 0)
    }

    private external fun nativeDisassemble(
        architecture: Int,
        bytes: ByteArray,
        address: Long,
        count: Int
    ): Array<DisassemblyResult>

    private external fun nativeGeneratePseudoCode(
        architecture: Int,
        bytes: ByteArray,
        address: Long,
        count: Int
    ): Array<DisassemblyResult>
}
