//! BFS V3 指针链扫描器
//!
//! 合并 Phase 1（指针收集）和 Phase 2（BFS 链构建）为一体：
//! - 统一使用 MapQueue 零拷贝存储，消除 rkyv 序列化开销
//! - 一次按 address 排序，不再双重排序
//! - 前缀和树优化 O(1) 链计数
//! - &str prefix 拼接替代 Vec<String> clone

use std::cmp::min;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Instant;

use anyhow::{anyhow, Result};
use log::{debug, info, log_enabled, warn, Level};
use rayon::prelude::*;

use crate::core::globals::PAGE_SIZE;
use crate::core::DRIVER_MANAGER;
use crate::pointer_scan::mapqueue_v2::MapQueue;
use crate::pointer_scan::scanner::ScanRegion;
use crate::pointer_scan::types::{
    ChainInfo, PointerData, PointerDir, PointerRange,
    PointerScanConfig, VmAreaData, VmStaticData,
};
use crate::wuwa::PageStatusBitmap;

/// 每层最大候选数，防止内存爆炸
const MAX_CANDIDATES_PER_LAYER: usize = 5_000_000;

/// Phase 1 读取分块大小
const CHUNK_SIZE: usize = 512 * 1024;

/// 进度回调的阶段标识
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProgressPhase {
    /// Phase 1: 扫描指针 (current=已完成区域, total=总区域数, extra=已找到指针数)
    ScanningPointers,
    /// Phase 2: 构建链 (current=当前层级, total=最大深度, extra=已找到链数)
    BuildingChains,
    /// Phase 3: 写入文件 (current=已写入数, total=总链数, extra=已写入数)
    WritingFile,
}

/// 扫描结果
pub struct ScanResult {
    /// 找到的指针链数量
    pub total_count: usize,
    /// 输出文件路径
    pub output_file: PathBuf,
}

/// BFS V3 扫描器：合并指针收集 + BFS 链构建
pub struct BfsV3Scanner {
    config: PointerScanConfig,
    regions: Vec<ScanRegion>,
    static_modules: Vec<VmStaticData>,
}

impl BfsV3Scanner {
    pub fn new(
        config: PointerScanConfig,
        regions: Vec<ScanRegion>,
        static_modules: Vec<VmStaticData>,
    ) -> Self {
        Self { config, regions, static_modules }
    }

    /// 主入口：执行完整的指针扫描流程
    pub fn run<F, C>(
        &self,
        output_path: PathBuf,
        max_chains: usize,
        progress_callback: F,
        check_cancelled: C,
    ) -> Result<ScanResult>
    where
        F: Fn(ProgressPhase, u32, u32, i64) + Sync,
        C: Fn() -> bool + Sync,
    {
        let timer = Instant::now();
        let target = self.config.target_address;
        let depth = self.config.max_depth as usize;
        let offset = self.config.max_offset as u64;

        info!(
            "BFS V3 扫描开始: 目标=0x{:X}, 深度={}, 偏移=0x{:X}, 区域数={}",
            target, depth, offset, self.regions.len()
        );

        // ========== Phase 1: 扫描所有指针 ==========
        let global_pointers = self.scan_all_pointers(&progress_callback, &check_cancelled)?;

        if check_cancelled() {
            return Err(anyhow!("扫描被取消"));
        }

        info!(
            "Phase 1 完成: 找到 {} 个指针, 耗时 {:.3}s",
            global_pointers.len(),
            timer.elapsed().as_secs_f64()
        );

        // ========== Phase 2: BFS 链构建 ==========
        self.build_chains(
            global_pointers,
            output_path,
            max_chains,
            &progress_callback,
            &check_cancelled,
        )
    }

    // ========== Phase 1: 指针收集 ==========

