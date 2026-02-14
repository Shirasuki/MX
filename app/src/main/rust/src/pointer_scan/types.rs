use rkyv::rancor::Error;
use rkyv::util::AlignedVec;
use rkyv::{deserialize, Archive, Deserialize, Serialize};

#[repr(C)]
#[derive(Archive, Deserialize, Serialize, Debug, PartialEq, Clone, Copy)]
#[rkyv(compare(PartialEq), derive(Debug))]
pub struct PointerData {
    /// The address where this pointer is located in memory
    pub address: u64,
    /// The value this pointer points to (target address)
    pub value: u64,
}

impl PointerData {
    /// Create a new pointer data entry
    pub fn new(address: u64, value: u64) -> Self {
        Self { address, value }
    }

    /// Get the pointer address
    pub fn address(&self) -> u64 {
        self.address
    }

    /// Get the pointer value (for sorting and searching)
    pub fn value(&self) -> u64 {
        self.value
    }
}

/// Memory region metadata for static module identification.
#[derive(Debug, Clone)]
pub struct VmStaticData {
    /// Module name (e.g., "libil2cpp.so")
    pub name: String,
    /// Module base address
    pub base_address: u64,
    /// Module end address
    pub end_address: u64,
    /// Module index (for modules loaded multiple times)
    pub index: u32,
    /// True if this is a static/code module
    pub is_static: bool,
    /// first module base address
    pub first_module_base_addr: u64,
}

impl VmStaticData {
    pub fn new(name: String, base_address: u64, end_address: u64, is_static: bool) -> Self {
        Self {
            name,
            base_address,
            end_address,
            index: 0,
            is_static,
            first_module_base_addr: 0,
        }
    }

    pub fn contains(&self, address: u64) -> bool {
        address >= self.base_address && address < self.end_address
    }

    pub fn offset_from_base(&self, address: u64) -> u64 {
        address.saturating_sub(self.base_address)
    }
}

/// A single step in a pointer chain.
#[derive(Debug, Clone)]
pub struct PointerChainStep {
    /// Module name if this is a static pointer, None if dynamic
    pub module_name: Option<String>,
    /// Module index (for duplicate module names)
    pub module_index: u32,
    /// Offset from module base (if static) or from previous pointer value
    pub offset: i64,
    /// True if this is the chain root (from static module)
    pub is_static: bool,
}

impl PointerChainStep {
    pub fn static_root(module_name: String, module_index: u32, offset: i64) -> Self {
        Self {
            module_name: Some(module_name),
            module_index,
            offset,
            is_static: true,
        }
    }

    pub fn dynamic_offset(offset: i64) -> Self {
        Self {
            module_name: None,
            module_index: 0,
            offset,
            is_static: false,
        }
    }
}

/// Complete pointer chain from a static module to the target address.
#[derive(Debug, Clone)]
pub struct PointerChain {
    /// Chain steps from root to target
    pub steps: Vec<PointerChainStep>,
    /// The final target address this chain points to
    pub target_address: u64,
}

impl PointerChain {
    pub fn new(target_address: u64) -> Self {
        Self {
            steps: Vec::new(),
            target_address,
        }
    }

    pub fn with_capacity(target_address: u64, capacity: usize) -> Self {
        Self {
            steps: Vec::with_capacity(capacity),
            target_address,
        }
    }

    pub fn push(&mut self, step: PointerChainStep) {
        self.steps.push(step);
    }

    pub fn depth(&self) -> usize {
        self.steps.len()
    }

    /// Format the chain as a string like "libil2cpp.so[0]+0x1A2B3C0->+0x18->-0x20"
    pub fn format(&self) -> String {
        if self.steps.is_empty() {
            return String::new();
        }

        let mut result = String::with_capacity(128);

        for (i, step) in self.steps.iter().enumerate() {
            if i == 0 {
                // First step should be static root
                if let Some(ref name) = step.module_name {
                    result.push_str(name);
                    result.push('[');
                    result.push_str(&step.module_index.to_string());
                    result.push_str("]+0x");
                    result.push_str(&format!("{:X}", step.offset));
                }
            } else {
                // Format signed offset
                if step.offset >= 0 {
                    result.push_str(&format!("->+0x{:X}", step.offset));
                } else {
                    result.push_str(&format!("->-0x{:X}", step.offset.abs()));
                }
            }
        }

        result
    }
}

