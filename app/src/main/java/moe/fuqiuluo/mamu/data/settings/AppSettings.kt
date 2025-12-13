package moe.fuqiuluo.mamu.data.settings

import com.tencent.mmkv.MMKV

/**
 * 应用主配置 - 基于 MMKV 的扩展属性
 */

private const val KEY_AUTO_START_FLOATING = "auto_start_floating_window"

private const val DEFAULT_AUTO_START_FLOATING = false

/**
 * 应用启动时自动显示悬浮窗
 */
var MMKV.autoStartFloatingWindow: Boolean
    get() = decodeBool(KEY_AUTO_START_FLOATING, DEFAULT_AUTO_START_FLOATING)
    set(value) {
        encode(KEY_AUTO_START_FLOATING, value)
    }