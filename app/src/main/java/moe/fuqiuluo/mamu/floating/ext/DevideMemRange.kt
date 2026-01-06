package moe.fuqiuluo.mamu.floating.ext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moe.fuqiuluo.mamu.driver.MemRegionEntry
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange

suspend fun Array<MemRegionEntry>.divideToSimpleMemoryRangeParallel(): List<DisplayMemRegionEntry> =
    coroutineScope {
        val currentPid = WuwaDriver.currentBindPid
        if (!WuwaDriver.isProcessBound) return@coroutineScope emptyList()
        if (!WuwaDriver.isProcessAlive(currentPid)) return@coroutineScope emptyList()
        val procInfo = WuwaDriver.getProcessInfo(currentPid)
        val procName = procInfo.name

        // 分块并行处理，每块约 500 个
        val chunkSize = 500
        val chunks = this@divideToSimpleMemoryRangeParallel.toList().chunked(chunkSize)

        val classified = chunks.map { chunk ->
            async(Dispatchers.Default) {
                chunk.mapNotNull { entry ->
                    classifyRegion(entry, procName)
                }
            }
        }.awaitAll().flatten()

        // 后处理：将 .bss 段关联到前一个 lib（参考 C++ 实现 memextend.cpp:88-102）
//    var prevEntry: DisplayMemRegionEntry? = null
//    classified.map { entry ->
//        if (entry.range == MemoryRange.Cb && prevEntry != null &&
//            (prevEntry.range == MemoryRange.Cd ||
//             prevEntry.range == MemoryRange.Oa ||
//             prevEntry.range == MemoryRange.Xs ||
//             prevEntry.range == MemoryRange.Xa ||
//             prevEntry.range == MemoryRange.Xx)) {
//            // 提取库名（去掉路径）并加上 :bss 后缀
//            val libName = prevEntry.name.substringAfterLast('/')
//            entry.copy(name = "$libName:bss")
//        } else {
//            entry
//        }.also {
//            prevEntry = it
//        }
//    }

        return@coroutineScope classified
    }

fun Array<MemRegionEntry>.divideToSimpleMemoryRange(): List<DisplayMemRegionEntry> {
    val currentPid = WuwaDriver.currentBindPid
    if (!WuwaDriver.isProcessBound) return emptyList()
    if (!WuwaDriver.isProcessAlive(currentPid)) return emptyList()
    val procInfo = WuwaDriver.getProcessInfo(currentPid)

    val classified = mapNotNull { entry ->
        classifyRegion(entry, procInfo.name)
    }

    // 后处理：将 .bss 段关联到前一个 lib（参考 C++ 实现 memextend.cpp:88-102）
//    var prevEntry: DisplayMemRegionEntry? = null
//    return classified.map { entry ->
//        if (entry.range == MemoryRange.Cb && prevEntry != null &&
//            (prevEntry.range == MemoryRange.Cd ||
//             prevEntry.range == MemoryRange.Oa ||
//             prevEntry.range == MemoryRange.Xs ||
//             prevEntry.range == MemoryRange.Xa ||
//             prevEntry.range == MemoryRange.Xx)) {
//            // 提取库名（去掉路径）并加上 :bss 后缀
//            val libName = prevEntry.name.substringAfterLast('/')
//            entry.copy(name = "$libName:bss")
//        } else {
//            entry
//        }.also {
//            prevEntry = it
//        }
//    }

    return classified
}

