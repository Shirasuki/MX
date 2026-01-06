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
