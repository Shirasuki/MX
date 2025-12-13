package moe.fuqiuluo.mamu.ui.screen

import android.content.Intent
import android.content.res.Configuration
import moe.fuqiuluo.mamu.DriverInstallActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.MainActivity
import moe.fuqiuluo.mamu.ui.theme.MXTheme
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.ui.viewmodel.PermissionSetupState
import moe.fuqiuluo.mamu.ui.viewmodel.PermissionSetupViewModel


private enum class SetupStep(
    val title: String,
    val description: String,
    val icon: ImageVector
) {
    CHECK_ROOT(
        title = "检查Root权限",
        description = "验证设备是否已获取Root权限",
        icon = Icons.Default.Security
    ),
    CONFIRM_ROOT(
        title = "确认Root授权",
        description = "允许应用使用Root权限",
        icon = Icons.Default.VerifiedUser
    ),
    GRANT_PERMISSIONS(
        title = "授予系统权限",
        description = "自动配置所需的系统权限",
        icon = Icons.Default.Settings
    ),
    CHECK_DRIVER(
        title = "检查驱动",
        description = "验证内存驱动是否已安装",
        icon = Icons.Default.Memory
    ),
    COMPLETED(
        title = "设置完成",
        description = "准备启动应用",
        icon = Icons.Default.TaskAlt
    )
}

private enum class StepStatus {
    PENDING,    // 未开始
    ACTIVE,     // 进行中
    COMPLETED,  // 已完成
    ERROR       // 错误
}

private data class StepState(
    val step: SetupStep,
    val status: StepStatus
)

@Composable
fun PermissionSetupScreen(
    viewModel: PermissionSetupViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val navigateToDriverInstall by viewModel.navigateToDriverInstallEvent.collectAsState()

    // 当状态为Completed时，跳转到主界面
    LaunchedEffect(state) {
        if (state is PermissionSetupState.Completed) {
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            context.startActivity(intent)
        }
    }

    // 导航到驱动安装界面
    LaunchedEffect(navigateToDriverInstall) {
        if (navigateToDriverInstall) {
            val intent = Intent(context, DriverInstallActivity::class.java)
            context.startActivity(intent)
            viewModel.resetNavigationEvent()
        }
    }

    // 启动时开始检查
    LaunchedEffect(Unit) {
        viewModel.startSetup()
    }

    // 根据当前状态计算步骤状态
    val stepStates = calculateStepStates(state)

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            // 标题
            Text(
                text = "权限设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "请按照步骤完成应用初始化",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Stepper 时间线
            VerticalStepper(
                steps = stepStates,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            // 当前步骤的详细内容
            StepContent(
                state = state,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun calculateStepStates(state: PermissionSetupState): List<StepState> {
    return when (state) {
        is PermissionSetupState.Initializing -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.CheckingRoot -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.NoRoot -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ERROR),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.WaitingUserConfirm -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.GrantingPermissions -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.ACTIVE),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.ApplyingAntiKillProtection -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.ACTIVE),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.CheckingDriver -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.ACTIVE),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.DriverNotInstalled -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.ERROR),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.Completed -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.COMPLETED),
            StepState(SetupStep.COMPLETED, StepStatus.COMPLETED)
        )

        is PermissionSetupState.Error -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ERROR),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )
    }
}

@Composable
private fun VerticalStepper(
    steps: List<StepState>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, stepState ->
            StepItem(
                stepState = stepState,
                showConnector = index < steps.size - 1
            )
        }
    }
}

