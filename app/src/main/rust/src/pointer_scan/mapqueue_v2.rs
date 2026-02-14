//! MapQueue - 基于文件 + mmap 的动态数组
//!
//! 核心机制：
//! - 使用应用缓存目录下的临时文件作为后备存储
//! - mmap 映射到虚拟地址空间
//! - 内存压力时 OS 自动换出到文件
//! - 避免 BFS 扫描时的内存爆炸
//!
//! Android 兼容：
//! - 不使用 tmpfile()（Android 没有 /tmp 目录权限）
//! - 使用应用沙盒目录 /data/data/<package>/cache
//!
//! 相比原 MmapQueue 的优势：
//! - 无 rkyv 序列化开销
//! - 直接内存映射，零拷贝访问
//! - 类型约束简单：只需 T: Copy

use std::fs::{File, OpenOptions};
use std::marker::PhantomData;
use std::ops::{Index, IndexMut};
use std::path::PathBuf;
use std::ptr::NonNull;
use std::slice;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;

use anyhow::{anyhow, Result};
use memmap2::MmapMut;
use once_cell::sync::Lazy;

/// 全局缓存目录配置
/// 在 JNI 初始化时设置，例如: /data/data/moe.fuqiuluo.mamu/cache
static CACHE_DIR: Lazy<RwLock<Option<PathBuf>>> = Lazy::new(|| RwLock::new(None));

/// 文件计数器，用于生成唯一文件名
static FILE_COUNTER: AtomicU64 = AtomicU64::new(0);

/// 设置全局缓存目录
/// 必须在使用 MapQueue 之前调用（通常在 JNI 初始化时）
pub fn set_cache_dir(path: &str) -> Result<()> {
    let mut cache_dir = CACHE_DIR.write().map_err(|e| anyhow!("set_cache_dir failed: {:?}", e))?;
    *cache_dir = Some(PathBuf::from(path));
    Ok(())
}

/// 获取缓存目录
fn get_cache_dir() -> Result<PathBuf> {
    let cache_dir = CACHE_DIR.read().map_err(|e| anyhow!("get_cache_dir failed: {:?}", e))?;
    cache_dir.clone().ok_or_else(|| anyhow!("Cache directory not set. Call set_cache_dir() first."))
}

/// 创建临时文件
fn create_temp_file() -> Result<(File, PathBuf)> {
    let cache_dir = get_cache_dir()?;

    // 确保目录存在
    std::fs::create_dir_all(&cache_dir).map_err(|e| anyhow!("Failed to create cache dir: {}", e))?;

    // 生成唯一文件名
    let counter = FILE_COUNTER.fetch_add(1, Ordering::SeqCst);
    let pid = std::process::id();
    let filename = format!("mq_{}_{}.tmp", pid, counter);
    let file_path = cache_dir.join(filename);

    // 创建文件
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(&file_path)
        .map_err(|e| anyhow!("Failed to create temp file {:?}: {}", file_path, e))?;

    Ok((file, file_path))
}

/// 基于 mmap 的动态数组，用于存储大量 BFS 中间数据
pub struct MapQueue<T: Copy> {
    /// 后备文件
    file: Option<File>,
    /// 文件路径（用于删除）
    file_path: Option<PathBuf>,
    /// mmap 映射区域
    mmap: Option<MmapMut>,
    /// 数据指针
    data: Option<NonNull<T>>,
    /// 当前元素数量
    len: usize,
    /// 容量（元素数量）
    capacity: usize,
    /// 类型标记
    _marker: PhantomData<T>,
}

// Safety: MapQueue 内部数据通过 mmap 管理，可以安全地跨线程发送
unsafe impl<T: Copy + Send> Send for MapQueue<T> {}
unsafe impl<T: Copy + Sync> Sync for MapQueue<T> {}

impl<T: Copy> MapQueue<T> {
    /// 创建空的 MapQueue
    pub fn new() -> Self {
        Self {
            file: None,
            file_path: None,
            mmap: None,
            data: None,
            len: 0,
            capacity: 0,
            _marker: PhantomData,
        }
    }

