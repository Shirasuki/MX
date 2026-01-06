//! 第二阶段：指针链构造器
//!
//! 本模块从目标地址反向构建指针链，追溯到静态模块。
//! 使用第一阶段构建的指针库来查找所有可能的路径。
//!
//! ## 算法
//! - `build_pointer_chains`: 主入口，调用分层BFS算法
//! - `build_pointer_chains_layered_bfs`: **分层BFS + rayon并行**

mod layer_bfs;
mod recursive_dfs;

use crate::pointer_scan::chain_builder::layer_bfs::build_pointer_chains_layered_bfs;
use crate::pointer_scan::chain_builder::recursive_dfs::build_pointer_chains_dfs;
use crate::pointer_scan::storage::MmapQueue;
use crate::pointer_scan::types::{PointerChain, PointerChainStep, PointerData, PointerScanConfig, VmStaticData};
use anyhow::Result;
use log::{debug, info, log_enabled, warn, Level};
use rayon::prelude::*;
use std::cmp::Ordering;
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering as AtomicOrdering};

/// 在 MmapQueue<PointerData> 中二分查找值在 [min, max) 范围内的指针。
/// 返回 (起始索引, 结束索引)。
fn find_range_in_pointer_queue(queue: &MmapQueue<PointerData>, min_value: u64, max_value: u64) -> (usize, usize) {
    let count = queue.len();
    if count == 0 {
        return (0, 0);
    }

    // 辅助函数：从归档的 PointerData 获取 value 字段
    let get_value = |index: usize| -> Option<u64> { queue.get(index).map(|archived| archived.value.to_native()) };

    // 二分查找：下界
    let mut left = 0;
    let mut right = count;
    while left < right {
        let mid = left + (right - left) / 2;
        match get_value(mid) {
            Some(val) if val < min_value => left = mid + 1,
            Some(_) => right = mid,
            None => break,
        }
    }
    let start_idx = left;

    // 二分查找：上界
    left = start_idx;
    right = count;
    while left < right {
        let mid = left + (right - left) / 2;
        match get_value(mid) {
            Some(val) if val < max_value => left = mid + 1,
            Some(_) => right = mid,
            None => break,
        }
    }
    let end_idx = left;

    (start_idx, end_idx)
}

/// 查找所有指向 [target - max_offset, target] 范围的指针。
/// 返回 Vec<(指针地址, 有符号偏移)>，其中 有符号偏移 = target - 指针值。
/// 正偏移：指针指向target下方
/// 负偏移：指针指向target上方
fn find_pointers_to_range(pointer_lib: &MmapQueue<PointerData>, target: u64, max_offset: u32) -> Vec<(u64, i64)> {
    let min_value = target.saturating_sub(max_offset as u64);
    let max_value = target + 1; // 上界不包含，所以 target+1 表示搜索到 target

    let (start_idx, end_idx) = find_range_in_pointer_queue(pointer_lib, min_value, max_value);

    if log_enabled!(Level::Debug) {
        debug!(
            "搜索指向 0x{:X} 的指针: min=0x{:X}, max=0x{:X}, 索引范围=[{}, {}), 指针库大小={}",
            target, min_value, max_value, start_idx, end_idx, pointer_lib.len()
        );
    }

    let mut results = Vec::with_capacity(end_idx - start_idx);

    for i in start_idx..end_idx {
        if let Some(archived) = pointer_lib.get(i) {
            let ptr_address = archived.address.to_native();
            let ptr_value = archived.value.to_native();
            // 有符号偏移：正值表示指针指向target下方
            let offset = (target as i64).wrapping_sub(ptr_value as i64);

            // 验证偏移在范围内
            if offset >= 0 && offset <= max_offset as i64 {
                // ptr_address这个位置有个指针值，把它读出来然后加上offset得到target
                results.push((ptr_address, offset));
            } else if log_enabled!(Level::Debug) {
                debug!(
                    "跳过超出范围的指针: 地址=0x{:X}, 值=0x{:X}, 偏移={}, max_offset={}",
                    ptr_address, ptr_value, offset, max_offset
                );
            }
        }
    }

    if log_enabled!(Level::Debug) {
        debug!("找到 {} 个指向 0x{:X} 的候选指针", results.len(), target);
    }

    results
}

/// 检查地址是否属于静态模块。
/// 如果找到，返回 (模块名, 模块索引, 基址偏移)。
///
/// 注意：不对静态模块内部的指针位置做 max_offset 限制，
/// 因为代码段可能很大（数MB），指针可以在任何位置。
/// max_offset 只用于指针链的偏移检查，不用于静态根的位置检查。
fn classify_pointer(
    address: u64,
    static_modules: &[VmStaticData],
    data_start: bool,
    _bss_start: bool,
    _max_offset: u32, // 不使用，仅为保持接口兼容
) -> Option<(String, u32, u64)> {
    for module in static_modules {
        if module.contains(address) {
            let local_offset = module.offset_from_base(address);

            // 计算返回的偏移：
            // - 如果 data_start=true 且 index!=0，使用相对于第一个段的偏移（统一基址）
            // - 否则使用相对于当前段的偏移
            let display_offset = if data_start && module.index != 0 {
                address.saturating_sub(module.first_module_base_addr)
            } else {
                local_offset
            };

            if log_enabled!(Level::Debug) {
                debug!(
                    "分类指针 0x{:X}: 模块={}, 索引={}, 偏移=0x{:X}",
                    address, module.name, module.index, display_offset
                );
            }

            return Some((module.name.clone(), module.index, display_offset));
        }
    }
    None
}

/// 第二阶段：使用分层BFS从目标地址构建指针链。
///
/// 这是主入口函数，使用并行分层BFS算法。
/// 层内并行、层间串行：每个深度层级内部并行处理，层级之间顺序处理。
///
/// # 参数
/// * `pointer_lib` - 第一阶段构建的已排序指针库
/// * `static_modules` - 静态模块列表（代码段）
/// * `config` - 扫描配置
/// * `progress_callback` - 进度回调 (当前深度, 已找到链数)
/// * `check_cancelled` - 检查是否取消的函数
///
/// # 返回
/// 完整指针链的向量
pub fn build_pointer_chains<F, C>(
    pointer_lib: &MmapQueue<PointerData>,
    static_modules: &[VmStaticData],
    config: &PointerScanConfig,
    progress_callback: F,
    check_cancelled: C,
) -> Result<Vec<PointerChain>>
where
    F: Fn(u32, i32, i64) + Sync + Send + 'static,
    C: Fn() -> bool + Sync,
{
    if config.is_layer_bfs {
        build_pointer_chains_layered_bfs(pointer_lib, static_modules, config, progress_callback, check_cancelled)
    } else {
        build_pointer_chains_dfs(pointer_lib, static_modules, config, progress_callback, check_cancelled)
    }
}
