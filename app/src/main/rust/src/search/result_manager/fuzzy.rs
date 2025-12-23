use crate::search::FuzzyCondition;
use crate::search::types::ValueType;
use anyhow::{Result, anyhow};
use log::{debug, info};
use memmap2::MmapMut;
use std::cmp::Ordering;
use std::fs::{File, OpenOptions};
use std::mem::size_of;
use std::path::PathBuf;

/// 模糊搜索结果项 - 存储地址和当前值
/// 使用 [u8; 8] 存储值（最大类型 Qword/Double 刚好 8 字节）
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct FuzzySearchResultItem {
    pub address: u64,          // 8 bytes
    pub value: [u8; 8],        // 8 bytes - 原始字节存储
    pub value_type: ValueType, // 1 byte
}
// 总共 17 字节 (packed)

// 为 packed 结构体手动实现比较 trait（按地址排序）
impl PartialEq for FuzzySearchResultItem {
    #[inline]
    fn eq(&self, other: &Self) -> bool {
        // 读取 packed 字段需要拷贝
        let self_addr = self.address;
        let other_addr = other.address;
        self_addr == other_addr
    }
}

impl Eq for FuzzySearchResultItem {}

impl PartialOrd for FuzzySearchResultItem {
    #[inline]
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for FuzzySearchResultItem {
    #[inline]
    fn cmp(&self, other: &Self) -> Ordering {
        let self_addr = self.address;
        let other_addr = other.address;
        self_addr.cmp(&other_addr)
    }
}

impl FuzzySearchResultItem {
    #[inline]
    pub fn new(address: u64, value: [u8; 8], value_type: ValueType) -> Self {
        FuzzySearchResultItem { address, value, value_type }
    }

    /// 从字节切片创建结果项
    #[inline]
    pub fn from_bytes(address: u64, bytes: &[u8], value_type: ValueType) -> Self {
        let mut value = [0u8; 8];
        let len = bytes.len().min(8);
        value[..len].copy_from_slice(&bytes[..len]);
        FuzzySearchResultItem { address, value, value_type }
    }

    /// 获取值的有效字节数
    #[inline]
    pub fn value_size(&self) -> usize {
        self.value_type.size()
    }

    /// 读取为 i64 值（用于整数比较）
    #[inline]
    pub fn as_i64(&self) -> i64 {
        match self.value_type {
            ValueType::Byte => self.value[0] as i8 as i64,
            ValueType::Word => i16::from_le_bytes(self.value[..2].try_into().unwrap()) as i64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => i32::from_le_bytes(self.value[..4].try_into().unwrap()) as i64,
            ValueType::Qword => i64::from_le_bytes(self.value),
            ValueType::Float => f32::from_le_bytes(self.value[..4].try_into().unwrap()) as i64,
            ValueType::Double => f64::from_le_bytes(self.value) as i64,
        }
    }

    /// 读取为 f64 值（用于浮点数比较）
    #[inline]
    pub fn as_f64(&self) -> f64 {
        match self.value_type {
            ValueType::Byte => self.value[0] as i8 as f64,
            ValueType::Word => i16::from_le_bytes(self.value[..2].try_into().unwrap()) as f64,
            ValueType::Dword | ValueType::Auto | ValueType::Xor => i32::from_le_bytes(self.value[..4].try_into().unwrap()) as f64,
            ValueType::Qword => i64::from_le_bytes(self.value) as f64,
            ValueType::Float => f32::from_le_bytes(self.value[..4].try_into().unwrap()) as f64,
            ValueType::Double => f64::from_le_bytes(self.value),
        }
    }

    /// 检查新值是否满足模糊搜索条件
    #[inline]
    pub fn matches_condition(&self, new_bytes: &[u8], condition: FuzzyCondition) -> bool {
        let new_item = FuzzySearchResultItem::from_bytes(self.address, new_bytes, self.value_type);

        if self.value_type.is_float_type() {
            self.matches_condition_float(&new_item, condition)
        } else {
            self.matches_condition_int(&new_item, condition)
        }
    }

