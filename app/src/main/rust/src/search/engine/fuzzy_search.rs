use super::super::result_manager::FuzzySearchResultItem;
use super::super::types::{FuzzyCondition, ValueType};
use super::manager::{BPLUS_TREE_ORDER, PAGE_SIZE};
use crate::core::DRIVER_MANAGER;
use crate::wuwa::PageStatusBitmap;
use anyhow::{anyhow, Result};
use bplustree::BPlusTreeSet;
use log::{debug, log_enabled, warn, Level};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use crate::search::engine::batch_reader::{cluster_addresses, parallel_batch_read};

/// 模糊搜索初始扫描
/// 记录指定内存区域内所有地址的当前值
/// 使用 BPlusTreeSet 存储结果，保持有序且支持高效删除
///
/// # 参数
/// * `value_type` - 要搜索的值类型
/// * `start` - 区域起始地址
/// * `end` - 区域结束地址
/// * `chunk_size` - 每次读取的块大小
/// * `processed_counter` - 已处理计数器（可选）
/// * `total_found_counter` - 找到总数计数器（可选）
/// * `check_cancelled` - 取消检查闭包（可选）
///
/// # 返回
/// 返回所有成功读取的地址及其值（有序）
pub(crate) fn fuzzy_initial_scan<F>(
    value_type: ValueType,
    start: u64,
    end: u64,
    chunk_size: usize,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    check_cancelled: Option<&F>,
) -> Result<BPlusTreeSet<FuzzySearchResultItem>>
where
    F: Fn() -> bool,
{
    let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let element_size = value_type.size();
    let page_size = *PAGE_SIZE;

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);

    let mut read_success = 0usize;
    let mut read_failed = 0usize;

    let mut current = start & !(*PAGE_SIZE as u64 - 1); // 页对齐
    let mut chunk_buffer = vec![0u8; chunk_size];

    while current < end {
        // Check cancellation at each chunk
        if let Some(check_fn) = check_cancelled {
            if check_fn() {
                if log_enabled!(Level::Debug) {
                    debug!("Fuzzy initial scan cancelled, returning {} results", results.len());
                }
                return Ok(results);
            }
        }

        let chunk_end = (current + chunk_size as u64).min(end);
        let chunk_len = (chunk_end - current) as usize;

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        let read_result = driver_manager.read_memory_unified(current, &mut chunk_buffer[..chunk_len], Some(&mut page_status));

        match read_result {
            Ok(_) => {
                let success_pages = page_status.success_count();
                if success_pages > 0 {
                    read_success += 1;

                    // 使用 rayon 并行处理 buffer，收集到临时 Vec
                    let chunk_results = scan_buffer_parallel(
                        &chunk_buffer[..chunk_len],
                        current,
                        start,
                        end,
                        element_size,
                        value_type,
                        page_size,
                        &page_status,
                    );

                    // 批量插入到 BPlusTreeSet
                    for item in chunk_results {
                        results.insert(item);
                    }
                } else {
                    read_failed += 1;
                }
            },
            Err(error) => {
                if log_enabled!(Level::Debug) {
                    warn!("Failed to read memory at 0x{:X} - 0x{:X}, err: {:?}", current, chunk_end, error);
                }
                read_failed += 1;
            },
        }

        // 更新计数器
        if let Some(counter) = processed_counter {
            counter.fetch_add(chunk_len, Ordering::Relaxed);
        }

        current = chunk_end;
    }

    if log_enabled!(Level::Debug) {
        let region_size = end - start;
        debug!(
            "Fuzzy initial scan: size={}MB, reads={} success + {} failed, found={}",
            region_size / 1024 / 1024,
            read_success,
            read_failed,
            results.len()
        );
    }

    // 更新总找到数
    if let Some(counter) = total_found_counter {
        counter.store(results.len(), Ordering::Relaxed);
    }

    Ok(results)
}

/// 使用 rayon 并行处理缓冲区，按页分割任务
/// 每个成功的页独立并行处理，无需比较操作
#[inline]
fn scan_buffer_parallel(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    element_size: usize,
    value_type: ValueType,
    page_size: usize,
    page_status: &PageStatusBitmap,
) -> Vec<FuzzySearchResultItem> {
    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    if search_start >= search_end {
        return Vec::new();
    }

    let num_pages = page_status.num_pages();

    // 收集所有成功页的索引
    let success_pages: Vec<usize> = (0..num_pages).filter(|&i| page_status.is_page_success(i)).collect();

    if success_pages.is_empty() {
        return Vec::new();
    }

    // 使用 rayon 并行处理每个成功的页
    success_pages
        .par_iter()
        .flat_map(|&page_idx| scan_single_page(buffer, buffer_addr, search_start, search_end, element_size, value_type, page_size, page_idx))
        .collect()
}

