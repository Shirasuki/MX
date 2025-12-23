use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicUsize};
use super::super::types::{SearchMode, SearchQuery, SearchValue, ValueType};
use super::manager::{BPLUS_TREE_ORDER, PAGE_MASK, PAGE_SIZE, ValuePair};
use crate::core::DRIVER_MANAGER;
use crate::wuwa::PageStatusBitmap;
use anyhow::Result;
use anyhow::anyhow;
use bplustree::BPlusTreeSet;
use log::{Level, debug, log_enabled, warn};
use memchr::memmem;

pub(crate) fn search_region_group(
    query: &SearchQuery,
    start: u64,
    end: u64,
    per_chunk_size: usize,
) -> Result<BPlusTreeSet<ValuePair>> {
    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
    let mut read_success = 0usize;
    let mut read_failed = 0usize;
    let mut matches_checked = 0usize;

    let min_element_size = query.values.iter().map(|v| v.value_type().size()).min().unwrap_or(1);
    let search_range = query.range as usize;

    let mut current = start & *PAGE_MASK as u64;
    let mut sliding_buffer = vec![0u8; per_chunk_size * 2]; // 双倍大小的滑动窗口缓冲区
    let mut is_first_chunk = true; // 是否是第一个chunk
    let mut prev_chunk_valid = false; // 前半部分是否有效（读取成功）

    while current < end {
        let chunk_end = (current + per_chunk_size as u64).min(end);
        let chunk_len = (chunk_end - current) as usize;

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        // 读取数据到滑动窗口的后半部分
        let read_result = driver_manager.read_memory_unified(
            current, 
            &mut sliding_buffer[per_chunk_size..per_chunk_size + chunk_len], 
            Some(&mut page_status)
        );

        match read_result {
            Ok(_) => {
                let success_pages = page_status.success_count();
                if success_pages > 0 {
                    read_success += 1;

                    if is_first_chunk {
                        // 第一个chunk：只搜索前半部分（刚读取的数据）
                        search_in_buffer_group(
                            &sliding_buffer[per_chunk_size..per_chunk_size + chunk_len],
                            current,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &page_status,
                            &mut results,
                            &mut matches_checked,
                        );
                        is_first_chunk = false;
                    } else if prev_chunk_valid {
                        // 非第一个chunk且前一个chunk有效：搜索重叠区域（从前半部分尾部到后半部分末尾）
                        let overlap_start_offset = per_chunk_size.saturating_sub(search_range);
                        let overlap_start_addr = current - search_range as u64;
                        let overlap_len = search_range + chunk_len;

                        // 创建一个组合的page_status用于重叠区域搜索
                        // 前半部分（重叠部分）假定已成功，后半部分使用实际的page_status
                        let mut combined_status = PageStatusBitmap::new(overlap_len, overlap_start_addr as usize);

                        let overlap_start_page = (overlap_start_addr as usize) / *PAGE_SIZE;
                        let overlap_end = overlap_start_addr as usize + search_range;
                        let overlap_end_page = (overlap_end + *PAGE_SIZE - 1) / *PAGE_SIZE;
                        let num_overlap_pages = overlap_end_page - overlap_start_page;

                        // 标记前半部分（重叠部分）为成功
                        for i in 0..num_overlap_pages {
                            combined_status.mark_success(i);
                        }

                        // 将后半部分的page_status映射到combined_status
                        let page_status_base = (current as usize) & *PAGE_MASK;
                        let combined_base = (overlap_start_addr as usize) & *PAGE_MASK;
                        let page_offset = (page_status_base - combined_base) / *PAGE_SIZE;

                        for i in 0..page_status.num_pages() {
                            if page_status.is_page_success(i) {
                                let combined_page_index = page_offset + i;
                                if combined_page_index < combined_status.num_pages() {
                                    combined_status.mark_success(combined_page_index);
                                }
                            }
                        }

                        search_in_buffer_group(
                            &sliding_buffer[overlap_start_offset..per_chunk_size + chunk_len],
                            overlap_start_addr,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &combined_status,
                            &mut results,
                            &mut matches_checked,
                        );
                    } else {
                        // 前一个chunk无效：只搜索当前chunk（后半部分）
                        search_in_buffer_group(
                            &sliding_buffer[per_chunk_size..per_chunk_size + chunk_len],
                            current,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &page_status,
                            &mut results,
                            &mut matches_checked,
                        );
                    }

                    prev_chunk_valid = true;
                } else {
                    read_failed += 1;
                    prev_chunk_valid = false;
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
                prev_chunk_valid = false;
            },
        }

        // 滑动窗口：把后半部分移动到前半部分
        if chunk_end < end {
            sliding_buffer.copy_within(per_chunk_size..per_chunk_size + chunk_len, 0);
        }

        current = chunk_end;
    }

    if log_enabled!(Level::Debug) {
        let region_size = end - start;
        debug!(
            "Group search stats: size={}MB, reads={} success + {} failed, matches_checked={}, found={}",
            region_size / 1024 / 1024,
            read_success,
            read_failed,
            matches_checked,
            results.len()
        );
    }

    Ok(results)
}

/// Deep group search for a memory region - finds ALL possible combinations
/// This is the deep search version of search_region_group
pub(crate) fn search_region_group_deep(
    query: &SearchQuery,
    start: u64,
    end: u64,
    per_chunk_size: usize,
) -> Result<BPlusTreeSet<ValuePair>> {
    // Use a no-op cancel check for backward compatibility.
    search_region_group_deep_with_cancel(query, start, end, per_chunk_size, &|| false)
}

/// Deep group search with cancellation support.
/// The `check_cancelled` closure is called periodically to check if the search should be cancelled.
pub(crate) fn search_region_group_deep_with_cancel<F>(
    query: &SearchQuery,
    start: u64,
    end: u64,
    per_chunk_size: usize,
    check_cancelled: &F,
) -> Result<BPlusTreeSet<ValuePair>>
where
    F: Fn() -> bool,
{
    // Check cancellation before starting.
    if check_cancelled() {
        return Ok(BPlusTreeSet::new(BPLUS_TREE_ORDER));
    }

    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
    let mut read_success = 0usize;
    let mut read_failed = 0usize;
    let mut matches_checked = 0usize;

    let min_element_size = query.values.iter().map(|v| v.value_type().size()).min().unwrap_or(1);
    let search_range = query.range as usize;

    let mut current = start & *PAGE_MASK as u64;
    let mut sliding_buffer = vec![0u8; per_chunk_size * 2];
    let mut is_first_chunk = true;
    let mut prev_chunk_valid = false;

    while current < end {
        // Check cancellation at each chunk.
        if check_cancelled() {
            return Ok(results);
        }

        let chunk_end = (current + per_chunk_size as u64).min(end);
        let chunk_len = (chunk_end - current) as usize;

        let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);

        let read_result = driver_manager.read_memory_unified(
            current,
            &mut sliding_buffer[per_chunk_size..per_chunk_size + chunk_len],
            Some(&mut page_status)
        );

        match read_result {
            Ok(_) => {
                let success_pages = page_status.success_count();
                if success_pages > 0 {
                    read_success += 1;

                    if is_first_chunk {
                        search_in_buffer_group_deep_with_cancel(
                            &sliding_buffer[per_chunk_size..per_chunk_size + chunk_len],
                            current,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &page_status,
                            &mut results,
                            &mut matches_checked,
                            check_cancelled,
                        );
                        is_first_chunk = false;
                    } else if prev_chunk_valid {
                        let overlap_start_offset = per_chunk_size.saturating_sub(search_range);
                        let overlap_start_addr = current - search_range as u64;
                        let overlap_len = search_range + chunk_len;

                        let mut combined_status = PageStatusBitmap::new(overlap_len, overlap_start_addr as usize);

                        let overlap_start_page = (overlap_start_addr as usize) / *PAGE_SIZE;
                        let overlap_end = overlap_start_addr as usize + search_range;
                        let overlap_end_page = (overlap_end + *PAGE_SIZE - 1) / *PAGE_SIZE;
                        let num_overlap_pages = overlap_end_page - overlap_start_page;

                        for i in 0..num_overlap_pages {
                            combined_status.mark_success(i);
                        }

                        let page_status_base = (current as usize) & *PAGE_MASK;
                        let combined_base = (overlap_start_addr as usize) & *PAGE_MASK;
                        let page_offset = (page_status_base - combined_base) / *PAGE_SIZE;

                        for idx in 0..page_status.num_pages() {
                            if page_status.is_page_success(idx) {
                                let combined_page_index = page_offset + idx;
                                if combined_page_index < combined_status.num_pages() {
                                    combined_status.mark_success(combined_page_index);
                                }
                            }
                        }

                        search_in_buffer_group_deep_with_cancel(
                            &sliding_buffer[overlap_start_offset..per_chunk_size + chunk_len],
                            overlap_start_addr,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &combined_status,
                            &mut results,
                            &mut matches_checked,
                            check_cancelled,
                        );
                    } else {
                        search_in_buffer_group_deep_with_cancel(
                            &sliding_buffer[per_chunk_size..per_chunk_size + chunk_len],
                            current,
                            start,
                            chunk_end,
                            min_element_size,
                            query,
                            &page_status,
                            &mut results,
                            &mut matches_checked,
                            check_cancelled,
                        );
                    }

                    prev_chunk_valid = true;
                } else {
                    read_failed += 1;
                    prev_chunk_valid = false;
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
                prev_chunk_valid = false;
            },
        }

        if chunk_end < end {
            sliding_buffer.copy_within(per_chunk_size..per_chunk_size + chunk_len, 0);
        }

        current = chunk_end;
    }

    if log_enabled!(Level::Debug) {
        let region_size = end - start;
        debug!(
            "Deep group search stats: size={}MB, reads={} success + {} failed, matches_checked={}, found={}",
            region_size / 1024 / 1024,
            read_success,
            read_failed,
            matches_checked,
            results.len()
        );
    }

    Ok(results)
}

#[inline]
pub(crate) fn search_in_buffer_group(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
) {
    // anchor-first 优化：尝试使用第一个 Fixed 值作为 anchor 进行 SIMD 扫描
    let mut anchor_index = None;
    let mut anchor_bytes_storage = [0u8; 8]; // 最大 8 字节（Qword/Double）
    let mut anchor_bytes_len = 0;

    for (idx, value) in query.values.iter().enumerate() {
        match value {
            SearchValue::FixedInt { value, value_type } => {
                let size = value_type.size();
                anchor_bytes_storage[..size].copy_from_slice(&value[..size]);
                anchor_bytes_len = size;
                anchor_index = Some(idx);
                break;
            },
            SearchValue::FixedFloat { value, value_type } => {
                match value_type {
                    ValueType::Float => {
                        let f32_val = *value as f32;
                        let bytes = f32_val.to_le_bytes();
                        anchor_bytes_storage[..4].copy_from_slice(&bytes);
                        anchor_bytes_len = 4;
                    },
                    ValueType::Double => {
                        let bytes = value.to_le_bytes();
                        anchor_bytes_storage[..8].copy_from_slice(&bytes);
                        anchor_bytes_len = 8;
                    },
                    _ => continue,
                }
                anchor_index = Some(idx);
                break;
            },
            _ => continue,
        }
    }

    // 如果没有找到 Fixed 值作为 anchor，回退到传统逐地址扫描
    if anchor_index.is_none() {
        search_in_buffer_group_fallback(
            buffer,
            buffer_addr,
            region_start,
            region_end,
            min_element_size,
            query,
            page_status,
            results,
            matches_checked,
        );
        return;
    }

    // 使用 anchor-first SIMD 优化
    let anchor_bytes = &anchor_bytes_storage[..anchor_bytes_len];
    let finder = memmem::Finder::new(anchor_bytes);
    let mut candidates = Vec::new();
    let mut pos = 0;

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    // 计算第一个对齐地址
    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    // 预先构建成功页的地址范围
    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    let buffer_page_start = buffer_addr & !(*PAGE_SIZE as u64 - 1);
    let anchor_alignment = anchor_bytes_len;

    // SIMD 快速扫描找到所有 anchor 候选位置
    while pos < buffer.len() {
        if let Some(offset) = finder.find(&buffer[pos..]) {
            let absolute_offset = pos + offset;
            let addr = buffer_addr + absolute_offset as u64;

            // 过滤1: 检查对齐（使用 anchor 的大小）
            if addr % anchor_alignment as u64 == 0 && addr >= first_addr && addr < search_end {
                candidates.push(absolute_offset);
            }

            pos = absolute_offset + 1;
        } else {
            break;
        }
    }

    // 对候选位置做页面过滤和完整校验
    let anchor_idx = anchor_index.unwrap();

    for &offset in &candidates {
        let anchor_addr = buffer_addr + offset as u64;

        // 根据搜索模式计算需要验证的区域
        let (start_addr, _start_offset) = if query.mode == SearchMode::Ordered {
            // Ordered 模式：根据 anchor 在 query 中的位置，反推序列起始位置
            let anchor_offset_in_sequence = query.values[..anchor_idx]
                .iter()
                .map(|v| v.value_type().size())
                .sum::<usize>();

            let seq_start_addr = anchor_addr.saturating_sub(anchor_offset_in_sequence as u64);
            let seq_start_offset = offset.saturating_sub(anchor_offset_in_sequence);

            (seq_start_addr, seq_start_offset)
        } else {
            // Unordered 模式：从 anchor_addr - range 开始
            let range_start = anchor_addr.saturating_sub(query.range as u64);
            let range_start_offset = if range_start < buffer_addr {
                0
            } else {
                (range_start - buffer_addr) as usize
            };
            (range_start, range_start_offset)
        };

        // 检查地址是否在有效范围内
        let check_range_addr = if query.mode == SearchMode::Ordered {
            start_addr
        } else {
            anchor_addr
        };
        if check_range_addr < region_start || check_range_addr >= region_end {
            continue;
        }

        // 检查地址是否在有效页范围内
        let check_addr = if query.mode == SearchMode::Ordered {
            start_addr
        } else {
            anchor_addr
        };
        let mut in_valid_page = false;
        for (start_page, end_page) in &page_ranges {
            let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
            let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

            if check_addr >= page_range_start && check_addr < page_range_end {
                in_valid_page = true;
                break;
            }
        }

        if !in_valid_page {
            continue;
        }

        // 完整校验
        let total_values_size: usize = query.values.iter().map(|v| v.value_type().size()).sum();
        let min_buffer_size = (total_values_size as u64).max(query.range as u64);

        let (check_start, check_end) = if query.mode == SearchMode::Ordered {
            // Ordered 模式：序列必须完整在 buffer 内才能验证
            if start_addr < buffer_addr {
                continue;
            }
            (
                start_addr,
                (start_addr + min_buffer_size).min(buffer_end).min(region_end),
            )
        } else {
            // Unordered 模式：需要覆盖 anchor 前后的范围
            let unordered_start = anchor_addr.saturating_sub(query.range as u64).max(buffer_addr);
            let unordered_end = (anchor_addr + query.range as u64).min(buffer_end).min(region_end);
            (unordered_start, unordered_end)
        };

        let check_start_offset = (check_start - buffer_addr) as usize;
        let range_size = (check_end - check_start) as usize;

        if check_start_offset + range_size <= buffer.len() {
            *matches_checked += 1;

            if let Some(offsets) = try_match_group_at_address(
                &buffer[check_start_offset..check_start_offset + range_size],
                check_start,
                query,
            ) {
                for (idx, value_offset) in offsets.iter().enumerate() {
                    let value_addr = check_start + *value_offset as u64;
                    let value_type = query.values[idx].value_type();
                    results.insert((value_addr, value_type).into());
                }
            }
        }
    }
}

/// 传统逐地址扫描方法（用于没有 Fixed 值作为 anchor 时的降级）
#[inline]
pub(crate) fn search_in_buffer_group_fallback(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
) {
    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);
    let search_range = query.range as u64;

    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    // 优化：预先构建成功页的地址范围
    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    // buffer_addr 所在页的起始地址（页对齐）
    let buffer_page_start = buffer_addr & !(*PAGE_SIZE as u64 - 1);

    for (start_page, end_page) in page_ranges {
        // 将相对页索引转换为绝对地址范围
        let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
        let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

        // 限制在 buffer 和搜索范围内
        let range_start = page_range_start.max(buffer_addr);
        let range_end = page_range_end.min(search_end).min(buffer_end);

        if range_start >= range_end {
            continue;
        }

        // 找到这个范围内第一个 >= first_addr 且对齐的地址
        let mut addr = if range_start <= first_addr {
            first_addr // first_addr 已经对齐
        } else {
            // range_start > first_addr，需要对齐
            let rem = range_start % min_element_size as u64;
            if rem == 0 {
                range_start
            } else {
                range_start + min_element_size as u64 - rem
            }
        };

        // 在这个有效页范围内搜索
        while addr < range_end {
            let offset = (addr - buffer_addr) as usize;
            if offset < buffer.len() {
                let range_end_check = (addr + search_range).min(buffer_end).min(search_end);
                let range_size = (range_end_check - addr) as usize;

                if range_size >= query.range as usize && offset + range_size <= buffer.len() {
                    *matches_checked += 1;

                    if let Some(offsets) = try_match_group_at_address(&buffer[offset..offset + range_size], addr, query)
                    {
                        // 保存所有匹配值的地址
                        for (idx, value_offset) in offsets.iter().enumerate() {
                            let value_addr = addr + *value_offset as u64;
                            let value_type = query.values[idx].value_type();
                            results.insert((value_addr, value_type).into());
                        }
                    }
                }
            }
            addr += min_element_size as u64;
        }
    }
}