    fn matches_condition_int(&self, new_item: &FuzzySearchResultItem, condition: FuzzyCondition) -> bool {
        let old_val = self.as_i64();
        let new_val = new_item.as_i64();
        let diff = new_val.wrapping_sub(old_val);

        match condition {
            FuzzyCondition::Initial => true,
            FuzzyCondition::Unchanged => old_val == new_val,
            FuzzyCondition::Changed => old_val != new_val,
            FuzzyCondition::Increased => new_val > old_val,
            FuzzyCondition::Decreased => new_val < old_val,
            FuzzyCondition::IncreasedBy(amount) => diff == amount,
            FuzzyCondition::DecreasedBy(amount) => diff == -amount,
            FuzzyCondition::IncreasedByRange(min, max) => diff >= min && diff <= max,
            FuzzyCondition::DecreasedByRange(min, max) => {
                let neg_diff = -diff;
                neg_diff >= min && neg_diff <= max
            },
            FuzzyCondition::IncreasedByPercent(percent) => {
                if old_val == 0 {
                    new_val > 0
                } else {
                    let threshold = (old_val as f64 * (1.0 + percent as f64)) as i64;
                    new_val >= threshold
                }
            },
            FuzzyCondition::DecreasedByPercent(percent) => {
                if old_val == 0 {
                    new_val < 0
                } else {
                    let threshold = (old_val as f64 * (1.0 - percent as f64)) as i64;
                    new_val <= threshold
                }
            },
        }
    }

    fn matches_condition_float(&self, new_item: &FuzzySearchResultItem, condition: FuzzyCondition) -> bool {
        let old_val = self.as_f64();
        let new_val = new_item.as_f64();
        let diff = new_val - old_val;
        let epsilon = 1e-9;

        match condition {
            FuzzyCondition::Initial => true,
            FuzzyCondition::Unchanged => (old_val - new_val).abs() < epsilon,
            FuzzyCondition::Changed => (old_val - new_val).abs() >= epsilon,
            FuzzyCondition::Increased => new_val > old_val + epsilon,
            FuzzyCondition::Decreased => new_val < old_val - epsilon,
            FuzzyCondition::IncreasedBy(amount) => (diff - amount as f64).abs() < epsilon,
            FuzzyCondition::DecreasedBy(amount) => (diff + amount as f64).abs() < epsilon,
            FuzzyCondition::IncreasedByRange(min, max) => diff >= min as f64 && diff <= max as f64,
            FuzzyCondition::DecreasedByRange(min, max) => {
                let neg_diff = -diff;
                neg_diff >= min as f64 && neg_diff <= max as f64
            },
            FuzzyCondition::IncreasedByPercent(percent) => {
                if old_val.abs() < epsilon {
                    new_val > epsilon
                } else {
                    let threshold = old_val * (1.0 + percent as f64);
                    new_val >= threshold
                }
            },
            FuzzyCondition::DecreasedByPercent(percent) => {
                if old_val.abs() < epsilon {
                    new_val < -epsilon
                } else {
                    let threshold = old_val * (1.0 - percent as f64);
                    new_val <= threshold
                }
            },
        }
    }

    /// 更新值（用于细化搜索后保存新值）
    pub fn with_new_value(&self, new_bytes: &[u8]) -> Self {
        FuzzySearchResultItem::from_bytes(self.address, new_bytes, self.value_type)
    }
}

/// 模糊搜索结果管理器 - 内存 + 磁盘混合存储
pub struct FuzzySearchResultManager {
    memory_buffer: Vec<FuzzySearchResultItem>,
    memory_buffer_capacity: usize,
    cache_dir: PathBuf,
    disk_file_path: Option<PathBuf>,
    disk_file: Option<File>,
    mmap: Option<MmapMut>,
    disk_count: usize,
    total_count: usize,
}

impl FuzzySearchResultManager {
    const ITEM_SIZE: usize = size_of::<FuzzySearchResultItem>();

    pub fn new(memory_buffer_size: usize, cache_dir: PathBuf) -> Self {
        let capacity = if memory_buffer_size == 0 { 0 } else { memory_buffer_size / Self::ITEM_SIZE };

        if memory_buffer_size == 0 {
            info!(
                "Initializing FuzzySearchResultManager: memory_buffer_capacity=0 (direct disk write mode), cache_dir={:?}",
                cache_dir
            );
        } else {
            info!(
                "Initializing FuzzySearchResultManager: memory_buffer_capacity={} items ({} MB), cache_dir={:?}",
                capacity,
                memory_buffer_size / 1024 / 1024,
                cache_dir
            );
        }

        FuzzySearchResultManager {
            memory_buffer: Vec::with_capacity(capacity),
            memory_buffer_capacity: capacity,
            cache_dir,
            disk_file_path: None,
            disk_file: None,
            mmap: None,
            disk_count: 0,
            total_count: 0,
        }
    }

