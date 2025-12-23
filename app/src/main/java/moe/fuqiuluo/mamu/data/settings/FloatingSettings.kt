package moe.fuqiuluo.mamu.data.settings

import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.MemoryDisplayFormat
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange

/**
 * 悬浮窗配置 - 基于 MMKV 的扩展属性
 */

private const val KEY_OPACITY = "opacity"
private const val KEY_MEMORY_RANGES = "memory_ranges"
private const val KEY_HIDE_MODE_1 = "hide_mode_1"
private const val KEY_HIDE_MODE_2 = "hide_mode_2"
private const val KEY_HIDE_MODE_3 = "hide_mode_3"
private const val KEY_HIDE_MODE_4 = "hide_mode_4"
private const val KEY_SKIP_MEMORY = "skip_memory"
private const val KEY_AUTO_PAUSE = "auto_pause"
private const val KEY_FREEZE_INTERVAL = "freeze_interval"
private const val KEY_LIST_UPDATE_INTERVAL = "list_update_interval"
private const val KEY_MEMORY_ACCESS_MODE = "memory_rw_mode"
private const val KEY_KEYBOARD = "keyboard"
private const val KEY_LANGUAGE = "language"
private const val KEY_FILTER_SYSTEM_PROCESS = "filter_system_process"
private const val KEY_FILTER_LINUX_PROCESS = "filter_linux_process"
private const val KEY_TOP_MOST_LAYER = "top_most_layer2"
private const val KEY_MEMORY_BUFFER_SIZE = "memory_buffer_size"
private const val KEY_SEARCH_PAGE_SIZE = "search_page_size"
private const val KEY_TAB_SWITCH_ANIMATION = "tab_switch_animation"
private const val KEY_RUDE_SEARCH = "rude_search"
private const val KEY_FAILED_PAGE_THRESHOLD = "failed_page_threshold"
private const val KEY_CHUNK_SIZE = "chunk_size"
private const val KEY_DIALOG_TRANSPARENCY_ENABLED = "dialog_transparency_enabled"
private const val KEY_KEYBOARD_STATE = "keyboard_state"
private const val KEY_MEMORY_REGION_CACHE_INTERVAL = "memory_region_cache_interval_v2"
private const val KEY_MEMORY_DISPLAY_FORMATS = "memory_display_formats"
private const val KEY_COMPATIBILITY_MODE = "compatibility_mode"

private const val DEFAULT_OPACITY = 0.55f
private const val DEFAULT_MEMORY_BUFFER_SIZE = 512
private const val DEFAULT_SEARCH_PAGE_SIZE = 100
private const val DEFAULT_CHUNK_SIZE = 512
private const val DEFAULT_SKIP_MEMORY = 0 // 0=否, 1=空, 2=空orZygote
private const val DEFAULT_AUTO_PAUSE = false
private const val DEFAULT_FREEZE_INTERVAL = 33000 // 微秒
private const val DEFAULT_LIST_UPDATE_INTERVAL = 1000 // 毫秒
private const val DEFAULT_MEMORY_ACCESS_MODE = 0 // 0=无, 1=透写, 2=无缓, 3=普通
private const val DEFAULT_KEYBOARD = 0 // 0=内置, 1=系统
private const val DEFAULT_LANGUAGE = 0 // 0=中文, 1=英文
private const val DEFAULT_FILTER_SYSTEM_PROCESS = true // 默认过滤系统进程
private const val DEFAULT_FILTER_LINUX_PROCESS = true // 默认过滤Linux进程
private const val DEFAULT_TOP_MOST_LAYER = false // 默认不启用最高层级绘制
private const val DEFAULT_TAB_SWITCH_ANIMATION = false // 默认不启用Tab切换动画
private const val DEFAULT_RUDE_SEARCH = true // 默认不启用粗鲁搜索模式
private const val DEFAULT_FAILED_PAGE_THRESHOLD = 4 // 默认连续失败页阈值
private const val DEFAULT_DIALOG_TRANSPARENCY_ENABLED = true // 默认启用dialog透明度
private const val DEFAULT_KEYBOARD_STATE = 1 // 默认展开 (0=折叠, 1=展开, 2=功能)
private const val DEFAULT_MEMORY_REGION_CACHE_INTERVAL = 3000 // 默认 500ms 缓存间隔
private const val DEFAULT_COMPATIBILITY_MODE = false // 默认不启用兼容模式

/**
 * 悬浮窗透明度 (0.0 - 1.0)
 */
var MMKV.floatingOpacity: Float
    get() = decodeFloat(KEY_OPACITY, DEFAULT_OPACITY)
    set(value) {
        encode(KEY_OPACITY, value)
    }

