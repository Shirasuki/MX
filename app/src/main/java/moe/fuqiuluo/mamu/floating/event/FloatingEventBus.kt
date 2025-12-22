package moe.fuqiuluo.mamu.floating.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 悬浮窗事件总线
 * 用于在不同控制器之间广播事件，实现解耦通信
 */
object FloatingEventBus {
    // 地址值变更事件流（单个）
    private val _addressValueChangedEvents = MutableSharedFlow<AddressValueChangedEvent>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val addressValueChangedEvents: SharedFlow<AddressValueChangedEvent> = _addressValueChangedEvents.asSharedFlow()

    // 地址值变更事件流（批量）
    private val _batchAddressValueChangedEvents = MutableSharedFlow<BatchAddressValueChangedEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val batchAddressValueChangedEvents: SharedFlow<BatchAddressValueChangedEvent> = _batchAddressValueChangedEvents.asSharedFlow()

    // 进程状态变更事件流
    private val _processStateEvents = MutableSharedFlow<ProcessStateEvent>(
        replay = 1,  // 保留最新状态，新订阅者可以立即获取当前状态
        extraBufferCapacity = 4
    )
    val processStateEvents: SharedFlow<ProcessStateEvent> = _processStateEvents.asSharedFlow()

    // UI 操作请求事件流
    private val _uiActionEvents = MutableSharedFlow<UIActionEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val uiActionEvents: SharedFlow<UIActionEvent> = _uiActionEvents.asSharedFlow()

    // 内存范围配置变更事件流
    private val _memoryRangeChangedEvents = MutableSharedFlow<MemoryRangeChangedEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val memoryRangeChangedEvents: SharedFlow<MemoryRangeChangedEvent> = _memoryRangeChangedEvents.asSharedFlow()

    // 保存搜索结果事件流
    private val _saveSearchResultsEvents = MutableSharedFlow<SaveSearchResultsEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val saveSearchResultsEvents: SharedFlow<SaveSearchResultsEvent> = _saveSearchResultsEvents.asSharedFlow()

    // 搜索结果更新事件流（从保存地址界面搜索后）
    private val _searchResultsUpdatedEvents = MutableSharedFlow<SearchResultsUpdatedEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val searchResultsUpdatedEvents: SharedFlow<SearchResultsUpdatedEvent> = _searchResultsUpdatedEvents.asSharedFlow()

    // 导航到内存地址事件流
    private val _navigateToMemoryAddressEvents = MutableSharedFlow<NavigateToMemoryAddressEvent>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val navigateToMemoryAddressEvents: SharedFlow<NavigateToMemoryAddressEvent> = _navigateToMemoryAddressEvents.asSharedFlow()

    // 保存内存预览到地址事件流
    private val _saveMemoryPreviewEvents = MutableSharedFlow<SaveMemoryPreviewEvent>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val saveMemoryPreviewEvents: SharedFlow<SaveMemoryPreviewEvent> = _saveMemoryPreviewEvents.asSharedFlow()

    /**
     * 发送地址值变更事件（单个）
     */
    suspend fun emitAddressValueChanged(event: AddressValueChangedEvent) {
        _addressValueChangedEvents.emit(event)
    }

    /**
     * 发送地址值变更事件（批量）
     * 用于批量修改操作，避免发送大量单个事件造成性能问题
     */
    suspend fun emitBatchAddressValueChanged(event: BatchAddressValueChangedEvent) {
        _batchAddressValueChangedEvents.emit(event)
    }

    /**
     * 发送进程状态变更事件
     */
    suspend fun emitProcessState(event: ProcessStateEvent) {
        _processStateEvents.emit(event)
    }

    /**
     * 发送 UI 操作请求事件
     */
    suspend fun emitUIAction(event: UIActionEvent) {
        _uiActionEvents.emit(event)
    }

    /**
     * 发送内存范围配置变更事件
     */
    suspend fun emitMemoryRangeChanged() {
        _memoryRangeChangedEvents.emit(MemoryRangeChangedEvent)
    }

    /**
     * 发送保存搜索结果事件
     */
    suspend fun emitSaveSearchResults(event: SaveSearchResultsEvent) {
        _saveSearchResultsEvents.emit(event)
    }

    /**
     * 发送搜索结果更新事件
     */
    suspend fun emitSearchResultsUpdated(event: SearchResultsUpdatedEvent) {
        _searchResultsUpdatedEvents.emit(event)
    }

    /**
     * 发送导航到内存地址事件
     */
    suspend fun emitNavigateToMemoryAddress(event: NavigateToMemoryAddressEvent) {
        _navigateToMemoryAddressEvents.emit(event)
    }

    /**
     * 发送保存内存预览事件
     */
    suspend fun emitSaveMemoryPreview(event: SaveMemoryPreviewEvent) {
        _saveMemoryPreviewEvents.emit(event)
    }
}