private fun classifyRegion(entry: MemRegionEntry, procName: String): DisplayMemRegionEntry? {
    if (entry.start == entry.end) {
        return null
    }

    val range = run {
        if (!entry.isWritable && entry.isExecutable) {
            if (entry.name.contains(procName)) {
                return@run MemoryRange.Xa
            }

            // OAT files: executable segments only
            if ((entry.name.endsWith(".oat") && entry.name.startsWith("/data/misc"))) {
                if (entry.isExecutable)
                    return@run MemoryRange.Oa

                return@run MemoryRange.J
            }

            // JIT code cache: shared + executable
            if ((entry.name.contains("jit-cache") ||
                        entry.name.contains("jit-code-cache") ||
                        entry.name.contains("dalvik-jit")) &&
                (entry.isExecutable || entry.isShared)
            ) {
                return@run MemoryRange.Jc
            }

            if (entry.name.contains("/data/")) {
                MemoryRange.Xa
            }

            return@run MemoryRange.Xs
        }

        if (entry.name.isNotEmpty() && entry.name.startsWith("/dev/")) {
            if (
                entry.name.contains("/dev/mali", ignoreCase = true) ||
                entry.name.contains("/dev/kgsl", ignoreCase = true) ||
                entry.name.contains("/dev/nv") ||
                entry.name.contains("/dev/tegra") ||
                entry.name.contains("/dev/ion") ||
                entry.name.contains("/dev/pvr") ||
                entry.name.contains("/dev/render") ||
                entry.name.contains("/dev/galcore") ||
                entry.name.contains("/dev/fimg2d") ||
                entry.name.contains("/dev/quadd") ||
                entry.name.contains("/dev/graphics") ||
                entry.name.contains("/dev/mm_") ||
                entry.name.contains("/dev/dri/")
            ) {
                return@run MemoryRange.V
            }
        }

        if (entry.name.isNotEmpty()) {
            if (
                entry.name.startsWith("/dev/") &&
                entry.name.contains("/dev/xLog")
            ) {
                return@run MemoryRange.B
            }

            if (
                entry.name.startsWith("/system/fonts/") ||
                entry.name.startsWith("/product/fonts/") ||
                entry.name.startsWith("/data/data/com.google.android.gms/files/fonts/")
            ) {
                if (entry.isReadable && entry.isShared)
                    return@run MemoryRange.B
                else
                    return@run MemoryRange.O
            }
            if (
                entry.name.startsWith("anon_inode:dma_buf")
            ) {
                return@run MemoryRange.B
            }
        }


        if (entry.start == 0x10001000L && entry.isReadable && entry.isWritable) {
            return@run MemoryRange.S
        }

        if (entry.name.isNotEmpty()) {
            // Thread stack and TLS: readable + writable
            if ((entry.name.contains("[anon:stack_and_tls:") ||
                        entry.name.contains("[anon:thread signal stack]")) &&
                entry.isReadable && entry.isWritable
            ) {
                return@run MemoryRange.Ts
            }

            // VDEX files: readable + writable
            if ((entry.name.endsWith(".vdex")) &&
                entry.isReadable
            ) {
                return@run MemoryRange.Vx
            }

            // DEX/ODEX files: readable segments
            if ((entry.name.endsWith(".dex") ||
                        entry.name.endsWith(".odex") ||
                        entry.name.contains(".dex (del") ||
                        entry.name.contains(".odex (del")) &&
                entry.isReadable
            ) {
                return@run MemoryRange.Dx
            }

            if (entry.name.contains("[anon:.bss]")) {
                return@run MemoryRange.Cb
            }

            if (entry.name.startsWith("/system/")) {
                return@run MemoryRange.O
            }

            if (entry.name.startsWith("/dev/zero")) {
                return@run MemoryRange.O
            }

            if (entry.name.contains("PPSSPP_RAM")) {
                return@run MemoryRange.Ps
            }

            if (
                fun(name: String): Boolean {
                    if (name.isEmpty()) return false
                    if (name.contains("system@")) return false
                    if (name.contains("gralloc")) return false
                    if (name.startsWith("[vdso]")) return false
                    if (name.startsWith("[vectors]")) return false
                    if (name.startsWith("/dev/") && name.startsWith("/dev/ashmem")) return false
                    return true
                }(entry.name)
            ) {
                fun isDalvikSpecificChunk(name: String): Boolean {
                    val hit = name.contains("eap") ||
                            name.contains("dalvik-alloc") ||
                            name.contains("dalvik-main") ||
                            name.contains("dalvik-large") ||
                            name.contains("dalvik-free")
                    if (!hit) return false
                    if (name.contains("itmap")) return false
                    if (name.contains("ygote")) return false
                    if (name.contains("ard")) return false
                    if (name.contains("jit")) return false
                    if (name.contains("inear")) return false
                    return true
                }

                if (entry.name.contains("dalvik")) {
                    if (isDalvikSpecificChunk(entry.name)) {
                        return@run MemoryRange.Jh
                    } else {
                        return@run MemoryRange.J
                    }
                }

                if (entry.name.contains("/lib") && entry.name.contains(".so")) {
                    if (entry.name.contains(procName)) {
                        return@run MemoryRange.Cd
                    } else if (entry.name.contains("/data/")) {
                        return@run MemoryRange.Cd
                    }
                }

                if (entry.name.contains("malloc")) {
                    return@run MemoryRange.Ca
                }

                if (entry.name.contains("[heap]")) {
                    return@run MemoryRange.Ch
                }

                if (entry.name.contains("[stack")) {
                    return@run MemoryRange.S
                }

                if (entry.name.startsWith("/dev/ashmem") && !entry.name.contains("MemoryHeapBase")) {
                    return@run MemoryRange.As
                }
            }
        }

        if (entry.name.isEmpty()) {
            return@run MemoryRange.An
        }

        if (entry.nonProt) {
            return@run MemoryRange.Xx
        }

        MemoryRange.O
    }

    return DisplayMemRegionEntry(entry.start, entry.end, entry.type, entry.name, range)
}