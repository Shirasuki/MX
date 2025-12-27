//! JNI methods for driver installation

use crate::core::globals::TOKIO_RUNTIME;
use crate::ext::jni::{JniResult, JniResultExt};
use anyhow::anyhow;
use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jsize};
use jni_macro::jni_method;
use lazy_static::lazy_static;
use log::{debug, error, info};
use obfstr::obfstr as s;
use obfstr::obfstring as ss;
use std::fs;
use std::io::{Cursor, Read, Write};
use std::path::Path;

lazy_static! {
    static ref DRIVER_LIST: Vec<(String, String)> = vec![
        (
            ss!("android12-5.10"),
            ss!("https://vip.123pan.cn/1818969283/ymjew503t0l000d7w32xmw1jz1hmciinDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android13-5.10"),
            ss!("https://vip.123pan.cn/1818969283/ymjew503t0n000d7w32yi31ktdcv3llyDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android13-5.15"),
            ss!("https://vip.123pan.cn/1818969283/yk6baz03t0m000d7w33gud8zyu2p4u4kDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android14-5.15"),
            ss!("https://vip.123pan.cn/1818969283/yk6baz03t0l000d7w33g1mjccon2owarDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android14-6.1"),
            ss!("https://vip.123pan.cn/1818969283/ymjew503t0m000d7w32y2f4o7g18l20eDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android15-6.6"),
            ss!("https://vip.123pan.cn/1818969283/ymjew503t0n000d7w32yi6kyrrilfmbrDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
        (
            ss!("android16-6.12"),
            ss!("https://vip.123pan.cn/1818969283/yk6baz03t0m000d7w33gvm7laq4fr9zsDIYPBdrzBIi1DvxPAIazAY==.png")
        ),
    ];
}

/// 检查驱动是否已安装
fn is_driver_installed(driver_name: &str) -> bool {
    if let Ok(modules) = fs::read_to_string(s!("/proc/modules")) {
        modules.lines().any(|line| line.starts_with(s!("wuwa")))
    } else {
        false
    }
}

/// 获取可用驱动列表
#[jni_method(
    90,
    "moe/fuqiuluo/mamu/driver/WuwaDriver",
    "nativeGetAvailableDrivers",
    "()[Lmoe/fuqiuluo/mamu/data/model/DriverInfo;"
)]
pub fn jni_get_available_drivers<'l>(mut env: JNIEnv<'l>, _obj: JObject) -> JObjectArray<'l> {
    (|| -> JniResult<JObjectArray<'l>> {
        let driver_info_class = env.find_class(s!("moe/fuqiuluo/mamu/data/model/DriverInfo"))?;
        let result_array = env.new_object_array(DRIVER_LIST.len() as jsize, &driver_info_class, JObject::null())?;

        for (i, (name, _url)) in DRIVER_LIST.iter().enumerate() {
            let jname = env.new_string(name)?;
            let jdisplay_name = env.new_string(format!("{} {}", s!("Driver"), name))?;

            let driver_info = env.new_object(
                &driver_info_class,
                s!("(Ljava/lang/String;Ljava/lang/String;Z)V"),
                &[(&jname).into(), (&jdisplay_name).into(), JNI_FALSE.into()],
            )?;

            env.set_object_array_element(&result_array, i as jsize, driver_info)?;
        }

        debug!("{}: {}", s!("获取驱动列表成功，数量"), DRIVER_LIST.len());

        Ok(result_array)
    })()
    .or_throw(&mut env)
}

/// 根据驱动名称查找URL
fn find_driver_url(driver_name: &str) -> Option<&str> {
    DRIVER_LIST.iter().find(|(name, _)| name == driver_name).map(|(_, url)| url.as_str())
}