pub(crate) fn try_match_group_at_address(buffer: &[u8], start_addr: u64, query: &SearchQuery) -> Option<Vec<usize>> {
    match query.mode {
        SearchMode::Ordered => try_match_ordered(buffer, start_addr, query),
        SearchMode::Unordered => try_match_unordered(buffer, start_addr, query),
    }
}

pub(crate) fn try_match_ordered(buffer: &[u8], _start_addr: u64, query: &SearchQuery) -> Option<Vec<usize>> {
    let mut offsets = Vec::with_capacity(query.values.len());
    let mut current_offset = 0usize;

    for target_value in &query.values {
        let value_size = target_value.value_type().size();
        let mut found = false;

        while current_offset + value_size <= buffer.len() {
            let element_bytes = &buffer[current_offset..current_offset + value_size];

            if let Ok(true) = target_value.matched(element_bytes) {
                offsets.push(current_offset);
                current_offset += value_size;
                found = true;
                break;
            }

            let alignment = target_value.value_type().size();
            current_offset += alignment;
        }

        if !found {
            return None;
        }
    }

    Some(offsets)
}

pub(crate) fn try_match_unordered(buffer: &[u8], _start_addr: u64, query: &SearchQuery) -> Option<Vec<usize>> {
    let mut offsets = vec![None; query.values.len()];
    let mut found_count = 0;

    for (value_idx, target_value) in query.values.iter().enumerate() {
        if offsets[value_idx].is_some() {
            continue;
        }

        let value_size = target_value.value_type().size();
        let alignment = value_size;
        let mut offset = 0usize;

        while offset + value_size <= buffer.len() {
            let element_bytes = &buffer[offset..offset + value_size];

            if let Ok(true) = target_value.matched(element_bytes) {
                offsets[value_idx] = Some(offset);
                found_count += 1;
                break;
            }

            offset += alignment;
        }
    }

    if found_count == query.values.len() {
        Some(offsets.into_iter().map(|o| o.unwrap()).collect())
    } else {
        None
    }
}

