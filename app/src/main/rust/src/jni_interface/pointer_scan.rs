//! JNI methods for PointerScanner.

use std::collections::HashMap;
use crate::ext::jni::{JniResult, JniResultExt};
use crate::pointer_scan::manager::POINTER_SCAN_MANAGER;
use crate::pointer_scan::scanner::ScanRegion;
use crate::pointer_scan::shared_buffer::SHARED_BUFFER_SIZE;
use crate::pointer_scan::types::{ScanPhase, VmStaticData};
use anyhow::anyhow;
use jni::objects::{JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use jni_macro::jni_method;
use log::{error, info, log_enabled, Level};

/// Initialize the pointer scanner with a cache directory.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeInit", "(Ljava/lang/String;)Z")]
pub fn jni_init_pointer_scanner(mut env: JNIEnv, _class: JObject, cache_dir: JString) -> jboolean {
    (|| -> JniResult<jboolean> {
        let cache_dir_str: String = env.get_string(&cache_dir)?.into();

        let mut manager = POINTER_SCAN_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire PointerScanManager write lock"))?;

        manager.init(cache_dir_str)?;

        info!("PointerScanner initialized successfully");
        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Set the shared buffer for progress communication.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeSetSharedBuffer", "(Ljava/nio/ByteBuffer;)Z")]
pub fn jni_set_pointer_scan_buffer(mut env: JNIEnv, _class: JObject, buffer: JObject) -> jboolean {
    (|| -> JniResult<jboolean> {
        let buffer = (&buffer).into();
        let ptr = env.get_direct_buffer_address(buffer)?;
        let capacity = env.get_direct_buffer_capacity(buffer)?;

        if capacity < SHARED_BUFFER_SIZE {
            return Err(anyhow!("Buffer too small, need at least {} bytes", SHARED_BUFFER_SIZE));
        }

        let mut manager = POINTER_SCAN_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire PointerScanManager write lock"))?;

        if manager.set_shared_buffer(ptr, capacity) {
            Ok(JNI_TRUE)
        } else {
            Err(anyhow!("Failed to set shared buffer"))
        }
    })()
    .or_throw(&mut env)
}

/// Start a pointer scan asynchronously.
///
/// # Arguments
/// * `target_address` - The address to find pointers to
/// * `max_depth` - Maximum depth of pointer chain
/// * `max_offset` - Maximum offset per level
/// * `align` - Pointer alignment
/// * `regions` - Memory regions as [start1, end1, start2, end2, ...]
/// * `region_names` - Names of the regions
/// * `static_flags` - Boolean flags indicating if each region is static
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeStartScan", "(JIII[J[Ljava/lang/String;[Z[IZI)Z")]
pub fn jni_start_pointer_scan(
    mut env: JNIEnv,
    _class: JObject,
    target_address: jlong,
    max_depth: jint,
    max_offset: jint,
    align: jint,
    regions: JLongArray,
    region_names: JObjectArray,
    static_flags: JObject, // jbooleanArray
    perm_flags: JIntArray,
    is_layer_bfs: jboolean,
    max_results: jint
) -> jboolean {
    (|| -> JniResult<jboolean> {
        // Parse regions
        let regions_len = env.get_array_length(&regions)? as usize;
        let region_count = regions_len / 2;

        let names_count = env.get_array_length(&region_names)? as usize;
        if names_count != region_count {
            return Err(anyhow!("Region count mismatch: {} regions but {} names", region_count, names_count));
        }

        // Get region data
        let mut region_data = vec![0i64; regions_len];
        env.get_long_array_region(&regions, 0, &mut region_data)?;

        // Get static flags
        let static_flags_array: JObject = static_flags;
        let static_flags_jarray = unsafe { jni::objects::JBooleanArray::from_raw(static_flags_array.as_raw()) };
        let flags_len = env.get_array_length(&static_flags_jarray)? as usize;
        let mut static_data = vec![0u8; flags_len];
        env.get_boolean_array_region(&static_flags_jarray, 0, &mut static_data)?;

        // Get permission flags
        let perm_len = env.get_array_length(&perm_flags)? as usize;
        let mut perm_data = vec![0i32; perm_len];
        env.get_int_array_region(&perm_flags, 0, &mut perm_data)?;

        const MEM_READABLE: i32 = 0x01;
        const MEM_WRITABLE: i32 = 0x02;

        let mut scan_regions = Vec::with_capacity(region_count);
        let mut static_modules = Vec::new();

        for i in 0..region_count {
            let start = region_data[i * 2] as u64;
            let end = region_data[i * 2 + 1] as u64;

            let name_obj = env.get_object_array_element(&region_names, i as i32)?;
            let name_jstr = JString::from(name_obj);
            let name: String = env.get_string(&name_jstr)?.into();

            let is_static = static_data[i] != 0;
            let perms = if i < perm_len { perm_data[i] } else { 0 };
            let is_readable = (perms & MEM_READABLE) != 0;
            let is_writable = (perms & MEM_WRITABLE) != 0;

            // 跳过不可读也不可写的段
            if !is_readable && !is_writable {
                continue;
            }

            scan_regions.push(ScanRegion {
                start,
                end,
                name: name.clone(),
            });

            if is_static {
                static_modules.push(VmStaticData::new(name, start, end, true));
            }
        }

        // Assign indices and first_module_base_addr to static modules with duplicate names
        // 同名模块共享第一个段的基址，用于计算统一的偏移
        let mut name_counts: HashMap<String, u32> = HashMap::new();
        let mut first_base_addrs: HashMap<String, u64> = HashMap::new();
        for module in &mut static_modules {
            let count = name_counts.entry(module.name.clone()).or_insert(0);
            module.index = *count;
            if *count == 0 {
                // 记录该名称第一个模块的基址
                first_base_addrs.insert(module.name.clone(), module.base_address);
            }
            // 所有同名模块共享第一个段的基址
            module.first_module_base_addr = *first_base_addrs.get(&module.name).unwrap();
            *count += 1;
        }

        if log_enabled!(Level::Debug) {
            info!("Static modules:");
            for module in &static_modules {
                info!("  {} [{}]: 0x{:X} - 0x{:X}", module.name, module.index, module.base_address, module.end_address);
            }
        }

        info!(
            "Starting pointer scan: target=0x{:X}, depth={}, offset=0x{:X}, regions={}, static_modules={}",
            target_address,
            max_depth,
            max_offset,
            scan_regions.len(),
            static_modules.len()
        );

        let mut manager = POINTER_SCAN_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire PointerScanManager write lock"))?;

        manager.start_scan_async(
            target_address as u64,
            max_depth as u32,
            max_offset as u32,
            align as u32,
            scan_regions,
            static_modules,
            is_layer_bfs == 1u8,
            max_results as u32
        )?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Check if a scan is currently in progress.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeIsScanning", "()Z")]
pub fn jni_is_scanning(_env: JNIEnv, _class: JObject) -> jboolean {
    match POINTER_SCAN_MANAGER.read() {
        Ok(manager) => {
            if manager.is_scanning() {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        },
        Err(_) => JNI_FALSE,
    }
}

/// Request cancellation of the current scan.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeRequestCancel", "()V")]
pub fn jni_cancel_pointer_scan(_env: JNIEnv, _class: JObject) {
    if let Ok(manager) = POINTER_SCAN_MANAGER.read() {
        manager.request_cancel();
    }
}

/// Get the number of chains found.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeGetChainCount", "()J")]
pub fn jni_get_chain_count(_env: JNIEnv, _class: JObject) -> jlong {
    match POINTER_SCAN_MANAGER.read() {
        Ok(manager) => {
            if let Some(result) = manager.get_scan_result() {
                result.total_count as jlong
            } else {
                0
            }
        },
        Err(_) => 0,
    }
}

/// Get the output file path of the scan result.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeGetOutputFilePath", "()Ljava/lang/String;")]
pub fn jni_get_output_file_path(mut env: JNIEnv, _class: JObject) -> jni::sys::jstring {
    (|| -> JniResult<jni::sys::jstring> {
        let manager = POINTER_SCAN_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire PointerScanManager read lock"))?;

        let path = manager.get_scan_result()
            .map(|r| r.output_file)
            .unwrap_or_default();

        let jstr = env.new_string(&path)?;
        Ok(jstr.into_raw())
    })()
    .or_throw(&mut env)
}

/// Get a range of chain results.
/// @deprecated Results are now written directly to file. Use nativeGetOutputFilePath() instead.
#[jni_method(
    70,
    "moe/fuqiuluo/mamu/driver/PointerScanner",
    "nativeGetChains",
    "(II)[Lmoe/fuqiuluo/mamu/driver/PointerChainResult;"
)]
pub fn jni_get_chains(mut env: JNIEnv, _class: JObject, _start: jint, _count: jint) -> jobjectArray {
    (|| -> JniResult<jobjectArray> {
        // 结果现在直接写入文件，此方法返回空数组
        // Results are now written directly to file, this method returns empty array
        let chain_class = env.find_class("moe/fuqiuluo/mamu/driver/PointerChainResult")?;
        let result_array = env.new_object_array(0, &chain_class, JObject::null())?;
        Ok(result_array.into_raw())
    })()
    .or_throw(&mut env)
}

/// Clear all scan results.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeClear", "()V")]
pub fn jni_clear_pointer_scan(_env: JNIEnv, _class: JObject) {
    if let Ok(mut manager) = POINTER_SCAN_MANAGER.write() {
        manager.clear();
    }
}

/// Get the current scan phase.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/PointerScanner", "nativeGetPhase", "()I")]
pub fn jni_get_phase(_env: JNIEnv, _class: JObject) -> jint {
    match POINTER_SCAN_MANAGER.read() {
        Ok(manager) => manager.get_phase() as jint,
        Err(_) => ScanPhase::Idle as jint,
    }
}