/**
 * 保存地址列表刷新间隔 (毫秒)
 */
var MMKV.saveListUpdateInterval: Int
    get() = decodeInt(KEY_LIST_UPDATE_INTERVAL, DEFAULT_LIST_UPDATE_INTERVAL)
    set(value) {
        encode(KEY_LIST_UPDATE_INTERVAL, value)
    }

/**
 * 内存缓冲区大小 (MB)
 */
var MMKV.memoryBufferSize: Int
    get() = decodeInt(KEY_MEMORY_BUFFER_SIZE, DEFAULT_MEMORY_BUFFER_SIZE)
    set(value) {
        encode(KEY_MEMORY_BUFFER_SIZE, value)
    }

/**
 * 搜索结果每页大小
 */
var MMKV.searchPageSize: Int
    get() = decodeInt(KEY_SEARCH_PAGE_SIZE, DEFAULT_SEARCH_PAGE_SIZE)
    set(value) {
        encode(KEY_SEARCH_PAGE_SIZE, value)
    }

/**
 * 跳过内存选项
 * 0 = 否
 * 1 = 空
 * 2 = 空或Zygote
 */
var MMKV.skipMemoryOption: Int
    get() = decodeInt(KEY_SKIP_MEMORY, DEFAULT_SKIP_MEMORY)
    set(value) {
        encode(KEY_SKIP_MEMORY, value)
    }

/**
 * 打开悬浮窗自动暂停进程
 */
