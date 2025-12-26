# Mamu

**A memory modifier similar to GameGuardian**, written in Kotlin+Rust, based on [android-wuwa](https://github.com/fuqiuluo/android-wuwa)!

[![License](https://img.shields.io/badge/license-GPLv3-green.svg)](https://github.com/Shirasuki/MX/blob/master/LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-orange.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/language-Kotlin%20%2B%20Rust-purple.svg)](https://kotlinlang.org)

[中文文档](./README.zh-CN.md) | **English**

---

> **⚠️ CRITICAL SECURITY NOTICES**
>
> - **ROOT ACCESS REQUIRED**: This tool requires privileged system access
> - **EDUCATIONAL USE ONLY**: For research, learning, and authorized security testing purposes only
> - **LEGAL RESPONSIBILITY**: Users assume all liability for the use of this software
> - **DETECTION RISK**: May be detected by anti-cheat systems and security mechanisms
> - **NO WARRANTY**: Provided AS-IS without any warranty or guarantee
> - **USE RESPONSIBLY**: Respect software licenses, terms of service, and applicable laws

> **By using this software, you acknowledge:**
>
> - You have **authorized access** to the target application and device
> - You understand and accept **all legal risks** and responsibilities
> - You will **not use** this tool for illegal purposes
> - The developers assume **no liability** for misuse or damages
> - You will comply with all **applicable laws and regulations**

---

## Key Features

- **Real-Time Memory Search & Modification**
  - Exact value search (integers, floats, doubles, text, hex)
  - Fuzzy search for unknown values
  - Refined search to narrow down results
  - Batch modification and value freezing

- **Floating Window Interface**
  - **Search Tab**: Exact search/Group search/Fuzzy search with real-time progress
  - **Settings Tab**: Process binding, memory range configuration
  - **Saved Addresses Tab**: Bookmark and manage memory addresses
  - **Memory Preview Tab**: Hex dump visualization with ASCII representation
  - **Breakpoints Tab**: Debugging breakpoints and watchpoints (Not implemented)

- **Advanced Memory Access**
  - Multiple access modes: _NORMAL_, _WRITETHROUGH_, _NOCACHE_, _PGFAULT_
  - Permission-based memory region filtering
  - Automatic retry on access failures
  - Brand new memory region mapping analysis

## System Requirements

### Device Requirements

| Requirement          | Specification                           |
|----------------------|-----------------------------------------|
| **Operating System** | Android 7.0+ (API 24+)                  |
| **Architecture**     | ARM64-v8a (aarch64) only                |
| **Root Access**      | Required (Magisk, KernelSU, or SuperSU) |

### Build Requirements

| Requirement     | Version                           |
|-----------------|-----------------------------------|
| **Android SDK** | compileSdk 36, targetSdk 35       |
| **Android NDK** | Latest stable with LLVM toolchain |
| **Rust**        | Latest stable (1.70+)             |
| **Gradle**      | 8.13+                             |

## Installation

### Option A: Download Pre-built APK

> **Coming Soon**: Pre-built APK releases will be available on the [Releases](https://github.com/Shirasuki/MX/releases) page

1. Download the latest APK from the releases page
2. Enable "Install from Unknown Sources" in Android settings
3. Install the APK on your rooted device
4. Launch the app and grant root access when prompted
5. Allow all required permissions (most are auto-granted via root)

### Option B: Build from Source

See the [Building from Source](#building-from-source) section below for detailed instructions.

## Building from Source

### Prerequisites

#### Install Rust Toolchain

```bash
# Linux/macOS/WSL
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Windows (use rustup-init.exe from https://rustup.rs)
```

#### Add Android Target

```bash
rustup target add aarch64-linux-android
```

#### Set Environment Variables

**Linux/macOS:**
```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/<version>

# Example:
# export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/26.1.10909125
```

**Windows (PowerShell):**
```powershell
$env:ANDROID_SDK_ROOT = "C:\Users\<YourUsername>\AppData\Local\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_SDK_ROOT\ndk\<version>"
```

**Windows (Command Prompt):**
```cmd
set ANDROID_SDK_ROOT=C:\Users\<YourUsername>\AppData\Local\Android\Sdk
set ANDROID_NDK_HOME=%ANDROID_SDK_ROOT%\ndk\<version>
```

### Build Steps

#### Clone the Repository

```bash
git clone https://github.com/Shirasuki/MX.git
cd MX
```

#### Build Debug APK

```bash
# Automatically compiles Rust library and builds APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

#### Build Release APK

```bash
./gradlew assembleRelease

# Output: app/build/outputs/apk/release/app-release.apk
# Note: Requires signing configuration in build.gradle.kts
```

#### Install to Device

```bash
# Install debug build via ADB
./gradlew installDebug

# Or manually
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Manual Rust Build (Optional)

The Gradle build system automatically compiles the Rust library via the `buildRustAndroid` task. Manual compilation is rarely needed but can be done:

```bash
cd app/src/main/rust

# Debug build
cargo build --target aarch64-linux-android

# Release build (with optimizations)
cargo build --target aarch64-linux-android --release
```

**Build Flow**: The Gradle build automatically triggers `buildRustAndroid → copyRustLibs → preBuild`, ensuring the Rust library is compiled and copied to `app/src/main/jniLibs/arm64-v8a/libmamu_core.so` before the Android build begins.

### Troubleshooting Build Issues

| Issue                            | Solution                                                                   |
|----------------------------------|----------------------------------------------------------------------------|
| **NDK not found**                | Set `ANDROID_NDK_HOME` environment variable or install NDK via SDK Manager |
| **Rust target missing**          | Run `rustup target add aarch64-linux-android`                              |
| **Linker errors**                | Ensure NDK version has LLVM toolchain (r21+)                               |
| **Permission denied on gradlew** | Run `chmod +x gradlew` (Linux/macOS)                                       |

## Architecture Overview

Mamu uses a layered hybrid architecture combining Kotlin for UI/presentation and Rust for performance-critical operations.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│          UI Layer (Jetpack Compose + ViewBinding)       │
│   - Compose screens (HomeScreen, dialogs)              │
│   - XML layouts (Floating window, custom keyboard)     │
├─────────────────────────────────────────────────────────┤
│      Presentation Layer (Controllers + ViewModels)      │
│   - MainViewModel (StateFlow for home screen)          │
│   - FloatingControllers (Search, Settings, etc.)       │
│   - FloatingWindowStateManager (service state sync)    │
├─────────────────────────────────────────────────────────┤
│        Data/Repository Layer (DataSources)              │
│   - MMKV (configuration persistence)                   │
├─────────────────────────────────────────────────────────┤
│         JNI Bridge Layer (Kotlin Facades)               │
│   - WuwaDriver (process mgmt, memory regions, R/W)     │
│   - SearchEngine (memory search)                       │
└─────────────────────────────────────────────────────────┘
```

## Roadmap

### Planned Features

- [x] **Group Search**: Search for related values in memory
- [x] **Offset Calculator**: Calculate offsets between related addresses
- [ ] **Search History**: Save and replay previous search sessions
- [ ] **Base Conversion Tools**: Hex/Decimal/Binary/Octal converter
- [ ] **Script Automation**: Lua scripting support for automated operations
- [ ] **Multi-Architecture**: Support for arm, x86, x86_64 (beyond ARM64)
- [ ] **CSV Import/Export**: Batch import/export saved addresses
- [ ] **Memory Diff**: Compare memory snapshots over time
- [ ] **Speed Hack**: Modify game speed (time manipulation)
- [ ] **Breakpoint Debugging**: Debug processes with breakpoints
- [ ] **Imgui**: Support rendering script interfaces using imgui
- [ ] **Pointer Chain Scanning**
- [ ] **Plugin System for Extensions**

## Contributing

Contributions are welcome! Whether it's bug fixes, new features, documentation improvements, or translations, your help is appreciated!

### Special Thanks

- **GameGuardian** - Popular Android memory manipulation tool
- **Cheat Engine** - PC memory scanner and debugger
- Magisk/KernelSU for providing root access infrastructure
- niqiuqiux's [PointerScan](https://github.com/niqiuqiux/PointerScan) for C++ chain pointer scanning implementation!

---

## Disclaimer

**THIS SOFTWARE IS PROVIDED "AS IS" WITHOUT WARRANTY OF ANY KIND**, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM, OUT OF, OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

**By using Mamu, you acknowledge that:**

- You have read and understood this README and the LICENSE
- You accept all risks associated with memory manipulation
- You will use this tool responsibly and legally
- You understand that the developers assume no liability for any damages or legal consequences

**For educational and research purposes only. Use at your own risk.**

---

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](https://github.com/Shirasuki/MX/blob/master/LICENSE) file for the full license text.

**[⬆ Back to Top](#mamu)**
