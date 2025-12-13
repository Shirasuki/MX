package moe.fuqiuluo.mamu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.model.DriverInfo
import moe.fuqiuluo.mamu.data.model.SeLinuxStatus
import moe.fuqiuluo.mamu.data.model.SystemInfo
import moe.fuqiuluo.mamu.data.local.DriverDataSource
import moe.fuqiuluo.mamu.data.local.SystemDataSource
import moe.fuqiuluo.mamu.floating.FloatingWindowStateManager

data class MainUiState(
    val isLoading: Boolean = true,
    val systemInfo: SystemInfo = SystemInfo(),
    val driverInfo: DriverInfo? = null,
    val seLinuxStatus: SeLinuxStatus? = null,
    val hasRootAccess: Boolean = false,
    val isFloatingWindowActive: Boolean = false,
    val error: String? = null
)

class MainViewModel(
    private val systemDataSource: SystemDataSource = SystemDataSource(),
    private val driverDataSource: DriverDataSource = DriverDataSource()
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeFloatingWindowState()
    }

    private fun observeFloatingWindowState() {
        viewModelScope.launch {
            FloatingWindowStateManager.isActive.collect { isActive ->
                _uiState.value = _uiState.value.copy(isFloatingWindowActive = isActive)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val systemInfo = systemDataSource.getSystemInfo()
                val hasRoot = systemDataSource.hasRootAccess()
                val seLinuxStatus = systemDataSource.getSeLinuxStatus()
                val driverInfo = driverDataSource.getDriverInfo()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    systemInfo = systemInfo,
                    driverInfo = driverInfo,
                    seLinuxStatus = seLinuxStatus,
                    hasRootAccess = hasRoot
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
}