    pub fn clear(&mut self) -> Result<()> {
        self.memory_buffer.clear();
        self.total_count = 0;
        self.disk_count = 0;
        debug!("Fuzzy search results cleared");
        Ok(())
    }

    pub fn clear_disk(&mut self) -> Result<()> {
        drop(self.mmap.take());
        drop(self.disk_file.take());

        if let Some(ref path) = self.disk_file_path {
            if path.exists() {
                std::fs::remove_file(path)?;
                debug!("Removed fuzzy disk file: {:?}", path);
            }
        }

        self.disk_file_path = None;
        self.disk_count = 0;
        info!("Fuzzy disk resources cleared");
        Ok(())
    }

    pub fn destroy(&mut self) -> Result<()> {
        self.memory_buffer.clear();
        self.total_count = 0;
        self.disk_count = 0;

        if let Some(ref path) = self.disk_file_path {
            drop(self.mmap.take());
            drop(self.disk_file.take());
            if path.exists() {
                std::fs::remove_file(path)?;
                debug!("Removed fuzzy disk file: {:?}", path);
            }
        }

        self.disk_file_path = None;
        info!("FuzzySearchResultManager destroyed");
        Ok(())
    }

    pub fn add_result(&mut self, item: FuzzySearchResultItem) -> Result<()> {
        if self.memory_buffer_capacity == 0 {
            self.write_to_disk(&item)?;
        } else if self.memory_buffer.len() < self.memory_buffer_capacity {
            self.memory_buffer.push(item);
        } else {
            self.write_to_disk(&item)?;
        }

        self.total_count += 1;
        Ok(())
    }

    fn write_to_disk(&mut self, item: &FuzzySearchResultItem) -> Result<()> {
        if self.disk_file.is_none() {
            self.init_disk_file()?;
        }

        if let Some(ref mut mmap) = self.mmap {
            let offset = self.disk_count * Self::ITEM_SIZE;
            let mmap_size = mmap.len();

            if offset + Self::ITEM_SIZE > mmap_size {
                drop(self.mmap.take());
                let new_size = mmap_size + 128 * 1024 * 1024;
                if let Some(ref file) = self.disk_file {
                    file.set_len(new_size as u64)?;
                }
                self.mmap = Some(unsafe { MmapMut::map_mut(self.disk_file.as_ref().unwrap())? });
            }

            let mmap = self.mmap.as_mut().unwrap();
            unsafe {
                let ptr = mmap.as_mut_ptr().add(offset) as *mut FuzzySearchResultItem;
                ptr.write(*item);
            }

            self.disk_count += 1;
        }

        Ok(())
    }

    fn init_disk_file(&mut self) -> Result<()> {
        let file_path = self.cache_dir.join("mamu_fuzzy_results.bin");

        debug!("Creating fuzzy disk file: {:?}", file_path);

        let initial_size = 128 * 1024 * 1024;
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(true).open(&file_path)?;

        file.set_len(initial_size as u64)?;

        let mmap = unsafe { MmapMut::map_mut(&file)? };

        self.disk_file_path = Some(file_path);
        self.disk_file = Some(file);
        self.mmap = Some(mmap);

        info!("Fuzzy disk file initialized with size {} MB", initial_size / 1024 / 1024);
        Ok(())
    }

    pub fn get_results(&self, start: usize, size: usize) -> Result<Vec<FuzzySearchResultItem>> {
        let end = std::cmp::min(start + size, self.total_count);
        if start >= self.total_count {
            return Ok(Vec::new());
        }

        let mut results = Vec::with_capacity(end - start);

        for i in start..end {
            if i < self.memory_buffer.len() {
                results.push(self.memory_buffer[i]);
            } else {
                let disk_index = i - self.memory_buffer.len();
                if let Some(ref mmap) = self.mmap {
                    let offset = disk_index * Self::ITEM_SIZE;
                    unsafe {
                        let ptr = mmap.as_ptr().add(offset) as *const FuzzySearchResultItem;
                        results.push(*ptr);
                    }
                }
            }
        }

        Ok(results)
    }

