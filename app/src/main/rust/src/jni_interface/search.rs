//! JNI methods for SearchEngine.

use crate::core::DRIVER_MANAGER;
use crate::ext::jni::{JniResult, JniResultExt};
use crate::search::SearchResultItem;
use crate::search::engine::{SEARCH_ENGINE_MANAGER, SHARED_BUFFER_SIZE, SearchProgressCallback};
use crate::search::parser::parse_search_query;
use crate::search::result_manager::SearchResultMode;
use crate::search::types::ValueType;
use anyhow::anyhow;
use jni::objects::{GlobalRef, JIntArray, JLongArray, JObject, JString, JValue};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jobjectArray};
use jni::{JNIEnv, JavaVM};
use jni_macro::jni_method;
use log::{Level, error, log_enabled, warn};
use std::ops::Not;
use std::sync::Arc;

struct JniCallback {
    vm: JavaVM,
    callback: GlobalRef,
}

impl SearchProgressCallback for JniCallback {
    fn on_search_complete(&self, total_found: usize, total_regions: usize, elapsed_millis: u64) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            let result = env.call_method(
                &self.callback,
                "onSearchComplete",
                "(JIJ)V",
                &[
                    JValue::Long(total_found as jlong),
                    JValue::Int(total_regions as jint),
                    JValue::Long(elapsed_millis as jlong),
                ],
            );

            if let Err(e) = result {
                error!("Failed to call onSearchComplete: {:?}", e);
            }
        }
    }
}

fn jint_to_value_type(value: jint) -> Option<ValueType> {
    match value {
        0 => Some(ValueType::Byte),
        1 => Some(ValueType::Word),
        2 => Some(ValueType::Dword),
        3 => Some(ValueType::Qword),
        4 => Some(ValueType::Float),
        5 => Some(ValueType::Double),
        6 => Some(ValueType::Auto),
        7 => Some(ValueType::Xor),
        _ => None,
    }
}

