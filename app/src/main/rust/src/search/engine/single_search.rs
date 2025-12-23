use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicUsize};
use super::super::types::{SearchValue, ValueType};
use super::manager::{BPLUS_TREE_ORDER, PAGE_MASK, PAGE_SIZE, ValuePair};
use crate::core::DRIVER_MANAGER;
use crate::wuwa::PageStatusBitmap;
use anyhow::{Result, anyhow};
use bplustree::BPlusTreeSet;
use log::{Level, debug, log_enabled, warn};

pub(crate) fn search_region_single(
    target: &SearchValue,
    start: u64,        // 区域起始地址
    end: u64,          // 区域结束地址
    chunk_size: usize, // 每次读取的块大小
) -> Result<BPlusTreeSet<ValuePair>> {
    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let value_type = target.value_type();
    let element_size = value_type.size();

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
    let mut read_success = 0usize;
    let mut read_failed = 0usize;
    let mut matches_checked = 0usize;

    let mut current = start & !(*PAGE_SIZE as u64 - 1); // 当前的页对齐地址
    let mut chunk_buffer = vec![0u8; chunk_size]; // 读取缓冲区

    while current < end {
        let chunk_end = (current + chunk_size as u64).min(end); // 当前块的结束地址，如果超过end则取end
        let chunk_len = (chunk_end - current) as usize; // 当前块的实际长度

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        let read_result = driver_manager.read_memory_unified(
            current, 
            &mut chunk_buffer[..chunk_len], 
            Some(&mut page_status)
        );

        match read_result {
            Ok(_) => {
                let success_pages = page_status.success_count();
                if success_pages > 0 {
                    read_success += 1;
                    search_in_buffer_with_status(
                        &chunk_buffer[..chunk_len],
                        current,
                        start,
                        end,
                        element_size,
                        target,
                        value_type,
                        &page_status,
                        &mut results,
                        &mut matches_checked,
                    );
                } else {
                    read_failed += 1;
                }
            },
            Err(error) => {
                if log_enabled!(Level::Debug) {
                    warn!(
                        "Failed to read memory at 0x{:X} - 0x{:X}, err: {:?}",
                        current, chunk_end, error
                    );
                }
                read_failed += 1;
            },
        }

        current = chunk_end;
    }

    // if log_enabled!(Level::Debug) {
    //     let region_size = end - start;
    //     debug!(
    //         "Region stats: size={}MB, reads={} success + {} failed, matches_checked={}, found={}",
    //         region_size / 1024 / 1024,
    //         read_success,
    //         read_failed,
    //         matches_checked,
    //         results.len()
    //     );
    // }

    Ok(results)
}

#[inline]
pub(crate) fn search_in_buffer_with_status(
    buffer: &[u8],
    buffer_addr: u64,                      // 当前读取的缓冲区对应目标进程的一块内存的起始地址
    region_start: u64,                     // 搜索区域的起始地址
    region_end: u64,                       // 搜索区域的结束地址
    element_size: usize,                   // 元素大小
    target: &SearchValue,                  // 目标搜索值
    value_type: ValueType,                 // 目标值类型
    page_status: &PageStatusBitmap,        // 页面状态位图
    results: &mut BPlusTreeSet<ValuePair>, // 搜索结果
    matches_checked: &mut usize,           // 检查的匹配数
) {
    let buffer_end = buffer_addr + buffer.len() as u64; // 结束地址
    let search_start = buffer_addr.max(region_start); // 实际搜索起始地址
    let search_end = buffer_end.min(region_end); // 实际搜索结束地址

    // 避免用户输入非对齐的地址，确保从对齐地址开始搜索
    let rem = search_start % element_size as u64; // 对齐调整
    let first_addr = if rem == 0 {
        search_start // 已对齐
    } else {
        search_start + element_size as u64 - rem // 调整到下一个对齐地址
    };

    // 使用内核风格的页索引追踪：遇到新页时递增
    let mut page_index = 0usize;
    let mut last_page_va = u64::MAX; // 初始化为无效值

    let mut addr = first_addr; // 从第一个对齐地址开始搜索
    while addr + element_size as u64 <= search_end {
        // 遍历搜索范围，只要还有足够的空间存放一个元素就继续
        let offset = (addr - buffer_addr) as usize; // 计算当前地址在缓冲区中的偏移
        if offset + element_size <= buffer.len() {
            // 确保偏移加元素大小不超过缓冲区长度，二次检查

            // 计算当前地址所在的页
            let page_va = addr & *PAGE_MASK as u64;

            // 如果移动到新页，递增页索引（类似内核实现）
            if page_va != last_page_va && last_page_va != u64::MAX {
                page_index += 1;
            }
            last_page_va = page_va;

            // 检查该页是否成功读取，如果成功则进行匹配检查
            if page_index < page_status.num_pages() && page_status.is_page_success(page_index) {
                // 获取当前元素的字节切片
                let element_bytes = &buffer[offset..offset + element_size];
                *matches_checked += 1;

                // 检查是否匹配目标值
                if let Ok(true) = target.matched(element_bytes) {
                    results.insert((addr, value_type).into());
                }
            }
        }
        // 移动到下一个对齐地址
        addr += element_size as u64;
    }
}

