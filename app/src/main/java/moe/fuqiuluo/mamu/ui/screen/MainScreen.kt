package moe.fuqiuluo.mamu.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            onNavigateBack = { showSettings = false }
        )
    } else {
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
                // 使用 Crossfade 实现平滑的页面切换动画
                Crossfade(
                    targetState = selectedTab,
                    label = "tab_crossfade"
                ) { tab ->
                    when (tab) {
                        0 -> HomeScreen(viewModel = viewModel)
                        1 -> ModulesScreen()
                        2 -> ToolsScreen()
                        3 -> LogsScreen()
                    }
                }
            }
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
