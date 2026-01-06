package moe.fuqiuluo.mamu.driver

/**
 * Represents a pointer chain result from pointer scanning.
 *
 * A pointer chain describes a path from a static module base address
 * to a target address through a series of pointer dereferences with offsets.
 *
 * Example chain format: "libil2cpp.so[0]+0x1A2B3C0->+0x18->+0x48"
 * This means:
 * 1. Start at libil2cpp.so base address
 * 2. Add offset 0x1A2B3C0 and read pointer
 * 3. Add offset 0x18 and read pointer
 * 4. Add offset 0x48 to get final target address
 */
data class PointerChainResult(
    /** Formatted chain string (e.g., "libil2cpp.so[0]+0x1A2B3C0->+0x18->+0x48") */
    val chainString: String,
    /** Module name at chain root (e.g., "libil2cpp.so") */
    val moduleName: String,
    /** Module index for duplicate module names */
    val moduleIndex: Int,
    /** All offsets in the chain, including base offset */
    val offsets: LongArray,
    /** The final target address this chain points to */
    val targetAddress: Long
) {
    /**
     * Gets the depth of the chain (number of pointer dereferences).
     */
    val depth: Int get() = offsets.size

    /**
     * Gets the base offset from the module.
     */
    val baseOffset: Long get() = if (offsets.isNotEmpty()) offsets[0] else 0L

    /**
     * Copies the chain string to clipboard-friendly format.
     */
    fun toClipboardString(): String = chainString

    val simpleChainString: String
        get() = chainString.split("/").last()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PointerChainResult

        if (chainString != other.chainString) return false
        if (moduleName != other.moduleName) return false
        if (moduleIndex != other.moduleIndex) return false
        if (!offsets.contentEquals(other.offsets)) return false
        if (targetAddress != other.targetAddress) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chainString.hashCode()
        result = 31 * result + moduleName.hashCode()
        result = 31 * result + moduleIndex
        result = 31 * result + offsets.contentHashCode()
        result = 31 * result + targetAddress.hashCode()
        return result
    }
}
