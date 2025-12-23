@file:Suppress("KotlinJniMissingFunction")

package moe.fuqiuluo.mamu.driver

import android.util.Log
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "SearchEngine"

object SearchEngine {
    /**
     * Shared buffer size in bytes.
     * Memory layout:
     * [0-3]   status         (Rust writes)  SearchStatus enum
     * [4-7]   progress       (Rust writes)  0-100
     * [8-11]  regions_done   (Rust writes)  completed region count
     * [12-19] found_count    (Rust writes)  total results found (i64)
     * [20-23] heartbeat      (Rust writes)  periodic random value
     * [24-27] cancel_flag    (Kotlin writes) 1 = cancel requested
     * [28-31] error_code     (Rust writes)  error code when status is Error
     */
    const val SHARED_BUFFER_SIZE = 32

    /** Search status constants. */
    object Status {
        const val IDLE = 0
        const val SEARCHING = 1
        const val COMPLETED = 2
        const val CANCELLED = 3
        const val ERROR = 4
    }

    /** Error code constants. */
    object ErrorCode {
        const val NONE = 0
        const val NOT_INITIALIZED = 1
        const val INVALID_QUERY = 2
        const val MEMORY_READ_FAILED = 3
        const val INTERNAL_ERROR = 4
        const val ALREADY_SEARCHING = 5
    }

    /** Shared buffer offsets. */
    private object Offset {
        const val STATUS = 0
        const val PROGRESS = 4
        const val REGIONS_DONE = 8
        const val FOUND_COUNT = 12
        const val HEARTBEAT = 20
        const val CANCEL_FLAG = 24
        const val ERROR_CODE = 28
    }

    private var sharedBuffer: ByteBuffer? = null