    /// 扫描所有内存区域，收集有效指针，按 address 排序后存入 MapQueue
    fn scan_all_pointers<F, C>(
        &self,
        progress_callback: &F,
        check_cancelled: &C,
    ) -> Result<MapQueue<PointerData>>
    where
        F: Fn(ProgressPhase, u32, u32, i64) + Sync,
        C: Fn() -> bool + Sync,
    {
        // 构建合并后的 valid_ranges 用于二分查找验证
        let mut valid_ranges: Vec<(u64, u64)> = self.regions.iter()
            .map(|r| (r.start, r.end))
            .collect();
        valid_ranges.sort_unstable_by_key(|r| r.0);

        // 合并重叠区间
        if !valid_ranges.is_empty() {
            let mut merged = Vec::with_capacity(valid_ranges.len());
            let mut current = valid_ranges[0];
            for &next in &valid_ranges[1..] {
                if next.0 <= current.1 {
                    current.1 = current.1.max(next.1);
                } else {
                    merged.push(current);
                    current = next;
                }
            }
            merged.push(current);
            valid_ranges = merged;
        }

        let total_regions = self.regions.len();
        let completed = Arc::new(AtomicUsize::new(0));
        let total_found = Arc::new(AtomicUsize::new(0));
        let cancelled = Arc::new(AtomicBool::new(false));
        let align = self.config.align;

        // 并行扫描所有 region
        let results: Vec<Vec<PointerData>> = self.regions.par_iter()
            .filter_map(|region| {
                if cancelled.load(Ordering::Relaxed) || check_cancelled() {
                    cancelled.store(true, Ordering::Relaxed);
                    return None;
                }

                let pointers = scan_region(region, align, &valid_ranges, &cancelled);

                let count = pointers.len();
                let found = total_found.fetch_add(count, Ordering::Relaxed) + count;
                let done = completed.fetch_add(1, Ordering::Relaxed) + 1;

                if done % 50 == 0 || done == total_regions {
                    progress_callback(ProgressPhase::ScanningPointers, done as u32, total_regions as u32, found as i64);
                }

                Some(pointers)
            })
            .collect();

        if cancelled.load(Ordering::Relaxed) {
            return Err(anyhow!("扫描被取消"));
        }

        // 合并所有结果
        let total: usize = results.iter().map(|v| v.len()).sum();
        let mut all_pointers: Vec<PointerData> = Vec::with_capacity(total);
        for mut batch in results {
            all_pointers.append(&mut batch);
        }

        // 按 address 排序（一次排序，不再按 value 排）
        all_pointers.par_sort_unstable_by_key(|p| p.address);

        // 移入 MapQueue
        let mut queue = MapQueue::with_capacity(all_pointers.len())?;
        queue.extend_from_slice(&all_pointers)?;

        Ok(queue)
    }

    // ========== Phase 2: BFS 链构建 ==========