    /// 创建指定容量的 MapQueue
    pub fn with_capacity(capacity: usize) -> Result<Self> {
        let mut queue = Self::new();
        queue.reserve(capacity)?;
        Ok(queue)
    }

    /// 当前元素数量
    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    /// 是否为空
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// 容量
    #[inline]
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// 清空数据（不释放内存）
    pub fn clear(&mut self) {
        self.len = 0;
    }

    /// 截断到指定长度（不释放内存）
    pub fn truncate(&mut self, new_len: usize) {
        if new_len < self.len {
            self.len = new_len;
        }
    }

    /// 预留容量
    pub fn reserve(&mut self, new_capacity: usize) -> Result<()> {
        if new_capacity <= self.capacity {
            return Ok(());
        }

        let new_size = new_capacity * size_of::<T>();

        // 创建新的临时文件
        let (file, file_path) = create_temp_file()?;

        // 设置文件大小
        file.set_len(new_size as u64).map_err(|e| anyhow!("Failed to set file length: {}", e))?;

        // 创建 mmap 映射
        let mut new_mmap = unsafe { MmapMut::map_mut(&file).map_err(|e| anyhow!("Failed to mmap: {}", e))? };

        // 复制旧数据
        if let Some(old_data) = self.data {
            let old_bytes = self.len * size_of::<T>();
            unsafe {
                std::ptr::copy_nonoverlapping(old_data.as_ptr() as *const u8, new_mmap.as_mut_ptr(), old_bytes);
            }
        }

        // 获取新数据指针
        let new_data = NonNull::new(new_mmap.as_mut_ptr() as *mut T).ok_or_else(|| anyhow!("Mmap returned null pointer"))?;

        // 删除旧文件
        self.cleanup_old_file();

        self.file = Some(file);
        self.file_path = Some(file_path);
        self.mmap = Some(new_mmap);
        self.data = Some(new_data);
        self.capacity = new_capacity;

        Ok(())
    }

    /// 清理旧文件
    fn cleanup_old_file(&mut self) {
        // 先释放 mmap
        self.mmap = None;
        self.file = None;

        // 删除旧文件
        if let Some(ref path) = self.file_path {
            let _ = std::fs::remove_file(path);
        }
        self.file_path = None;
    }

    /// 计算增长后的容量
    fn grow_capacity(&self, min_capacity: usize) -> usize {
        let new_capacity = if self.capacity == 0 {
            1024 // 初始容量
        } else {
            self.capacity + self.capacity / 2
        };
        new_capacity.max(min_capacity)
    }

    /// 添加元素
    pub fn push(&mut self, value: T) -> Result<()> {
        if self.len == self.capacity {
            self.reserve(self.grow_capacity(self.len + 1))?;
        }

        unsafe {
            let ptr = self.data.unwrap().as_ptr().add(self.len);
            std::ptr::write(ptr, value);
        }
        self.len += 1;

        Ok(())
    }

    /// 批量添加元素
    pub fn extend_from_slice(&mut self, values: &[T]) -> Result<()> {
        if values.is_empty() {
            return Ok(());
        }

        let new_len = self.len + values.len();
        if new_len > self.capacity {
            self.reserve(self.grow_capacity(new_len))?;
        }

        unsafe {
            let ptr = self.data.unwrap().as_ptr().add(self.len);
            std::ptr::copy_nonoverlapping(values.as_ptr(), ptr, values.len());
        }
        self.len = new_len;

        Ok(())
    }

    /// 弹出最后一个元素
    pub fn pop(&mut self) -> Option<T> {
        if self.len == 0 {
            return None;
        }
        self.len -= 1;
        unsafe {
            let ptr = self.data.unwrap().as_ptr().add(self.len);
            Some(std::ptr::read(ptr))
        }
    }

