#![allow(non_snake_case)]
pub mod core;
pub mod wuwa;
pub mod ext;
pub mod search;
pub mod disasm;
pub mod jni_interface;

use android_logger::Config;
use jni::sys::{jint, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use log::{info, LevelFilter};
use obfstr::obfstr as s;
use std::ffi::c_void;
use std::path::Path;

#[jni_macro::jni_onload(100)]
fn init_logger(_env: &mut JNIEnv, _vm: &JavaVM) {
    // 判断文件 /data/user/0/moe.fuqiuluo.mamu/files/log_enable 是否存在，存在则启用 debug 日志
    // 备选判断 /sdcard/mamu_log_enable
    // 备选判断 /data/user/999/moe.fuqiuluo.mamu/files/log_enable
    let log_level = if Path::new(s!("/data/user/0/moe.fuqiuluo.mamu/files/log_enable")).exists()
        || Path::new(s!("/sdcard/mamu_log_enable")).exists()
        || Path::new(s!("/data/user/999/moe.fuqiuluo.mamu/files/log_enable")).exists()
    {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    };

    android_logger::init_once(
        Config::default()
            .with_max_level(log_level)
            .with_tag(s!("MamuCore")),
    );
}

#[allow(non_snake_case)]
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let mut env = vm.get_env().expect(s!("Cannot get reference to the JNIEnv"));

    // Collect all registered initializers and sort by priority (descending)
    let mut initializers: Vec<_> = jni_macro::inventory::iter::<jni_macro::JniInitializer>().collect();
    initializers.sort_by_key(|init| std::cmp::Reverse(init.priority));

    // Execute all initializers in priority order
    for init in initializers {
        info!("Executing JNI initializer: {} (priority: {})", init.name, init.priority);
        (init.init_fn)(&mut env, &vm);
    }

    // Automatically register all methods marked with #[jni_method]
    // Methods are grouped by priority and class
    jni_macro::register_all_jni_methods(&mut env);

    info!("{}, env = {:?}", s!("Mamu核心载入成功！"), env);

    JNI_VERSION_1_6
}