fn format_value(bytes: &[u8], typ: ValueType) -> String {
    match typ {
        ValueType::Byte => {
            if bytes.len() >= 1 {
                format!("{}", bytes[0])
            } else {
                "N/A".to_string()
            }
        },
        ValueType::Word => {
            if bytes.len() >= 2 {
                let value = u16::from_le_bytes([bytes[0], bytes[1]]);
                format!("{}", value)
            } else {
                "N/A".to_string()
            }
        },
        ValueType::Dword | ValueType::Auto | ValueType::Xor => {
            if bytes.len() >= 4 {
                let value = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                format!("{}", value)
            } else {
                "N/A".to_string()
            }
        },
        ValueType::Qword => {
            if bytes.len() >= 8 {
                let value = u64::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]]);
                format!("{}", value)
            } else {
                "N/A".to_string()
            }
        },
        ValueType::Float => {
            if bytes.len() >= 4 {
                let value = f32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
                format!("{}", value)
            } else {
                "N/A".to_string()
            }
        },
        ValueType::Double => {
            if bytes.len() >= 8 {
                let value = f64::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]]);
                format!("{}", value)
            } else {
                "N/A".to_string()
            }
        },
    }
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeInitSearchEngine", "(JLjava/lang/String;J)Z")]
pub fn jni_init_search_engine(mut env: JNIEnv, _class: JObject, memory_buffer_size: jlong, cache_dir: JString, chunk_size: jlong) -> jboolean {
    (|| -> JniResult<jboolean> {
        let cache_dir_str: String = env.get_string(&cache_dir)?.into();

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.init(memory_buffer_size as usize, cache_dir_str, chunk_size as usize)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Sets the shared buffer for progress communication. Requires at least 32 bytes.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeSetSharedBuffer", "(Ljava/nio/ByteBuffer;)Z")]
pub fn jni_set_shared_buffer(mut env: JNIEnv, _class: JObject, buffer: JObject) -> jboolean {
    (|| -> JniResult<jboolean> {
        let buffer = (&buffer).into();
        let ptr = env.get_direct_buffer_address(buffer)?;
        let capacity = env.get_direct_buffer_capacity(buffer)?;

        if capacity < SHARED_BUFFER_SIZE {
            return Err(anyhow!("Buffer too small, need at least {} bytes", SHARED_BUFFER_SIZE));
        }

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        if manager.set_shared_buffer(ptr, capacity) {
            Ok(JNI_TRUE)
        } else {
            Err(anyhow!("Failed to set shared buffer"))
        }
    })()
    .or_throw(&mut env)
}

/// Clears the shared buffer.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeClearSharedBuffer", "()V")]
pub fn jni_clear_shared_buffer(mut env: JNIEnv, _class: JObject) {
    (|| -> JniResult<()> {
        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.clear_shared_buffer();
        Ok(())
    })()
    .or_throw(&mut env)
}

/// Starts an async search. Returns immediately. Progress is communicated via the shared buffer.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeStartSearchAsync", "(Ljava/lang/String;I[JZZ)Z")]
pub fn jni_start_search_async(
    mut env: JNIEnv,
    _class: JObject,
    query_str: JString,
    default_type: jint,
    regions: JLongArray,
    use_deep_search: jboolean,
    keep_results: jboolean,
) -> jboolean {
    (|| -> JniResult<jboolean> {
        let query: String = env.get_string(&query_str)?.into();

        let value_type = jint_to_value_type(default_type).ok_or_else(|| anyhow!("Invalid value type: {}", default_type))?;

        let search_query = parse_search_query(&query, value_type).map_err(|e| anyhow!("Parse error: {}", e))?;

        let regions_len = env.get_array_length(&regions)? as usize;
        if regions_len % 2 != 0 {
            return Err(anyhow!("Regions array length must be even"));
        }

        let mut regions_buf = vec![0i64; regions_len];
        env.get_long_array_region(&regions, 0, &mut regions_buf)?;

        let memory_regions: Vec<(u64, u64)> = regions_buf.chunks(2).map(|chunk| (chunk[0] as u64, chunk[1] as u64)).collect();

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.start_search_async(search_query, memory_regions, use_deep_search != JNI_FALSE, keep_results != JNI_FALSE)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Starts an async refine search. Returns immediately.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeStartRefineAsync", "(Ljava/lang/String;I)Z")]
pub fn jni_start_refine_async(mut env: JNIEnv, _class: JObject, query_str: JString, default_type: jint) -> jboolean {
    (|| -> JniResult<jboolean> {
        let query: String = env.get_string(&query_str)?.into();

        let value_type = jint_to_value_type(default_type).ok_or_else(|| anyhow!("Invalid value type: {}", default_type))?;

        let search_query = parse_search_query(&query, value_type).map_err(|e| anyhow!("Parse error: {}", e))?;

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.start_refine_async(search_query)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Checks if a search is currently running.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeIsSearching", "()Z")]
pub fn jni_is_searching(mut env: JNIEnv, _class: JObject) -> jboolean {
    (|| -> JniResult<jboolean> {
        let manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        Ok(if manager.is_searching() { JNI_TRUE } else { JNI_FALSE })
    })()
    .or_throw(&mut env)
}

/// Requests cancellation of the current search via CancellationToken.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeRequestCancel", "()V")]
pub fn jni_request_cancel(mut env: JNIEnv, _class: JObject) {
    (|| -> JniResult<()> {
        let manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        manager.request_cancel();
        Ok(())
    })()
    .or_throw(&mut env)
}

/// Legacy synchronous search method. Kept for backward compatibility.
#[jni_method(
    70,
    "moe/fuqiuluo/mamu/driver/SearchEngine",
    "nativeSearch",
    "(Ljava/lang/String;I[JZLmoe/fuqiuluo/mamu/driver/SearchProgressCallback;)J"
)]
pub fn jni_search(
    mut env: JNIEnv,
    _class: JObject,
    query_str: JString,
    default_type: jint,
    regions: JLongArray,
    use_deep_search: jboolean,
    callback_obj: JObject,
) -> jlong {
    (|| -> JniResult<jlong> {
        let query: String = env.get_string(&query_str)?.into();

        let value_type = jint_to_value_type(default_type).ok_or_else(|| anyhow!("Invalid value type: {}", default_type))?;

        let search_query = parse_search_query(&query, value_type).map_err(|e| anyhow!("Parse error: {}", e))?;

        let regions_len = env.get_array_length(&regions)? as usize;
        if regions_len % 2 != 0 {
            return Err(anyhow!("Regions array length must be even"));
        }

        let mut regions_buf = vec![0i64; regions_len];
        env.get_long_array_region(&regions, 0, &mut regions_buf)?;

        let memory_regions: Vec<(u64, u64)> = regions_buf.chunks(2).map(|chunk| (chunk[0] as u64, chunk[1] as u64)).collect();

        let callback: Option<Arc<dyn SearchProgressCallback>> = if callback_obj.is_null() {
            None
        } else {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(callback_obj)?;
            Some(Arc::new(JniCallback { vm, callback: global_ref }))
        };

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        let count = manager.search_memory(&search_query, &memory_regions, use_deep_search != JNI_FALSE, callback)?;

        Ok(count as jlong)
    })()
    .or_throw(&mut env)
}

#[jni_method(
    70,
    "moe/fuqiuluo/mamu/driver/SearchEngine",
    "nativeGetResults",
    "(II)[Lmoe/fuqiuluo/mamu/driver/SearchResultItem;"
)]
pub fn jni_get_results(mut env: JNIEnv, _class: JObject, start: jint, size: jint) -> jobjectArray {
    (|| -> JniResult<jobjectArray> {
        // Use warn level for diagnostic - easier to see in logcat
        if log_enabled!(Level::Debug) {
            warn!("jni_get_results called: start={}, size={}", start, size);
        }
        let search_manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        let current_mode = search_manager.get_current_mode()?;

        if log_enabled!(Level::Debug) {
            let total_count = search_manager.get_total_count().unwrap_or(0);
            // Diagnostic log - always print to help debug timing issues
            warn!("[DIAG] jni_get_results: mode={:?}, total_count={}, requesting start={}, size={}", current_mode, total_count, start, size);
        }
        let mut results = search_manager
            .get_results(start as usize, size as usize)?
            .into_iter()
            .enumerate()
            .map(|(index, value)| (index, value))
            .collect::<Vec<(usize, SearchResultItem)>>();

        if log_enabled!(Level::Debug) {
            warn!("[DIAG] jni_get_results: got {} results", results.len());
        }
        let filter = search_manager.get_filter();
        if filter.is_active() {
            results = results
                .into_iter()
                .filter(|(_idx, item)| {
                    if filter.enable_address_filter {
                        let addr = match item {
                            SearchResultItem::Exact(exact) => exact.address,
                            SearchResultItem::Fuzzy(fuzzy) => fuzzy.address,
                        };
                        if addr < filter.address_start || addr > filter.address_end {
                            return false;
                        }
                    }

                    if filter.enable_type_filter && filter.type_ids.is_empty().not() {
                        let typ = match item {
                            SearchResultItem::Exact(exact) => exact.typ,
                            SearchResultItem::Fuzzy(fuzzy) => fuzzy.value_type,
                        };
                        if !filter.type_ids.contains(&typ) {
                            return false;
                        }
                    }

                    true
                })
                .collect::<Vec<(usize, SearchResultItem)>>();
        }

        // 根据模式选择不同的 Java 类
        let (class, is_fuzzy) = match current_mode {
            SearchResultMode::Exact => {
                (env.find_class("moe/fuqiuluo/mamu/driver/ExactSearchResultItem")?, false)
            },
            SearchResultMode::Fuzzy => {
                (env.find_class("moe/fuqiuluo/mamu/driver/FuzzySearchResultItem")?, true)
            },
        };

        let array = env.new_object_array(results.len() as jint, &class, JObject::null())?;

        let driver_manager = DRIVER_MANAGER.read().map_err(|_| anyhow!("Failed to acquire DriverManager read lock"))?;

        for (i, (native_position, item)) in results.into_iter().enumerate() {
            let obj = match item {
                SearchResultItem::Exact(exact) => {
                    let value_str = {
                        let size = exact.typ.size();
                        let mut buffer = vec![0u8; size];

                        if driver_manager.read_memory_unified(exact.address, &mut buffer, None).is_ok() {
                            format_value(&buffer, exact.typ)
                        } else {
                            "N/A".to_string()
                        }
                    };

                    let value_jstring = env.new_string(&value_str)?;

                    env.new_object(
                        &class,
                        "(JJILjava/lang/String;)V",
                        &[
                            JValue::Long(native_position as i64),
                            JValue::Long(exact.address as i64),
                            JValue::Int(exact.typ.to_id()),
                            JValue::Object(&value_jstring),
                        ],
                    )?
                },
                SearchResultItem::Fuzzy(fuzzy) => {
                    let buffer = fuzzy.value.as_ref();
                    let current_value_str = format_value(&buffer, fuzzy.value_type);

                    let current_value_jstring = env.new_string(&current_value_str)?;

                    // data class FuzzySearchResultItem(
                    //     override val nativePosition: Long,
                    //     val address: Long,
                    //     val value: String,
                    //     val valueType: Int
                    // ): SearchResultItem
                    env.new_object(
                        &class,
                        "(JJLjava/lang/String;I)V",
                        &[
                            JValue::Long(native_position as i64),
                            JValue::Long(fuzzy.address as i64),
                            JValue::Object(&current_value_jstring),
                            JValue::Int(fuzzy.value_type.to_id()),
                        ],
                    )?
                },
            };
            env.set_object_array_element(&array, i as jint, obj)?;
        }

        Ok(array.into_raw())
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeGetTotalResultCount", "()J")]
pub fn jni_get_total_result_count(mut env: JNIEnv, _class: JObject) -> jlong {
    (|| -> JniResult<jlong> {
        let manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        let count = manager.get_total_count()?;
        if log_enabled!(Level::Debug) {
            log::debug!("jni_get_total_result_count: count = {}", count);
        }
        Ok(count as jlong)
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeClearSearchResults", "()V")]
pub fn jni_clear_result(mut env: JNIEnv, _class: JObject) {
    (|| -> JniResult<()> {
        if log_enabled!(Level::Debug) {
            warn!("jni_clear_result called - clearing all search results");
        }

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        manager.clear_results()?;

        Ok(())
    })()
    .or_throw(&mut env);
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeRemoveResult", "(I)Z")]
pub fn jni_remove_result(mut env: JNIEnv, _class: JObject, index: jint) -> jboolean {
    (|| -> JniResult<jboolean> {
        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.remove_result(index as usize)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeRemoveResults", "([I)Z")]
pub fn jni_remove_results(mut env: JNIEnv, _class: JObject, indices_array: JIntArray) -> jboolean {
    (|| -> JniResult<jboolean> {
        let len = env.get_array_length(&indices_array)? as usize;
        let mut indices_buf = vec![0i32; len];
        env.get_int_array_region(&indices_array, 0, &mut indices_buf)?;

        let indices: Vec<usize> = indices_buf.into_iter().map(|i| i as usize).collect();

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.remove_results_batch(indices)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeKeepOnlyResults", "([I)Z")]
pub fn jni_keep_only_results(mut env: JNIEnv, _class: JObject, indices_array: JIntArray) -> jboolean {
    (|| -> JniResult<jboolean> {
        let len = env.get_array_length(&indices_array)? as usize;
        let mut indices_buf = vec![0i32; len];
        env.get_int_array_region(&indices_array, 0, &mut indices_buf)?;

        let indices: Vec<usize> = indices_buf.into_iter().map(|i| i as usize).collect();

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.keep_only_results(indices)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeSetFilter", "(ZJJZ[I)V")]
pub fn jni_set_filter(
    mut env: JNIEnv,
    _class: JObject,
    enable_address_filter: jboolean,
    address_start: jlong,
    address_end: jlong,
    enable_type_ids_filter: jboolean,
    type_ids: JIntArray,
) {
    (|| -> JniResult<()> {
        let type_ids_len = env.get_array_length(&type_ids)? as usize;
        let mut type_ids_buf = vec![0i32; type_ids_len];
        env.get_int_array_region(&type_ids, 0, &mut type_ids_buf)?;

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.set_filter(
            enable_address_filter != JNI_FALSE,
            address_start as u64,
            address_end as u64,
            enable_type_ids_filter != JNI_FALSE,
            type_ids_buf,
        )?;

        Ok(())
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeClearFilter", "()V")]
pub fn jni_clear_filter(mut env: JNIEnv, _class: JObject) {
    (|| -> JniResult<()> {
        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.clear_filter()?;

        Ok(())
    })()
    .or_throw(&mut env)
}

#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeGetCurrentSearchMode", "()I")]
pub fn jni_get_current_search_mode(mut env: JNIEnv, _class: JObject) -> jint {
    (|| -> JniResult<jint> {
        let manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        let mode = manager.get_current_mode()?;
        let mode_value = match mode {
            crate::search::result_manager::SearchResultMode::Exact => 0,
            crate::search::result_manager::SearchResultMode::Fuzzy => 1,
        };

        Ok(mode_value)
    })()
    .or_throw(&mut env)
}

/// Sets compatibility mode.
/// When enabled, all search results are stored in fuzzy format,
/// allowing seamless switching between exact and fuzzy searches.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeSetCompatibilityMode", "(Z)V")]
pub fn jni_set_compatibility_mode(mut env: JNIEnv, _class: JObject, enabled: jboolean) {
    (|| -> JniResult<()> {
        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.set_compatibility_mode(enabled != JNI_FALSE);
        Ok(())
    })()
    .or_throw(&mut env)
}

/// Gets compatibility mode.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeGetCompatibilityMode", "()Z")]
pub fn jni_get_compatibility_mode(mut env: JNIEnv, _class: JObject) -> jboolean {
    (|| -> JniResult<jboolean> {
        let manager = SEARCH_ENGINE_MANAGER
            .read()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager read lock"))?;

        Ok(if manager.get_compatibility_mode() { JNI_TRUE } else { JNI_FALSE })
    })()
    .or_throw(&mut env)
}

/// Legacy synchronous refine search method.
#[jni_method(
    70,
    "moe/fuqiuluo/mamu/driver/SearchEngine",
    "nativeRefineSearch",
    "(Ljava/lang/String;ILmoe/fuqiuluo/mamu/driver/SearchProgressCallback;)J"
)]
pub fn jni_refine_search(mut env: JNIEnv, _class: JObject, query_str: JString, default_type: jint, callback_obj: JObject) -> jlong {
    (|| -> JniResult<jlong> {
        let query: String = env.get_string(&query_str)?.into();

        let value_type = jint_to_value_type(default_type).ok_or_else(|| anyhow!("Invalid value type: {}", default_type))?;

        let search_query = parse_search_query(&query, value_type).map_err(|e| anyhow!("Parse error: {}", e))?;

        let callback: Option<Arc<dyn SearchProgressCallback>> = if callback_obj.is_null() {
            None
        } else {
            let vm = env.get_java_vm()?;
            let global_ref = env.new_global_ref(callback_obj)?;
            Some(Arc::new(JniCallback { vm, callback: global_ref }))
        };

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        let count = manager.refine_search(&search_query, callback)?;

        Ok(count as jlong)
    })()
    .or_throw(&mut env)
}

/// Legacy method for backward compatibility. Use nativeSetSharedBuffer instead.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeSetProgressBuffer", "(Ljava/nio/ByteBuffer;)Z")]
pub fn jni_set_progress_buffer(mut env: JNIEnv, _class: JObject, buffer: JObject) -> jboolean {
    jni_set_shared_buffer(env, _class, buffer)
}

/// Legacy method for backward compatibility. Use nativeClearSharedBuffer instead.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeClearProgressBuffer", "()V")]
pub fn jni_clear_progress_buffer(mut env: JNIEnv, _class: JObject) {
    jni_clear_shared_buffer(env, _class)
}

/// Adds results from saved addresses. Clears existing results and adds new ones.
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeAddResultsFromAddresses", "([J[I)Z")]
pub fn jni_add_results_from_addresses(mut env: JNIEnv, _class: JObject, addresses_array: JLongArray, types_array: JIntArray) -> jboolean {
    (|| -> JniResult<jboolean> {
        let addr_len = env.get_array_length(&addresses_array)? as usize;
        let type_len = env.get_array_length(&types_array)? as usize;

        if addr_len != type_len {
            return Err(anyhow!("Address array and type array must have the same length"));
        }

        if addr_len == 0 {
            return Err(anyhow!("Address array is empty"));
        }

        let mut addresses = vec![0i64; addr_len];
        env.get_long_array_region(&addresses_array, 0, &mut addresses)?;

        let mut types = vec![0i32; type_len];
        env.get_int_array_region(&types_array, 0, &mut types)?;

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.clear_results()?;
        manager.set_result_mode(SearchResultMode::Exact)?;

        let mut results = Vec::with_capacity(addr_len);
        for i in 0..addr_len {
            let address = addresses[i] as u64;
            let type_id = types[i];
            let value_type = ValueType::from_id(type_id).ok_or_else(|| anyhow!("Invalid value type id: {}", type_id))?;
            results.push(SearchResultItem::new_exact(address, value_type));
        }

        manager.add_results_batch(results)?;

        if log_enabled!(Level::Debug) {
            log::debug!("Added {} results from saved addresses", addr_len);
        }

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Starts async fuzzy initial search. Records all values in memory regions.
///
/// Parameters:
/// - value_type: The value type to search for (0=Byte, 1=Word, 2=Dword, 3=Qword, 4=Float, 5=Double)
/// - regions: Array of [start1, end1, start2, end2, ...] memory region pairs
/// - keep_results: If true and currently in exact mode, convert exact results to fuzzy results
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeStartFuzzySearchAsync", "(I[JZ)Z")]
pub fn jni_start_fuzzy_search_async(mut env: JNIEnv, _class: JObject, value_type_id: jint, regions: JLongArray, keep_results: jboolean) -> jboolean {
    (|| -> JniResult<jboolean> {
        let value_type = jint_to_value_type(value_type_id).ok_or_else(|| anyhow!("Invalid value type: {}", value_type_id))?;

        let regions_len = env.get_array_length(&regions)? as usize;
        if regions_len % 2 != 0 {
            return Err(anyhow!("Regions array length must be even"));
        }

        let mut regions_buf = vec![0i64; regions_len];
        env.get_long_array_region(&regions, 0, &mut regions_buf)?;

        let memory_regions: Vec<(u64, u64)> = regions_buf.chunks(2).map(|chunk| (chunk[0] as u64, chunk[1] as u64)).collect();

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.start_fuzzy_search_async(value_type, memory_regions, keep_results != JNI_FALSE)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}

/// Starts async fuzzy refine search with a condition.
///
/// Parameters:
/// - condition_id: The fuzzy condition type
///   - 0: Initial (should not be used for refine)
///   - 1: Unchanged
///   - 2: Changed
///   - 3: Increased
///   - 4: Decreased
///   - 5: IncreasedBy(param1)
///   - 6: DecreasedBy(param1)
///   - 7: IncreasedByRange(param1, param2)
///   - 8: DecreasedByRange(param1, param2)
///   - 9: IncreasedByPercent(param1 / 100.0)
///   - 10: DecreasedByPercent(param1 / 100.0)
/// - param1: First parameter for conditions that need it
/// - param2: Second parameter for range conditions
#[jni_method(70, "moe/fuqiuluo/mamu/driver/SearchEngine", "nativeStartFuzzyRefineAsync", "(IJJ)Z")]
pub fn jni_start_fuzzy_refine_async(mut env: JNIEnv, _class: JObject, condition_id: jint, param1: jlong, param2: jlong) -> jboolean {
    use crate::search::types::FuzzyCondition;

    (|| -> JniResult<jboolean> {
        let condition = FuzzyCondition::from_id(condition_id, param1, param2).ok_or_else(|| anyhow!("Invalid fuzzy condition id: {}", condition_id))?;

        if condition.is_initial() {
            return Err(anyhow!("Cannot use Initial condition for refine search"));
        }

        let mut manager = SEARCH_ENGINE_MANAGER
            .write()
            .map_err(|_| anyhow!("Failed to acquire SearchEngineManager write lock"))?;

        manager.start_fuzzy_refine_async(condition)?;

        Ok(JNI_TRUE)
    })()
    .or_throw(&mut env)
}