/// Configuration for pointer scanning.
#[derive(Debug, Clone)]
pub struct PointerScanConfig {
    /// Target address to find pointers to
    pub target_address: u64,
    /// Maximum depth of pointer chain (default: 5)
    pub max_depth: u32,
    /// Maximum offset per level in bytes (default: 0x1000)
    pub max_offset: u32,
    /// Pointer alignment in bytes (default: 4)
    pub align: u32,
    /// Use Layer-BFS to build pointer chain
    pub is_layer_bfs: bool,
    /// lookup Base Addr from start of .data
    pub data_start: bool,
    /// lookup Base Addr from start of .bss
    pub bss_start: bool,
}

impl Default for PointerScanConfig {
    fn default() -> Self {
        Self {
            target_address: 0,
            max_depth: 5,
            max_offset: 0x1000,
            align: 4,
            is_layer_bfs: false,
            data_start: true,
            bss_start: false,
        }
    }
}

impl PointerScanConfig {
    pub fn new(target_address: u64) -> Self {
        Self {
            target_address,
            ..Default::default()
        }
    }

    pub fn with_depth(mut self, depth: u32) -> Self {
        self.max_depth = depth;
        self
    }

    pub fn with_offset(mut self, offset: u32) -> Self {
        self.max_offset = offset;
        self
    }

    pub fn with_align(mut self, align: u32) -> Self {
        self.align = align;
        self
    }
}

/// Scan phase enumeration for progress tracking.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ScanPhase {
    /// No scan in progress
    Idle = 0,
    /// Phase 1: Scanning all memory for valid pointers
    ScanningPointers = 1,
    /// Phase 2: Building pointer chains from target
    BuildingChains = 2,
    /// Scan completed successfully
    Completed = 3,
    /// Scan was cancelled by user
    Cancelled = 4,
    /// Scan encountered an error
    Error = 5,
    /// Phase 3: Writing chains to file
    WritingFile = 6,
}

impl From<i32> for ScanPhase {
    fn from(value: i32) -> Self {
        match value {
            0 => ScanPhase::Idle,
            1 => ScanPhase::ScanningPointers,
            2 => ScanPhase::BuildingChains,
            3 => ScanPhase::Completed,
            4 => ScanPhase::Cancelled,
            5 => ScanPhase::Error,
            6 => ScanPhase::WritingFile,
            _ => ScanPhase::Idle,
        }
    }
}

/// Error codes for pointer scanning.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ScanErrorCode {
    /// No error
    None = 0,
    /// Scanner not initialized
    NotInitialized = 1,
    /// Invalid target address
    InvalidAddress = 2,
    /// Memory read failed
    MemoryReadFailed = 3,
    /// Internal error
    InternalError = 4,
    /// Scan already in progress
    AlreadyScanning = 5,
    /// No process bound
    NoProcessBound = 6,
    /// Storage error (mmap failed)
    StorageError = 7,
}

// ============================================================================
// BFS V2 隐式树数据结构 (来自 PointerScan-rust)
// ============================================================================

use crate::pointer_scan::mapqueue_v2::MapQueue;

/// 指针目录：存储指针信息和索引范围
///
/// 用于 BFS 扫描中建立层级间的关联关系。
/// 通过 start/end 索引建立隐式树结构，避免显式存储父子关系。
///
/// 结构大小：24 bytes (比显式路径存储更紧凑)
#[derive(Debug, Clone, Copy, Default)]
#[repr(C)]
pub struct PointerDir {
    /// 指针地址
    pub address: u64,
    /// 指针指向的值
    pub value: u64,
    /// 索引起始（指向下一层的范围）
    pub start: u32,
    /// 索引结束 [start, end)
    pub end: u32,
}

