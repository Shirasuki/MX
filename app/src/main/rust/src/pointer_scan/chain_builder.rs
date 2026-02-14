//! 第二阶段：指针链构造器
//!
//! 本模块从目标地址反向构建指针链，追溯到静态模块。
//! 使用第一阶段构建的指针库来查找所有可能的路径。
//!
//! ## 算法
//! - BFS V2: MapQueue + PointerDir 隐式树（保留）
//! - BFS V3: 合并 Phase 1 + Phase 2，前缀和优化，统一 MapQueue 存储

pub mod bfs_v2;
pub mod bfs_v3;

// Re-export BFS V3 scanner as default
pub use bfs_v3::{BfsV3Scanner, ProgressPhase, ScanResult};