// ==================== Deep Search (Exhaustive Combination Search) ====================

/// Deep group search - finds ALL possible combinations when there are duplicate values
///
/// Unlike standard group search which uses a first-match greedy strategy,
/// deep search exhaustively finds all valid combinations using DFS backtracking.
///
/// # Use Cases
/// - When memory contains duplicate values and you need all combinations
/// - Example: Memory [100, 200, 300, 300] with query [100, 200, 300]
///   - Standard search finds: 3 addresses (first match only)
///   - Deep search finds: 4 addresses (all combinations)
///
/// # Performance
/// This is slower than standard search due to backtracking algorithm.
/// Use only when you need to find all combinations.
pub(crate) fn search_in_buffer_group_deep(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
) {
    match query.mode {
        SearchMode::Ordered => search_ordered_deep(
            buffer,
            buffer_addr,
            region_start,
            region_end,
            min_element_size,
            query,
            page_status,
            results,
            matches_checked,
        ),
        SearchMode::Unordered => search_unordered_deep(
            buffer,
            buffer_addr,
            region_start,
            region_end,
            min_element_size,
            query,
            page_status,
            results,
            matches_checked,
        ),
    }
}

/// Deep group search with cancellation support.
pub(crate) fn search_in_buffer_group_deep_with_cancel<F>(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
    check_cancelled: &F,
) where
    F: Fn() -> bool,
{
    match query.mode {
        SearchMode::Ordered => search_ordered_deep_with_cancel(
            buffer,
            buffer_addr,
            region_start,
            region_end,
            min_element_size,
            query,
            page_status,
            results,
            matches_checked,
            check_cancelled,
        ),
        SearchMode::Unordered => search_unordered_deep_with_cancel(
            buffer,
            buffer_addr,
            region_start,
            region_end,
            min_element_size,
            query,
            page_status,
            results,
            matches_checked,
            check_cancelled,
        ),
    }
}