impl PointerDir {
    pub fn new(address: u64, value: u64) -> Self {
        Self {
            address,
            value,
            start: 0,
            end: 1,
        }
    }

    pub fn with_range(address: u64, value: u64, start: u32, end: u32) -> Self {
        Self { address, value, start, end }
    }

    /// 从 PointerData 转换
    pub fn from_data(data: &PointerData) -> Self {
        Self::new(data.address, data.value)
    }

    /// 子节点数量
    #[inline]
    pub fn child_count(&self) -> u32 {
        self.end.saturating_sub(self.start)
    }
}

/// 内存范围类型
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum MemRange {
    Anonymous = 1,
    CHeap = 2,
    CAlloc = 4,
    CodeApp = 8,
    CodeSystem = 16,
    CBss = 32,
    CData = 64,
    Other = 128,
}

impl MemRange {
    /// 从名称和权限判断内存范围类型
    pub fn detect(name: &str, perms: &str) -> Self {
        if name.is_empty() {
            return Self::Anonymous;
        }

        if name == "[heap]" {
            return Self::CHeap;
        }

        if name.starts_with("[anon:libc_malloc") || name.starts_with("[anon:scudo:") {
            return Self::CAlloc;
        }

        // 先检测 Code_app（需要 x 权限）
        if name.contains("/data/app/") && perms.contains('x') && name.contains(".so") {
            return Self::CodeApp;
        }

        if name.contains("/system/framework/") {
            return Self::CodeSystem;
        }

        if name.contains("[anon:.bss]") {
            return Self::CBss;
        }

        // C_data 是 /data/app/ + .so 但没有 x 权限的区域
        if name.contains("/data/app/") && name.contains(".so") {
            return Self::CData;
        }

        Self::Other
    }

    /// 是否为静态区域（用于指针链扫描）
    pub fn is_static(&self) -> bool {
        matches!(self, Self::CData | Self::CodeApp)
    }
}

/// 内存区域数据（用于 BFS V2）
#[derive(Clone)]
pub struct VmAreaData {
    /// 起始地址
    pub start: u64,
    /// 结束地址
    pub end: u64,
    /// 内存范围类型
    pub range: MemRange,
    /// 模块名
    pub name: String,
    /// 模块计数（同名模块的序号）
    pub count: i32,
    /// 同名模块首段基址（用于计算统一偏移）
    pub first_base: u64,
}

impl VmAreaData {
    pub fn new(start: u64, end: u64, range: MemRange, name: String, count: i32) -> Self {
        Self { start, end, range, name, count, first_base: start }
    }

    pub fn from_static(vma: &VmStaticData) -> Self {
        Self {
            start: vma.base_address,
            end: vma.end_address,
            range: if vma.is_static { MemRange::CData } else { MemRange::Other },
            name: vma.name.clone(),
            count: vma.index as i32,
            first_base: vma.first_module_base_addr,
        }
    }

    /// 区域大小
    pub fn size(&self) -> u64 {
        self.end - self.start
    }
}

impl std::fmt::Debug for VmAreaData {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "VmArea {{ 0x{:x}-0x{:x} {:?} {}[{}] }}",
            self.start, self.end, self.range, self.name, self.count
        )
    }
}

/// 指针范围：关联静态模块和扫描结果
pub struct PointerRange {
    /// BFS 层级
    pub level: i32,
    /// 关联的内存区域
    pub vma: VmAreaData,
    /// 扫描结果（使用 MapQueue 避免内存爆炸）
    pub results: MapQueue<PointerDir>,
}

impl PointerRange {
    pub fn new(level: i32, vma: VmAreaData, results: MapQueue<PointerDir>) -> Self {
        Self { level, vma, results }
    }
}

