package moe.fuqiuluo.mamu.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.tutorial.TutorialContainer
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
    var showTutorial by remember { mutableStateOf(false) }
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
    } else if (showTutorial) {
        TutorialContainer(
            windowSizeClass = windowSizeClass,
            onExit = { showTutorial = false }
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
                            onShowTutorial = { showTutorial = true }
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
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            TabContent(
                                selectedTab = selectedTab,
                                windowSizeClass = windowSizeClass,
                                viewModel = viewModel,
                                onShowTutorial = { showTutorial = true }
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
    onShowTutorial: () -> Unit
) {
    Crossfade(
        targetState = selectedTab,
        label = "tab_crossfade"
    ) { tab ->
        when (tab) {
            0 -> HomeScreen(
                windowSizeClass = windowSizeClass,
                viewModel = viewModel,
                onStartPractice = onShowTutorial
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
