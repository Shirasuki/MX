//! Global state management for core components

use crate::core::driver_manager::DriverManager;
use lazy_static::lazy_static;
use std::sync::RwLock;
use tokio::runtime::Runtime;

lazy_static! {
    pub static ref DRIVER_MANAGER: RwLock<DriverManager> = RwLock::new(DriverManager::new());

    /// Global tokio runtime for async tasks
    /// 使用多线程运行时，worker threads 数量为 CPU 核心数
    pub static ref TOKIO_RUNTIME: Runtime = Runtime::new().expect("Failed to create tokio runtime");
}

lazy_static! {
    pub static ref PAGE_SIZE: usize = {
        nix::unistd::sysconf(nix::unistd::SysconfVar::PAGE_SIZE)
            .ok()
            .flatten()
            .filter(|&size| size > 0)
            .map(|size| size as usize)
            .unwrap_or(4096)
    };
    pub static ref PAGE_MASK: usize = !(*PAGE_SIZE - 1);
}