    fn build_chains<F, C>(
        &self,
        global_pointers: MapQueue<PointerData>,
        output_path: PathBuf,
        max_chains: usize,
        progress_callback: &F,
        check_cancelled: &C,
    ) -> Result<ScanResult>
    where
        F: Fn(ProgressPhase, u32, u32, i64) + Sync,
        C: Fn() -> bool + Sync,
    {
        let timer = Instant::now();
        let target = self.config.target_address;
        let depth = self.config.max_depth as usize;
        let offset = self.config.max_offset as u64;
        let gp_slice = global_pointers.as_slice();

        info!(
            "BFS V3 Phase 2: 目标=0x{:X}, 深度={}, 偏移=0x{:X}, 指针库={}",
            target, depth, offset, gp_slice.len()
        );

        // 初始化 dirs 和 ranges
        let mut dirs: Vec<MapQueue<PointerDir>> = (0..=depth)
            .map(|_| MapQueue::new())
            .collect();
        let mut ranges: Vec<PointerRange> = Vec::new();
        let mut first_range_idx = 0;

        // BFS 展开
        for level in 0..=depth {
            if check_cancelled() {
                return Err(anyhow!("扫描被取消"));
            }

            if level > 0 {
                let curr = search_pointer(gp_slice, &dirs[level - 1], offset);

                if log_enabled!(Level::Debug) {
                    debug!("层级 {}: 搜索到 {} 个指针", level, curr.len());
                }

                if curr.is_empty() {
                    break;
                }

                filter_pointer_ranges(
                    &self.static_modules,
                    &mut dirs,
                    &mut ranges,
                    curr,
                    level as i32,
                )?;

                // 创建层间索引
                let (left, right) = dirs.split_at_mut(level);
                let prev = &left[level - 1];
                let curr = &mut right[0];
                create_assoc_dir_index(prev, curr, offset);

                // 候选裁剪
                if dirs[level].len() > MAX_CANDIDATES_PER_LAYER {
                    warn!(
                        "[候选裁剪] 层级 {} 从 {} 裁剪到 {}",
                        level, dirs[level].len(), MAX_CANDIDATES_PER_LAYER
                    );
                    dirs[level].truncate(MAX_CANDIDATES_PER_LAYER);
                }
            } else {
                // Level 0: 目标地址
                let curr = vec![PointerData::new(target, 0)];
                filter_pointer_ranges(
                    &self.static_modules,
                    &mut dirs,
                    &mut ranges,
                    curr,
                    0,
                )?;
                first_range_idx = ranges.len();
            }

            // Phase 2 进度
            progress_callback(ProgressPhase::BuildingChains, level as u32, depth as u32, ranges.len() as i64);
        }

        // 补充静态模块索引
        for idx in first_range_idx..ranges.len() {
            let level = ranges[idx].level;
            if level > 0 {
                let prev = &dirs[level as usize - 1];
                create_assoc_range_index(prev, &mut ranges[idx].results, offset);
            }
        }

        if ranges.is_empty() {
            info!("BFS V3 扫描完成: 未找到指针链");
            File::create(&output_path)?;
            return Ok(ScanResult { total_count: 0, output_file: output_path });
        }

        info!(
            "搜索和关联完成, 耗时: {:.3}s",
            timer.elapsed().as_secs_f64()
        );

        // 构建前缀和树
        let chain_info = build_pointer_dirs_tree(&dirs, &ranges)?;
        if chain_info.is_empty() {
            File::create(&output_path)?;
            return Ok(ScanResult { total_count: 0, output_file: output_path });
        }

        // 统计链数量（O(1) per range entry）
        let mut total_count = 0usize;
        for range in &ranges {
            let mut module_count = 0usize;
            let level_count = &chain_info.counts[range.level as usize];
            for dir in range.results.iter() {
                module_count = module_count.saturating_add(
                    level_count[dir.end as usize].saturating_sub(level_count[dir.start as usize])
                );
            }
            total_count = total_count.saturating_add(module_count);
            info!(
                "模块 {}[{}] 层级{}: {} 条链",
                range.vma.name, range.vma.count, range.level, module_count
            );
        }

        info!("BFS V3: 共找到 {} 条指针链", total_count);

        // 写入文本文件
        let effective_total = min(total_count, max_chains);
        progress_callback(ProgressPhase::WritingFile, 0, effective_total as u32, 0);

        let written = write_to_text(
            &chain_info,
            &ranges,
            &output_path,
            target,
            depth,
            offset,
            max_chains,
            &|w| progress_callback(ProgressPhase::WritingFile, w as u32, effective_total as u32, w as i64),
            check_cancelled,
        )?;

        info!(
            "BFS V3 扫描完成: 总计 {} 条链, 写入 {} 条, 耗时 {:.3}s",
            total_count, written, timer.elapsed().as_secs_f64()
        );

        // 最终进度
        progress_callback(ProgressPhase::WritingFile, written as u32, written as u32, written as i64);

        Ok(ScanResult { total_count, output_file: output_path })
    }
}

// ============================================================================
// 独立辅助函数
// ============================================================================

/// 扫描单个 region 的所有指针
fn scan_region(
    region: &ScanRegion,
    align: u32,
    valid_ranges: &[(u64, u64)],
    cancelled: &AtomicBool,
) -> Vec<PointerData> {
    let driver_manager = match DRIVER_MANAGER.read() {
        Ok(dm) => dm,
        Err(_) => return Vec::new(),
    };

    let mut buffer = vec![0u8; CHUNK_SIZE];
    let mut current_addr = region.start;
    let mut pointers = Vec::new();
    let step = align as usize;

    while current_addr < region.end {
        if cancelled.load(Ordering::Relaxed) {
            break;
        }

        let read_size = min(CHUNK_SIZE as u64, region.end - current_addr) as usize;
        let mut page_bitmap = PageStatusBitmap::new(read_size, current_addr as usize);

        if driver_manager
            .read_memory_unified(current_addr, &mut buffer[..read_size], Some(&mut page_bitmap))
            .is_ok()
        {
            let num_pages = page_bitmap.num_pages();
            for page_idx in 0..num_pages {
                if !page_bitmap.is_page_success(page_idx) {
                    continue;
                }

                let page_start = page_idx * *PAGE_SIZE;
                let page_end = min(page_start + *PAGE_SIZE, read_size);
                if page_start >= page_end || (page_end - page_start) < 8 {
                    continue;
                }

                let scan_limit = page_end - 8;
                for off in (page_start..=scan_limit).step_by(step) {
                    let bytes = unsafe { buffer.get_unchecked(off..off + 8) };
                    let value = u64::from_le_bytes(bytes.try_into().unwrap());
                    let masked = value & 0x0000_FFFF_FFFF_FFFF;

                    if is_valid_pointer(masked, valid_ranges) {
                        let addr = current_addr + off as u64;
                        pointers.push(PointerData::new(addr, masked));
                    }
                }
            }
        }

        current_addr += read_size as u64;
    }

    pointers
}

