import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moe.fuqiuluo.mamu"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "moe.fuqiuluo.mamu"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ViewModel and LiveData
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Material Icons Extended
    implementation(libs.androidx.compose.material.icons.extended)

    // MMKV for key-value storage
    implementation(libs.mmkv)

    // Traditional Android Views (needed for floating window)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Rust library integration
val coreRustBasePath = "$projectDir/src/main/rust"
val coreRustTargetPath = "$coreRustBasePath/target"

// 读取 Android SDK 路径
val localProperties = Properties()
val localPropertiesFile = File(rootProject.projectDir, "local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream -> localProperties.load(stream) }
}

// 获取 Android SDK 路径
val androidSdkRoot: String = System.getenv("ANDROID_SDK_ROOT")
    ?: System.getenv("ANDROID_HOME")
    ?: localProperties.getProperty("sdk.dir")
    ?: throw GradleException("Android SDK 路径未找到。请设置 ANDROID_SDK_ROOT 环境变量或在 local.properties 中配置 sdk.dir")

// 获取 NDK 路径
val ndkHome = System.getenv("ANDROID_NDK_HOME")
    ?: File(androidSdkRoot, "ndk").listFiles()
        ?.maxByOrNull { it.name }
        ?.absolutePath
    ?: throw GradleException("Android NDK 未找到。请安装 NDK 或设置 ANDROID_NDK_HOME 环境变量")

println("使用 Android SDK: $androidSdkRoot")
println("使用 Android NDK: $ndkHome")

fun isReleaseBuild(): Boolean {
    return gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = true)
    }
}

fun Exec.initAndroidNdkEnv() {
    // Android NDK configuration
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch")

    val ndkHostTag = when {
        hostOs.contains("windows") -> "windows-x86_64"
        hostOs.contains("mac") || hostOs.contains("darwin") -> {
            if (hostArch.contains("aarch64") || hostArch.contains("arm")) {
                "darwin-aarch64"  // Apple Silicon
            } else {
                "darwin-x86_64"   // Intel Mac
            }
        }

        hostOs.contains("linux") -> "linux-x86_64"
        else -> throw GradleException("Unsupported host OS: $hostOs")
    }

    val toolchain = File(ndkHome)
        .resolve("toolchains")
        .resolve("llvm")
        .resolve("prebuilt")
        .resolve(ndkHostTag)

    // Windows needs .cmd/.exe extensions, Unix-like systems don't
    val isWindows = hostOs.contains("windows")
    val clangSuffix = if (isWindows) ".cmd" else ""
    val arSuffix = if (isWindows) ".exe" else ""

    environment("ANDROID_NDK_HOME", ndkHome)
    environment(
        "CC_aarch64_linux_android", toolchain
            .resolve("bin")
            .resolve("aarch64-linux-android21-clang$clangSuffix")
            .absolutePath
    )
    environment(
        "AR_aarch64_linux_android", toolchain
            .resolve("bin")
            .resolve("llvm-ar$arSuffix")
            .absolutePath
    )
    environment(
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", toolchain
            .resolve("bin")
            .resolve("aarch64-linux-android21-clang$clangSuffix")
            .absolutePath
    )
}

// Build Rust library for Android
tasks.register<Exec>("buildRustAndroid") {
    workingDir = File(coreRustBasePath)
    initAndroidNdkEnv()

    val isRelease = isReleaseBuild()
    val buildMode = if (isRelease) "release" else "debug"

    val args = mutableListOf("cargo", "build", "--target", "aarch64-linux-android")
    if (isRelease) {
        args.add("--release")
    }

    commandLine(*args.toTypedArray())

    doFirst {
        println("构建 Mamu Core Rust 库 (${buildMode} 模式)")
    }
}

// Copy Rust .so files to jniLibs
tasks.register<Copy>("copyRustLibs") {
    dependsOn("buildRustAndroid")
    val isRelease = isReleaseBuild()
    val buildMode = if (isRelease) "release" else "debug"
    from(File(coreRustTargetPath).resolve("aarch64-linux-android").resolve(buildMode)) {
        include("*.so")
    }
    into("src/main/jniLibs/arm64-v8a")
}

// Ensure Rust libraries are built before Android build
tasks.named("preBuild") {
    dependsOn("copyRustLibs")
}

// Create jniLibs directory if it doesn't exist
tasks.register("createJniLibsDir") {
    doLast {
        file("src/main/jniLibs/arm64-v8a").mkdirs()
    }
}

tasks.named("copyRustLibs") {
    dependsOn("createJniLibsDir")
}