    pub fn get_all_results(&self) -> Result<Vec<FuzzySearchResultItem>> {
        self.get_results(0, self.total_count)
    }

    pub fn total_count(&self) -> usize {
        self.total_count
    }

    pub fn memory_count(&self) -> usize {
        self.memory_buffer.len()
    }

    pub fn disk_count(&self) -> usize {
        self.disk_count
    }

    /// 更新指定索引的结果项（用于细化搜索后更新值）
    pub fn update_result(&mut self, index: usize, item: FuzzySearchResultItem) -> Result<()> {
        if index >= self.total_count {
            return Err(anyhow!("Index out of bounds: {} >= {}", index, self.total_count));
        }

        if index < self.memory_buffer.len() {
            self.memory_buffer[index] = item;
        } else {
            let disk_index = index - self.memory_buffer.len();
            if let Some(ref mut mmap) = self.mmap {
                let offset = disk_index * Self::ITEM_SIZE;
                unsafe {
                    let ptr = mmap.as_mut_ptr().add(offset) as *mut FuzzySearchResultItem;
                    ptr.write(item);
                }
            }
        }

        Ok(())
    }

    /// 批量替换所有结果（用于细化搜索后）
    pub fn replace_all(&mut self, results: Vec<FuzzySearchResultItem>) -> Result<()> {
        self.clear()?;
        for item in results {
            self.add_result(item)?;
        }
        Ok(())
    }

    pub fn remove_result(&mut self, index: usize) -> Result<()> {
        if index >= self.total_count {
            return Err(anyhow!("Index out of bounds: {} >= {}", index, self.total_count));
        }

        if index < self.memory_buffer.len() {
            self.memory_buffer.remove(index);
        } else {
            let disk_index = index - self.memory_buffer.len();
            self.remove_disk_item(disk_index)?;
        }

        self.total_count -= 1;
        debug!("Removed fuzzy result at index {}, total count: {}", index, self.total_count);
        Ok(())
    }

    fn remove_disk_item(&mut self, disk_index: usize) -> Result<()> {
        if disk_index >= self.disk_count {
            return Err(anyhow!("Disk index out of bounds"));
        }

        if let Some(ref mut mmap) = self.mmap {
            let src_offset = (disk_index + 1) * Self::ITEM_SIZE;
            let dst_offset = disk_index * Self::ITEM_SIZE;
            let move_count = self.disk_count - disk_index - 1;

            if move_count > 0 {
                unsafe {
                    let src = mmap.as_ptr().add(src_offset);
                    let dst = mmap.as_mut_ptr().add(dst_offset);
                    std::ptr::copy(src, dst, move_count * Self::ITEM_SIZE);
                }
            }

            self.disk_count -= 1;
        }

        Ok(())
    }

    pub fn remove_results_batch(&mut self, mut indices: Vec<usize>) -> Result<()> {
        if indices.is_empty() {
            return Ok(());
        }

        indices.sort_unstable();
        indices.dedup();
        indices.retain(|&idx| idx < self.total_count);
        if indices.is_empty() {
            return Ok(());
        }

        let delete_count = indices.len();
        let memory_len = self.memory_buffer.len();

        let (memory_indices, disk_indices): (Vec<usize>, Vec<usize>) = indices.into_iter().partition(|&idx| idx < memory_len);

        if !memory_indices.is_empty() {
            self.remove_memory_batch(&memory_indices);
        }

        if !disk_indices.is_empty() {
            let adjusted_disk_indices: Vec<usize> = disk_indices.iter().map(|&idx| idx - memory_len).collect();
            self.remove_disk_batch(&adjusted_disk_indices)?;
        }

        self.total_count -= delete_count;
        debug!("Batch removed {} fuzzy results, total: {}", delete_count, self.total_count);
        Ok(())
    }

