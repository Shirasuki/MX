//! JNI methods for Disassembler

use anyhow::anyhow;
use crate::disasm::{Architecture, disassemble, disassemble_with_pseudo};
use crate::ext::jni::{JniResult, JniResultExt};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray, jsize};
use jni_macro::jni_method;
use log::{debug, error};

/// Converts DisassemblyResult to Java object
fn disasm_result_to_jobject<'l>(
    env: &mut JNIEnv<'l>,
    result: &crate::disasm::DisassemblyResult,
) -> JniResult<JObject<'l>> {
    let class = env.find_class("moe/fuqiuluo/mamu/driver/DisassemblyResult")?;

    // Convert bytes to hex string
    let bytes_hex = result
        .bytes
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<Vec<_>>()
        .join(" ");
    let bytes_str = env.new_string(bytes_hex)?;

    let mnemonic_str = env.new_string(&result.mnemonic)?;
    let operands_str = env.new_string(&result.operands)?;

    let pseudo_str = if let Some(ref pseudo) = result.pseudo_code {
        env.new_string(pseudo)?.into()
    } else {
        JObject::null()
    };

    // DisassemblyResult(address: Long, bytes: String, mnemonic: String, operands: String, pseudoCode: String?)
    Ok(env.new_object(
        class,
        "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
        &[
            (result.address as jlong).into(),
            (&bytes_str).into(),
            (&mnemonic_str).into(),
            (&operands_str).into(),
            (&pseudo_str).into(),
        ],
    )?)
}

#[jni_method(
    85,
    "moe/fuqiuluo/mamu/driver/Disassembler",
    "nativeDisassemble",
    "(I[BJI)[Lmoe/fuqiuluo/mamu/driver/DisassemblyResult;"
)]
pub fn jni_disassemble(
    mut env: JNIEnv,
    _obj: JObject,
    arch: jint,
    bytes: JByteArray,
    address: jlong,
    count: jint,
) -> jobjectArray {
    (|| -> JniResult<jobjectArray> {
        debug!("Disassemble: arch={}, address=0x{:x}, count={}", arch, address, count);

        // Convert architecture
        let architecture = Architecture::from_i32(arch)
            .map_err(|e| anyhow!("Invalid architecture: {}", e))?;

        // Get bytes
        let byte_array = env.convert_byte_array(&bytes)?;

        // Disassemble
        let results = disassemble(architecture, &byte_array, address as u64, count as usize)
            .map_err(|e| anyhow!("Disassembly failed: {}", e))?;

        debug!("Disassembled {} instructions", results.len());

        // Create result array
        let result_class = env.find_class("moe/fuqiuluo/mamu/driver/DisassemblyResult")?;
        let array = env.new_object_array(results.len() as jsize, result_class, JObject::null())?;

        // Fill array
        for (i, result) in results.iter().enumerate() {
            let obj = disasm_result_to_jobject(&mut env, result)?;
            env.set_object_array_element(&array, i as jsize, obj)?;
        }

        Ok(array.into_raw())
    })()
    .or_throw(&mut env)
}

#[jni_method(
    85,
    "moe/fuqiuluo/mamu/driver/Disassembler",
    "nativeGeneratePseudoCode",
    "(I[BJI)[Lmoe/fuqiuluo/mamu/driver/DisassemblyResult;"
)]
pub fn jni_generate_pseudo_code(
    mut env: JNIEnv,
    _obj: JObject,
    arch: jint,
    bytes: JByteArray,
    address: jlong,
    count: jint,
) -> jobjectArray {
    (|| -> JniResult<jobjectArray> {
        debug!(
            "Generate pseudo-code: arch={}, address=0x{:x}, count={}",
            arch, address, count
        );

        // Convert architecture
        let architecture = Architecture::from_i32(arch)
            .map_err(|e| anyhow!("Invalid architecture: {}", e))?;

        // Get bytes
        let byte_array = env.convert_byte_array(&bytes)?;

        // Disassemble with pseudo-code
        let results = disassemble_with_pseudo(architecture, &byte_array, address as u64, count as usize)
            .map_err(|e| anyhow!("Pseudo-code generation failed: {}", e))?;

        debug!("Generated pseudo-code for {} instructions", results.len());

        // Create result array
        let result_class = env.find_class("moe/fuqiuluo/mamu/driver/DisassemblyResult")?;
        let array = env.new_object_array(results.len() as jsize, result_class, JObject::null())?;

        // Fill array
        for (i, result) in results.iter().enumerate() {
            let obj = disasm_result_to_jobject(&mut env, result)?;
            env.set_object_array_element(&array, i as jsize, obj)?;
        }

        Ok(array.into_raw())
    })()
    .or_throw(&mut env)
}
