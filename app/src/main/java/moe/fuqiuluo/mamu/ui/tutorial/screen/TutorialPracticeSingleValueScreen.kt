package moe.fuqiuluo.mamu.ui.tutorial.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import moe.fuqiuluo.mamu.driver.LocalMemoryOps
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialPracticeScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit,
    onNextLevel: () -> Unit = {}
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    // 分配内存并存储初始值 42
    var memoryAddress by remember { mutableStateOf(0UL) }
    var currentValue by remember { mutableIntStateOf(42) }
    var isSuccess by remember { mutableStateOf(false) }

    // 初始化内存
    DisposableEffect(Unit) {
        val address = LocalMemoryOps.alloc(4) // 4 bytes for Int
        if (address != 0UL) {
            LocalMemoryOps.writeInt(address, 42)
            memoryAddress = address
        }

        onDispose {
            if (memoryAddress != 0UL) {
                LocalMemoryOps.free(memoryAddress, 4)
            }
        }
    }

    // 定期从内存读取值（检测用户是否通过悬浮窗修改了值）
    LaunchedEffect(memoryAddress) {
        if (memoryAddress != 0UL) {
            while (true) {
                val value = LocalMemoryOps.readInt(memoryAddress)
                if (value != currentValue) {
                    currentValue = value
                    if (value == 114514) {
                        isSuccess = true
                    }
                }
                delay(100) // 每 100ms 检查一次
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("单值搜索练习") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveLayout.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(paddingValues)
                    .padding(Dimens.paddingLg(adaptiveLayout)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd(adaptiveLayout))
            ) {
                // 说明卡片
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                Dimens.spacingSm(
                                    adaptiveLayout
                                )
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "练习目标",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                        Text(
                            text = "使用悬浮窗搜索下方显示的数值，找到内存地址后将其修改为 114514",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 步骤提示
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.paddingMd(adaptiveLayout))
                    ) {
                        Text(
                            text = "操作步骤",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))

                        val steps = listOf(
                            "启动悬浮窗",
                            "选择进程：moe.fuqiuluo.mamu (PID: ${LocalMemoryOps.getPid()})",
                            "搜索当前值：$currentValue",
                            "使用 +1/-1 按钮改变值",
                            "再次搜索新值筛选结果",
                            "重复直到找到唯一地址",
                            "修改该地址的值为 114514"
                        )

                        steps.forEachIndexed { index, step ->
                            Row(
                                modifier = Modifier.padding(
                                    vertical = Dimens.spacingXxs(
                                        adaptiveLayout
                                    )
                                ),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.width(Dimens.stepNumberWidth(adaptiveLayout))
                                )
                                Text(
                                    text = step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

                // 数值显示卡片
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(Dimens.paddingLg(adaptiveLayout))
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 内存地址
                        Text(
                            text = "内存地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SelectionContainer {
                            Text(
                                text = if (memoryAddress != 0UL) {
                                    // 屏蔽 MTE/TBI 标签，只显示低 48 位有效地址
                                    val cleanAddress = memoryAddress and 0x0000FFFFFFFFFFFFUL
                                    "0x${cleanAddress.toString(16).uppercase()}"
                                } else {
                                    "分配中..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

                        // 当前值
                        Text(
                            text = "当前值",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentValue.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )

                        Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

                        // +1 / -1 按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                Dimens.spacingLg(
                                    adaptiveLayout
                                )
                            )
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    if (memoryAddress != 0UL) {
                                        val newValue = currentValue - 1
                                        LocalMemoryOps.writeInt(memoryAddress, newValue)
                                        currentValue = newValue
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null)
                            }

                            FilledTonalButton(
                                onClick = {
                                    if (memoryAddress != 0UL) {
                                        val newValue = currentValue + 1
                                        LocalMemoryOps.writeInt(memoryAddress, newValue)
                                        currentValue = newValue
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }
                }

                // 成功提示
                if (isSuccess) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
                            verticalArrangement = Arrangement.spacedBy(
                                Dimens.spacingMd(
                                    adaptiveLayout
                                )
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    Dimens.spacingMd(
                                        adaptiveLayout
                                    )
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(Dimens.iconLg(adaptiveLayout))
                                )
                                Column {
                                    Text(
                                        text = "恭喜完成！",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "你已经学会了基本的单值搜索和修改",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Button(
                                onClick = onNextLevel,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NavigateNext,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(Dimens.spacingXs(adaptiveLayout)))
                                Text("进入下一关：指针链搜索")
                            }
                        }
                    }
                }

                // 目标提示
                Text(
                    text = "目标值：114514",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
