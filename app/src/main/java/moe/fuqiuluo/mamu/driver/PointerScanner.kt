@file:Suppress("KotlinJniMissingFunction")

package moe.fuqiuluo.mamu.driver

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin interface for the native pointer scanner.
 *
 * Pointer scanning finds all memory paths (chains) from static modules
 * to a target address. This is useful for creating stable pointers that
 * survive process restarts.
 *
 * Example usage:
 * ```kotlin
 * // Initialize
 * PointerScanner.init(cacheDir.absolutePath)
 *
 * // Start scan
 * val regions = WuwaDriver.queryMemRegions()
 * PointerScanner.startScan(
 *     targetAddress = 0x7F92A45678,
 *     maxDepth = 5,
 *     maxOffset = 0x1000,
 *     regions = regions
 * )
 *
 * // Monitor progress
 * while (PointerScanner.getPhase() == Phase.SCANNING_POINTERS ||
 *        PointerScanner.getPhase() == Phase.BUILDING_CHAINS) {
 *     val progress = PointerScanner.getProgress()
 *     // Update UI
 *     delay(100)
 * }
 *
 * // Get results
 * val chains = PointerScanner.getChains(0, 100)
 * ```
 */
object PointerScanner {
    /**
     * Shared buffer size in bytes.
     * Memory layout:
     * [0-3]   phase          (Rust writes)  ScanPhase enum
     * [4-7]   progress       (Rust writes)  0-100
     * [8-11]  regions_done   (Rust writes)  completed region count
     * [12-19] pointers_found (Rust writes)  total pointers found (i64)
     * [20-27] chains_found   (Rust writes)  total chains found (i64)
     * [28-31] current_depth  (Rust writes)  current search depth
     * [32-35] heartbeat      (Rust writes)  periodic value for liveness
     * [36-39] cancel_flag    (Kotlin writes) 1 = cancel requested
     * [40-43] error_code     (Rust writes)  error code when phase is Error
     * [44-47] reserved
     */
    const val SHARED_BUFFER_SIZE = 48

    /** Scan phase constants. */
    object Phase {
        const val IDLE = 0
        const val SCANNING_POINTERS = 1
        const val BUILDING_CHAINS = 2
        const val COMPLETED = 3
        const val CANCELLED = 4
        const val ERROR = 5
    }

    /** Error code constants. */
    object ErrorCode {
        const val NONE = 0
        const val NOT_INITIALIZED = 1
        const val INVALID_ADDRESS = 2
        const val MEMORY_READ_FAILED = 3
        const val INTERNAL_ERROR = 4
        const val ALREADY_SCANNING = 5
        const val NO_PROCESS_BOUND = 6
        const val STORAGE_ERROR = 7
    }

    /** Shared buffer offsets. */
    private object Offset {
        const val PHASE = 0
        const val PROGRESS = 4
        const val REGIONS_DONE = 8
        const val POINTERS_FOUND = 12
        const val CHAINS_FOUND = 20
        const val CURRENT_DEPTH = 28
        const val HEARTBEAT = 32
        const val CANCEL_FLAG = 36
        const val ERROR_CODE = 40
    }

    private var sharedBuffer: ByteBuffer? = null
    private var isInitialized = false

