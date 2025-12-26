package moe.fuqiuluo.mamu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.model.DriverInfo
import moe.fuqiuluo.mamu.driver.WuwaDriver

data class DriverInstallUiState(
    val isLoading: Boolean = true,
    val drivers: List<DriverInfo> = emptyList(),
    val selectedDriver: DriverInfo? = null,
    val isInstalling: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val shouldRestartApp: Boolean = false
)

class DriverInstallViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(DriverInstallUiState())
    val uiState: StateFlow<DriverInstallUiState> = _uiState.asStateFlow()

    init {
        loadDrivers()
    }

    fun loadDrivers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val drivers = WuwaDriver.getAvailableDrivers().toList()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    drivers = drivers
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "加载驱动列表失败: ${e.message}"
                )
            }
        }
    }

    fun selectDriver(driver: DriverInfo) {
        _uiState.value = _uiState.value.copy(
            selectedDriver = driver,
            error = null,
            successMessage = null
        )
    }

    fun downloadAndInstallDriver() {
        val driver = _uiState.value.selectedDriver ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 开始安装
                _uiState.value = _uiState.value.copy(
                    isInstalling = true,
                    error = null,
                    successMessage = null
                )

                val result = WuwaDriver.downloadAndInstallDriver(driver.name)

                if (result.success) {
                    _uiState.value = _uiState.value.copy(
                        isInstalling = false,
                        successMessage = "驱动安装成功！应用即将重启...",
                        selectedDriver = null
                    )
                    delay(2000)
                    _uiState.value = _uiState.value.copy(shouldRestartApp = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isInstalling = false,
                        error = "安装失败: ${result.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isInstalling = false,
                    error = "操作失败: ${e.message}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            error = null,
            successMessage = null
        )
    }
}