    fn remove_memory_batch(&mut self, sorted_indices: &[usize]) {
        if sorted_indices.is_empty() || self.memory_buffer.is_empty() {
            return;
        }

        let first_del = sorted_indices[0];
        let mem_len = self.memory_buffer.len();

        if first_del >= mem_len {
            return;
        }

        let mut write_pos = first_del;
        let mut delete_iter = sorted_indices.iter().peekable();

        for read_pos in first_del..mem_len {
            if let Some(&&del_idx) = delete_iter.peek() {
                if read_pos == del_idx {
                    delete_iter.next();
                    continue;
                }
            }

            if write_pos != read_pos {
                self.memory_buffer[write_pos] = self.memory_buffer[read_pos];
            }
            write_pos += 1;
        }

        self.memory_buffer.truncate(write_pos);
    }

    fn remove_disk_batch(&mut self, sorted_disk_indices: &[usize]) -> Result<()> {
        if sorted_disk_indices.is_empty() || self.disk_count == 0 {
            return Ok(());
        }

        let Some(ref mut mmap) = self.mmap else {
            return Ok(());
        };

        let first_del = sorted_disk_indices[0];

        if first_del >= self.disk_count {
            return Ok(());
        }

        let mut write_pos = first_del;
        let mut delete_iter = sorted_disk_indices.iter().peekable();

        for read_pos in first_del..self.disk_count {
            if let Some(&&del_idx) = delete_iter.peek() {
                if del_idx >= self.disk_count {
                    while delete_iter.next().is_some() {}
                } else if read_pos == del_idx {
                    delete_iter.next();
                    continue;
                }
            }

            if write_pos != read_pos {
                unsafe {
                    let src = mmap.as_ptr().add(read_pos * Self::ITEM_SIZE);
                    let dst = mmap.as_mut_ptr().add(write_pos * Self::ITEM_SIZE);
                    std::ptr::copy_nonoverlapping(src, dst, Self::ITEM_SIZE);
                }
            }
            write_pos += 1;
        }

        self.disk_count = write_pos;
        Ok(())
    }

    pub fn keep_only_results(&mut self, mut keep_indices: Vec<usize>) -> Result<()> {
        if keep_indices.is_empty() {
            self.memory_buffer.clear();
            self.disk_count = 0;
            self.total_count = 0;
            debug!("Kept 0 fuzzy results, cleared all");
            return Ok(());
        }

        let keep_count = keep_indices.len();
        let remove_count = self.total_count.saturating_sub(keep_count);

        if remove_count == 0 {
            debug!("Keep all {} fuzzy results, nothing to remove", self.total_count);
            return Ok(());
        }

        if keep_count <= remove_count {
            debug!(
                "Using rebuild strategy for fuzzy: keep {} items, would remove {} items",
                keep_count, remove_count
            );

            keep_indices.sort_unstable();

            let mut kept_items: Vec<FuzzySearchResultItem> = Vec::with_capacity(keep_count);
            for &idx in &keep_indices {
                if idx >= self.total_count {
                    continue;
                }
                if idx < self.memory_buffer.len() {
                    kept_items.push(self.memory_buffer[idx]);
                } else {
                    let disk_index = idx - self.memory_buffer.len();
                    if let Some(ref mmap) = self.mmap {
                        let offset = disk_index * Self::ITEM_SIZE;
                        unsafe {
                            let ptr = mmap.as_ptr().add(offset) as *const FuzzySearchResultItem;
                            kept_items.push(*ptr);
                        }
                    }
                }
            }

            self.memory_buffer.clear();
            self.disk_count = 0;
            self.total_count = 0;

            for item in kept_items {
                self.add_result(item)?;
            }

            debug!("Rebuild complete: kept {} fuzzy results, removed {} results", self.total_count, remove_count);
        } else {
            debug!(
                "Using batch delete strategy for fuzzy: keep {} items, remove {} items",
                keep_count, remove_count
            );

            use std::collections::HashSet;
            let keep_set: HashSet<usize> = keep_indices.into_iter().collect();

            let remove_indices: Vec<usize> = (0..self.total_count).filter(|i| !keep_set.contains(i)).collect();

            self.remove_results_batch(remove_indices)?;

            debug!(
                "Batch delete complete: kept {} fuzzy results, removed {} results",
                self.total_count, remove_count
            );
        }

        Ok(())
    }
}

impl Drop for FuzzySearchResultManager {
    fn drop(&mut self) {
        let _ = self.destroy();
    }
}
