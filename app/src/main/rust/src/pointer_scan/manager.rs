//! Pointer Scan Manager
//!
//! This module provides the main entry point for pointer scanning.
//! It coordinates Phase 1 (pointer scanning) and Phase 2 (chain building),
//! manages async execution, and provides JNI-accessible state.

use crate::core::globals::TOKIO_RUNTIME;
use crate::pointer_scan::chain_builder;
use crate::pointer_scan::scanner::{self, ScanRegion};
use crate::pointer_scan::shared_buffer::PointerScanSharedBuffer;
use crate::pointer_scan::storage::MmapQueue;
use crate::pointer_scan::types::{PointerChain, PointerData, PointerScanConfig, ScanErrorCode, ScanPhase, VmStaticData};
use anyhow::{anyhow, Result};
use lazy_static::lazy_static;
use log::{error, info, log_enabled, warn, Level};
use std::path::PathBuf;
use std::sync::RwLock;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

lazy_static! {
    pub static ref POINTER_SCAN_MANAGER: RwLock<PointerScanManager> = RwLock::new(PointerScanManager::new());
}

/// Manages pointer scan operations.
pub struct PointerScanManager {
    /// Pointer library built in Phase 1
    pointer_library: Option<MmapQueue<PointerData>>,
    /// Pointer chain results from Phase 2
    chain_results: Vec<PointerChain>,
    /// Current scan configuration
    config: PointerScanConfig,
    /// Shared buffer for progress communication
    shared_buffer: PointerScanSharedBuffer,
    /// Cancellation token for current scan
    cancel_token: Option<CancellationToken>,
    /// Handle to the async scan task
    scan_handle: Option<JoinHandle<()>>,
    /// Cache directory for temporary files
    cache_dir: PathBuf,
    /// Current scan phase
    current_phase: ScanPhase,
    /// Last error code
    last_error: ScanErrorCode,
}

impl PointerScanManager {
    /// Create a new manager instance.
    pub fn new() -> Self {
        Self {
            pointer_library: None,
            chain_results: Vec::new(),
            config: PointerScanConfig::default(),
            shared_buffer: PointerScanSharedBuffer::new(),
            cancel_token: None,
            scan_handle: None,
            cache_dir: PathBuf::from("/data/data/moe.fuqiuluo.mamu/cache"),
            current_phase: ScanPhase::Idle,
            last_error: ScanErrorCode::None,
        }
    }

    /// Initialize the manager with a cache directory.
    pub fn init(&mut self, cache_dir: String) -> Result<()> {
        self.cache_dir = PathBuf::from(cache_dir);
        if !self.cache_dir.exists() {
            std::fs::create_dir_all(&self.cache_dir)?;
        }
        info!("PointerScanManager initialized with cache_dir: {:?}", self.cache_dir);
        Ok(())
    }

    /// Set the shared buffer for progress communication.
    pub fn set_shared_buffer(&mut self, ptr: *mut u8, len: usize) -> bool {
        self.shared_buffer.set(ptr, len)
    }

    /// Check if a scan is currently in progress.
    pub fn is_scanning(&self) -> bool {
        if let Some(ref handle) = self.scan_handle {
            !handle.is_finished()
        } else {
            false
        }
    }

    /// Request cancellation of the current scan.
    pub fn request_cancel(&self) {
        if let Some(ref token) = self.cancel_token {
            token.cancel();
        }
    }

    /// Get the current scan phase.
    pub fn get_phase(&self) -> ScanPhase {
        self.current_phase
    }

    /// Get the last error code.
    pub fn get_error(&self) -> ScanErrorCode {
        self.last_error
    }

    /// Get the number of chain results.
    pub fn get_chain_count(&self) -> usize {
        if log_enabled!(Level::Debug) {
            info!("PointerScanManager get_chain count: {}", self.chain_results.len());
        }
        self.chain_results.len()
    }

    /// Get a slice of chain results.
    pub fn get_chain_results(&self, start: usize, count: usize) -> Vec<PointerChain> {
        if log_enabled!(Level::Debug) {
            info!("PointerScanManager get_chain results(start = {}, count = {})", start, count);
        }

        let end = std::cmp::min(start + count, self.chain_results.len());
        if start >= self.chain_results.len() {
            return Vec::new();
        }
        let rrt = self.chain_results[start..end].to_vec();

        if log_enabled!(Level::Debug) {
            for x in &rrt {
                info!("---- {}", x.format());
            }
        }

        rrt
    }

    /// Clear all results and reset state.
    pub fn clear(&mut self) {
        self.pointer_library = None;
        self.chain_results.clear();
        self.current_phase = ScanPhase::Idle;
        self.last_error = ScanErrorCode::None;
        self.shared_buffer.reset();
    }

