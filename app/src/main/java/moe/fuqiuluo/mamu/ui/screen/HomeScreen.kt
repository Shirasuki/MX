package moe.fuqiuluo.mamu.ui.screen

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.data.model.DriverStatus
import moe.fuqiuluo.mamu.data.model.SeLinuxMode
import moe.fuqiuluo.mamu.data.model.SystemInfo
import moe.fuqiuluo.mamu.floating.service.FloatingWindowService
import moe.fuqiuluo.mamu.ui.theme.MXTheme
import moe.fuqiuluo.mamu.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mamu") },
                actions = {
                    IconButton(onClick = { viewModel.loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { toggleFloatingWindow(context, uiState.isFloatingWindowActive) }
            ) {
                Icon(
                    imageVector = if (uiState.isFloatingWindowActive) {
                        Icons.Default.Close
                    } else {
                        Icons.Default.Window
                    },
                    contentDescription = if (uiState.isFloatingWindowActive) {
                        "关闭悬浮窗"
                    } else {
                        "启动悬浮窗"
                    }
                )
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 状态概览卡片（驱动、Root、SELinux）
                StatusOverviewCard(
                    driverStatus = uiState.driverInfo?.status,
                    isProcessBound = uiState.driverInfo?.isProcessBound ?: false,
                    boundPid = uiState.driverInfo?.boundPid ?: -1,
                    hasRoot = uiState.hasRootAccess,
                    seLinuxMode = uiState.seLinuxStatus?.mode,
                    seLinuxModeString = uiState.seLinuxStatus?.modeString
                )

                // README 卡片
                ReadmeCard()

                // 系统信息卡片
                SystemInfoCard(
                    systemInfo = uiState.systemInfo
                )

                // 错误信息
                uiState.error?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "错误",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun toggleFloatingWindow(context: Context, isActive: Boolean) {
    if (isActive) {
        // 关闭悬浮窗
        val intent = Intent(context, FloatingWindowService::class.java)
        context.stopService(intent)
    } else {
        // 启动悬浮窗
        val intent = Intent(context, FloatingWindowService::class.java)
        context.startService(intent)
    }
}

@Composable
fun StatusOverviewCard(
    driverStatus: DriverStatus?,
    isProcessBound: Boolean,
    boundPid: Int,
    hasRoot: Boolean,
    seLinuxMode: SeLinuxMode?,
    seLinuxModeString: String?
) {
    StatusCard(
        title = "状态概览",
        icon = Icons.Default.Dashboard
    ) {
        // 驱动状态
        val driverStatusText = when (driverStatus) {
            DriverStatus.LOADED -> if (isProcessBound && boundPid > 0) "已加载 (PID: $boundPid)" else "已加载"
            DriverStatus.NOT_LOADED -> "未加载"
            DriverStatus.ERROR -> "错误"
            null -> "未知"
        }
        val driverColor = when (driverStatus) {
            DriverStatus.LOADED -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }
        StatusItem(
            label = "驱动",
            value = driverStatusText,
            color = driverColor
        )

        // Root权限
        StatusItem(
            label = "Root",
            value = if (hasRoot) "已获取" else "未获取",
            color = if (hasRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        // SELinux状态
        val (selinuxText, selinuxColor) = when (seLinuxMode) {
            SeLinuxMode.ENFORCING -> "强制模式" to MaterialTheme.colorScheme.error
            SeLinuxMode.PERMISSIVE -> "宽容模式" to MaterialTheme.colorScheme.tertiary
            SeLinuxMode.DISABLED -> "已禁用" to MaterialTheme.colorScheme.primary
            SeLinuxMode.UNKNOWN, null -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatusItem(
            label = "SELinux",
            value = seLinuxModeString ?: selinuxText,
            color = selinuxColor
        )
    }
}

@Composable
fun ReadmeCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "关于 Mamu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Mamu 是一个需要 Root 权限的 Android 内存操作和调试工具。" +
                        "通过悬浮窗界面，可以在运行时搜索、监控和修改进程内存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击右下角按钮启动悬浮窗",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 安全警告分隔线
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "安全警告",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "FloatingWindowService 可能被目标应用检测到（通过 Service 查询）" +
                        "请用户自行实现应用隐藏（修改包名、进程名、使用 Xposed/LSPosed 隐藏等），" +
                        "否则可能被特定系统检测并导致封号，影响日常进程使用，尽管Mamu只是一个调试工具。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun SystemInfoCard(
    systemInfo: SystemInfo
) {
    StatusCard(
        title = "设备信息",
        icon = Icons.Default.PhoneAndroid
    ) {
        StatusItem(
            label = "设备",
            value = "${systemInfo.deviceBrand} ${systemInfo.deviceModel}"
        )
        StatusItem(
            label = "系统",
            value = "Android ${systemInfo.androidVersion} (API ${systemInfo.sdkVersion})"
        )
        StatusItem(
            label = "架构",
            value = systemInfo.cpuAbi
        )
    }
}

@Composable
fun StatusCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun StatusItem(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

// ============ Previews ============

@Preview(
    name = "Light Mode",
    showBackground = true
)
@Preview(
    name = "Dark Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun StatusOverviewCardPreview() {
    MXTheme {
        StatusOverviewCard(
            driverStatus = DriverStatus.LOADED,
            isProcessBound = true,
            boundPid = 12345,
            hasRoot = true,
            seLinuxMode = SeLinuxMode.PERMISSIVE,
            seLinuxModeString = "宽容模式"
        )
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true
)
@Preview(
    name = "Dark Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ReadmeCardPreview() {
    MXTheme {
        ReadmeCard()
    }
}

@Preview(
    name = "Light Mode",
    showBackground = true
)
@Preview(
    name = "Dark Mode",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun SystemInfoCardPreview() {
    MXTheme {
        SystemInfoCard(
            systemInfo = SystemInfo(
                deviceBrand = "Google",
                deviceModel = "Pixel 8 Pro",
                androidVersion = "15",
                sdkVersion = 35,
                cpuAbi = "arm64-v8a"
            )
        )
    }
}