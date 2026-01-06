//! Phase 1: Pointer Scanner
//!
//! This module scans all readable memory regions for valid pointers.
//! A valid pointer is a 64-bit value whose lower 48 bits fall within
//! a known memory region.

use std::cmp::min;
use std::path::PathBuf;
use crate::core::DRIVER_MANAGER;
use crate::pointer_scan::storage::MmapQueue;
use crate::pointer_scan::types::{PointerData, PointerScanConfig};
use anyhow::{anyhow, Result};
use log::{debug, error, info, log_enabled, warn, Level};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc};
use std::{process, thread};
use std::fs::File;
use std::io::{BufWriter, Write};
use std::time::Instant;
use itertools::Itertools;
use memmap2::Mmap;
use nix::libc;
use rkyv::rancor::Error as RkyvError;
use crate::core::globals::PAGE_SIZE;
use crate::wuwa::PageStatusBitmap;

/// Memory region for scanning.
#[derive(Debug, Clone)]
pub struct ScanRegion {
    pub start: u64,
    pub end: u64,
    pub name: String,
}

impl ScanRegion {
    pub fn size(&self) -> u64 {
        self.end.saturating_sub(self.start)
    }
}

/// Validates if a 64-bit value could be a valid pointer.
///
/// On ARM64, only the lower 48 bits are used for addressing.
/// The value must fall within a known memory region to be considered valid.
#[inline]
fn is_valid_pointer(value: u64, valid_ranges: &[(u64, u64)]) -> bool {
    // Mask to 48-bit addressable space (ARM64)
    let masked = value & 0x0000_FFFF_FFFF_FFFF;

    // Quick range check
    if valid_ranges.is_empty() {
        return false;
    }

    let min_addr = valid_ranges.first().map(|r| r.0).unwrap_or(0);
    let max_addr = valid_ranges.last().map(|r| r.1).unwrap_or(0);

    if masked < min_addr || masked >= max_addr {
        return false;
    }

    // Binary search for containing region
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

/// Scan a single memory chunk for valid pointers.
/// Only scans pages that were successfully read (indicated by page_bitmap).
/// Returns a vector of found pointers with their addresses and values.
#[inline(always)]
fn scan_chunk_for_pointers(
    buffer: &[u8],
    base_addr: u64,
    align: u32,
    valid_ranges: &[(u64, u64)],
    page_bitmap: &PageStatusBitmap,
) -> Vec<PointerData> {
    let mut results = Vec::with_capacity(1024);

    if buffer.len() < 8 {
        return results;
    }

    let step = align as usize;
    let num_pages = page_bitmap.num_pages();

    // 直接迭代所有页面，避免 collect() 分配内存
    for page_idx in 0..num_pages {
        // 快速跳过读取失败的页
        if !page_bitmap.is_page_success(page_idx) {
            continue;
        }

        // 计算当前页在 buffer 中的范围
        let page_start_idx = page_idx * *PAGE_SIZE;
        // 处理 Chunk 结尾可能不满一页的情况
        let page_end_idx = min(page_start_idx + *PAGE_SIZE, buffer.len());

        // 如果这一页在 buffer 范围外（防御性编程），跳过
        if page_start_idx >= page_end_idx {
            continue;
        }

        // 实际可用的切片
        let page_slice = &buffer[page_start_idx..page_end_idx];

        // 只有当剩余数据足够放一个 u64 (8字节) 时才扫描
        if page_slice.len() < 8 {
            continue;
        }

        // 限制扫描的终点，防止读取越界
        // 例子：Slice 长度 4096。最大 offset 应该是 4088。4088..4096 是最后8字节。
        let scan_limit = page_slice.len() - 8;

        for offset in (0..=scan_limit).step_by(step) {
            // Safety: 我们已经通过 scan_limit 保证了 offset+8 不会越界
            // 使用 try_into 会被编译器优化掉，这里是零开销
            let bytes = unsafe {
                // 使用 unsafe get_unchecked 可以进一步减少边界检查，提升 extreme performance
                // 但在标准安全代码中， slice索引就够了。这里演示最安全写法。
                page_slice.get_unchecked(offset..offset + 8)
            };

            let value = u64::from_le_bytes(bytes.try_into().unwrap());

            // is_valid_pointer 最好是 #[inline] 的
            if is_valid_pointer(value, valid_ranges) {
                // 计算实际内存地址：基址 + 页偏移 + 页内偏移
                let ptr_address = base_addr + (page_start_idx + offset) as u64;
                results.push(PointerData::new(ptr_address, value));
            }
        }
    }

    results
}

/// Scan a single memory region for valid pointers.
/// Returns a vector of all pointers found in this region.
fn scan_region_for_pointers(
    region: &ScanRegion,
    chunk_size: usize, // todo: 当前大小写死了512kb
    valid_ranges: &[(u64, u64)],
    config: &PointerScanConfig,
    cancelled: &AtomicBool,
) -> Result<Vec<PointerData>> {
    assert_eq!(region.start & (*PAGE_SIZE as u64 - 1), 0);
    assert_eq!(region.end & (*PAGE_SIZE as u64 - 1), 0);
    assert_eq!(chunk_size & (*PAGE_SIZE - 1), 0);

    let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager lock"))?;

    let mut buffer = vec![0u8; chunk_size];
    let mut current_addr = region.start;
    let mut region_pointers = Vec::new();

    while current_addr < region.end {
        if cancelled.load(Ordering::Relaxed) {
            break;
        }

        let read_size = min(chunk_size as u64, region.end - current_addr) as usize;

        // 每次创建 bitmap 开销极小（只是几个整数计算），可以接受
        let mut page_bitmap = PageStatusBitmap::new(read_size, current_addr as usize);

        match driver_manager.read_memory_unified(current_addr, &mut buffer[..read_size], Some(&mut page_bitmap)) {
            Ok(_) => {
                // todo：Chunk 边界的指针遗漏，在 scan_region_for_pointers 中，你按 chunk_size (512KB) 逐块读取内存
                // 在 scan_chunk_for_pointers 中，扫描循环限制为 scan_limit = page_slice.len() - 8
                // 这意味着如果一个指针横跨了两个 Chunk（例如：指针起始地址在 Chunk A 的最后 4 个字节，结束地址在 Chunk B 的前 4 个字节），这个指针会被彻底漏掉。它在 Chunk A 中因为长度不足 8 被截断，在 Chunk B 中因为起始偏移是 0 而被跳过。
                let chunk_results = scan_chunk_for_pointers(&buffer[..read_size], current_addr, config.align, valid_ranges, &page_bitmap);

                if !chunk_results.is_empty() {
                    if log_enabled!(Level::Debug) {
                        debug!("Chunk scan success: addr = 0x{:X}, found {} pointers", current_addr, chunk_results.len());
                    }
                    region_pointers.extend(chunk_results);
                }
            },
            Err(e) => {
                debug!("Failed to read memory at 0x{:X}-0x{:X}: {}", current_addr, current_addr + read_size as u64, e);
                // Continue with next chunk
            },
        }

        current_addr += read_size as u64;
    }

    Ok(region_pointers)
}

/// Phase 1: Scan all readable memory for valid pointers.
///
/// This function scans all provided memory regions in parallel,
/// identifies valid pointers, and stores them in a memory-mapped queue.
///
/// # Arguments
/// * `regions` - List of memory regions to scan
/// * `config` - Scan configuration
/// * `cache_dir` - Directory for temporary files
/// * `progress_callback` - Callback for progress updates (regions_done, total_regions, pointers_found)
/// * `check_cancelled` - Function to check if scan should be cancelled
///
/// # Returns
/// A sorted MmapQueue containing all found pointers
pub fn scan_all_pointers<F, C>(
    regions: &[ScanRegion],
    config: &PointerScanConfig,
    cache_dir: &PathBuf,
    progress_callback: F,
    check_cancelled: C,
) -> Result<MmapQueue<PointerData>>
where
    F: Fn(usize, usize, i64) + Send + Sync,
    C: Fn() -> bool + Send + Sync,
{
    let start_time = Instant::now();

    // 内存阈值：每积累 1000 万个指针 (约160MB) 就进行一次排序落盘
    // 加上 buffer 双倍缓冲，总额外内存开销约 320MB，非常安全
    const BATCH_SIZE_THRESHOLD: usize = 10_000_000;
    const CHUNK_SIZE: usize = 512 * 1024; // 512KB 读取分块

    if log_enabled!(Level::Debug) {
        info!("Starting pointer scan: {} regions, Batch Threshold: {}", regions.len(), BATCH_SIZE_THRESHOLD);
    }

    // Build sorted valid address ranges for binary search
    let mut valid_ranges: Vec<(u64, u64)> = regions.iter().map(|r| (r.start, r.end)).collect();
    if !valid_ranges.is_empty() {
        valid_ranges.sort_unstable_by_key(|r| r.0);
        let mut merged = Vec::with_capacity(valid_ranges.len());
        let mut current = valid_ranges[0];
        for &next in &valid_ranges[1..] {
            if next.0 <= current.1 {
                current.1 = std::cmp::max(current.1, next.1);
            } else {
                merged.push(current);
                current = next;
            }
        }
        merged.push(current);
        valid_ranges = merged;
    }
    debug!("Optimized valid ranges count: {}", valid_ranges.len());

    let total_regions = regions.len();
    let completed_regions = Arc::new(AtomicUsize::new(0));
    let total_found = Arc::new(AtomicUsize::new(0));
    let cancelled = Arc::new(AtomicBool::new(false));

    // 创建通道：扫描线程(Producers) -> 排序写入线程(Consumer)
    // sync_channel(4) 提供背压，防止扫描太快内存爆掉
    let (tx, rx) = mpsc::sync_channel::<Vec<PointerData>>(4);

    let writer_handle = thread::spawn({
        let cache_dir = cache_dir.clone();
        let cancelled = cancelled.clone();

        move || -> Result<Vec<PathBuf>> {
            let mut temp_files = Vec::new();
            let mut buffer: Vec<PointerData> = Vec::with_capacity(BATCH_SIZE_THRESHOLD);

            for mut chunk in rx {
                if cancelled.load(Ordering::Relaxed) { break; }

                buffer.append(&mut chunk);

                if buffer.len() >= BATCH_SIZE_THRESHOLD {
                    let path = sort_and_write_temp_file(&mut buffer, &cache_dir)?;
                    temp_files.push(path);
                }
            }

            // 处理剩余数据
            if !buffer.is_empty() && !cancelled.load(Ordering::Relaxed) {
                let path = sort_and_write_temp_file(&mut buffer, &cache_dir)?;
                temp_files.push(path);
            }

            Ok(temp_files)
        }
    });

    let scan_result = regions.par_iter().try_for_each(|region| -> Result<()> {
        if cancelled.load(Ordering::Relaxed) || check_cancelled() {
            cancelled.store(true, Ordering::Relaxed);
            return Err(anyhow!("Scan cancelled"));
        }

        // 调用扫描函数
        let chunk_res = scan_region_for_pointers(
            region,
            CHUNK_SIZE,
            &valid_ranges,
            config,
            &cancelled,
        );

        match chunk_res {
            Ok(pointers) => {
                let count = pointers.len();
                if count > 0 {
                    // 发送给写入线程，如果队列满会阻塞当前线程
                    if tx.send(pointers).is_err() {
                        return Err(anyhow!("Writer thread disconnected"));
                    }

                    let found = total_found.fetch_add(count, Ordering::Relaxed) + count;
                    let done = completed_regions.fetch_add(1, Ordering::Relaxed) + 1;

                    if done % 50 == 0 {
                        progress_callback(done, total_regions, found as i64);
                    }
                } else {
                    let done = completed_regions.fetch_add(1, Ordering::Relaxed) + 1;
                    if done % 50 == 0 {
                        progress_callback(done, total_regions, total_found.load(Ordering::Relaxed) as i64);
                    }
                }
            },
            Err(e) => {
                warn!("Failed scan region {}: {}", region.start, e);
            }
        }
        Ok(())
    });

    // 关闭发送端
    drop(tx);

    // 检查扫描是否被取消或出错
    if let Err(e) = scan_result {
        // 等待写入线程退出
        let _ = writer_handle.join();
        return Err(e);
    }

    // 等待所有临时文件写入完成
    let temp_files = writer_handle.join().map_err(|_| anyhow!("Writer panicked"))??;

    if cancelled.load(Ordering::Relaxed) {
        return Err(anyhow!("Scan cancelled during flush"));
    }

    let total_items = total_found.load(Ordering::Relaxed);
    info!("Scan phase done in {:.2}s. Found {} pointers. Merging {} temp files...",
        start_time.elapsed().as_secs_f64(), total_items, temp_files.len());

    if temp_files.is_empty() {
        return MmapQueue::new(cache_dir, "pointer_lib");
    }
    let final_queue = merge_temp_files_kway(temp_files, cache_dir, "pointer_lib")?;

    info!("All done! Total time: {:.2}s", start_time.elapsed().as_secs_f64());
    Ok(final_queue)
}

fn sort_and_write_temp_file(buffer: &mut Vec<PointerData>, dir: &PathBuf) -> Result<PathBuf> {
    // 并行排序 (CPU 密集)
    buffer.par_sort_unstable_by(|a, b| a.value.cmp(&b.value));

    // 写入文件 (IO 密集)
    let filename = format!("scan_chunk_{}_{}.tmp", process::id(), uuid::Uuid::new_v4());
    let path = dir.join(filename);

    let file = File::create(&path)?;
    let mut writer = BufWriter::with_capacity(1024 * 1024, file); // 1MB buffer

    let byte_slice = unsafe {
        std::slice::from_raw_parts(
            buffer.as_ptr() as *const u8,
            buffer.len() * size_of::<PointerData>(),
        )
    };
    writer.write_all(byte_slice)?;

    writer.flush()?;
    buffer.clear(); // 清空内容但保留容量
    Ok(path)
}

fn merge_temp_files_kway(files: Vec<PathBuf>, out_dir: &PathBuf, out_name: &str) -> Result<MmapQueue<PointerData>> {
    let mmap_handles: Vec<Mmap> = files.iter()
        .map(|path| {
            let file = File::open(path).expect("Failed to open temp file");
            unsafe { Mmap::map(&file).expect("Failed to mmap file") }
        })
        .collect();

    let iterators = mmap_handles.iter().map(|mmap| {
        // 计算元素数量
        let count = mmap.len() / size_of::<PointerData>();

        // Unsafe Cast: 直接将字节流视为结构体数组
        let slice = unsafe {
            std::slice::from_raw_parts(
                mmap.as_ptr() as *const PointerData,
                count
            )
        };

        // 转换为迭代器
        slice.iter()
    });

    // K-Way 归并
    let merged_stream = iterators.kmerge_by(|a, b| {
        a.value < b.value
    });

    // 初始化输出队列
    let mut queue = MmapQueue::<PointerData>::new(out_dir, out_name)?;

    let mut batch_buffer = Vec::with_capacity(20_000);
    for ptr in merged_stream {
        batch_buffer.push(*ptr); // 解引用并拷贝 (PointerData 是 Copy)

        if batch_buffer.len() >= 20_000 {
            queue.push_batch(&batch_buffer)?;
            batch_buffer.clear();
        }
    }

    // 写入剩余数据
    if !batch_buffer.is_empty() {
        queue.push_batch(&batch_buffer)?;
    }

    // 清理临时文件
    drop(mmap_handles);
    for path in files {
        let _ = std::fs::remove_file(path);
    }

    Ok(queue)
}