/// 单值细化搜索
/// 逐个读取地址的值，再用rayon并行判断
/// 返回仍然匹配的地址列表
#[deprecated]
pub(crate) fn refine_single_search(
    addresses: &[ValuePair],
    target: &SearchValue,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
) -> Result<Vec<ValuePair>> {
    use rayon::prelude::*;
    use std::sync::atomic::Ordering;

    if addresses.is_empty() {
        return Ok(Vec::new());
    }

    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let target_type = target.value_type();
    let element_size = target_type.size();

    // 过滤类型不匹配的地址
    let filtered_addresses: Vec<_> = addresses
        .iter()
        .filter(|p| p.value_type == target_type)
        .cloned()
        .collect();

    if filtered_addresses.is_empty() {
        return Ok(Vec::new());
    }

    // 逐个读取每个地址的值
    let mut address_values: Vec<(ValuePair, Vec<u8>)> = Vec::with_capacity(filtered_addresses.len());

    for pair in &filtered_addresses {
        let mut buffer = vec![0u8; element_size];
        if driver_manager.read_memory_unified(pair.addr, &mut buffer, None).is_ok() {
            address_values.push((pair.clone(), buffer));
        }

        // 更新已处理计数器
        if let Some(counter) = &processed_counter {
            counter.fetch_add(1, Ordering::Relaxed);
        }
    }

    drop(driver_manager);

    // 用rayon并行判断
    let results: Vec<ValuePair> = address_values
        .into_par_iter()
        .filter_map(|(pair, bytes)| {
            if let Ok(true) = target.matched(&bytes) {
                if let Some(counter) = &total_found_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Some(pair)
            } else {
                None
            }
        })
        .collect();

    if log_enabled!(Level::Debug) {
        debug!(
            "Refine single search: {} -> {} results",
            filtered_addresses.len(),
            results.len()
        );
    }

    Ok(results)
}

/// Single value refine search with cancel and progress callbacks.
/// This version supports cancellation checking and progress updates during the search.
pub(crate) fn refine_single_search_with_cancel<F, P>(
    addresses: &[ValuePair],
    target: &SearchValue,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    check_cancelled: &F,
    update_progress: &P,
) -> Result<Vec<ValuePair>>
where
    F: Fn() -> bool + Sync,
    P: Fn(usize, usize) + Sync,
{
    use rayon::prelude::*;
    use std::sync::atomic::Ordering;

    if addresses.is_empty() {
        return Ok(Vec::new());
    }

    // Check cancellation before starting.
    if check_cancelled() {
        return Ok(Vec::new());
    }

    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let target_type = target.value_type();
    let element_size = target_type.size();

    // Filter addresses with non-matching types.
    let filtered_addresses: Vec<_> = addresses
        .iter()
        .filter(|p| p.value_type == target_type)
        .cloned()
        .collect();

    if filtered_addresses.is_empty() {
        return Ok(Vec::new());
    }

    let total_addresses = filtered_addresses.len();

    // Read values for each address sequentially.
    let mut address_values: Vec<(ValuePair, Vec<u8>)> = Vec::with_capacity(filtered_addresses.len());

    for (idx, pair) in filtered_addresses.iter().enumerate() {
        // Check cancellation periodically.
        if idx % 1000 == 0 && check_cancelled() {
            return Ok(Vec::new());
        }

        let mut buffer = vec![0u8; element_size];
        if driver_manager.read_memory_unified(pair.addr, &mut buffer, None).is_ok() {
            address_values.push((pair.clone(), buffer));
        }

        // Update processed counter and progress.
        if let Some(counter) = &processed_counter {
            let processed = counter.fetch_add(1, Ordering::Relaxed) + 1;
            // Update progress every 100 addresses.
            if processed % 100 == 0 {
                let found = total_found_counter
                    .map(|c| c.load(Ordering::Relaxed))
                    .unwrap_or(0);
                update_progress(processed, found);
            }
        }
    }

    drop(driver_manager);

    // Check cancellation before parallel matching.
    if check_cancelled() {
        return Ok(Vec::new());
    }

    // Use rayon for parallel matching.
    let results: Vec<ValuePair> = address_values
        .into_par_iter()
        .filter_map(|(pair, bytes)| {
            if let Ok(true) = target.matched(&bytes) {
                if let Some(counter) = &total_found_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Some(pair)
            } else {
                None
            }
        })
        .collect();

    // Final progress update.
    let found_count = total_found_counter
        .map(|c| c.load(Ordering::Relaxed))
        .unwrap_or(results.len());
    update_progress(total_addresses, found_count);

    if log_enabled!(Level::Debug) {
        debug!(
            "Refine single search with cancel: {} -> {} results",
            filtered_addresses.len(),
            results.len()
        );
    }

    Ok(results)
}