/// Deep search for ordered mode using DFS backtracking
fn search_ordered_deep(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
) {
    use std::collections::HashSet;

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    // Calculate first aligned address
    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    // Get successful page ranges
    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    let buffer_page_start = buffer_addr & *PAGE_MASK as u64;
    let search_range = query.range as u64;

    // Iterate through each aligned address as potential starting point
    for (start_page, end_page) in page_ranges {
        let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
        let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

        let range_start = page_range_start.max(buffer_addr);
        let range_end = page_range_end.min(search_end).min(buffer_end);

        if range_start >= range_end {
            continue;
        }

        let mut addr = if range_start <= first_addr {
            first_addr
        } else {
            let rem = range_start % min_element_size as u64;
            if rem == 0 {
                range_start
            } else {
                range_start + min_element_size as u64 - rem
            }
        };

        while addr < range_end {
            let offset = (addr - buffer_addr) as usize;
            if offset < buffer.len() {
                let range_end_check = (addr + search_range).min(buffer_end).min(search_end);
                let range_size = (range_end_check - addr) as usize;

                if range_size >= query.range as usize && offset + range_size <= buffer.len() {
                    *matches_checked += 1;

                    // Use DFS to find all combinations
                    let mut chosen = Vec::with_capacity(query.values.len());
                    let mut used = HashSet::new();

                    dfs_ordered(
                        &buffer[offset..offset + range_size],
                        addr,
                        0, // Start from first query value
                        0, // Start searching from offset 0
                        query,
                        &mut chosen,
                        &mut used,
                        results,
                    );
                }
            }
            addr += min_element_size as u64;
        }
    }
}

/// DFS backtracking for ordered search
fn dfs_ordered(
    buffer: &[u8],
    base_addr: u64,
    query_idx: usize,
    search_offset: usize,
    query: &SearchQuery,
    chosen: &mut Vec<(u64, ValueType)>,
    used: &mut std::collections::HashSet<u64>,
    results: &mut BPlusTreeSet<ValuePair>,
) {
    // Found complete match
    if query_idx == query.values.len() {
        for (addr, vt) in chosen.iter() {
            results.insert(ValuePair::new(*addr, *vt));
        }
        return;
    }

    let target_value = &query.values[query_idx];
    let value_size = target_value.value_type().size();
    let alignment = value_size;

    let mut offset = search_offset;
    while offset + value_size <= buffer.len() {
        let addr = base_addr + offset as u64;

        // Check if address is already used
        if used.contains(&addr) {
            offset += alignment;
            continue;
        }

        // Check if value matches
        let element_bytes = &buffer[offset..offset + value_size];
        if let Ok(true) = target_value.matched(element_bytes) {
            // Choose this position
            chosen.push((addr, target_value.value_type()));
            used.insert(addr);

            // Recurse to next query value (search from next aligned position)
            dfs_ordered(
                buffer,
                base_addr,
                query_idx + 1,
                offset + value_size, // Continue from next position
                query,
                chosen,
                used,
                results,
            );

            // Backtrack
            chosen.pop();
            used.remove(&addr);
        }

        offset += alignment;
    }
}

/// Deep search for unordered mode using DFS backtracking
fn search_unordered_deep(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
) {
    use std::collections::HashSet;

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    let buffer_page_start = buffer_addr & *PAGE_MASK as u64;
    let search_range = query.range as u64;

    for (start_page, end_page) in page_ranges {
        let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
        let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

        let range_start = page_range_start.max(buffer_addr);
        let range_end = page_range_end.min(search_end).min(buffer_end);

        if range_start >= range_end {
            continue;
        }

        let mut addr = if range_start <= first_addr {
            first_addr
        } else {
            let rem = range_start % min_element_size as u64;
            if rem == 0 {
                range_start
            } else {
                range_start + min_element_size as u64 - rem
            }
        };

        while addr < range_end {
            let offset = (addr - buffer_addr) as usize;
            if offset < buffer.len() {
                let unordered_start = addr.saturating_sub(search_range).max(buffer_addr);
                let unordered_end = (addr + search_range).min(buffer_end).min(search_end);
                let start_offset = (unordered_start - buffer_addr) as usize;
                let range_size = (unordered_end - unordered_start) as usize;

                if range_size >= query.range as usize && start_offset + range_size <= buffer.len() {
                    *matches_checked += 1;

                    let mut chosen = Vec::with_capacity(query.values.len());
                    let mut used = HashSet::new();

                    dfs_unordered(
                        &buffer[start_offset..start_offset + range_size],
                        unordered_start,
                        0, // Start from first query value
                        0, // Start searching from offset 0
                        query,
                        &mut chosen,
                        &mut used,
                        results,
                    );
                }
            }
            addr += min_element_size as u64;
        }
    }
}

