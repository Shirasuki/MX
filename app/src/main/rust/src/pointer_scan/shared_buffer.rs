//! Shared buffer for progress communication with Kotlin.
//!
//! This module provides a shared memory buffer for communicating scan progress
//! and status between the Rust native code and the Kotlin UI layer.
//! The buffer is a direct ByteBuffer allocated on the Kotlin side and passed
//! to Rust via JNI.

use std::sync::atomic::{AtomicPtr, Ordering};

/// Size of the shared buffer in bytes.
pub const SHARED_BUFFER_SIZE: usize = 48;

/// Offsets for fields in the shared buffer.
pub mod offsets {
    /// Scan phase (i32): Idle, ScanningPointers, BuildingChains, Completed, etc.
    pub const PHASE: usize = 0;
    /// Progress percentage (i32): 0-100
    pub const PROGRESS: usize = 4;
    /// Number of memory regions processed (i32)
    pub const REGIONS_DONE: usize = 8;
    /// Total pointers found (i64)
    pub const POINTERS_FOUND: usize = 12;
    /// Total pointer chains found (i64)
    pub const CHAINS_FOUND: usize = 20;
    /// Current search depth (i32)
    pub const CURRENT_DEPTH: usize = 28;
    /// Heartbeat value (i32): Changes periodically to indicate liveness
    pub const HEARTBEAT: usize = 32;
    /// Cancel flag (i32): Set to 1 by Kotlin to request cancellation
    pub const CANCEL_FLAG: usize = 36;
    /// Error code (i32)
    pub const ERROR_CODE: usize = 40;
    /// Reserved for future use
    pub const RESERVED: usize = 44;
}

/// Shared buffer for communicating with Kotlin.
pub struct PointerScanSharedBuffer {
    ptr: AtomicPtr<u8>,
    len: usize,
    heartbeat_counter: std::sync::atomic::AtomicU32,
}

impl PointerScanSharedBuffer {
    /// Create a new uninitialized shared buffer.
    pub fn new() -> Self {
        Self {
            ptr: AtomicPtr::new(std::ptr::null_mut()),
            len: 0,
            heartbeat_counter: std::sync::atomic::AtomicU32::new(0),
        }
    }

    /// Set the buffer pointer (called from JNI).
    ///
    /// # Safety
    /// The pointer must point to valid memory of at least SHARED_BUFFER_SIZE bytes
    /// that remains valid for the lifetime of scan operations.
    pub fn set(&mut self, ptr: *mut u8, len: usize) -> bool {
        if ptr.is_null() || len < SHARED_BUFFER_SIZE {
            return false;
        }
        self.ptr.store(ptr, Ordering::SeqCst);
        self.len = len;
        true
    }

    /// Check if the buffer is initialized.
    pub fn is_initialized(&self) -> bool {
        !self.ptr.load(Ordering::Relaxed).is_null()
    }

    /// Reset the buffer contents to initial state.
    pub fn reset(&self) {
        let ptr = self.ptr.load(Ordering::Relaxed);
        if ptr.is_null() {
            return;
        }

        unsafe {
            // Zero out the entire buffer
            std::ptr::write_bytes(ptr, 0, SHARED_BUFFER_SIZE);
        }
    }

    /// Write an i32 value at the given offset.
    #[inline]
    fn write_i32(&self, offset: usize, value: i32) {
        let ptr = self.ptr.load(Ordering::Relaxed);
        if ptr.is_null() || offset + 4 > self.len {
            return;
        }
        unsafe {
            let dest = ptr.add(offset) as *mut i32;
            dest.write_volatile(value);
        }
    }

    /// Write an i64 value at the given offset.
    #[inline]
    fn write_i64(&self, offset: usize, value: i64) {
        let ptr = self.ptr.load(Ordering::Relaxed);
        if ptr.is_null() || offset + 8 > self.len {
            return;
        }
        unsafe {
            let dest = ptr.add(offset) as *mut i64;
            dest.write_volatile(value);
        }
    }

    /// Read an i32 value from the given offset.
    #[inline]
    fn read_i32(&self, offset: usize) -> i32 {
        let ptr = self.ptr.load(Ordering::Relaxed);
        if ptr.is_null() || offset + 4 > self.len {
            return 0;
        }
        unsafe {
            let src = ptr.add(offset) as *const i32;
            src.read_volatile()
        }
    }

    /// Write the current scan phase.
    pub fn write_phase(&self, phase: crate::pointer_scan::types::ScanPhase) {
        self.write_i32(offsets::PHASE, phase as i32);
    }

    /// Write the progress percentage (0-100).
    pub fn write_progress(&self, progress: i32) {
        let clamped = progress.clamp(0, 100);
        self.write_i32(offsets::PROGRESS, clamped);
    }

    /// Write the number of memory regions processed.
    pub fn write_regions_done(&self, count: i32) {
        self.write_i32(offsets::REGIONS_DONE, count);
    }

    /// Write the total number of pointers found.
    pub fn write_pointers_found(&self, count: i64) {
        self.write_i64(offsets::POINTERS_FOUND, count);
    }

    /// Write the total number of chains found.
    pub fn write_chains_found(&self, count: i64) {
        self.write_i64(offsets::CHAINS_FOUND, count);
    }

    /// Write the current search depth.
    pub fn write_current_depth(&self, depth: i32) {
        self.write_i32(offsets::CURRENT_DEPTH, depth);
    }

    /// Write the error code.
    pub fn write_error_code(&self, code: crate::pointer_scan::types::ScanErrorCode) {
        self.write_i32(offsets::ERROR_CODE, code as i32);
    }

    /// Update the heartbeat value.
    pub fn update_heartbeat(&self) {
        let value = self.heartbeat_counter.fetch_add(1, Ordering::Relaxed);
        self.write_i32(offsets::HEARTBEAT, value as i32);
    }

    /// Check if cancellation was requested.
    pub fn is_cancel_requested(&self) -> bool {
        self.read_i32(offsets::CANCEL_FLAG) != 0
    }

    /// Clear the cancel flag.
    pub fn clear_cancel_flag(&self) {
        self.write_i32(offsets::CANCEL_FLAG, 0);
    }

    /// Update progress for Phase 1 (pointer scanning).
    pub fn update_scanning_progress(&self, regions_done: i32, total_regions: i32, pointers_found: i64) {
        // Phase 1 is 0-50% of total progress
        let progress = if total_regions > 0 {
            (regions_done as f32 / total_regions as f32 * 50.0) as i32
        } else {
            0
        };
        self.write_progress(progress);
        self.write_regions_done(regions_done);
        self.write_pointers_found(pointers_found);
        self.update_heartbeat();
    }

    /// Update progress for Phase 2 (chain building).
    pub fn update_building_progress(&self, current_depth: i32, max_depth: i32, chains_found: i64) {
        // Phase 2 is 50-100% of total progress
        let progress = if max_depth > 0 {
            50 + (current_depth as f32 / max_depth as f32 * 50.0) as i32
        } else {
            50
        };
        self.write_progress(progress);
        self.write_current_depth(current_depth);
        self.write_chains_found(chains_found);
        self.update_heartbeat();
    }
}

impl Default for PointerScanSharedBuffer {
    fn default() -> Self {
        Self::new()
    }
}

// Safety: The buffer pointer is protected by atomic operations
unsafe impl Send for PointerScanSharedBuffer {}
unsafe impl Sync for PointerScanSharedBuffer {}