    /// 调整大小
    pub fn resize(&mut self, new_len: usize, value: T) -> Result<()> {
        if new_len > self.capacity {
            self.reserve(self.grow_capacity(new_len))?;
        }

        if new_len > self.len {
            unsafe {
                let ptr = self.data.unwrap().as_ptr();
                for i in self.len..new_len {
                    std::ptr::write(ptr.add(i), value);
                }
            }
        }
        self.len = new_len;

        Ok(())
    }

    /// 获取切片
    pub fn as_slice(&self) -> &[T] {
        if self.len == 0 {
            return &[];
        }
        unsafe { slice::from_raw_parts(self.data.unwrap().as_ptr(), self.len) }
    }

    /// 获取可变切片
    pub fn as_mut_slice(&mut self) -> &mut [T] {
        if self.len == 0 {
            return &mut [];
        }
        unsafe { slice::from_raw_parts_mut(self.data.unwrap().as_ptr(), self.len) }
    }

    /// 迭代器
    pub fn iter(&self) -> impl Iterator<Item = &T> {
        self.as_slice().iter()
    }

    /// 可变迭代器
    pub fn iter_mut(&mut self) -> impl Iterator<Item = &mut T> {
        self.as_mut_slice().iter_mut()
    }

    /// 获取第一个元素
    pub fn first(&self) -> Option<&T> {
        self.as_slice().first()
    }

    /// 获取最后一个元素
    pub fn last(&self) -> Option<&T> {
        self.as_slice().last()
    }

    /// 字节大小
    pub fn size_in_bytes(&self) -> usize {
        self.len * size_of::<T>()
    }

    /// 获取指定索引的元素引用
    pub fn get(&self, index: usize) -> Option<&T> {
        if index < self.len { Some(&self.as_slice()[index]) } else { None }
    }

    /// 获取指定索引的可变元素引用
    pub fn get_mut(&mut self, index: usize) -> Option<&mut T> {
        if index < self.len { Some(&mut self.as_mut_slice()[index]) } else { None }
    }

    /// 按 key 排序
    pub fn sort_by_key<K, F>(&mut self, f: F)
    where
        K: Ord,
        F: FnMut(&T) -> K,
    {
        self.as_mut_slice().sort_by_key(f);
    }

    /// 不稳定排序（更快）
    pub fn sort_unstable_by_key<K, F>(&mut self, f: F)
    where
        K: Ord,
        F: FnMut(&T) -> K,
    {
        self.as_mut_slice().sort_unstable_by_key(f);
    }
}

impl<T: Copy> Default for MapQueue<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T: Copy> Index<usize> for MapQueue<T> {
    type Output = T;

    fn index(&self, index: usize) -> &Self::Output {
        assert!(index < self.len, "index out of bounds: {} >= {}", index, self.len);
        unsafe { &*self.data.unwrap().as_ptr().add(index) }
    }
}

impl<T: Copy> IndexMut<usize> for MapQueue<T> {
    fn index_mut(&mut self, index: usize) -> &mut Self::Output {
        assert!(index < self.len, "index out of bounds: {} >= {}", index, self.len);
        unsafe { &mut *self.data.unwrap().as_ptr().add(index) }
    }
}

impl<T: Copy> Drop for MapQueue<T> {
    fn drop(&mut self) {
        // 先释放 mmap
        self.mmap = None;
        self.data = None;
        self.file = None;

        // 删除临时文件
        if let Some(ref path) = self.file_path {
            let _ = std::fs::remove_file(path);
        }
    }
}

impl<T: Copy> Clone for MapQueue<T> {
    fn clone(&self) -> Self {
        let mut new_queue = Self::new();
        if self.len > 0 {
            new_queue.reserve(self.len).expect("clone reserve failed");
            unsafe {
                std::ptr::copy_nonoverlapping(self.data.unwrap().as_ptr(), new_queue.data.unwrap().as_ptr(), self.len);
            }
            new_queue.len = self.len;
        }
        new_queue
    }
}
