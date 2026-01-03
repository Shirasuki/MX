package moe.fuqiuluo.mamu.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.tutorial.screen.TutorialPracticeScreen
import moe.fuqiuluo.mamu.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: MainViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showTutorialPractice by remember { mutableStateOf(false) }
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)

    if (showAbout) {
        AboutScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { showAbout = false }
        )
    } else if (showSettings) {
        SettingsScreen(
            windowSizeClass = windowSizeClass,
            onNavigateBack = { showSettings = false },
            onShowAbout = { showAbout = true }
        )
    } else if (showTutorialPractice) {
        TutorialPracticeScreen(
            windowSizeClass = windowSizeClass,
            onBack = { showTutorialPractice = false }
        )
    } else {
        when (adaptiveLayout.windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> {
                // 竖屏布局：底部导航栏
                Scaffold(
                    topBar = {
                        if (selectedTab == 0) {
                            TopAppBar(
                                title = { Text("Mamu") },
                                actions = {
                                    IconButton(onClick = { viewModel.loadData() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                                    }
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Default.Settings, contentDescription = "设置")
                                    }
                                }
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            bottomNavItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label) },
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index }
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues)) {
                        TabContent(
                            selectedTab = selectedTab,
                            windowSizeClass = windowSizeClass,
                            viewModel = viewModel,
                            onShowTutorialPractice = { showTutorialPractice = true }
                        )
                    }
                }
            }
            else -> {
                // 横屏布局：统一TopAppBar + NavigationRail + Content
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Mamu") },
                            actions = {
                                IconButton(onClick = { viewModel.loadData() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "设置")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Row(
                        modifier = Modifier
                            .padding(top = paddingValues.calculateTopPadding())
                            .fillMaxSize()
                    ) {
                        NavigationRail {
                            bottomNavItems.forEachIndexed { index, item ->
                                NavigationRailItem(
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = { Text(item.label) },
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index }
                                )
                            }
                        }
                        Box(modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()) {
                            TabContent(
                                selectedTab = selectedTab,
                                windowSizeClass = windowSizeClass,
                                viewModel = viewModel,
                                onShowTutorialPractice = { showTutorialPractice = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: Int,
    windowSizeClass: WindowSizeClass,
    viewModel: MainViewModel,
    onShowTutorialPractice: () -> Unit
) {
    Crossfade(
        targetState = selectedTab,
        label = "tab_crossfade"
    ) { tab ->
        when (tab) {
            0 -> HomeScreen(
                windowSizeClass = windowSizeClass,
                viewModel = viewModel,
                onStartPractice = onShowTutorialPractice
            )
            1 -> ModulesScreen(windowSizeClass = windowSizeClass)
            2 -> ToolsScreen(windowSizeClass = windowSizeClass)
            3 -> LogsScreen(windowSizeClass = windowSizeClass)
        }
    }
}

data class BottomNavItem(
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem("首页", Icons.Default.Home),
    BottomNavItem("模块", Icons.Default.Extension),
    BottomNavItem("工具", Icons.Default.Build),
    BottomNavItem("日志", Icons.AutoMirrored.Filled.Article)
)