/// 二分查找验证指针有效性
#[inline]
fn is_valid_pointer(masked: u64, valid_ranges: &[(u64, u64)]) -> bool {
    if valid_ranges.is_empty() {
        return false;
    }
    let min_addr = valid_ranges[0].0;
    let max_addr = valid_ranges[valid_ranges.len() - 1].1;
    if masked < min_addr || masked >= max_addr {
        return false;
    }
    valid_ranges
        .binary_search_by(|(start, end)| {
            if masked < *start {
                std::cmp::Ordering::Greater
            } else if masked >= *end {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

/// 在全局指针中搜索指向上一层的指针
fn search_pointer(
    global_pointers: &[PointerData],
    prev_dirs: &MapQueue<PointerDir>,
    offset: u64,
) -> Vec<PointerData> {
    if prev_dirs.is_empty() {
        return Vec::new();
    }

    let prev_slice = prev_dirs.as_slice();
    let prev_len = prev_slice.len();
    let mut results = Vec::new();

    for p in global_pointers {
        let value = p.value;
        let lower = prev_slice.partition_point(|d| d.address < value);
        if lower >= prev_len {
            continue;
        }
        let target_addr = prev_slice[lower].address;
        if target_addr >= value && (target_addr - value) <= offset {
            results.push(*p);
        }
    }

    results.sort_unstable_by_key(|p| p.address);
    results
}

/// 过滤指针范围：静态区域加入 ranges，其余加入 dirs
fn filter_pointer_ranges(
    static_modules: &[VmStaticData],
    dirs: &mut Vec<MapQueue<PointerDir>>,
    ranges: &mut Vec<PointerRange>,
    curr: Vec<PointerData>,
    level: i32,
) -> Result<()> {
    let mut matched_addrs: Vec<u64> = Vec::new();

    for module in static_modules {
        let module_pointers: Vec<&PointerData> = curr
            .iter()
            .filter(|p| p.address >= module.base_address && p.address < module.end_address)
            .collect();

        if module_pointers.is_empty() {
            continue;
        }

        let mut results = MapQueue::with_capacity(module_pointers.len())?;
        for p in &module_pointers {
            results.push(PointerDir::from_data(p))?;
            matched_addrs.push(p.address);
        }

        if log_enabled!(Level::Debug) {
            debug!("{}[{}]: {} 个指针", module.name, module.index, module_pointers.len());
        }

        ranges.push(PointerRange::new(
            level,
            VmAreaData::from_static(module),
            results,
        ));
    }

    matched_addrs.sort_unstable();
    for p in curr {
        if matched_addrs.binary_search(&p.address).is_err() {
            dirs[level as usize].push(PointerDir::from_data(&p))?;
        }
    }

    Ok(())
}

/// 创建层间索引
fn create_assoc_dir_index(
    prev: &MapQueue<PointerDir>,
    curr: &mut MapQueue<PointerDir>,
    offset: u64,
) {
    let prev_slice = prev.as_slice();
    for dir in curr.as_mut_slice() {
        let value = dir.value;
        dir.start = prev_slice.partition_point(|p| p.address < value) as u32;
        dir.end = prev_slice.partition_point(|p| p.address <= value.saturating_add(offset)) as u32;
    }
}

/// 为 range 结果创建索引
fn create_assoc_range_index(
    prev: &MapQueue<PointerDir>,
    results: &mut MapQueue<PointerDir>,
    offset: u64,
) {
    let prev_slice = prev.as_slice();
    for dir in results.as_mut_slice() {
        let value = dir.value;
        dir.start = prev_slice.partition_point(|p| p.address < value) as u32;
        dir.end = prev_slice.partition_point(|p| p.address <= value.saturating_add(offset)) as u32;
    }
}

/// 构建前缀和树（从参考实现移植）
///
/// contents[level]: 收集每层 dirs 的 PointerDir 指针
/// counts[0] = [0, 1]
/// counts[level] 通过累加 counts[level-1][dir.end] - counts[level-1][dir.start] 构建
/// 实现 O(1) 链计数查询
fn build_pointer_dirs_tree(
    dirs: &[MapQueue<PointerDir>],
    ranges: &[PointerRange],
) -> Result<ChainInfo> {
    if ranges.is_empty() {
        return Ok(ChainInfo::new(Vec::new(), Vec::new()));
    }

    let max_level = ranges.iter().map(|r| r.level).max().unwrap_or(0) as usize;

    let mut counts: Vec<MapQueue<usize>> = (0..=max_level)
        .map(|_| MapQueue::new())
        .collect();
    let mut contents: Vec<MapQueue<*const PointerDir>> = (0..=max_level)
        .map(|_| MapQueue::new())
        .collect();

    // 收集每层 dirs 的指针
    for level in (0..=max_level).rev() {
        for dir in dirs[level].iter() {
            contents[level].push(dir as *const PointerDir)?;
        }
    }

    // 构建前缀和
    // counts[0] = [0, 1]: level 0 是叶子层，基准值固定
    counts[0].push(0)?;
    counts[0].push(1)?;

    for level in 1..=max_level {
        let prev_count_data: Vec<usize> = counts[level - 1].as_slice().to_vec();
        let prev_content_len = contents[level - 1].len();

        let mut cumulative = 0usize;
        counts[level].push(cumulative)?;

        for i in 0..prev_content_len {
            let dir = unsafe { &*contents[level - 1][i] };
            let diff = prev_count_data[dir.end as usize]
                .saturating_sub(prev_count_data[dir.start as usize]);
            cumulative = cumulative.saturating_add(diff);
            counts[level].push(cumulative)?;
        }
    }

    Ok(ChainInfo::new(counts, contents))
}

/// 写入文本文件
fn write_to_text<F, C>(
    chain_info: &ChainInfo,
    ranges: &[PointerRange],
    output_path: &PathBuf,
    target: u64,
    depth: usize,
    offset: u64,
    max_chains: usize,
    progress_callback: &F,
    check_cancelled: &C,
) -> Result<usize>
where
    F: Fn(usize),
    C: Fn() -> bool,
{
    let file = File::create(output_path)?;
    let mut writer = BufWriter::with_capacity(1024 * 1024, file);

    // 文件头
    writeln!(writer, "# Pointer Scan Results")?;
    writeln!(writer, "# Target: 0x{:X}", target)?;
    writeln!(writer, "# Depth: {}", depth)?;
    writeln!(writer, "# Offset: 0x{:X}", offset)?;
    writeln!(writer, "# Generated by Mamu Pointer Scanner V3")?;
    writeln!(writer, "#")?;
    writeln!(writer, "# Format: module_name[index]+base_offset->offset1->offset2->...")?;
    writeln!(writer)?;

    let mut written = 0usize;
    let mut last_reported = 0usize;

    'outer: for range in ranges {
        for dir in range.results.iter() {
            if written >= max_chains || check_cancelled() {
                break 'outer;
            }

            let base_offset = dir.address - range.vma.start;
            let short_name = range.vma.name.rsplit('/').next().unwrap_or(&range.vma.name);
            let prefix = format!("{}[{}]+0x{:X}", short_name, range.vma.count, base_offset);

            written += write_chain_recursive_text(
                &mut writer,
                chain_info,
                dir,
                range.level as usize,
                &prefix,
                max_chains - written,
            )?;

            // 每写入 10万 条汇报一次进度
            if written - last_reported >= 100_000 {
                progress_callback(written);
                last_reported = written;
            }
        }
    }

    writer.flush()?;
    Ok(written)
}

/// 递归输出指针链（使用 &str prefix 避免 Vec<String> clone）
fn write_chain_recursive_text<W: Write>(
    writer: &mut W,
    chain_info: &ChainInfo,
    dir: &PointerDir,
    level: usize,
    prefix: &str,
    max_chains: usize,
) -> Result<usize> {
    if max_chains == 0 {
        return Ok(0);
    }

    if level == 0 {
        writeln!(writer, "{}", prefix)?;
        return Ok(1);
    }

    let content = &chain_info.contents[level - 1];
    let mut count = 0usize;

    for i in dir.start..dir.end {
        if count >= max_chains {
            break;
        }

        let child = unsafe { &*content[i as usize] };
        let child_offset = child.address.wrapping_sub(dir.value) as i64;

        let new_prefix = if child_offset >= 0 {
            format!("{}->+0x{:X}", prefix, child_offset)
        } else {
            format!("{}->-0x{:X}", prefix, child_offset.unsigned_abs())
        };

        count += write_chain_recursive_text(
            writer,
            chain_info,
            child,
            level - 1,
            &new_prefix,
            max_chains - count,
        )?;
    }

    Ok(count)
}