/// DFS backtracking for unordered search
fn dfs_unordered(
    buffer: &[u8],
    base_addr: u64,
    query_idx: usize,
    search_offset: usize,
    query: &SearchQuery,
    chosen: &mut Vec<(u64, ValueType)>,
    used: &mut std::collections::HashSet<u64>,
    results: &mut BPlusTreeSet<ValuePair>,
) {
    // Found complete match
    if query_idx == query.values.len() {
        for (addr, vt) in chosen.iter() {
            results.insert(ValuePair::new(*addr, *vt));
        }
        return;
    }

    let target_value = &query.values[query_idx];
    let value_size = target_value.value_type().size();
    let alignment = value_size;

    let mut offset = search_offset;
    while offset + value_size <= buffer.len() {
        let addr = base_addr + offset as u64;

        if used.contains(&addr) {
            offset += alignment;
            continue;
        }

        let element_bytes = &buffer[offset..offset + value_size];
        if let Ok(true) = target_value.matched(element_bytes) {
            chosen.push((addr, target_value.value_type()));
            used.insert(addr);

            // For unordered mode, continue searching from next position (not necessarily adjacent)
            dfs_unordered(
                buffer,
                base_addr,
                query_idx + 1,
                offset + alignment, // Continue from next aligned position
                query,
                chosen,
                used,
                results,
            );

            chosen.pop();
            used.remove(&addr);
        }

        offset += alignment;
    }
}

/// Deep search for ordered mode with cancellation support.
fn search_ordered_deep_with_cancel<F>(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
    check_cancelled: &F,
) where
    F: Fn() -> bool,
{
    use std::collections::HashSet;
    use std::sync::atomic::{AtomicBool, Ordering};

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    let buffer_page_start = buffer_addr & *PAGE_MASK as u64;
    let search_range = query.range as u64;

    // Use AtomicBool to propagate cancellation from DFS.
    let cancelled = AtomicBool::new(false);

    for (start_page, end_page) in page_ranges {
        // Check cancellation at page range level.
        if check_cancelled() || cancelled.load(Ordering::Relaxed) {
            return;
        }

        let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
        let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

        let range_start = page_range_start.max(buffer_addr);
        let range_end = page_range_end.min(search_end).min(buffer_end);

        if range_start >= range_end {
            continue;
        }

        let mut addr = if range_start <= first_addr {
            first_addr
        } else {
            let rem = range_start % min_element_size as u64;
            if rem == 0 {
                range_start
            } else {
                range_start + min_element_size as u64 - rem
            }
        };

        let mut iteration_count = 0u64;
        while addr < range_end {
            // Check cancellation periodically (every 1000 iterations).
            iteration_count += 1;
            if iteration_count % 1000 == 0 && check_cancelled() {
                cancelled.store(true, Ordering::Relaxed);
                return;
            }

            let offset = (addr - buffer_addr) as usize;
            if offset < buffer.len() {
                let range_end_check = (addr + search_range).min(buffer_end).min(search_end);
                let range_size = (range_end_check - addr) as usize;

                if range_size >= query.range as usize && offset + range_size <= buffer.len() {
                    *matches_checked += 1;

                    let mut chosen = Vec::with_capacity(query.values.len());
                    let mut used = HashSet::new();

                    dfs_ordered_with_cancel(
                        &buffer[offset..offset + range_size],
                        addr,
                        0,
                        0,
                        query,
                        &mut chosen,
                        &mut used,
                        results,
                        check_cancelled,
                        &cancelled,
                    );

                    if cancelled.load(Ordering::Relaxed) {
                        return;
                    }
                }
            }
            addr += min_element_size as u64;
        }
    }
}

/// DFS backtracking for ordered search with cancellation support.
fn dfs_ordered_with_cancel<F>(
    buffer: &[u8],
    base_addr: u64,
    query_idx: usize,
    search_offset: usize,
    query: &SearchQuery,
    chosen: &mut Vec<(u64, ValueType)>,
    used: &mut std::collections::HashSet<u64>,
    results: &mut BPlusTreeSet<ValuePair>,
    check_cancelled: &F,
    cancelled: &AtomicBool,
) where
    F: Fn() -> bool,
{
    use std::sync::atomic::Ordering;

    // Check cancellation flag.
    if cancelled.load(Ordering::Relaxed) {
        return;
    }

    // Found complete match.
    if query_idx == query.values.len() {
        for (addr, vt) in chosen.iter() {
            results.insert(ValuePair::new(*addr, *vt));
        }
        return;
    }

    let target_value = &query.values[query_idx];
    let value_size = target_value.value_type().size();
    let alignment = value_size;

    let mut offset = search_offset;
    let mut iteration_count = 0u64;
    while offset + value_size <= buffer.len() {
        // Check cancellation periodically (every 500 iterations in DFS).
        iteration_count += 1;
        if iteration_count % 500 == 0 {
            if check_cancelled() {
                cancelled.store(true, Ordering::Relaxed);
                return;
            }
        }

        let addr = base_addr + offset as u64;

        if used.contains(&addr) {
            offset += alignment;
            continue;
        }

        let element_bytes = &buffer[offset..offset + value_size];
        if let Ok(true) = target_value.matched(element_bytes) {
            chosen.push((addr, target_value.value_type()));
            used.insert(addr);

            dfs_ordered_with_cancel(
                buffer,
                base_addr,
                query_idx + 1,
                offset + value_size,
                query,
                chosen,
                used,
                results,
                check_cancelled,
                cancelled,
            );

            // Check if we should stop.
            if cancelled.load(Ordering::Relaxed) {
                return;
            }

            chosen.pop();
            used.remove(&addr);
        }

        offset += alignment;
    }
}

