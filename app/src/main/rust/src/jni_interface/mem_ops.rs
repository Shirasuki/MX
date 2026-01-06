//! JNI methods for LocalMemoryOps

use jni::JNIEnv;
use jni::objects::{JByteArray, JObject};
use jni::sys::{jint, jlong};
use jni_macro::jni_method;
use std::alloc::{Layout, alloc, dealloc};
use std::ffi::c_void;
use std::ptr;
use nix::libc;

static mut PRACTICE_GLOBAL_INSTANCE: *mut c_void = ptr::null_mut();

/// Allocate memory of specified size (like malloc)
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeAlloc", "(I)J")]
pub fn jni_practice_alloc(_env: JNIEnv, _obj: JObject, size: jint) -> jlong {
    if size <= 0 {
        return 0;
    }

    let layout = Layout::from_size_align(size as usize, 8).unwrap();
    let ptr = unsafe { alloc(layout) };

    if ptr.is_null() {
        return 0;
    }

    let address = ptr as jlong;

    address
}

/// Free allocated memory
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeFree", "(JI)V")]
pub fn jni_practice_free(_env: JNIEnv, _obj: JObject, address: jlong, size: jint) {
    if address == 0 || size <= 0 {
        return;
    }

    let ptr = address as *mut u8;
    let layout = Layout::from_size_align(size as usize, 8).unwrap();

    unsafe {
        dealloc(ptr, layout);
    }
}

/// Read bytes from memory
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeRead", "(JI)[B")]
pub fn jni_practice_read<'l>(mut env: JNIEnv<'l>, _obj: JObject, address: jlong, size: jint) -> JByteArray<'l> {
    let ptr = address as *const u8;
    let bytes = unsafe { std::slice::from_raw_parts(ptr, size as usize) };

    let result = env.new_byte_array(size).unwrap();
    env.set_byte_array_region(&result, 0, bytemuck::cast_slice(bytes)).unwrap();

    result
}

/// Write bytes to memory
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeWrite", "(J[B)V")]
pub fn jni_practice_write(mut env: JNIEnv, _obj: JObject, address: jlong, data: JByteArray) {
    let len = env.get_array_length(&data).unwrap() as usize;
    let ptr = address as *mut u8;

    let mut buffer = vec![0i8; len];
    env.get_byte_array_region(&data, 0, &mut buffer).unwrap();

    unsafe {
        std::ptr::copy_nonoverlapping(buffer.as_ptr() as *const u8, ptr, len);
    }
}

/// Get current process ID
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeGetPid", "()I")]
pub fn jni_practice_get_pid(_env: JNIEnv, _obj: JObject) -> jint {
    std::process::id() as jint
}

/// Get Page Size
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeGetPageSize", "()I")]
pub fn jni_practice_get_page_size(_env: JNIEnv, _obj: JObject) -> jint {
    unsafe {
        let page_size = libc::sysconf(libc::_SC_PAGESIZE);

        page_size as jint
    }
}

/// Get the address of PRACTICE_GLOBAL_INSTANCE (static .data/.bss variable)
/// This is used as the base address for pointer chain tutorial
/// Returns the ADDRESS of the static variable itself (in .data/.bss), not its value
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeGetStaticBase", "()J")]
pub fn jni_practice_get_static_base(_env: JNIEnv, _obj: JObject) -> jlong {
    unsafe {
        ptr::addr_of!(PRACTICE_GLOBAL_INSTANCE) as jlong
    }
}

/// Set the value of PRACTICE_GLOBAL_INSTANCE (write pointer to static variable)
/// This is used to construct the pointer chain for tutorial
#[jni_method(70, "moe/fuqiuluo/mamu/driver/LocalMemoryOps", "nativeSetStaticBase", "(J)V")]
pub fn jni_practice_set_static_base(_env: JNIEnv, _obj: JObject, value: jlong) {
    unsafe {
        PRACTICE_GLOBAL_INSTANCE = value as *mut c_void;
    }
}