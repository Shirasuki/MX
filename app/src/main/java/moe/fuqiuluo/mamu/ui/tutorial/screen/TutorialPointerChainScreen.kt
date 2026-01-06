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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.toString
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
fun TutorialPointerChainScreen(
    windowSizeClass: WindowSizeClass,
    onBack: () -> Unit
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)

    // 指针链内存结构
    data class PointerChain(
        val staticBase: ULong,       // 静态基址（.data/.bss段）
        val level1Address: ULong,    // 第一层指针
        val level2Address: ULong,    // 第二层指针
        val targetAddress: ULong,    // 目标地址
        val targetValue: Int,        // 目标值
        val baseOffset: Long,        // 基址到第一层的偏移
        val offset1: Long,           // 第一层到第二层的偏移
        val offset2: Long            // 第二层到目标的偏移
    )

    var pointerChain by remember { mutableStateOf<PointerChain?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var currentValue by remember { mutableIntStateOf(0) }

    // 初始化指针链结构
    DisposableEffect(Unit) {
        // 分配内存块（目标值和指针）
        val targetAddr = LocalMemoryOps.alloc(4)       // 目标值
        val level2Addr = LocalMemoryOps.alloc(8)       // 第二层指针
        val level1Addr = LocalMemoryOps.alloc(8)       // 第一层指针
        val staticBase = LocalMemoryOps.getStaticBase() // 静态基址（.data/.bss）

        if (targetAddr != 0UL && level2Addr != 0UL && level1Addr != 0UL && staticBase != 0UL) {
            val targetValue = 888888

            // 构建指针链：staticBase -> level1 -> level2 -> target
            // 设置一些偏移量
            val baseOffset = 0x0L      // 静态变量直接存储指针
            val offset1 = 0x20L
            val offset2 = 0x18L

            // 写入目标值
            LocalMemoryOps.writeInt(targetAddr, targetValue)
            currentValue = targetValue

            // 构建链：level2 + offset2 = target
            LocalMemoryOps.writeLong(level2Addr, (targetAddr - offset2.toULong()).toLong())

            // 构建链：level1 + offset1 = level2
            LocalMemoryOps.writeLong(level1Addr, (level2Addr - offset1.toULong()).toLong())

            // 构建链：staticBase 存储 level1 的地址
            // staticBase 是静态变量本身的地址（在 .data/.bss 段）
            LocalMemoryOps.writeLong(staticBase, level1Addr.toLong())

            pointerChain = PointerChain(
                staticBase = staticBase,
                level1Address = level1Addr,
                level2Address = level2Addr,
                targetAddress = targetAddr,
                targetValue = targetValue,
                baseOffset = baseOffset,
                offset1 = offset1,
                offset2 = offset2
            )
        }

        onDispose {
            pointerChain?.let {
                LocalMemoryOps.free(it.targetAddress, 4)
                LocalMemoryOps.free(it.level2Address, 8)
                LocalMemoryOps.free(it.level1Address, 8)
                // 注意：staticBase 是静态全局变量，不需要释放
            }
        }
    }

    // 定期从内存读取值（检测用户是否通过悬浮窗修改了值或找到了指针链）
    LaunchedEffect(pointerChain) {
        pointerChain?.let { chain ->
            while (true) {
                val value = LocalMemoryOps.readInt(chain.targetAddress)
                if (value != currentValue) {
                    currentValue = value
                }

                // 检查用户是否通过指针扫描找到了正确的链
                // 这里需要实现验证逻辑
                // todo：如果用户找到了链并且能看到结果，就算成功

                delay(100)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指针链搜索练习") },
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
                            text = "使用指针扫描功能找到下方目标地址的指针链。指针链可以在程序重启后依然有效，是修改器的核心技术。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 指针链知识卡片
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.paddingMd(adaptiveLayout))
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
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "什么是指针链？",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                        Text(
                            text = "指针链是一种多级指针结构，例如：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacingXxs(adaptiveLayout)))
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[static]+0x1234->+0x20->+0x18",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(Dimens.paddingXs(adaptiveLayout))
                            )
                        }
                        Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                        Text(
                            text = "最外层从静态区域（.data/.bss）开始，通过多次指针跳转最终指向目标值。由于静态区域在程序重启后地址不变，指针链可以持续有效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
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
                            "启动悬浮窗并绑定进程：moe.fuqiuluo.mamu",
                            "在搜索结果中找到目标地址",
                            "长按目标地址，选择「指针扫描」",
                            "等待扫描完成（可能需要几分钟）",
                            "查看扫描结果，找到指针链",
                            "验证指针链正确性"
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

                // 目标信息卡片
                pointerChain?.let { chain ->
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
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(
                                Dimens.spacingMd(
                                    adaptiveLayout
                                )
                            )
                        ) {
                            // 目标地址
                            Text(
                                text = "目标地址",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val cleanTargetAddr = chain.targetAddress and 0x0000FFFFFFFFFFFFUL
                            SelectionContainer {
                                Text(
                                    text = "0x${cleanTargetAddr.toString(16).uppercase()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            HorizontalDivider()

                            // 当前值
                            Text(
                                text = "当前值",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentValue.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            HorizontalDivider()

                            // 参考答案（调试用，实际发布时可以隐藏）
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(Dimens.paddingMd(adaptiveLayout))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(
                                            Dimens.spacingXs(
                                                adaptiveLayout
                                            )
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout)),
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text = "参考链结构",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    Spacer(
                                        modifier = Modifier.height(
                                            Dimens.spacingXxs(
                                                adaptiveLayout
                                            )
                                        )
                                    )

                                    val cleanStaticBase = chain.staticBase and 0x0000FFFFFFFFFFFFUL
                                    val cleanLevel1Addr =
                                        chain.level1Address and 0x0000FFFFFFFFFFFFUL
                                    val cleanLevel2Addr =
                                        chain.level2Address and 0x0000FFFFFFFFFFFFUL

                                    // 显示指针链格式：[staticBase]+0x0->+0x20->+0x18
                                    Text(
                                        text = "[static:0x${
                                            cleanStaticBase.toString(16).uppercase()
                                        }]+0x${chain.baseOffset.toString(16).uppercase()}" +
                                                "->+0x${chain.offset1.toString(16).uppercase()}" +
                                                "->+0x${chain.offset2.toString(16).uppercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(
                                        modifier = Modifier.height(
                                            Dimens.spacingXs(
                                                adaptiveLayout
                                            )
                                        )
                                    )

                                    Text(
                                        text = "静态基址(.data/.bss): 0x${
                                            cleanStaticBase.toString(
                                                16
                                            ).uppercase()
                                        }\n" +
                                                "层1: 0x${
                                                    cleanLevel1Addr.toString(16).uppercase()
                                                }\n" +
                                                "层2: 0x${
                                                    cleanLevel2Addr.toString(16).uppercase()
                                                }\n" +
                                                "目标: 0x${
                                                    cleanTargetAddr.toString(16).uppercase()
                                                }",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                        Row(
                            modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
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
                                    text = "你已经掌握了指针链搜索技术",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // 提示信息
                Text(
                    text = "提示：指针扫描可能需要较长时间，请耐心等待",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