@Composable
private fun StepItem(
    stepState: StepState,
    showConnector: Boolean,
    modifier: Modifier = Modifier
) {
    val iconColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        StepStatus.ERROR -> MaterialTheme.colorScheme.error
        StepStatus.PENDING -> MaterialTheme.colorScheme.outline
    }

    val containerColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        StepStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.onSurface
        StepStatus.ERROR -> MaterialTheme.colorScheme.error
        StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val alpha by animateFloatAsState(
        targetValue = if (stepState.status == StepStatus.PENDING) 0.5f else 1f,
        animationSpec = tween(300),
        label = "step_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        // Icon column with connector
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            // Step icon
            Surface(
                shape = CircleShape,
                color = containerColor,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (stepState.status) {
                        StepStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Completed",
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        StepStatus.ACTIVE -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                                color = iconColor
                            )
                        }
                        StepStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Error",
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        StepStatus.PENDING -> {
                            Icon(
                                imageVector = stepState.step.icon,
                                contentDescription = stepState.step.title,
                                tint = iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Connector line
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(
                            if (stepState.status == StepStatus.COMPLETED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Step info
        Column(
            modifier = Modifier
                .weight(1f)
        ) {
            Text(
                text = stepState.step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (stepState.status == StepStatus.ACTIVE) FontWeight.Bold else FontWeight.Medium,
                color = textColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stepState.step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 自定义Root检查命令（仅在CHECK_ROOT步骤失败时显示）
            if (stepState.status == StepStatus.ERROR && stepState.step == SetupStep.CHECK_ROOT) {
                var showCustomCommand by remember { mutableStateOf(false) }
                var customCommand by remember { mutableStateOf(RootConfigManager.getCustomRootCommand()) }

                Spacer(modifier = Modifier.height(8.dp))

                // 可点击的行，带图标和文字
                Surface(
                    onClick = { showCustomCommand = !showCustomCommand },
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "自定义检查命令",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showCustomCommand) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showCustomCommand) "收起" else "展开",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // 展开的输入区域
                AnimatedVisibility(
                    visible = showCustomCommand,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = customCommand,
                            onValueChange = {
                                customCommand = it
                                RootConfigManager.setCustomRootCommand(it)
                            },
                            label = { Text("Root检查命令") },
                            placeholder = { Text("echo test") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "默认: ${RootConfigManager.DEFAULT_ROOT_COMMAND}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showConnector) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StepContent(
    state: PermissionSetupState,
    viewModel: PermissionSetupViewModel
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        when (state) {
            is PermissionSetupState.Initializing -> {
                InitializingContent()
            }
            is PermissionSetupState.CheckingRoot -> {
                CheckingRootContent()
            }
            is PermissionSetupState.NoRoot -> {
                NoRootContent(onRetry = { viewModel.retryRootCheck() })
            }
            is PermissionSetupState.WaitingUserConfirm -> {
                WaitingConfirmContent(
                    onConfirm = { viewModel.confirmUseRoot() }
                )
            }
            is PermissionSetupState.GrantingPermissions -> {
                GrantingPermissionsContent(
                    current = state.current,
                    total = state.total,
                    currentPermission = state.currentPermission
                )
            }
            is PermissionSetupState.ApplyingAntiKillProtection -> {
                ApplyingAntiKillProtectionContent(
                    current = state.current,
                    total = state.total,
                    currentMeasure = state.currentMeasure
                )
            }
            is PermissionSetupState.CheckingDriver -> {
                CheckingDriverContent()
            }
            is PermissionSetupState.DriverNotInstalled -> {
                DriverNotInstalledContent(
                    onInstall = { viewModel.navigateToDriverInstall() }
                )
            }
            is PermissionSetupState.Completed -> {
                CompletedContent(
                    allGranted = state.allGranted,
                    grantedCount = state.grantedCount,
                    totalCount = state.totalCount
                )
            }
            is PermissionSetupState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.retryRootCheck() }
                )
            }
        }
    }
}

@Composable
private fun InitializingContent() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "正在初始化...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun CheckingRootContent() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "正在检查Root权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请在弹出的授权窗口中允许Root访问",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NoRootContent(onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "未获取Root权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "此应用需要Root权限才能正常运行。请确保您的设备已Root，并在授权管理器中允许访问。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "可以在上方的步骤中自定义Root检查命令后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun WaitingConfirmContent(
    onConfirm: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "Success",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root权限已获取",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "将使用Root权限自动授予应用所需的系统权限",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "包括：悬浮窗权限、外部存储访问权限等",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("开始授权")
            }
        }
    }
}

@Composable
private fun GrantingPermissionsContent(
    current: Int,
    total: Int,
    currentPermission: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    CircularProgressIndicator(
                        progress = { current.toFloat() / total.toFloat() },
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Text(
                        text = "$current",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "正在授予权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "当前权限",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentPermission,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ApplyingAntiKillProtectionContent(
    current: Int,
    total: Int,
    currentMeasure: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    CircularProgressIndicator(
                        progress = { current.toFloat() / total.toFloat() },
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "$current",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "应用究极免杀保护",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "当前保护措施",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentMeasure,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CheckingDriverContent() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "正在检查驱动",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在验证内存驱动是否已安装...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverNotInstalledContent(onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "驱动未安装",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "内存驱动尚未安装，需要安装驱动才能使用完整功能。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onInstall,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("安装驱动")
            }
        }
    }
}

@Composable
private fun CompletedContent(
    allGranted: Boolean,
    grantedCount: Int,
    totalCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.TaskAlt else Icons.Default.Warning,
                    contentDescription = if (allGranted) "Success" else "Warning",
                    modifier = Modifier.size(24.dp),
                    tint = if (allGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allGranted) "权限授予完成" else "部分权限授予成功",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已授予 $grantedCount / $totalCount 项权限",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在启动主界面...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Error",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "发生错误",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("重试")
            }
        }
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
private fun PreviewCheckingRoot() {
    MXTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "请按照步骤完成应用初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                VerticalStepper(
                    steps = listOf(
                        StepState(SetupStep.CHECK_ROOT, StepStatus.ACTIVE),
                        StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
                        StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
                        StepState(SetupStep.COMPLETED, StepStatus.PENDING)
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                CheckingRootContent()
            }
        }
    }
}

