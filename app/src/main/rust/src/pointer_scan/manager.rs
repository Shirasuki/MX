//! Pointer Scan Manager
//!
//! This module provides the main entry point for pointer scanning.
//! It coordinates Phase 1 (pointer scanning) and Phase 2 (chain building),
//! manages async execution, and provides JNI-accessible state.

use crate::core::globals::TOKIO_RUNTIME;
use crate::pointer_scan::chain_builder::{BfsV3Scanner, ProgressPhase};
use crate::pointer_scan::mapqueue_v2;
use crate::pointer_scan::scanner::ScanRegion;
use crate::pointer_scan::shared_buffer::PointerScanSharedBuffer;
use crate::pointer_scan::types::{PointerScanConfig, ScanErrorCode, ScanPhase, VmStaticData};
use anyhow::{anyhow, Result};
use lazy_static::lazy_static;
use log::{error, info, log_enabled, Level};
use std::path::PathBuf;
use std::sync::RwLock;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

lazy_static! {
    pub static ref POINTER_SCAN_MANAGER: RwLock<PointerScanManager> = RwLock::new(PointerScanManager::new());
}

/// 扫描完成结果
#[derive(Debug, Clone)]
pub struct ScanCompleteResult {
    /// 找到的指针链数量
    pub total_count: usize,
    /// 输出文件路径
    pub output_file: String,
}

/// Manages pointer scan operations.
pub struct PointerScanManager {
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
    /// 扫描完成结果
    scan_result: Option<ScanCompleteResult>,
}

impl PointerScanManager {
    /// Create a new manager instance.
    pub fn new() -> Self {
        Self {
            config: PointerScanConfig::default(),
            shared_buffer: PointerScanSharedBuffer::new(),
            cancel_token: None,
            scan_handle: None,
            cache_dir: PathBuf::from("/data/data/moe.fuqiuluo.mamu/cache"),
            current_phase: ScanPhase::Idle,
            last_error: ScanErrorCode::None,
            scan_result: None,
        }
    }

    /// Initialize the manager with a cache directory.
    pub fn init(&mut self, cache_dir: String) -> Result<()> {
        self.cache_dir = PathBuf::from(&cache_dir);
        if !self.cache_dir.exists() {
            std::fs::create_dir_all(&self.cache_dir)?;
        }

        // 设置 MapQueue 的缓存目录（Android 兼容）
        mapqueue_v2::set_cache_dir(&cache_dir)?;

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

    /// Get scan result (total count and output file path)
    pub fn get_scan_result(&self) -> Option<ScanCompleteResult> {
        self.scan_result.clone()
    }

    /// Clear all results and reset state.
    pub fn clear(&mut self) {
        self.current_phase = ScanPhase::Idle;
        self.last_error = ScanErrorCode::None;
        self.shared_buffer.reset();
        self.scan_result = None;
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
        _is_layer_bfs: bool, // 不再使用，保留参数兼容性
        max_results: u32,
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
            is_layer_bfs: true, // 始终使用 BFS V2
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
            Self::run_scan_task(config, regions, static_modules, cache_dir, cancel_token, max_results).await;
        });

        self.scan_handle = Some(handle);
        Ok(())
    }

    /// The async scan task that runs V3 scanner (merged Phase 1 + Phase 2).
    async fn run_scan_task(
        config: PointerScanConfig,
        regions: Vec<ScanRegion>,
        static_modules: Vec<VmStaticData>,
        _cache_dir: PathBuf,
        cancel_token: CancellationToken,
        max_results: u32,
    ) {
        // 生成输出文件路径
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let output_path = PathBuf::from(format!(
            "/sdcard/pointer_scan_0x{:X}_{}.txt",
            config.target_address,
            timestamp
        ));

        let cancel_token_clone = cancel_token.clone();
        let output_path_clone = output_path.clone();

        let scan_result = tokio::task::spawn_blocking(move || {
            let scanner = BfsV3Scanner::new(config, regions, static_modules);

            // 0 表示无限制
            let effective_max = if max_results == 0 { usize::MAX } else { max_results as usize };

            scanner.run(
                output_path_clone,
                effective_max,
                |phase, current, total, extra| {
                    if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
                        match phase {
                            ProgressPhase::ScanningPointers => {
                                manager.shared_buffer.update_scanning_progress(
                                    current as i32,
                                    total as i32,
                                    extra,
                                );
                            }
                            ProgressPhase::BuildingChains => {
                                // 首次进入 Phase 2 时更新阶段
                                if current == 0 {
                                    manager.shared_buffer.write_phase(ScanPhase::BuildingChains);
                                }
                                manager.shared_buffer.update_building_progress(
                                    current as i32,
                                    total as i32,
                                    extra,
                                );
                            }
                            ProgressPhase::WritingFile => {
                                if current == 0 {
                                    manager.shared_buffer.write_phase(ScanPhase::WritingFile);
                                }
                                manager.shared_buffer.update_writing_progress(
                                    current as i32,
                                    total as i32,
                                    extra,
                                );
                            }
                        }
                    }
                },
                || cancel_token_clone.is_cancelled(),
            )
        })
        .await;

        // 检查取消
        if cancel_token.is_cancelled() {
            if log_enabled!(Level::Debug) {
                info!("Scan cancelled");
            }
            if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                manager.current_phase = ScanPhase::Cancelled;
                manager.shared_buffer.write_phase(ScanPhase::Cancelled);
            }
            return;
        }

        // 处理结果
        match scan_result {
            Ok(Ok(result)) => {
                info!(
                    "V3 扫描完成: {} 条链, 输出到 {}",
                    result.total_count,
                    result.output_file.display()
                );
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.scan_result = Some(ScanCompleteResult {
                        total_count: result.total_count,
                        output_file: result.output_file.to_string_lossy().to_string(),
                    });
                    manager.current_phase = ScanPhase::Completed;
                    manager.shared_buffer.write_phase(ScanPhase::Completed);
                    manager.shared_buffer.write_progress(100);
                    manager.shared_buffer.write_chains_found(result.total_count as i64);
                }
            },
            Ok(Err(e)) => {
                error!("V3 扫描失败: {}", e);
                if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
                    manager.current_phase = ScanPhase::Error;
                    manager.last_error = ScanErrorCode::InternalError;
                    manager.shared_buffer.write_phase(ScanPhase::Error);
                    manager.shared_buffer.write_error_code(ScanErrorCode::InternalError);
                }
            },
            Err(e) => {
                error!("V3 扫描任务 panic: {}", e);
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
