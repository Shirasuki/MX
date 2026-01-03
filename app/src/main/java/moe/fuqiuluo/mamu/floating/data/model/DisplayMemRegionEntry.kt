package moe.fuqiuluo.mamu.floating.data.model

import moe.fuqiuluo.mamu.driver.MemRegionEntry

data class DisplayMemRegionEntry(
    val start: Long, val end: Long, val type: Int, val name: String, val range: MemoryRange
) {
    companion object {
        const val MEM_READABLE = 0x01
        const val MEM_WRITABLE = 0x02
        const val MEM_EXECUTABLE = 0x04
        const val MEM_SHARED = 0x08

        /**
         * 从 MemRegionEntry 创建 DisplayMemRegionEntry
         */
        fun fromMemRegionEntry(region: MemRegionEntry): DisplayMemRegionEntry {
            return DisplayMemRegionEntry(
                start = region.start,
                end = region.end,
                type = region.type,
                name = region.name,
                range = determineMemoryRange(region)
            )
        }

        /**
         * 根据内存区域信息判断其所属的 MemoryRange 类型
         */
        private fun determineMemoryRange(region: MemRegionEntry): MemoryRange {
            val name = region.name.lowercase()
            val isExec = region.isExecutable
            val isWrite = region.isWritable
            val isRead = region.isReadable

            // 无权限区域
            if (!isRead && !isWrite && !isExec) {
                return MemoryRange.Xx
            }

            return when {
                // Java Heap - dalvik 相关
                name.contains("[anon:dalvik-main space") -> MemoryRange.Jh
                name.contains("[anon:dalvik-large object space") -> MemoryRange.Jh
                name.contains("[anon:dalvik-free list large object space") -> MemoryRange.Jh
                name.contains("[anon:dalvik-non moving space") -> MemoryRange.Jh
                name.contains("[anon:dalvik-zygote space") -> MemoryRange.Jh
                name.contains("[anon:dalvik-alloc space") -> MemoryRange.Jh
                name.contains("dalvik") && !isExec -> MemoryRange.Jh

                // JIT 代码缓存
                name.contains("jit-cache") || name.contains("jit-zygote-cache") -> MemoryRange.Jc

                // DEX/VDEX/OAT
                name.endsWith(".dex") -> MemoryRange.Dx
                name.endsWith(".vdex") -> MemoryRange.Vx
                name.endsWith(".oat") || name.endsWith(".odex") -> MemoryRange.Oa

                // 应用代码 (.so 可执行)
                name.endsWith(".so") && isExec -> MemoryRange.Xa
                // C++ 数据段 (.so 不可执行)
                name.endsWith(".so") && !isExec && isWrite -> MemoryRange.Cd
                name.endsWith(".so") && !isExec && !isWrite -> MemoryRange.Cb

                // 系统库
                name.contains("/system/") && isExec -> MemoryRange.Xs
                name.contains("/vendor/") && isExec -> MemoryRange.Xs
                name.contains("/apex/") && isExec -> MemoryRange.Xs

                // 应用路径
                name.startsWith("/data/app/") && isExec -> MemoryRange.Xa
                name.startsWith("/data/data/") -> MemoryRange.Ca

                // 栈
                name.contains("[stack") -> MemoryRange.S
                name.contains("stack") -> MemoryRange.Ts

                // Ashmem
                name.contains("/dev/ashmem") -> MemoryRange.As

                // 匿名映射
                name.startsWith("[anon:libc_malloc") -> MemoryRange.Ch
                name.startsWith("[anon:scudo") -> MemoryRange.Ch
                name.startsWith("[anon:") -> MemoryRange.An
                name == "[anon]" || name.isEmpty() -> MemoryRange.An

                // 其他
                else -> MemoryRange.O
            }
        }
    }

    val isReadable: Boolean
        get() = (type and MEM_READABLE) != 0

    val isWritable: Boolean
        get() = (type and MEM_WRITABLE) != 0

    val isExecutable: Boolean
        get() = (type and MEM_EXECUTABLE) != 0

    val isShared: Boolean
        get() = (type and MEM_SHARED) != 0

    val hasProt: Boolean
        get() = type != 0

    val nonProt: Boolean
        get() = !isReadable && !isWritable && !isExecutable

    val permissionString: String
        get() = buildString {
            append(if (isReadable) 'r' else '-')
            append(if (isWritable) 'w' else '-')
            append(if (isExecutable) 'x' else '-')
            append(if (isShared) 's' else 'p')
        }

    val size: Long
        get() = end - start

    fun containsAddress(address: Long): Boolean {
        return address in start until end
    }

    override fun toString(): String {
        return "%s (0x%016X-0x%016X [%s] %s, size=%d)".format(
            range.code, start, end, permissionString, name, size
        )
    }
}