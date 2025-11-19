package moe.fuqiuluo.mamu.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗状态管理器
 * 用于在 Service 和 Compose UI 之间同步悬浮窗的开启/关闭状态
 */
object FloatingWindowStateManager {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }
}