    /**
     * Initializes the search engine.
     * @param bufferSize Search buffer size in bytes (for caching search results).
     * @param cacheFileDir Cache file directory.
     * @param chunkSize Chunk size in bytes, default 512KB.
     * @return Whether initialization was successful.
     */
    fun initSearchEngine(
        bufferSize: Long,
        cacheFileDir: String,
        chunkSize: Long = 512 * 1024,
    ): Boolean {
        if (nativeInitSearchEngine(bufferSize, cacheFileDir, chunkSize)) {
            // Allocate shared buffer for progress communication.
            sharedBuffer =
                ByteBuffer.allocateDirect(SHARED_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            nativeSetSharedBuffer(sharedBuffer!!)
            return true
        }
        return false
    }

    /**
     * Gets the shared buffer for direct access to progress information.
     * Can be used to read progress or request cancellation.
     */
    fun getSharedBuffer(): ByteBuffer? = sharedBuffer

    /**
     * Reads current search status from shared buffer.
     * @return One of Status constants.
     */
    fun getStatus(): Int = sharedBuffer?.getInt(Offset.STATUS) ?: Status.IDLE

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
     * Reads total found count from shared buffer.
     */
    fun getFoundCount(): Long = sharedBuffer?.getLong(Offset.FOUND_COUNT) ?: 0

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
     * Requests cancellation by writing to shared buffer. No JNI call needed.
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
     * Checks if search is currently running.
     */
    fun isSearching(): Boolean = nativeIsSearching()

    /**
     * Requests cancellation via CancellationToken (JNI call).
     */
    fun requestCancel() {
        nativeRequestCancel()
    }

    /**
     * Starts an async exact/group search. Returns immediately.
     * Progress is communicated via the shared buffer.
     * @param query Search content.
     * @param type Data type.
     * @param ranges Memory range set.
     * @param useDeepSearch Whether to use deep search.
     * @return Whether the search started successfully.
     */
    fun startSearchAsync(
        query: String,
        type: DisplayValueType,
        ranges: Set<MemoryRange>,
        useDeepSearch: Boolean,
        keepResult: Boolean = false,
    ): Boolean {
        val nativeRegions = mutableListOf<Long>()

        WuwaDriver.queryMemRegionsWithRetry()
            .divideToSimpleMemoryRange()
            .filter { ranges.contains(it.range) }
            .forEach {
                nativeRegions.add(it.start)
                nativeRegions.add(it.end)
            }

        clearSharedBuffer()
        newSharedBuffer()

        return nativeStartSearchAsync(
            query,
            type.nativeId,
            nativeRegions.toLongArray(),
            useDeepSearch,
            keepResult
        )
    }

    /**
     * Starts an async exact/group search with custom memory regions.
     * @param query Search content.
     * @param type Data type.
     * @param regions Memory region array, format [start1, end1, start2, end2, ...].
     * @param useDeepSearch Whether to use deep search.
     * @param keepResult Whether to keep existing results when switching modes.
     * @return Whether the search started successfully.
     */
    fun startSearchAsyncWithCustomRange(
        query: String,
        type: DisplayValueType,
        regions: LongArray,
        useDeepSearch: Boolean,
        keepResult: Boolean = false,
    ): Boolean {
        clearSharedBuffer()
        if (!newSharedBuffer()) {
            throw RuntimeException("failed to init SharedBuffer")
        }
        return nativeStartSearchAsync(query, type.nativeId, regions, useDeepSearch, keepResult)
    }

    /**
     * Starts an async refine search. Returns immediately.
     * @param query Search content.
     * @param type Data type.
     * @return Whether the search started successfully.
     */
    fun startRefineAsync(
        query: String,
        type: DisplayValueType,
    ): Boolean {
        clearSharedBuffer()
        newSharedBuffer()
        return nativeStartRefineAsync(query, type.nativeId)
    }

    // Legacy synchronous methods kept for backward compatibility.

    /**
     * Executes exact/group search synchronously (legacy).
     * @param query Search content.
     * @param type Data type.
     * @param ranges Memory range set.
     * @param useDeepSearch Whether to use deep search.
     * @param cb Search progress callback.
     * @return Number of results found.
     */
    @Deprecated("Low performance")
    fun searchExact(
        query: String,
        type: DisplayValueType,
        ranges: Set<MemoryRange>,
        useDeepSearch: Boolean,
        cb: SearchProgressCallback
    ): Long {
        val nativeRegions = mutableListOf<Long>()

        WuwaDriver.queryMemRegionsWithRetry()
            .divideToSimpleMemoryRange()
            .filter { ranges.contains(it.range) }
            .forEach {
                nativeRegions.add(it.start)
                nativeRegions.add(it.end)
            }

        return nativeSearch(query, type.nativeId, nativeRegions.toLongArray(), useDeepSearch, cb)
    }

    /**
     * Executes exact/group search with custom regions synchronously (legacy).
     */
    @Deprecated("Low performance")
    fun exactSearchWithCustomRange(
        query: String,
        type: DisplayValueType,
        regions: LongArray,
        useDeepSearch: Boolean,
        cb: SearchProgressCallback
    ): Long {
        return nativeSearch(query, type.nativeId, regions, useDeepSearch, cb)
    }

    /**
     * Gets search results.
     * @param start Starting index.
     * @param count Number of results to get.
     * @return Search result array.
     */
    fun getResults(start: Int, count: Int): Array<SearchResultItem> {
        return nativeGetResults(start, count).also {
            Log.w(TAG, "getResults($start, $count) -> ${it.size}")
        }
    }

    /**
     * Gets total result count.
     */
    fun getTotalResultCount(): Long {
        return nativeGetTotalResultCount()
    }

    /**
     * Clears search results.
     */
    fun clearSearchResults() {
        nativeClearSearchResults()
    }

    /**
     * Removes a single search result.
     * @param index Search result index.
     * @return Whether removal was successful.
     */
    fun removeResult(index: Int): Boolean {
        return nativeRemoveResult(index)
    }

    /**
     * Removes multiple search results.
     * @param indices Search result index array.
     * @return Whether removal was successful.
     */
    fun removeResults(indices: IntArray): Boolean {
        return nativeRemoveResults(indices)
    }

    /**
     * Keeps only the specified search results, removes all others.
     * @param indices Search result index array to keep.
     * @return Whether operation was successful.
     */
    fun keepOnlyResults(indices: IntArray): Boolean {
        return nativeKeepOnlyResults(indices)
    }

    /**
     * Sets filter conditions (address range, value range, data type, permissions).
     * Only affects search result filtering, does not affect actual search process.
     */
    fun setFilter(
        enableAddressFilter: Boolean,
        addressStart: Long,
        addressEnd: Long,
        enableTypeFilter: Boolean,
        typeIds: IntArray,
    ) {
        nativeSetFilter(
            enableAddressFilter, addressStart, addressEnd,
            enableTypeFilter, typeIds,
        )
    }

    /**
     * Clears all filter conditions.
     */
    fun clearFilter() {
        nativeClearFilter()
    }

    /**
     * Adds results from saved addresses.
     * Clears existing search results and adds new ones from the provided addresses.
     * @param addresses Array of memory addresses.
     * @param types Array of value type IDs corresponding to each address.
     * @return Whether the operation was successful.
     */
    fun addResultsFromAddresses(
        addresses: Collection<Long>,
        types: Array<DisplayValueType>
    ): Boolean {
        return nativeAddResultsFromAddresses(
            addresses.toLongArray(),
            types.map { it.nativeId }.toIntArray()
        )
    }

    /**
     * Gets current search mode.
     * @return Current search mode (EXACT or FUZZY).
     */
    fun getCurrentSearchMode(): SearchMode {
        val nativeValue = nativeGetCurrentSearchMode()
        return SearchMode.fromNativeValue(nativeValue)
    }

    /**
     * Sets compatibility mode.
     * When enabled, all search results are stored in fuzzy format,
     * allowing seamless switching between exact and fuzzy searches.
     * @param enabled Whether to enable compatibility mode.
     */
    fun setCompatibilityMode(enabled: Boolean) {
        nativeSetCompatibilityMode(enabled)
    }

    /**
     * Gets compatibility mode.
     * @return Whether compatibility mode is enabled.
     */
    fun getCompatibilityMode(): Boolean {
        return nativeGetCompatibilityMode()
    }

    /**
     * Starts an async fuzzy initial search. Records all values in memory regions.
     * @param type Data type to search for.
     * @param ranges Memory range set.
     * @param keepResult If true and currently in exact mode, convert exact results to fuzzy results.
     * @return Whether the search started successfully.
     */
    fun startFuzzySearchAsync(
        type: DisplayValueType,
        ranges: Set<MemoryRange>,
        keepResult: Boolean = false,
    ): Boolean {
        val nativeRegions = mutableListOf<Long>()

        WuwaDriver.queryMemRegionsWithRetry()
            .divideToSimpleMemoryRange()
            .filter { ranges.contains(it.range) }
            .forEach {
                nativeRegions.add(it.start)
                nativeRegions.add(it.end)
            }

        clearSharedBuffer()
        newSharedBuffer()

        return nativeStartFuzzySearchAsync(type.nativeId, nativeRegions.toLongArray(), keepResult)
    }

    /**
     * Starts an async fuzzy initial search with custom memory regions.
     * @param type Data type to search for.
     * @param regions Memory region array, format [start1, end1, start2, end2, ...].
     * @param keepResult If true and currently in exact mode, convert exact results to fuzzy results.
     * @return Whether the search started successfully.
     */
    fun startFuzzySearchAsyncWithCustomRange(
        type: DisplayValueType,
        regions: LongArray,
        keepResult: Boolean = false,
    ): Boolean {
        clearSharedBuffer()
        if (!newSharedBuffer()) {
            throw RuntimeException("failed to init SharedBuffer")
        }
        return nativeStartFuzzySearchAsync(type.nativeId, regions, keepResult)
    }

    /**
     * Starts an async fuzzy refine search with a condition.
     * @param condition Fuzzy condition to apply.
     * @param param1 First parameter for conditions that need it.
     * @param param2 Second parameter for range conditions.
     * @return Whether the search started successfully.
     */
    fun startFuzzyRefineAsync(
        condition: FuzzyCondition,
        param1: Long = 0,
        param2: Long = 0,
    ): Boolean {
        clearSharedBuffer()
        newSharedBuffer()
        return nativeStartFuzzyRefineAsync(condition.nativeId, param1, param2)
    }

    /**
     * Executes refine search synchronously (legacy).
     */
    @Deprecated("Low performance")
    fun refineSearch(
        query: String,
        type: DisplayValueType,
        cb: SearchProgressCallback
    ): Long {
        return nativeRefineSearch(query, type.nativeId, cb)
    }

    /**
     * New shared buffer
     */
    private fun newSharedBuffer(): Boolean {
        if (sharedBuffer != null) {
            return false
        }
        val sharedBuffer =
            ByteBuffer.allocateDirect(SHARED_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return setSharedBuffer(sharedBuffer)
    }

    /**
     * Sets shared buffer (DirectByteBuffer).
     * @param buffer DirectByteBuffer, native layer will directly read/write progress data.
     * @return Whether setting was successful.
     */
    private fun setSharedBuffer(buffer: ByteBuffer): Boolean {
        if (!buffer.isDirect) {
            throw IllegalArgumentException("Buffer must be a DirectByteBuffer")
        }
        if (buffer.capacity() < SHARED_BUFFER_SIZE) {
            throw IllegalArgumentException("Buffer must be at least $SHARED_BUFFER_SIZE bytes")
        }
        sharedBuffer = buffer
        return nativeSetSharedBuffer(buffer)
    }

    /**
     * Clears shared buffer.
     */
    private fun clearSharedBuffer() {
        nativeClearSharedBuffer()
        sharedBuffer = null
    }

    // Legacy methods for backward compatibility.

    @Deprecated("Use setSharedBuffer instead", ReplaceWith("setSharedBuffer(buffer)"))
    private fun setProgressBuffer(buffer: ByteBuffer): Boolean = setSharedBuffer(buffer)

    @Deprecated("Use clearSharedBuffer instead", ReplaceWith("clearSharedBuffer()"))
    private fun clearProgressBuffer() = clearSharedBuffer()

    // Native method declarations.
    private external fun nativeInitSearchEngine(
        bufferSize: Long,
        cacheFileDir: String,
        chunkSize: Long
    ): Boolean

    private external fun nativeSetSharedBuffer(buffer: ByteBuffer): Boolean
    private external fun nativeClearSharedBuffer()

    private external fun nativeStartSearchAsync(
        query: String,
        defaultType: Int,
        regions: LongArray,
        useDeepSearch: Boolean,
        keepResult: Boolean
    ): Boolean

    private external fun nativeStartRefineAsync(query: String, defaultType: Int): Boolean
    private external fun nativeIsSearching(): Boolean
    private external fun nativeRequestCancel()

    @Deprecated("同步搜索版本已废弃")
    private external fun nativeSearch(
        query: String,
        defaultType: Int,
        regions: LongArray,
        useDeepSearch: Boolean,
        cb: SearchProgressCallback
    ): Long

    private external fun nativeGetResults(start: Int, count: Int): Array<SearchResultItem>
    private external fun nativeGetTotalResultCount(): Long
    private external fun nativeClearSearchResults()
    private external fun nativeRemoveResult(index: Int): Boolean
    private external fun nativeRemoveResults(indices: IntArray): Boolean
    private external fun nativeKeepOnlyResults(indices: IntArray): Boolean
    private external fun nativeSetFilter(
        enableAddressFilter: Boolean,
        addressStart: Long,
        addressEnd: Long,
        enableTypeFilter: Boolean,
        typeIds: IntArray,
    )

    private external fun nativeClearFilter()
    private external fun nativeGetCurrentSearchMode(): Int
    private external fun nativeSetCompatibilityMode(enabled: Boolean)
    private external fun nativeGetCompatibilityMode(): Boolean
    @Deprecated("同步搜索版本已废弃")
    private external fun nativeRefineSearch(
        query: String,
        defaultType: Int,
        cb: SearchProgressCallback
    ): Long

    private external fun nativeAddResultsFromAddresses(
        addresses: LongArray,
        types: IntArray
    ): Boolean

    private external fun nativeStartFuzzySearchAsync(
        valueType: Int,
        regions: LongArray,
        keepResult: Boolean
    ): Boolean

    private external fun nativeStartFuzzyRefineAsync(
        conditionId: Int,
        param1: Long,
        param2: Long
    ): Boolean

    // Legacy native methods kept for backward compatibility.
    @Deprecated("Low performance")
    private external fun nativeSetProgressBuffer(buffer: ByteBuffer): Boolean

    @Deprecated("Low performance")
    private external fun nativeClearProgressBuffer()
}