/// Deep search for unordered mode with cancellation support.
fn search_unordered_deep_with_cancel<F>(
    buffer: &[u8],
    buffer_addr: u64,
    region_start: u64,
    region_end: u64,
    min_element_size: usize,
    query: &SearchQuery,
    page_status: &PageStatusBitmap,
    results: &mut BPlusTreeSet<ValuePair>,
    matches_checked: &mut usize,
    check_cancelled: &F,
) where
    F: Fn() -> bool,
{
    use std::collections::HashSet;
    use std::sync::atomic::{AtomicBool, Ordering};

    let buffer_end = buffer_addr + buffer.len() as u64;
    let search_start = buffer_addr.max(region_start);
    let search_end = buffer_end.min(region_end);

    let rem = search_start % min_element_size as u64;
    let first_addr = if rem == 0 {
        search_start
    } else {
        search_start + min_element_size as u64 - rem
    };

    let page_ranges = page_status.get_success_page_ranges();
    if page_ranges.is_empty() {
        return;
    }

    let buffer_page_start = buffer_addr & *PAGE_MASK as u64;
    let search_range = query.range as u64;

    let cancelled = AtomicBool::new(false);

    for (start_page, end_page) in page_ranges {
        // Check cancellation at page range level.
        if check_cancelled() || cancelled.load(Ordering::Relaxed) {
            return;
        }

        let page_range_start = buffer_page_start + (start_page * *PAGE_SIZE) as u64;
        let page_range_end = buffer_page_start + (end_page * *PAGE_SIZE) as u64;

        let range_start = page_range_start.max(buffer_addr);
        let range_end = page_range_end.min(search_end).min(buffer_end);

        if range_start >= range_end {
            continue;
        }

        let mut addr = if range_start <= first_addr {
            first_addr
        } else {
            let rem = range_start % min_element_size as u64;
            if rem == 0 {
                range_start
            } else {
                range_start + min_element_size as u64 - rem
            }
        };

        let mut iteration_count = 0u64;
        while addr < range_end {
            // Check cancellation periodically.
            iteration_count += 1;
            if iteration_count % 1000 == 0 && check_cancelled() {
                cancelled.store(true, Ordering::Relaxed);
                return;
            }

            let offset = (addr - buffer_addr) as usize;
            if offset < buffer.len() {
                let unordered_start = addr.saturating_sub(search_range).max(buffer_addr);
                let unordered_end = (addr + search_range).min(buffer_end).min(search_end);
                let start_offset = (unordered_start - buffer_addr) as usize;
                let range_size = (unordered_end - unordered_start) as usize;

                if range_size >= query.range as usize && start_offset + range_size <= buffer.len() {
                    *matches_checked += 1;

                    let mut chosen = Vec::with_capacity(query.values.len());
                    let mut used = HashSet::new();

                    dfs_unordered_with_cancel(
                        &buffer[start_offset..start_offset + range_size],
                        unordered_start,
                        0,
                        0,
                        query,
                        &mut chosen,
                        &mut used,
                        results,
                        check_cancelled,
                        &cancelled,
                    );

                    if cancelled.load(Ordering::Relaxed) {
                        return;
                    }
                }
            }
            addr += min_element_size as u64;
        }
    }
}

/// DFS backtracking for unordered search with cancellation support.
fn dfs_unordered_with_cancel<F>(
    buffer: &[u8],
    base_addr: u64,
    query_idx: usize,
    search_offset: usize,
    query: &SearchQuery,
    chosen: &mut Vec<(u64, ValueType)>,
    used: &mut std::collections::HashSet<u64>,
    results: &mut BPlusTreeSet<ValuePair>,
    check_cancelled: &F,
    cancelled: &AtomicBool,
) where
    F: Fn() -> bool,
{
    use std::sync::atomic::Ordering;

    if cancelled.load(Ordering::Relaxed) {
        return;
    }

    if query_idx == query.values.len() {
        for (addr, vt) in chosen.iter() {
            results.insert(ValuePair::new(*addr, *vt));
        }
        return;
    }

    let target_value = &query.values[query_idx];
    let value_size = target_value.value_type().size();
    let alignment = value_size;

    let mut offset = search_offset;
    let mut iteration_count = 0u64;
    while offset + value_size <= buffer.len() {
        iteration_count += 1;
        if iteration_count % 500 == 0 {
            if check_cancelled() {
                cancelled.store(true, Ordering::Relaxed);
                return;
            }
        }

        let addr = base_addr + offset as u64;

        if used.contains(&addr) {
            offset += alignment;
            continue;
        }

        let element_bytes = &buffer[offset..offset + value_size];
        if let Ok(true) = target_value.matched(element_bytes) {
            chosen.push((addr, target_value.value_type()));
            used.insert(addr);

            dfs_unordered_with_cancel(
                buffer,
                base_addr,
                query_idx + 1,
                offset + alignment,
                query,
                chosen,
                used,
                results,
                check_cancelled,
                cancelled,
            );

            if cancelled.load(Ordering::Relaxed) {
                return;
            }

            chosen.pop();
            used.remove(&addr);
        }

        offset += alignment;
    }
}

// ==================== Refine Search (Result Improvement) ====================

