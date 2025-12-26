package moe.fuqiuluo.mamu.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.data.model.DriverInfo
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.viewmodel.DriverInstallViewModel
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import kotlin.system.exitProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverInstallScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: DriverInstallViewModel = viewModel()
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.shouldRestartApp) {
        if (uiState.shouldRestartApp) {
            restartApp(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("驱动安装") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDrivers() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveLayout.contentMaxWidth)
                    .fillMaxWidth()
                    .align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when {
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        uiState.drivers.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "无可用驱动",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }

                        else -> {
                            when (adaptiveLayout.windowSizeClass.widthSizeClass) {
                                WindowWidthSizeClass.Compact -> {
                                    // 竖屏布局：垂直Column
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        LazyColumn(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            contentPadding = PaddingValues(Dimens.paddingLg(adaptiveLayout)),
                                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
                                        ) {
                                            items(uiState.drivers) { driver ->
                                                DriverCard(
                                                    adaptiveLayout = adaptiveLayout,
                                                    driver = driver,
                                                    isSelected = uiState.selectedDriver == driver,
                                                    onSelect = { viewModel.selectDriver(driver) }
                                                )
                                            }
                                        }

                                        if (uiState.selectedDriver != null) {
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                tonalElevation = Dimens.elevationMd(adaptiveLayout)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
                                                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
                                                ) {
                                                    if (uiState.isInstalling) {
                                                        Text(
                                                            text = "正在下载并安装...",
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        LinearProgressIndicator(
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    } else {
                                                        Button(
                                                            onClick = { showConfirmDialog = true },
                                                            modifier = Modifier.fillMaxWidth(),
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Download,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                                                            )
                                                            Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                                                            Text("下载并安装")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // 横屏布局：Row（列表左侧 + 操作面板右侧）
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        // 左侧：驱动列表（80%）
                                        LazyColumn(
                                            modifier = Modifier
                                                .weight(0.8f)
                                                .fillMaxHeight(),
                                            contentPadding = PaddingValues(Dimens.paddingLg(adaptiveLayout)),
                                            verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
                                        ) {
                                            items(uiState.drivers) { driver ->
                                                DriverCard(
                                                    adaptiveLayout = adaptiveLayout,
                                                    driver = driver,
                                                    isSelected = uiState.selectedDriver == driver,
                                                    onSelect = { viewModel.selectDriver(driver) }
                                                )
                                            }
                                        }

                                        // 右侧：操作面板（20%）
                                        if (uiState.selectedDriver != null) {
                                            VerticalDivider()

                                            Surface(
                                                modifier = Modifier
                                                    .weight(0.2f)
                                                    .fillMaxHeight(),
                                                tonalElevation = Dimens.elevationMd(adaptiveLayout)
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(Dimens.paddingLg(adaptiveLayout)),
                                                    verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout)),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    // 显示选中驱动的名称
                                                    Text(
                                                        text = uiState.selectedDriver!!.displayName,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )

                                                    Spacer(modifier = Modifier.weight(1f))

                                                    if (uiState.isInstalling) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(Dimens.iconXl(adaptiveLayout))
                                                        )
                                                        Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                                                        Text(
                                                            text = "正在下载并安装...",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    } else {
                                                        Button(
                                                            onClick = { showConfirmDialog = true },
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Column(
                                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                                verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs(adaptiveLayout))
                                                            ) {
                                                                Icon(
                                                                    Icons.Default.Download,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
                                                                )
                                                                Text("安装")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    uiState.successMessage?.let { message ->
                        Snackbar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(Dimens.paddingLg(adaptiveLayout)),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            action = {
                                TextButton(onClick = { viewModel.clearMessages() }) {
                                    Text("关闭")
                                }
                            }
                        ) {
                            Text(message)
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog && uiState.selectedDriver != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("确认安装") },
            text = {
                Text("确定要安装驱动 ${uiState.selectedDriver!!.displayName} 吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.downloadAndInstallDriver()
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    uiState.error?.let { errorLog ->
        ErrorLogDialog(
            adaptiveLayout = adaptiveLayout,
            errorLog = errorLog,
            onDismiss = { viewModel.clearMessages() }
        )
    }
}

@Composable
fun DriverCard(
    adaptiveLayout: AdaptiveLayoutInfo,
    driver: DriverInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.paddingLg(adaptiveLayout)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = driver.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                Text(
                    text = driver.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (driver.installed) {
                AssistChip(
                    onClick = { },
                    label = { Text("已安装") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            if (isSelected && !driver.installed) {
                Icon(
                    Icons.Default.RadioButtonChecked,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else if (!driver.installed) {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = "未选中",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Dialog 使用响应式尺寸
@Composable
fun ErrorLogDialog(
    adaptiveLayout: AdaptiveLayoutInfo,
    errorLog: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Dimens.iconLg(adaptiveLayout))
            )
        },
        title = {
            Text(
                text = "驱动安装失败",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
            ) {
                Text(
                    text = "错误详情：",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimens.maxHeightLg(adaptiveLayout)),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = Dimens.elevationSm(adaptiveLayout)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(Dimens.paddingMd(adaptiveLayout))
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = errorLog,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Text(
                    text = "您可以复制日志内容并反馈给开发者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("驱动错误日志", errorLog)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                Text("复制日志")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun restartApp(context: Context) {
    val pkg = context.packageName
    RootShellExecutor.execNoWait(
        suCmd = RootConfigManager.getCustomRootCommand(),
        command = "(sleep 1 && am start-activity -n $pkg/.PermissionSetupActivity) &"
    )
    exitProcess(0)
}