var MMKV.autoPause: Boolean
    get() = decodeBool(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
    set(value) {
        encode(KEY_AUTO_PAUSE, value)
    }

/**
 * 冻结间隔 (微秒)
 */
var MMKV.freezeInterval: Int
    get() = decodeInt(KEY_FREEZE_INTERVAL, DEFAULT_FREEZE_INTERVAL)
    set(value) {
        encode(KEY_FREEZE_INTERVAL, value)
    }

/**
 * 内存读写模式
 * 0 = 无
 * 1 = 透写
 * 2 = 无缓
 * 3 = 普通
 * 4 = 深度
 */
var MMKV.memoryAccessMode: Int
    get() = decodeInt(KEY_MEMORY_ACCESS_MODE, DEFAULT_MEMORY_ACCESS_MODE)
    set(value) {
        WuwaDriver.setMemoryAccessMode(value) // 每次改变都同步到底层驱动
        encode(KEY_MEMORY_ACCESS_MODE, value)
    }

/**
 * 键盘类型
 * 0 = 内置键盘
 * 1 = 系统键盘
 */
var MMKV.keyboardType: Int
    get() = decodeInt(KEY_KEYBOARD, DEFAULT_KEYBOARD)
    set(value) {
        encode(KEY_KEYBOARD, value)
    }

/**
 * 语言选择
 * 0 = 中文
 * 1 = 英文
 */
var MMKV.languageSelection: Int
    get() = decodeInt(KEY_LANGUAGE, DEFAULT_LANGUAGE)
    set(value) {
        encode(KEY_LANGUAGE, value)
    }


/**
 * 过滤系统进程
 */
var MMKV.filterSystemProcess: Boolean
    get() = decodeBool(KEY_FILTER_SYSTEM_PROCESS, DEFAULT_FILTER_SYSTEM_PROCESS)
    set(value) {
        encode(KEY_FILTER_SYSTEM_PROCESS, value)
    }

/**
 * 过滤Linux进程
 */
var MMKV.filterLinuxProcess: Boolean
    get() = decodeBool(KEY_FILTER_LINUX_PROCESS, DEFAULT_FILTER_LINUX_PROCESS)
    set(value) {
        encode(KEY_FILTER_LINUX_PROCESS, value)
    }

/**
 * 悬浮窗最高层级绘制
 */
var MMKV.topMostLayer: Boolean
    get() = decodeBool(KEY_TOP_MOST_LAYER, DEFAULT_TOP_MOST_LAYER)
    set(value) {
        encode(KEY_TOP_MOST_LAYER, value)
    }

/**
 * 隐藏模式 1
 */
var MMKV.hideMode1: Boolean
    get() = decodeBool(KEY_HIDE_MODE_1, false)
    set(value) {
        encode(KEY_HIDE_MODE_1, value)
    }

/**
 * 隐藏模式 2
 */
var MMKV.hideMode2: Boolean
    get() = decodeBool(KEY_HIDE_MODE_2, false)
    set(value) {
        encode(KEY_HIDE_MODE_2, value)
    }

/**
 * 隐藏模式 3
 */
var MMKV.hideMode3: Boolean
    get() = decodeBool(KEY_HIDE_MODE_3, false)
    set(value) {
        encode(KEY_HIDE_MODE_3, value)
    }

/**
 * 隐藏模式 4
 */
var MMKV.hideMode4: Boolean
    get() = decodeBool(KEY_HIDE_MODE_4, false)
    set(value) {
        encode(KEY_HIDE_MODE_4, value)
    }

/**
 * 内存范围字符串
 * 格式示例: "0x1000-0x1FFF,0x3000-0x3FFF"
 */
var MMKV.selectedMemoryRanges: Set<MemoryRange>
    get() = (decodeStringSet(
        KEY_MEMORY_RANGES, setOf(
            MemoryRange.Jh.code,
            MemoryRange.Ch.code,
            MemoryRange.Ca.code,
            MemoryRange.Cd.code,
            MemoryRange.Cb.code,
            MemoryRange.Ps.code,
            MemoryRange.An.code,
        )
    ) ?: emptySet()).map {
        MemoryRange.fromCode(it)!!
    }.toSet()
    set(value) {
        encode(KEY_MEMORY_RANGES, value.map { it.code }.toSet())
    }

/**
 * Tab切换动画
 */
var MMKV.tabSwitchAnimation: Boolean
    get() = decodeBool(KEY_TAB_SWITCH_ANIMATION, DEFAULT_TAB_SWITCH_ANIMATION)
    set(value) {
        encode(KEY_TAB_SWITCH_ANIMATION, value)
    }

/**
 * 搜索分块大小 (KB)
 * 128 = 128KB
 * 512 = 512KB
 * 1024 = 1MB
 * 4096 = 4MB
 */
var MMKV.chunkSize: Int
    get() = decodeInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
    set(value) {
        encode(KEY_CHUNK_SIZE, value)
    }

/**
 * Dialog透明度开关
 * true = 启用透明度效果 (默认)
 * false = 禁用透明度效果 (完全不透明)
 */
var MMKV.dialogTransparencyEnabled: Boolean
    get() = decodeBool(KEY_DIALOG_TRANSPARENCY_ENABLED, DEFAULT_DIALOG_TRANSPARENCY_ENABLED)
    set(value) {
        encode(KEY_DIALOG_TRANSPARENCY_ENABLED, value)
    }

/**
 * 获取Dialog的实际透明度
 * 如果关闭了透明度开关，返回1.0f（完全不透明）
 * 否则返回配置的透明度值，但最低为0.85f
 */
fun MMKV.getDialogOpacity(): Float {
    return if (dialogTransparencyEnabled) {
        kotlin.math.max(floatingOpacity, 0.85f)
    } else {
        1.0f
    }
}

/**
 * 内置键盘状态
 * 0 = 折叠 (COLLAPSED)
 * 1 = 展开 (EXPANDED)
 * 2 = 功能 (FUNCTION)
 */
var MMKV.keyboardState: Int
    get() = decodeInt(KEY_KEYBOARD_STATE, DEFAULT_KEYBOARD_STATE)
    set(value) {
        encode(KEY_KEYBOARD_STATE, value)
    }

/**
 * 内存区域缓存间隔 (毫秒)
 * 在此间隔内跳转不会重新查询内存区域
 * 0 = 禁用缓存（每次都查询）
 */
var MMKV.memoryRegionCacheInterval: Int
    get() = decodeInt(KEY_MEMORY_REGION_CACHE_INTERVAL, DEFAULT_MEMORY_REGION_CACHE_INTERVAL)
    set(value) {
        encode(KEY_MEMORY_REGION_CACHE_INTERVAL, value)
    }

/**
 * 内存预览显示格式列表
 * 存储格式代码，如 "h,D,Q"
 */
var MMKV.memoryDisplayFormats: List<MemoryDisplayFormat>
    get() {
        val codesString = decodeString(KEY_MEMORY_DISPLAY_FORMATS, null)
        if (codesString.isNullOrEmpty()) {
            return MemoryDisplayFormat.getDefaultFormats()
        }
        return codesString.split(",").mapNotNull { code ->
            MemoryDisplayFormat.fromCode(code.trim())
        }.ifEmpty {
            MemoryDisplayFormat.getDefaultFormats()
        }
    }
    set(value) {
        val codesString = value.joinToString(",") { it.code }
        encode(KEY_MEMORY_DISPLAY_FORMATS, codesString)
    }

/**
 * 兼容模式
 * true = 启用兼容模式，所有搜索结果以模糊搜索格式存储
 * false = 标准模式，精确搜索和模糊搜索结果分别存储
 */
var MMKV.compatibilityMode: Boolean
    get() = decodeBool(KEY_COMPATIBILITY_MODE, DEFAULT_COMPATIBILITY_MODE)
    set(value) {
        encode(KEY_COMPATIBILITY_MODE, value)
    }