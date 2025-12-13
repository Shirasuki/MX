package moe.fuqiuluo.mamu.floating.ext

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import moe.fuqiuluo.mamu.databinding.FloatingFullscreenLayoutBinding

/**
 * 应用浮动窗口透明度设置
 */
fun FloatingFullscreenLayoutBinding.applyOpacity() {
    val config = MMKV.defaultMMKV()
    val opacity = config.floatingOpacity
    val alpha = (opacity * 255).toInt()

    /**
     * 递归应用透明度到所有使用 bg_settings_card 背景的视图
     */
    fun applyOpacityToCards(parent: View) {
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)

                // 对 MaterialCardView 应用透明度
                // 主要针对设置界面的卡片
                if (child is MaterialCardView) {
                    child.alpha = opacity
                }

                // 递归处理子视图
                applyOpacityToCards(child)
            }
        }
    }

    // 应用到根布局背景
    rootLayout.background?.alpha = alpha

    // 应用到设置界面的所有卡片
    applyOpacityToCards(contentContainer)
}