/// 下载并安装驱动（通过Java层的RootFileSystem和Shell）
#[jni_method(
    90,
    "moe/fuqiuluo/mamu/driver/WuwaDriver",
    "nativeDownloadAndInstallDriver",
    "(Ljava/lang/String;)Lmoe/fuqiuluo/mamu/data/model/DriverInstallResult;"
)]
pub fn jni_download_and_install_driver<'l>(mut env: JNIEnv<'l>, _obj: JObject, driver_name: JString) -> JObject<'l> {
    (|| -> JniResult<JObject<'l>> {
        let driver_name_str: String = env.get_string(&driver_name)?.into();

        //debug!("{}: {}", s!("开始下载并安装驱动"), driver_name_str);

        // 根据驱动名称查找URL
        let url_str = find_driver_url(&driver_name_str)
            .ok_or_else(|| anyhow!("{}: {}", s!("未找到驱动"), driver_name_str))?
            .to_string();

        let install_result = TOKIO_RUNTIME.block_on(async {
            // 下载并解压，获取.ko文件内容
            //debug!("{}: {}", s!("开始下载驱动"), driver_name_str);
            let client = reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(300))
                .use_rustls_tls()
                .build()
                .map_err(|e| anyhow!("{}: {}", s!("创建HTTP客户端失败"), e))?;

            let response = client.get(&url_str).send().await.map_err(|e| {
                let err_msg = format!("{}: {}", s!("下载请求失败"), e);
                if err_msg.contains(s!("123pan")) {
                    anyhow!("{}", s!("请检查您的网络连接，或者关闭一些代理软件!"))
                } else {
                    anyhow!("{}", err_msg)
                }
            })?;

            if !response.status().is_success() {
                return Err(anyhow!("{}: {}", s!("下载失败，HTTP状态码"), response.status()));
            }

            let zip_bytes = response.bytes().await.map_err(|e| anyhow!("{}: {}", s!("读取响应数据失败"), e))?;

            //info!("{}: {}, {}: {}", s!("驱动下载完成"), driver_name_str, s!("大小"), zip_bytes.len());

            // 直接从内存解压
            //debug!("{}: {}", s!("开始解压驱动"), driver_name_str);
            let cursor = Cursor::new(zip_bytes.as_ref());
            let mut archive = zip::ZipArchive::new(cursor).map_err(|e| anyhow!("{}: {}", s!("解析ZIP文件失败"), e))?;

            // 查找 .ko 文件
            let mut ko_file_data = None;
            let ko_filename = format!("{}{}", s!("android-wuwa-"), driver_name_str);

            for i in 0..archive.len() {
                let mut file = archive.by_index(i).map_err(|e| anyhow!("{}: {}", s!("读取ZIP条目失败"), e))?;

                if let Some(name) = file.name().rsplit('/').next().map(|it| it.to_string()) {
                    if name.contains(&ko_filename) && name.ends_with(s!(".ko")) {
                        let mut data = Vec::new();
                        Read::read_to_end(&mut file, &mut data).map_err(|e| anyhow!("{}: {}", s!("读取.ko文件失败"), e))?;
                        info!("{}: {}, {}: {}", s!("提取.ko文件成功"), name, s!("大小"), data.len());
                        ko_file_data = Some(data);
                        break;
                    }
                }
            }

            ko_file_data.ok_or_else(|| anyhow!("{}", s!("未在ZIP中找到.ko文件")))
        });

        match install_result {
            Ok(ko_data) => {
                // 调用Java层的RootFileSystem写文件
                let ko_temp_path = format!("{}{}", s!("/data/local/tmp/"), driver_name_str);
                let write_result = write_file_via_root(&mut env, &ko_temp_path, &ko_data);

                if let Err(e) = write_result {
                    let result_class = env.find_class(s!("moe/fuqiuluo/mamu/data/model/DriverInstallResult"))?;
                    let jmessage = env.new_string(&format!("{}: {}", s!("写入文件失败"), e))?;
                    return Ok(env.new_object(result_class, s!("(ZLjava/lang/String;)V"), &[JNI_FALSE.into(), (&jmessage).into()])?);
                }

                // 调用Java层的Shell执行insmod
                let insmod_cmd = format!("{} {}", s!("insmod"), ko_temp_path);
                let insmod_result = execute_shell_command(&mut env, &insmod_cmd);

                // 立即删除临时文件（无论成功失败）
                let _ = delete_file_via_root(&mut env, &ko_temp_path);

                match insmod_result {
                    Ok((success, message)) => {
                        // 检查dmesg内核日志以确认驱动载入状态
                        let dmesg_cmd = format!("{}", s!("dmesg | grep wuwa"));
                        let dmesg_output = match execute_shell_command(&mut env, &dmesg_cmd) {
                            Ok((_, output)) => output,
                            Err(e) => {
                                error!("{}: {}", s!("获取dmesg失败"), e);
                                String::new()
                            },
                        };

                        // 构建返回消息，包含内核日志
                        let final_message = if !dmesg_output.is_empty() {
                            if message.is_empty() {
                                format!("{}: {}", s!("内核日志"), dmesg_output)
                            } else {
                                format!("{}\n {}", message, dmesg_output)
                            }
                        } else {
                            message
                        };

                        if success {
                            info!("{}: {}", s!("驱动安装成功"), driver_name_str);
                        } else {
                            error!("{}: {}", s!("驱动安装失败"), final_message);
                        }

                        let result_class = env.find_class(s!("moe/fuqiuluo/mamu/data/model/DriverInstallResult"))?;
                        let jmessage = env.new_string(&final_message)?;
                        Ok(env.new_object(
                            result_class,
                            s!("(ZLjava/lang/String;)V"),
                            &[(if success { JNI_TRUE } else { JNI_FALSE }).into(), (&jmessage).into()],
                        )?)
                    },
                    Err(e) => {
                        error!("{}: {}", s!("执行insmod失败"), e);
                        let result_class = env.find_class(s!("moe/fuqiuluo/mamu/data/model/DriverInstallResult"))?;
                        let jmessage = env.new_string(&format!("{}: {}", s!("执行insmod失败"), e))?;
                        Ok(env.new_object(result_class, s!("(ZLjava/lang/String;)V"), &[JNI_FALSE.into(), (&jmessage).into()])?)
                    },
                }
            },
            Err(e) => {
                error!("{}: {}", s!("下载驱动失败"), e);
                let result_class = env.find_class(s!("moe/fuqiuluo/mamu/data/model/DriverInstallResult"))?;
                let jmessage = env.new_string(&e.to_string())?;
                Ok(env.new_object(result_class, s!("(ZLjava/lang/String;)V"), &[JNI_FALSE.into(), (&jmessage).into()])?)
            },
        }
    })()
    .or_throw(&mut env)
}