    /// Start an async pointer scan.
    ///
    /// This function returns immediately. Progress can be monitored via the shared buffer.
    pub fn start_scan_async(
        &mut self,
        target_address: u64,
        max_depth: u32,
        max_offset: u32,
        align: u32,
        regions: Vec<ScanRegion>,
        static_modules: Vec<VmStaticData>,
        is_layer_bfs: bool,
    ) -> Result<()> {
        if self.is_scanning() {
            self.last_error = ScanErrorCode::AlreadyScanning;
            return Err(anyhow!("Scan already in progress"));
        }

        if regions.is_empty() {
            self.last_error = ScanErrorCode::InvalidAddress;
            return Err(anyhow!("No memory regions provided"));
        }

        // Update config
        self.config = PointerScanConfig {
            target_address,
            max_depth,
            max_offset,
            align,
            is_layer_bfs,
            data_start: true,
            bss_start: false,
        };

        // Reset state
        self.clear();
        self.current_phase = ScanPhase::ScanningPointers;
        self.shared_buffer.write_phase(ScanPhase::ScanningPointers);

        // Create cancellation token
        let cancel_token = CancellationToken::new();
        self.cancel_token = Some(cancel_token.clone());

        // Clone data for the async task
        let config = self.config.clone();
        let cache_dir = self.cache_dir.clone();

        if log_enabled!(Level::Debug) {
            info!(
                "Starting pointer scan: target=0x{:X}, depth={}, offset=0x{:X}, regions={}",
                target_address,
                max_depth,
                max_offset,
                regions.len()
            );
        }

        // Spawn the scan task
        let handle = TOKIO_RUNTIME.spawn(async move {
            Self::run_scan_task(config, regions, static_modules, cache_dir, cancel_token).await;
        });

        self.scan_handle = Some(handle);
        Ok(())
    }

    /// The async scan task that runs Phase 1 and Phase 2.
    async fn run_scan_task(
        config: PointerScanConfig,
        regions: Vec<ScanRegion>, // 包含了static_modules
        static_modules: Vec<VmStaticData>,
        cache_dir: PathBuf,
        cancel_token: CancellationToken,
    ) {
        let check_cancelled = || cancel_token.is_cancelled();

        // Phase 1: Scan for pointers
        if log_enabled!(Level::Debug) {
            info!("Phase 1: Scanning for pointers...");
        }

        let cancel_token_clone = cancel_token.clone();
        let pointer_lib_result = tokio::task::spawn_blocking({
            let config = config.clone();
            let cache_dir = cache_dir.clone();
            move || {
                scanner::scan_all_pointers(
                    &regions,
                    &config,
                    &cache_dir,
                    |done, total, found| {
                        if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
                            manager.shared_buffer.update_scanning_progress(done as i32, total as i32, found);
                        }
                    },
                    || cancel_token_clone.is_cancelled(),
                )
            }
        })
        .await;

        // Check cancellation
        if check_cancelled() {
            if log_enabled!(Level::Debug) {
                info!("Scan cancelled during Phase 1");
            }
            if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                manager.current_phase = ScanPhase::Cancelled;
                manager.shared_buffer.write_phase(ScanPhase::Cancelled);
            }
            return;
        }

        // Process Phase 1 result
        let pointer_lib = match pointer_lib_result {
            Ok(Ok(lib)) => lib,
            Ok(Err(e)) => {
                error!("Phase 1 failed: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::MemoryReadFailed;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::MemoryReadFailed);
                }
                return;
            },
            Err(e) => {
                error!("Phase 1 task panicked: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
                return;
            },
        };

        if log_enabled!(Level::Debug) {
            info!("Phase 1 complete. Found {} pointers", pointer_lib.len());
        }

        // Update phase
        if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
            manager.current_phase = ScanPhase::BuildingChains;
            manager.shared_buffer.write_phase(ScanPhase::BuildingChains);
        }

        // Phase 2: Build chains
        if log_enabled!(Level::Debug) {
            info!("Phase 2: Building pointer chains...");
        }

        let chains_result = chain_builder::build_pointer_chains(
            &pointer_lib,
            &static_modules,
            &config,
            |depth, max_depth, chains_found| {
                if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
                    manager
                        .shared_buffer
                        .update_building_progress(depth as i32, max_depth, chains_found);
                }
            },
            || check_cancelled(),
        );

        // Check cancellation
        if check_cancelled() {
            if log_enabled!(Level::Debug) {
                info!("Scan cancelled during Phase 2");
            }

            if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                manager.current_phase = ScanPhase::Cancelled;
                manager.shared_buffer.write_phase(ScanPhase::Cancelled);
            }
            return;
        }

        // Store results
        match chains_result {
            Ok(chains) => {
                if log_enabled!(Level::Debug) {
                    info!("Phase 2 complete. Found {} chains", chains.len());
                }
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.pointer_library = Some(pointer_lib);
                    manager.chain_results = chains;
                    manager.current_phase = ScanPhase::Completed;
                    manager.shared_buffer.write_phase(ScanPhase::Completed);
                    manager.shared_buffer.write_progress(100);
                    manager.shared_buffer.write_chains_found(manager.chain_results.len() as i64);
                }
            },
            Err(e) => {
                error!("Phase 2 failed: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
            },
        }

        if log_enabled!(Level::Debug) {
            info!("Pointer scan task completed");
        }
    }
}

impl Default for PointerScanManager {
    fn default() -> Self {
        Self::new()
    }
}
