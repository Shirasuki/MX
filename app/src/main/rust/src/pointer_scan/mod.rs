//! Pointer Scan Module
//!
//! This module provides pointer scanning functionality for memory analysis.
//! It can find pointer chains from static modules to a target address,
//! which is useful for game memory hacking and reverse engineering.
//!
//! # Architecture
//!
//! The module is organized into the following components:
//!
//! - `types`: Core data structures (PointerData, PointerChain, etc.)
//! - `storage`: Memory-mapped storage for large pointer datasets
//! - `shared_buffer`: Progress communication with Kotlin via shared memory
//! - `scanner`: Phase 1 - Scan all memory for valid pointers
//! - `chain_builder`: Phase 2 - Build pointer chains from target address
//! - `manager`: Async task management and coordination
//!
//! # Usage
//!
//! ```ignore
//! use pointer_scan::manager::POINTER_SCAN_MANAGER;
//!
//! // Start a pointer scan
//! let mut manager = POINTER_SCAN_MANAGER.write().unwrap();
//! manager.start_scan_async(
//!     target_address,
//!     max_depth,
//!     max_offset,
//!     regions,
//!     static_modules,
//! )?;
//!
//! // Poll for results
//! let chains = manager.get_chain_results(0, 100);
//! ```

pub mod chain_builder;
pub mod manager;
pub mod scanner;
pub mod shared_buffer;
pub mod storage;
pub mod types;

// Re-export commonly used types
pub use manager::POINTER_SCAN_MANAGER;
pub use shared_buffer::PointerScanSharedBuffer;
pub use storage::MmapQueue;
pub use types::{*};