@Preview(
    name = "Light Mode - Confirming",
    showBackground = true
)
@Preview(
    name = "Dark Mode - Confirming",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewConfirming() {
    MXTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "请按照步骤完成应用初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                VerticalStepper(
                    steps = listOf(
                        StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
                        StepState(SetupStep.CONFIRM_ROOT, StepStatus.ACTIVE),
                        StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
                        StepState(SetupStep.COMPLETED, StepStatus.PENDING)
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                WaitingConfirmContent(
                    onConfirm = {}
                )
            }
        }
    }
}

@Preview(
    name = "Light Mode - Granting",
    showBackground = true
)
@Preview(
    name = "Dark Mode - Granting",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewGranting() {
    MXTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "请按照步骤完成应用初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                VerticalStepper(
                    steps = listOf(
                        StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
                        StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
                        StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.ACTIVE),
                        StepState(SetupStep.COMPLETED, StepStatus.PENDING)
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                GrantingPermissionsContent(
                    current = 3,
                    total = 5,
                    currentPermission = "android.permission.QUERY_ALL_PACKAGES"
                )
            }
        }
    }
}

@Preview(
    name = "Light Mode - Error",
    showBackground = true
)
@Preview(
    name = "Dark Mode - Error",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewError() {
    MXTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "请按照步骤完成应用初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                VerticalStepper(
                    steps = listOf(
                        StepState(SetupStep.CHECK_ROOT, StepStatus.ERROR),
                        StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
                        StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
                        StepState(SetupStep.COMPLETED, StepStatus.PENDING)
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                NoRootContent(onRetry = {})
            }
        }
    }
}

@Preview(
    name = "Light Mode - Completed",
    showBackground = true
)
@Preview(
    name = "Dark Mode - Completed",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun PreviewCompleted() {
    MXTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text(
                    text = "权限设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "请按照步骤完成应用初始化",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                VerticalStepper(
                    steps = listOf(
                        StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
                        StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
                        StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
                        StepState(SetupStep.CHECK_DRIVER, StepStatus.COMPLETED),
                        StepState(SetupStep.COMPLETED, StepStatus.COMPLETED),
                    ),
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                CompletedContent(
                    allGranted = true,
                    grantedCount = 5,
                    totalCount = 5
                )
            }
        }
    }
}