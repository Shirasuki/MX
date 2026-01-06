package moe.fuqiuluo.mamu.ui.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import moe.fuqiuluo.mamu.ui.tutorial.screen.TutorialPointerChainScreen
import moe.fuqiuluo.mamu.ui.tutorial.screen.TutorialPracticeScreen

@Composable
fun TutorialContainer(
    windowSizeClass: WindowSizeClass,
    initialLevel: TutorialLevel = TutorialLevel.first(),
    onExit: () -> Unit
) {
    var currentLevel by remember { mutableStateOf(initialLevel) }

    AnimatedContent(
        targetState = currentLevel,
        transitionSpec = {
            // 如果是下一关，从右往左滑入；如果是上一关，从左往右滑入
            val isForward = targetState.ordinal > initialState.ordinal
            if (isForward) {
                slideInHorizontally { width -> width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> -width } + fadeOut()
            } else {
                slideInHorizontally { width -> -width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> width } + fadeOut()
            }.using(SizeTransform(clip = false))
        },
        label = "tutorial_level_transition"
    ) { level ->
        when (level) {
            TutorialLevel.SINGLE_VALUE_SEARCH -> {
                TutorialPracticeScreen(
                    windowSizeClass = windowSizeClass,
                    onBack = onExit,
                    onNextLevel = {
                        // 进入下一关
                        level.next()?.let { nextLevel ->
                            currentLevel = nextLevel
                        }
                    }
                )
            }

            TutorialLevel.POINTER_CHAIN_SEARCH -> {
                TutorialPointerChainScreen(
                    windowSizeClass = windowSizeClass,
                    onBack = {
                        // 返回上一关或退出
                        level.previous()?.let { prevLevel ->
                            currentLevel = prevLevel
                        } ?: onExit()
                    }
                )
            }
        }
    }
}