    /**
     * Initialize the pointer scanner.
     * @param cacheDir Directory for temporary cache files (mmap storage).
     * @return Whether initialization was successful.
     */
    fun init(cacheDir: String): Boolean {
        if (isInitialized) return true

        if (nativeInit(cacheDir)) {
            // Allocate shared buffer for progress communication
            sharedBuffer = ByteBuffer.allocateDirect(SHARED_BUFFER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            nativeSetSharedBuffer(sharedBuffer!!)
            isInitialized = true
            return true
        }
        return false
    }

    /**
     * Gets the shared buffer for direct access to progress information.
     */
    fun getSharedBuffer(): ByteBuffer? = sharedBuffer

    /**
     * Reads current scan phase from shared buffer.
     * @return One of Phase constants.
     */
    fun getPhase(): Int = sharedBuffer?.getInt(Offset.PHASE) ?: Phase.IDLE

    /**
     * Reads current progress from shared buffer.
     * @return Progress value 0-100.
     */
    fun getProgress(): Int = sharedBuffer?.getInt(Offset.PROGRESS) ?: 0

    /**
     * Reads completed region count from shared buffer.
     */
    fun getRegionsDone(): Int = sharedBuffer?.getInt(Offset.REGIONS_DONE) ?: 0

    /**
     * Reads total pointers found from shared buffer.
     */
    fun getPointersFound(): Long = sharedBuffer?.getLong(Offset.POINTERS_FOUND) ?: 0

    /**
     * Reads total chains found from shared buffer.
     */
    fun getChainsFound(): Long = sharedBuffer?.getLong(Offset.CHAINS_FOUND) ?: 0

    /**
     * Reads current search depth from shared buffer.
     */
    fun getCurrentDepth(): Int = sharedBuffer?.getInt(Offset.CURRENT_DEPTH) ?: 0

    /**
     * Reads heartbeat value from shared buffer.
     */
    fun getHeartbeat(): Int = sharedBuffer?.getInt(Offset.HEARTBEAT) ?: 0

    /**
     * Reads error code from shared buffer.
     * @return One of ErrorCode constants.
     */
    fun getErrorCode(): Int = sharedBuffer?.getInt(Offset.ERROR_CODE) ?: ErrorCode.NONE

    /**
     * Requests cancellation by writing to shared buffer.
     */
    fun requestCancelViaBuffer() {
        sharedBuffer?.putInt(Offset.CANCEL_FLAG, 1)
    }

    /**
     * Clears the cancel flag in shared buffer.
     */
    private fun clearCancelFlag() {
        sharedBuffer?.putInt(Offset.CANCEL_FLAG, 0)
    }

    /**
     * Resets the shared buffer to initial state.
     */
    private fun resetSharedBuffer() {
        sharedBuffer?.let { buffer ->
            for (i in 0 until SHARED_BUFFER_SIZE step 4) {
                buffer.putInt(i, 0)
            }
        }
    }

    /**
     * Checks if a scan is currently in progress.
     */
    fun isScanning(): Boolean = nativeIsScanning()

    /**
     * Requests cancellation via native CancellationToken.
     */
    fun requestCancel() {
        nativeRequestCancel()
    }

    /**
     * Start an async pointer scan.
     *
     * @param targetAddress The address to find pointer chains to.
     * @param maxDepth Maximum depth of pointer chain (default: 5).
     * @param maxOffset Maximum offset per level in bytes (default: 0x1000).
     * @param align Pointer alignment in bytes (default: 4).
     * @param regions Memory regions to scan as list of (start, end, name, isStatic).
     * @return Whether the scan started successfully.
     */
    fun startScan(
        targetAddress: Long,
        maxDepth: Int = 5,
        maxOffset: Int = 0x1000,
        align: Int = 4,
        regions: List<MemoryRegionInfo>,
        isLayerBFS: Boolean
    ): Boolean {
        if (!isInitialized) {
            return false
        }

        // Prepare region data for JNI
        val regionAddresses = LongArray(regions.size * 2)
        val regionNames = Array(regions.size) { "" }
        val staticFlags = BooleanArray(regions.size)

        regions.forEachIndexed { index, region ->
            regionAddresses[index * 2] = region.start
            regionAddresses[index * 2 + 1] = region.end
            regionNames[index] = region.name
            staticFlags[index] = region.isStatic
        }

        resetSharedBuffer()
        clearCancelFlag()

        return nativeStartScan(
            targetAddress,
            maxDepth,
            maxOffset,
            align,
            regionAddresses,
            regionNames,
            staticFlags,
            isLayerBFS
        )
    }

    /**
     * Get the number of chains found.
     */
    fun getChainCount(): Long = nativeGetChainCount()

    /**
     * Get a range of chain results.
     * @param start Starting index.
     * @param count Number of results to retrieve.
     * @return Array of pointer chain results.
     */
    fun getChains(start: Int, count: Int): Array<PointerChainResult> {
        return nativeGetChains(start, count)
    }

    /**
     * Clear all scan results and reset state.
     */
    fun clear() {
        nativeClear()
        resetSharedBuffer()
    }

    /**
     * Get phase as human-readable string.
     */
    fun getPhaseString(): String = when (getPhase()) {
        Phase.IDLE -> "Idle"
        Phase.SCANNING_POINTERS -> "Scanning Pointers"
        Phase.BUILDING_CHAINS -> "Building Chains"
        Phase.COMPLETED -> "Completed"
        Phase.CANCELLED -> "Cancelled"
        Phase.ERROR -> "Error"
        else -> "Unknown"
    }

    /**
     * Get error code as human-readable string.
     */
    fun getErrorString(): String = when (getErrorCode()) {
        ErrorCode.NONE -> "None"
        ErrorCode.NOT_INITIALIZED -> "Not Initialized"
        ErrorCode.INVALID_ADDRESS -> "Invalid Address"
        ErrorCode.MEMORY_READ_FAILED -> "Memory Read Failed"
        ErrorCode.INTERNAL_ERROR -> "Internal Error"
        ErrorCode.ALREADY_SCANNING -> "Already Scanning"
        ErrorCode.NO_PROCESS_BOUND -> "No Process Bound"
        ErrorCode.STORAGE_ERROR -> "Storage Error"
        else -> "Unknown Error"
    }

    // Native method declarations
    private external fun nativeInit(cacheDir: String): Boolean
    private external fun nativeSetSharedBuffer(buffer: ByteBuffer): Boolean
    private external fun nativeStartScan(
        targetAddress: Long,
        maxDepth: Int,
        maxOffset: Int,
        align: Int,
        regions: LongArray,
        regionNames: Array<String>,
        staticFlags: BooleanArray,
        isLayerBFS: Boolean
    ): Boolean
    private external fun nativeIsScanning(): Boolean
    private external fun nativeRequestCancel()
    private external fun nativeGetChainCount(): Long
    private external fun nativeGetChains(start: Int, count: Int): Array<PointerChainResult>
    private external fun nativeClear()
    private external fun nativeGetPhase(): Int
}

/**
 * Information about a memory region for pointer scanning.
 */
data class MemoryRegionInfo(
    val start: Long,
    val end: Long,
    val name: String,
    val isStatic: Boolean
) {
    val size: Long get() = end - start

    companion object {
        /**
         * Create a static module region (code segment).
         */
        fun staticModule(start: Long, end: Long, name: String) =
            MemoryRegionInfo(start, end, name, isStatic = true)

        /**
         * Create a heap/dynamic region.
         */
        fun dynamicRegion(start: Long, end: Long, name: String = "[heap]") =
            MemoryRegionInfo(start, end, name, isStatic = false)
    }
}