/// 使用 DFS 算法对已有搜索结果进行组搜索改善
///
/// 在已有的搜索结果中，找到所有满足组搜索条件的地址组合
/// 使用深度优先搜索 (DFS) 回溯算法，可以找到所有可能的有效组合
///
/// # 参数
/// - `existing_results`: 已有的搜索结果集合 (B+树，已按地址排序)
/// - `query`: 组搜索查询条件
/// - `processed_counter`: 可选的已处理地址计数器（用于进度追踪）
///
/// # 返回值
/// 返回满足条件的所有地址的集合
#[deprecated]
pub(crate) fn refine_search_group_with_dfs(
    existing_results: &Vec<ValuePair>,
    query: &SearchQuery,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
) -> Result<BPlusTreeSet<ValuePair>> {
    use rayon::prelude::*;
    use std::collections::HashSet;
    use std::sync::atomic::Ordering;

    if log_enabled!(Level::Debug) {
        debug!("当前结果数量: {}", existing_results.len())
    }

    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let mut refined_results = BPlusTreeSet::new(BPLUS_TREE_ORDER);

    if query.values.is_empty() {
        return Ok(refined_results);
    }

    // 读取全部地址与当前值
    let mut addr_values: Vec<(u64, Vec<u8>)> = Vec::with_capacity(existing_results.len());
    for pair in existing_results.iter() {
        let addr = pair.addr;
        let value_size = pair.value_type.size();
        let mut buffer = vec![0u8; value_size];

        if driver_manager.read_memory_unified(addr, &mut buffer, None).is_ok() {
            addr_values.push((addr, buffer));
        } else {
            // 读取失败也要更新计数器
            if let Some(counter) = processed_counter {
                counter.fetch_add(1, Ordering::Relaxed);
            }
            if log_enabled!(Level::Debug) {
                warn!("读取内存失败在改善搜索的时候, addr: {:x}, size = {}", addr, value_size)
            }
        }
    }

    if addr_values.is_empty() {
        if log_enabled!(Level::Debug) {
            debug!("当前结果数量: {}, 但是都读取失败了", existing_results.len())
        }
        return Ok(refined_results);
    }

    if log_enabled!(Level::Debug) {
        debug!("结果数量: {}, 可读地址数: {}", existing_results.len(), addr_values.len());
    }

    // 找所有锚点
    let first_query_target = &query.values[0];
    let anchors: Vec<u64> = addr_values
        .par_iter()
        .filter_map(|(addr, bytes)| {
            if let Ok(true) = first_query_target.matched(&bytes) {
                Some(*addr) // 是锚点，不更新计数器
            } else {
                // 更新已处理计数器 (非锚点更新)
                if let Some(counter) = &processed_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }

                None
            }
        })
        .collect();

    if log_enabled!(Level::Debug) {
        debug!("锚点数量: {}, 可读地址数: {}", anchors.len(), addr_values.len());
    }

    if anchors.is_empty() {
        return Ok(refined_results);
    }

    if query.values.len() == 1 {
        // 单值改善, 直接返回锚点结果
        let value_type = query.values[0].value_type();
        for anchor_addr in anchors {
            refined_results.insert(ValuePair::new(anchor_addr, value_type));
        }
        return Ok(refined_results);
    }

    // 主循环：每个锚点执行 DFS
    for anchor_addr in anchors {
        let (min_addr, max_addr) = match query.mode {
            SearchMode::Unordered => (
                anchor_addr.saturating_sub(query.range as u64),
                anchor_addr + query.range as u64,
            ),
            SearchMode::Ordered => (anchor_addr, anchor_addr + query.range as u64),
        };

        // 候选（不含锚点本身，避免重复使用）
        let mut candidates: Vec<(u64, &Vec<u8>)> = Vec::new();
        for (addr, bytes) in &addr_values {
            if *addr >= min_addr && *addr <= max_addr && *addr != anchor_addr {
                candidates.push((*addr, bytes));
            }
        }

        // 剪枝：如果候选数量 < (query.values.len() - 1) 不可能成功
        if candidates.len() < query.values.len() - 1 {
            if let Some(counter) = &processed_counter {
                counter.fetch_add(1, Ordering::Relaxed); // 锚点被放弃，更新计数器
            }
            continue;
        }

        // DFS：寻找所有满足的组合
        let mut used: HashSet<u64> = HashSet::new();
        used.insert(anchor_addr);

        // 当前选择的地址（含锚点）
        let mut chosen: Vec<(u64, ValueType)> = Vec::with_capacity(query.values.len());
        chosen.push((anchor_addr, query.values[0].value_type()));

        // 回溯函数
        fn dfs(
            cand_idx: usize,
            candidates: &[(u64, &Vec<u8>)],
            query: &SearchQuery,
            chosen: &mut Vec<(u64, ValueType)>,
            used: &mut HashSet<u64>,
            refined_results: &mut BPlusTreeSet<ValuePair>,
        ) -> Result<()> {
            let need_total = query.values.len();
            let have = chosen.len();

            // 成功匹配全部查询值
            if have == need_total {
                for (addr, vt) in chosen.iter() {
                    refined_results.insert(ValuePair::new(*addr, *vt));
                }
                return Ok(());
            }

            // 剩余还需要匹配的查询值数量
            let remaining_need = need_total - have;

            // 剩余候选是否足够（剪枝）
            let remaining_candidates = candidates.len().saturating_sub(cand_idx);
            if remaining_candidates < remaining_need {
                return Ok(());
            }

            // 当前要匹配的查询值
            let sv = &query.values[have];

            // 遍历从 cand_idx 开始的候选
            for i in cand_idx..candidates.len() {
                let (addr, bytes) = candidates[i];

                // 安全检查：确保缓冲区大小足够
                if sv.value_type().size() > bytes.len() {
                    continue;
                }

                // 如果不匹配则跳过
                if !sv.matched(bytes).unwrap_or(false) {
                    continue;
                }

                // 地址唯一约束
                if used.contains(&addr) {
                    continue;
                }

                // 选择
                used.insert(addr);
                chosen.push((addr, sv.value_type()));

                // 下一层从 i+1 开始（保证组合不重复）
                dfs(i + 1, candidates, query, chosen, used, refined_results)?;

                // 回溯
                chosen.pop();
                used.remove(&addr);
            }

            Ok(())
        }

        dfs(0, &candidates, query, &mut chosen, &mut used, &mut refined_results)?;

        // 更新已处理计数器
        if let Some(counter) = &processed_counter {
            let cur = counter.fetch_add(1, Ordering::Relaxed);
            if log_enabled!(Level::Debug) {
                debug!("DFS结束 已处理地址数: {}", cur);
            }
        }

        if let Some(counter) = &total_found_counter {
            counter.store(refined_results.len(), Ordering::Relaxed);
        }
    }

    Ok(refined_results)
}

