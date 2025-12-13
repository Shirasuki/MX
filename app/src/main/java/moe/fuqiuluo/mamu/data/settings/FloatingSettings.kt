package moe.fuqiuluo.mamu.data.settings

import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.driver.WuwaDriver
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