/// 扫描单个页内的所有元素
#[inline]
fn scan_single_page(
    buffer: &[u8],
    buffer_addr: u64,
    search_start: u64,
    search_end: u64,
    element_size: usize,
    value_type: ValueType,
    page_size: usize,
    page_idx: usize,
) -> Vec<FuzzySearchResultItem> {
    let page_start_addr = buffer_addr + (page_idx * page_size) as u64;
    let page_end_addr = page_start_addr + page_size as u64;

    // 与搜索范围取交集
    let effective_start = page_start_addr.max(search_start);
    let effective_end = page_end_addr.min(search_end);

    if effective_start >= effective_end {
        return Vec::new();
    }

    // 对齐到元素边界
    let rem = effective_start % element_size as u64;
    let first_addr = if rem == 0 {
        effective_start
    } else {
        effective_start + element_size as u64 - rem
    };

    if first_addr >= effective_end {
        return Vec::new();
    }

    // 预计算元素数量，一次性分配
    let elements_count = ((effective_end - first_addr) as usize) / element_size;
    let mut results = Vec::with_capacity(elements_count);

    // 批量处理：直接遍历字节切片，无需逐元素检查页状态
    let start_offset = (first_addr - buffer_addr) as usize;
    let end_offset = (effective_end - buffer_addr) as usize;

    // 确保不越界
    let safe_end = end_offset.min(buffer.len());

    let mut offset = start_offset;
    let mut addr = first_addr;

    while offset + element_size <= safe_end {
        // 直接从 buffer 切片创建结果项
        let item = FuzzySearchResultItem::from_bytes(addr, &buffer[offset..offset + element_size], value_type);
        results.push(item);

        offset += element_size;
        addr += element_size as u64;
    }

    results
}

/// 模糊搜索细化
/// 读取已有结果的当前值，并根据条件过滤
/// 返回新的 BPlusTreeSet
///
/// # 参数
/// * `items` - 之前的搜索结果
/// * `condition` - 模糊搜索条件
/// * `processed_counter` - 已处理计数器（可选）
/// * `total_found_counter` - 找到总数计数器（可选）
/// * `update_progress` - 进度更新回调
/// * `check_cancelled` - 取消检查闭包（可选）
///
/// # 返回
/// 返回满足条件的结果项（包含新值，有序）
pub(crate) fn fuzzy_refine_search<P, F>(
    items: &Vec<FuzzySearchResultItem>,
    condition: FuzzyCondition,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    update_progress: &P,
    check_cancelled: Option<&F>,
) -> Result<BPlusTreeSet<FuzzySearchResultItem>>
where
    P: Fn(usize, usize) + Sync,
    F: Fn() -> bool + Sync,
{
    if items.is_empty() {
        return Ok(BPlusTreeSet::new(BPLUS_TREE_ORDER));
    }

    let total_items = items.len();

    let batches = cluster_addresses(items);

    if log_enabled!(Level::Debug) {
        debug!(
            "Fuzzy refine: {} items -> {} batches (avg {:.1} items/batch)",
            items.len(),
            batches.len(),
            items.len() as f64 / batches.len() as f64
        );
    }

    let items_with_current_value = parallel_batch_read(&batches, items, processed_counter, total_found_counter, update_progress, check_cancelled)?;

    if log_enabled!(Level::Debug) {
        debug!("Fuzzy refine: read {} / {} items successfully", items_with_current_value.len(), total_items);
    }

    let cancelled = Arc::new(AtomicBool::new(false));
    let cancelled_clone = Arc::clone(&cancelled);

    let matched: Vec<FuzzySearchResultItem> = items_with_current_value
        .par_iter()
        .take_any_while(|_| {
            if cancelled_clone.load(Ordering::Relaxed) {
                return false;
            }
            if let Some(check_fn) = check_cancelled {
                if check_fn() {
                    cancelled_clone.store(true, Ordering::Relaxed);
                    return false;
                }
            }
            true
        })
        .filter_map(|(old_item, current_value)| {
            if old_item.matches_condition(current_value, condition) {
                if let Some(counter) = total_found_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                Some(FuzzySearchResultItem::from_bytes(old_item.address, current_value, old_item.value_type))
            } else {
                None
            }
        })
        .collect();

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
    for item in matched {
        results.insert(item);
    }

    if log_enabled!(Level::Debug) {
        debug!("Fuzzy refine: checked {} items, found {} matches", items.len(), results.len());
    }

    // 最终更新进度到 100%
    if let Some(counter) = total_found_counter {
        counter.store(results.len(), Ordering::Relaxed);
    }
    update_progress(total_items, results.len());

    Ok(results)
}