fn write_file_via_root(env: &mut JNIEnv, path: &str, data: &[u8]) -> anyhow::Result<()> {
    let root_fs_class = env.find_class(s!("moe/fuqiuluo/mamu/data/local/RootFileSystem"))?;

    let jpath = env.new_string(path)?;
    let jdata = env.byte_array_from_slice(data)?;
    let create_parent = JNI_TRUE;

    let result = env.call_static_method(
        root_fs_class,
        s!("writeFile"),
        s!("(Ljava/lang/String;[BZ)Z"),
        &[(&jpath).into(), (&jdata).into(), create_parent.into()],
    )?;

    let success = result.z()?;
    if success {
        debug!("{}: {}", s!("写入文件成功"), path);
        Ok(())
    } else {
        Err(anyhow!("{}", s!("RootFileSystem.writeFile返回false")))
    }
}

fn execute_shell_command(env: &mut JNIEnv, command: &str) -> anyhow::Result<(bool, String)> {
    let shell_class = env.find_class(s!("com/topjohnwu/superuser/Shell"))?;

    let string_class = env.find_class(s!("java/lang/String"))?;
    let commands_array = env.new_object_array(1, string_class, JObject::null())?;
    let jcommand = env.new_string(command)?;
    env.set_object_array_element(&commands_array, 0, jcommand)?;

    // Shell.cmd(String... commands)
    let cmd_result = env.call_static_method(
        shell_class,
        s!("cmd"),
        s!("([Ljava/lang/String;)Lcom/topjohnwu/superuser/Shell$Job;"),
        &[(&commands_array).into()],
    )?;

    let job_obj = cmd_result.l()?;
    let exec_result = env.call_method(job_obj, s!("exec"), s!("()Lcom/topjohnwu/superuser/Shell$Result;"), &[])?;
    let result_obj = exec_result.l()?;

    let is_success_result = env.call_method(&result_obj, s!("isSuccess"), s!("()Z"), &[])?;
    let success = is_success_result.z()?;

    // 获取输出信息
    let message = if success {
        // 获取 stdout
        let out_result = env.call_method(&result_obj, s!("getOut"), s!("()Ljava/util/List;"), &[])?;
        let out_list = out_result.l()?;
        java_list_to_string(env, out_list)?
    } else {
        // 获取 stderr
        let err_result = env.call_method(&result_obj, s!("getErr"), s!("()Ljava/util/List;"), &[])?;
        let err_list = err_result.l()?;
        java_list_to_string(env, err_list)?
    };

    Ok((success, message))
}

/// 通过Java层的RootFileSystem删除文件
fn delete_file_via_root(env: &mut JNIEnv, path: &str) -> anyhow::Result<()> {
    let root_fs_class = env.find_class(s!("moe/fuqiuluo/mamu/data/local/RootFileSystem"))?;

    let jpath = env.new_string(path)?;

    let result = env.call_static_method(root_fs_class, s!("delete"), s!("(Ljava/lang/String;)Z"), &[(&jpath).into()])?;

    let success = result.z()?;
    if success {
        debug!("{}: {}", s!("删除文件成功"), path);
    }
    Ok(())
}

/// 将Java List<String>转换为Rust String
fn java_list_to_string(env: &mut JNIEnv, list: JObject) -> anyhow::Result<String> {
    let size_result = env.call_method(&list, s!("size"), s!("()I"), &[])?;
    let size = size_result.i()?;

    let mut lines = Vec::new();
    for i in 0..size {
        let element_result = env.call_method(&list, s!("get"), s!("(I)Ljava/lang/Object;"), &[i.into()])?;
        let element_obj = element_result.l()?;

        if !element_obj.is_null() {
            let jstring = JString::from(element_obj);
            let rust_string: String = env.get_string(&jstring)?.into();
            lines.push(rust_string);
        }
    }

    Ok(lines.join("\n"))
}

/// 检查驱动安装状态
#[jni_method(90, "moe/fuqiuluo/mamu/driver/WuwaDriver", "nativeIsDriverInstalled", "()Z")]
pub fn jni_is_driver_installed(_env: JNIEnv, _obj: JObject) -> jboolean {
    if is_driver_installed(s!("wuwa")) { JNI_TRUE } else { JNI_FALSE }
}
