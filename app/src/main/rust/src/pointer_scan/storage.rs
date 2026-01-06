//! Memory-mapped storage for large pointer datasets.
//!
//! This module provides `MmapQueue`, a memory-mapped queue that stores
//! pointer data on disk while providing fast random access. This allows
//! handling very large datasets (millions of pointers) without running
//! out of memory.

use anyhow::Result;
use memmap2::MmapMut;
use rancor::{Source, Strategy};
use rkyv::de::Pool;
use rkyv::rancor::{Error, Fallible};
use rkyv::ser::allocator::ArenaHandle;
use rkyv::util::AlignedVec;
use rkyv::{access_unchecked, rancor, to_bytes, Archive, Deserialize, Serialize};
use std::fs::{File, OpenOptions};
use std::marker::PhantomData;
use std::path::PathBuf;
use rkyv::api::high::HighSerializer;

const ALIGNMENT: usize = 16;
const RKYV_BUF_SIZE: usize = 4096;

pub struct MmapQueue<T> {
    file: File,
    file_path: PathBuf,
    mmap: Option<MmapMut>,
    capacity: usize,              // Total capacity in bytes
    count: usize,                 // Number of items stored
    write_offset: usize,          // Current write position in bytes
    indices: Vec<(usize, usize)>, // (offset, length)
    _phantom: PhantomData<T>,
}

impl<T> MmapQueue<T>
where
    T: Archive,
    T::Archived: 'static,
    T: for<'a> Serialize<HighSerializer<AlignedVec, ArenaHandle<'a>, Error>>,
{
    const INITIAL_SIZE: usize = 128 * 1024 * 1024;
    const GROW_SIZE: usize = 64 * 1024 * 1024;

    /// Create a new MmapQueue backed by a file in the given directory.
    ///
    /// # Arguments
    /// * `cache_dir` - Directory to store the backing file
    /// * `name` - Name prefix for the backing file
    pub fn new(cache_dir: &PathBuf, name: &str) -> Result<Self> {
        let file_path = cache_dir.join(format!("mamu_ps_{}.bin", name));

        // Create parent directory if it doesn't exist
        if let Some(parent) = file_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let file = OpenOptions::new().read(true).write(true).create(true).truncate(true).open(&file_path)?;

        file.set_len(Self::INITIAL_SIZE as u64)?;

        let mmap = unsafe { MmapMut::map_mut(&file)? };

        Ok(Self {
            file,
            file_path,
            mmap: Some(mmap),
            capacity: Self::INITIAL_SIZE,
            count: 0,
            write_offset: 0,
            indices: Vec::new(),
            _phantom: PhantomData,
        })
    }

    /// Push an item to the end of the queue.
    pub fn push(&mut self, item: &T) -> Result<()> {
        let bytes = to_bytes::<Error>(item)?;
        let size = bytes.len();

        let padding = (ALIGNMENT - (self.write_offset % ALIGNMENT)) % ALIGNMENT;
        let required_space = size + padding;

        // 对齐保存
        while self.write_offset + required_space > self.capacity {
            self.grow_old()?;
        }

        if let Some(ref mut mmap) = self.mmap {
            unsafe {
                let start_ptr = mmap.as_mut_ptr().add(self.write_offset + padding);
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), start_ptr, size);
            }
        } else {
            panic!("Mmap buffer is None");
        }

        let data_offset = self.write_offset + padding;
        self.indices.push((data_offset, size));
        self.write_offset += required_space;
        self.count += 1;

        Ok(())
    }

    /// Push multiple items efficiently.
    pub fn push_batch(&mut self, items: &[T]) -> Result<()> {
        for item in items {
            self.push(item)?;
        }
        Ok(())
    }

    pub fn get(&self, index: usize) -> Option<&T::Archived> {
        let (offset, length) = *self.indices.get(index)?;

        self.mmap.as_ref().map(|mmap| unsafe {
            let ptr = mmap.as_ptr().add(offset);
            let slice = std::slice::from_raw_parts(ptr, length);
            access_unchecked::<T::Archived>(slice)
        })
    }

    pub fn get_deserialized(&self, index: usize) -> Option<T>
    where
        T::Archived: Deserialize<T, T::Archived>,
        <T as Archive>::Archived: Fallible,
        <T as Archive>::Archived: Deserialize<T, Strategy<Pool, Error>>,
    {
        let archived = self.get(index)?;
        rkyv::deserialize::<T, Error>(archived).ok()
    }

    /// Get the number of items in the queue.
    pub fn len(&self) -> usize {
        self.count
    }

    /// Check if the queue is empty.
    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// Get the capacity in bytes.
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// Clear all items from the queue.
    pub fn clear(&mut self) {
        self.count = 0;
        self.write_offset = 0;
        self.indices.clear();
    }

    /// Grow the backing file and remap.
    fn grow_old(&mut self) -> Result<()> {
        // Drop current mmap
        self.mmap = None;

        // Grow file
        let new_size = self.capacity + Self::GROW_SIZE;
        self.file.set_len(new_size as u64)?;

        // Remap
        self.mmap = Some(unsafe { MmapMut::map_mut(&self.file)? });
        self.capacity = new_size;

        Ok(())
    }

    /// Flush changes to disk.
    pub fn flush(&self) -> Result<()> {
        if let Some(ref mmap) = self.mmap {
            mmap.flush()?;
        }
        Ok(())
    }

    /// Get the file path of the backing file.
    pub fn file_path(&self) -> &PathBuf {
        &self.file_path
    }
}

impl<T> Drop for MmapQueue<T> {
    fn drop(&mut self) {
        // Explicitly drop mmap before file
        self.mmap = None;
        // Try to remove the backing file
        let _ = std::fs::remove_file(&self.file_path);
    }
}