/// 指针链信息：BFS 扫描的最终结果
///
/// 使用层级累计计数来高效遍历结果树
pub struct ChainInfo {
    /// 每层的累计计数
    pub counts: Vec<MapQueue<usize>>,
    /// 每层的指针目录内容（存储指针）
    pub contents: Vec<MapQueue<*const PointerDir>>,
}

impl ChainInfo {
    pub fn new(counts: Vec<MapQueue<usize>>, contents: Vec<MapQueue<*const PointerDir>>) -> Self {
        Self { counts, contents }
    }

    pub fn is_empty(&self) -> bool {
        self.counts.is_empty() || self.contents.is_empty()
    }

    pub fn level_count(&self) -> usize {
        self.contents.len()
    }
}

// Safety: PointerDir 内部只有基本类型，指针指向的是 mmap 区域
unsafe impl Send for ChainInfo {}
unsafe impl Sync for ChainInfo {}

/// 二进制文件头（用于保存/加载扫描结果）
#[derive(Debug, Clone, Copy)]
#[repr(C)]
pub struct ChainHeader {
    /// 签名
    pub sign: [u8; 128],
    /// 模块数量
    pub module_count: i32,
    /// 版本号
    pub version: i32,
    /// 指针大小（4 或 8）
    pub size: i32,
    /// 层级数 [0, level)
    pub level: i32,
}

impl Default for ChainHeader {
    fn default() -> Self {
        let mut sign = [0u8; 128];
        let sig = b".bin from mamu-pointer-scan\n";
        sign[..sig.len()].copy_from_slice(sig);

        Self {
            sign,
            module_count: 0,
            version: 101,
            size: 8,
            level: 0,
        }
    }
}

/// 模块符号信息
#[derive(Debug, Clone, Copy)]
#[repr(C)]
pub struct ChainSymbol {
    /// 起始地址
    pub start: u64,
    /// 模块名
    pub name: [u8; 64],
    /// 内存范围类型
    pub range: i32,
    /// 模块计数
    pub count: i32,
    /// 指针数量
    pub pointer_count: i32,
    /// 层级
    pub level: i32,
}

impl Default for ChainSymbol {
    fn default() -> Self {
        Self {
            start: 0,
            name: [0u8; 64],
            range: 0,
            count: 0,
            pointer_count: 0,
            level: 0,
        }
    }
}

impl ChainSymbol {
    pub fn set_name(&mut self, name: &str) {
        let bytes = name.as_bytes();
        let len = bytes.len().min(63);
        self.name[..len].copy_from_slice(&bytes[..len]);
        self.name[len] = 0;
    }

    pub fn get_name(&self) -> &str {
        let end = self.name.iter().position(|&b| b == 0).unwrap_or(64);
        std::str::from_utf8(&self.name[..end]).unwrap_or("")
    }
}

/// 层级长度信息
#[derive(Debug, Clone, Copy, Default)]
#[repr(C)]
pub struct ChainLevelLen {
    /// 模块数量
    pub module_count: i32,
    /// 元素数量
    pub count: u32,
    /// 层级
    pub level: i32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pointer_dir_size() {
        // 确保结构体大小符合预期（用于二进制兼容）
        assert_eq!(std::mem::size_of::<PointerDir>(), 24);
        assert_eq!(std::mem::size_of::<PointerData>(), 16);
    }

    #[test]
    fn test_chain_symbol_name() {
        let mut sym = ChainSymbol::default();
        sym.set_name("libtest.so");
        assert_eq!(sym.get_name(), "libtest.so");
    }

    #[test]
    fn test_mem_range_detect() {
        assert_eq!(MemRange::detect("", "rw-p"), MemRange::Anonymous);
        assert_eq!(MemRange::detect("[heap]", "rw-p"), MemRange::CHeap);
        assert_eq!(MemRange::detect("/data/app/com.test/lib/libtest.so", "r-xp"), MemRange::CodeApp);
        assert_eq!(MemRange::detect("/data/app/com.test/lib/libtest.so", "rw-p"), MemRange::CData);
    }
}