/// Group refine search with DFS algorithm, with cancel and progress callbacks.
/// This version supports cancellation checking and progress updates during the search.
pub(crate) fn refine_search_group_with_dfs_and_cancel<F, P>(
    existing_results: &Vec<ValuePair>,
    query: &SearchQuery,
    processed_counter: Option<&Arc<AtomicUsize>>,
    total_found_counter: Option<&Arc<AtomicUsize>>,
    check_cancelled: &F,
    update_progress: &P,
) -> Result<BPlusTreeSet<ValuePair>>
where
    F: Fn() -> bool + Sync,
    P: Fn(usize, usize) + Sync,
{
    use rayon::prelude::*;
    use std::collections::HashSet;
    use std::sync::atomic::Ordering;

    if log_enabled!(Level::Debug) {
        debug!("Group refine with cancel - result count: {}", existing_results.len())
    }

    // Check cancellation before starting.
    if check_cancelled() {
        return Ok(BPlusTreeSet::new(BPLUS_TREE_ORDER));
    }

    let driver_manager = DRIVER_MANAGER
        .read()
        .map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let mut refined_results = BPlusTreeSet::new(BPLUS_TREE_ORDER);

    if query.values.is_empty() {
        return Ok(refined_results);
    }

    // Read all address values.
    let mut addr_values: Vec<(u64, Vec<u8>)> = Vec::with_capacity(existing_results.len());
    for (idx, pair) in existing_results.iter().enumerate() {
        // Check cancellation periodically.
        if idx % 1000 == 0 && check_cancelled() {
            return Ok(BPlusTreeSet::new(BPLUS_TREE_ORDER));
        }

        let addr = pair.addr;
        let value_size = pair.value_type.size();
        let mut buffer = vec![0u8; value_size];

        if driver_manager.read_memory_unified(addr, &mut buffer, None).is_ok() {
            addr_values.push((addr, buffer));
        } else {
            if let Some(counter) = processed_counter {
                counter.fetch_add(1, Ordering::Relaxed);
            }
            if log_enabled!(Level::Debug) {
                warn!("Failed to read memory during refine search, addr: {:x}, size = {}", addr, value_size)
            }
        }
    }

    if addr_values.is_empty() {
        if log_enabled!(Level::Debug) {
            debug!("Result count: {}, but all reads failed", existing_results.len())
        }
        return Ok(refined_results);
    }

    if log_enabled!(Level::Debug) {
        debug!("Result count: {}, readable addresses: {}", existing_results.len(), addr_values.len());
    }

    // Check cancellation.
    if check_cancelled() {
        return Ok(BPlusTreeSet::new(BPLUS_TREE_ORDER));
    }

    // Find all anchor points.
    let first_query_target = &query.values[0];
    let anchors: Vec<u64> = addr_values
        .par_iter()
        .filter_map(|(addr, bytes)| {
            if let Ok(true) = first_query_target.matched(&bytes) {
                Some(*addr)
            } else {
                if let Some(counter) = &processed_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                None
            }
        })
        .collect();

    if log_enabled!(Level::Debug) {
        debug!("Anchor count: {}, readable addresses: {}", anchors.len(), addr_values.len());
    }

    if anchors.is_empty() {
        return Ok(refined_results);
    }

    if query.values.len() == 1 {
        // Single value refine, return anchor results directly.
        let value_type = query.values[0].value_type();
        for anchor_addr in anchors {
            refined_results.insert(ValuePair::new(anchor_addr, value_type));
        }
        return Ok(refined_results);
    }

    let total_anchors = anchors.len();

    // Use AtomicBool to propagate cancellation across parallel tasks.
    let cancelled = AtomicBool::new(false);

    // Inner DFS function with cancellation support.
    fn dfs_with_cancel<FC>(
        cand_idx: usize,
        candidates: &[(u64, &Vec<u8>)],
        query: &SearchQuery,
        chosen: &mut Vec<(u64, ValueType)>,
        used: &mut HashSet<u64>,
        local_results: &mut Vec<(u64, ValueType)>,
        check_cancelled: &FC,
        cancelled: &AtomicBool,
        iteration_count: &mut u64,
    ) where
        FC: Fn() -> bool,
    {
        // Check cancellation flag.
        if cancelled.load(Ordering::Relaxed) {
            return;
        }

        let need_total = query.values.len();
        let have = chosen.len();

        if have == need_total {
            for (addr, vt) in chosen.iter() {
                local_results.push((*addr, *vt));
            }
            return;
        }

        let remaining_need = need_total - have;
        let remaining_candidates = candidates.len().saturating_sub(cand_idx);
        if remaining_candidates < remaining_need {
            return;
        }

        let sv = &query.values[have];

        for i in cand_idx..candidates.len() {
            // Check cancellation periodically.
            *iteration_count += 1;
            if *iteration_count % 500 == 0 {
                if check_cancelled() || cancelled.load(Ordering::Relaxed) {
                    cancelled.store(true, Ordering::Relaxed);
                    return;
                }
            }

            let (addr, bytes) = candidates[i];

            if sv.value_type().size() > bytes.len() {
                continue;
            }

            if !sv.matched(bytes).unwrap_or(false) {
                continue;
            }

            if used.contains(&addr) {
                continue;
            }

            used.insert(addr);
            chosen.push((addr, sv.value_type()));

            dfs_with_cancel(
                i + 1,
                candidates,
                query,
                chosen,
                used,
                local_results,
                check_cancelled,
                cancelled,
                iteration_count,
            );

            // Check if we should stop.
            if cancelled.load(Ordering::Relaxed) {
                return;
            }

            chosen.pop();
            used.remove(&addr);
        }
    }

    // Parallel processing of anchors using rayon.
    let all_results: Vec<Vec<(u64, ValueType)>> = anchors
        .par_iter()
        .filter_map(|anchor_addr| {
            // Check cancellation.
            if check_cancelled() || cancelled.load(Ordering::Relaxed) {
                cancelled.store(true, Ordering::Relaxed);
                return None;
            }

            let (min_addr, max_addr) = match query.mode {
                SearchMode::Unordered => (
                    anchor_addr.saturating_sub(query.range as u64),
                    anchor_addr + query.range as u64,
                ),
                SearchMode::Ordered => (*anchor_addr, anchor_addr + query.range as u64),
            };

            // Candidates (excluding anchor itself to avoid duplicate usage).
            let mut candidates: Vec<(u64, &Vec<u8>)> = Vec::new();
            for (addr, bytes) in &addr_values {
                if *addr >= min_addr && *addr <= max_addr && *addr != *anchor_addr {
                    candidates.push((*addr, bytes));
                }
            }

            // Pruning: if candidate count < (query.values.len() - 1), cannot succeed.
            if candidates.len() < query.values.len() - 1 {
                if let Some(counter) = &processed_counter {
                    counter.fetch_add(1, Ordering::Relaxed);
                }
                return None;
            }

            // DFS: find all valid combinations.
            let mut used: HashSet<u64> = HashSet::new();
            used.insert(*anchor_addr);

            let mut chosen: Vec<(u64, ValueType)> = Vec::with_capacity(query.values.len());
            chosen.push((*anchor_addr, query.values[0].value_type()));

            let mut local_results: Vec<(u64, ValueType)> = Vec::new();
            let mut iteration_count = 0u64;

            dfs_with_cancel(
                0,
                &candidates,
                query,
                &mut chosen,
                &mut used,
                &mut local_results,
                check_cancelled,
                &cancelled,
                &mut iteration_count,
            );

            // Update processed counter and progress.
            if let Some(counter) = &processed_counter {
                let processed = counter.fetch_add(1, Ordering::Relaxed) + 1;
                // Update progress every 100 anchors.
                if processed % 100 == 0 {
                    if let Some(found_counter) = &total_found_counter {
                        found_counter.fetch_add(local_results.len(), Ordering::Relaxed);
                    }
                    let found = total_found_counter
                        .map(|c| c.load(Ordering::Relaxed))
                        .unwrap_or(0);
                    update_progress(processed, found);
                }
            }

            if local_results.is_empty() {
                None
            } else {
                Some(local_results)
            }
        })
        .collect();

    // Check if cancelled.
    if cancelled.load(Ordering::Relaxed) {
        return Ok(refined_results);
    }

    // Merge all results into the final result set.
    for local_results in all_results {
        for (addr, vt) in local_results {
            refined_results.insert(ValuePair::new(addr, vt));
        }
    }

    // Final progress update.
    let final_count = refined_results.len();
    if let Some(counter) = &total_found_counter {
        counter.store(final_count, Ordering::Relaxed);
    }
    update_progress(total_anchors, final_count);

    Ok(refined_results)
}
