package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.local.RootFileSystem
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogImportAddressBinding
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入地址对话框
 * 从文件导入地址到保存地址列表
 */
class ImportAddressDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val coroutineScope: CoroutineScope,
    private val onImportComplete: ((List<SavedAddress>) -> Unit)? = null
) : BaseDialog(context) {

    companion object {
        @SuppressLint("SdCardPath")
        private val IMPORT_PATHS = arrayOf(
            "/sdcard/Mamu/export",
            "/sdcard/Mamu/addresses",
            "/sdcard/Download",
            "/sdcard/Documents"
        )
    }

    private var fileList = mutableListOf<FileItem>()

    data class FileItem(
        val name: String,
        val path: String,
        val size: Long
    )

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogImportAddressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 设置RecyclerView
        val adapter = FileListAdapter { fileItem ->
            performImport(fileItem)
            dialog.dismiss()
        }
        binding.fileList.layoutManager = LinearLayoutManager(context)
        binding.fileList.adapter = adapter

        // 加载文件列表
        coroutineScope.launch {
            val files = withContext(Dispatchers.IO) {
                loadFileList()
            }

            fileList.clear()
            fileList.addAll(files)
            adapter.setFiles(files)

            if (files.isEmpty()) {
                binding.fileList.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
            } else {
                binding.fileList.visibility = View.VISIBLE
                binding.emptyState.visibility = View.GONE
            }
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun loadFileList(): List<FileItem> {
        val files = mutableListOf<FileItem>()

        IMPORT_PATHS.forEach { dirPath ->
            try {
                if (RootFileSystem.isConnected()) {
                    RootFileSystem.listFiles(dirPath).forEach { file ->
                        if (file.name.endsWith(".txt") || file.name.endsWith(".csv")) {
                            files.add(FileItem(
                                name = file.name,
                                path = "$dirPath/${file.name}",
                                size = file.length()
                            ))
                        }
                    }
                } else {
                    val dir = File(dirPath)
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && (file.name.endsWith(".txt") || file.name.endsWith(".csv"))) {
                                files.add(FileItem(
                                    name = file.name,
                                    path = file.absolutePath,
                                    size = file.length()
                                ))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return files.distinctBy { it.path }.sortedByDescending { it.name }
    }

    private fun performImport(fileItem: FileItem) {
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val content = readFile(fileItem.path) ?: return@withContext null
                    parseImportContent(content)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            if (result != null) {
                val (pid, addresses) = result

                // 检查PID是否匹配
                val currentPid = WuwaDriver.currentBindPid
                if (pid > 0 && currentPid > 0 && pid != currentPid) {
                    notification.showWarning("警告：文件中的PID($pid)与当前进程PID($currentPid)不匹配")
                }

                if (addresses.isNotEmpty()) {
                    notification.showSuccess("成功导入 ${addresses.size} 个地址")
                    onImportComplete?.invoke(addresses)
                } else {
                    notification.showError("导入失败：文件中没有有效地址")
                }
            } else {
                notification.showError("导入失败")
            }
        }
    }

    private fun readFile(path: String): String? {
        return if (RootFileSystem.isConnected()) {
            RootFileSystem.readText(path)
        } else {
            try {
                File(path).readText(Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 解析导入内容
     * 格式：
     * PID
     * 名称|地址(小写hex)|类型|值|冻结|0|0|0|权限|区域名称|偏移
     */
    private fun parseImportContent(content: String): Pair<Int, List<SavedAddress>>? {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        // 第一行是PID
        val pid = lines.firstOrNull()?.toIntOrNull() ?: 0

        // 后续行是地址数据
        val addresses = mutableListOf<SavedAddress>()
        lines.drop(1).forEach { line ->
            try {
                val address = parseLine(line)
                if (address != null) {
                    addresses.add(address)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Pair(pid, addresses)
    }

    /**
     * 解析单行地址数据
     * 格式：名称|地址(小写hex)|类型|值|冻结|0|0|0|权限|区域名称|偏移
     */
    private fun parseLine(line: String): SavedAddress? {
        val parts = line.split("|")
        if (parts.size < 5) return null

        val name = parts[0]
        val addressStr = parts[1]
        val typeId = parts[2].toIntOrNull() ?: return null
        val value = parts[3]
        val frozen = parts[4] == "1"

        // 解析地址（支持带0x前缀和不带前缀）
        val address = if (addressStr.startsWith("0x", ignoreCase = true)) {
            addressStr.substring(2).toLongOrNull(16)
        } else {
            addressStr.toLongOrNull(16)
        } ?: return null

        // 解析内存范围（如果有）
        val rangeCode = if (parts.size > 9) {
            // 尝试从区域名称中提取范围代码
            extractRangeCode(parts[9])
        } else {
            "An"
        }
        val range = MemoryRange.fromCode(rangeCode) ?: MemoryRange.An

        return SavedAddress(
            address = address,
            name = name,
            valueType = typeId,
            value = value,
            isFrozen = frozen,
            range = range
        )
    }

    /**
     * 从区域名称中提取范围代码
     */
    private fun extractRangeCode(regionName: String): String {
        return when {
            regionName.contains("dalvik") || regionName.contains("Java") -> "Jh"
            regionName.contains("heap") -> "Ch"
            regionName.contains("alloc") -> "Ca"
            regionName.contains(".data") -> "Cd"
            regionName.contains(".bss") -> "Cb"
            regionName.contains("stack") -> "S"
            regionName.contains("anon") -> "An"
            else -> "An"
        }
    }

    /**
     * 文件列表适配器
     */
    private inner class FileListAdapter(
        private val onItemClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

        private val files = mutableListOf<FileItem>()

        fun setFiles(newFiles: List<FileItem>) {
            files.clear()
            files.addAll(newFiles)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_import_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.bind(file)
        }

        override fun getItemCount() = files.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val fileName: TextView = itemView.findViewById(R.id.file_name)
            private val fileSize: TextView = itemView.findViewById(R.id.file_size)

            fun bind(file: FileItem) {
                fileName.text = file.name
                fileSize.text = formatFileSize(file.size)
                itemView.setOnClickListener { onItemClick(file) }
            }

            private fun formatFileSize(size: Long): String {
                return when {
                    size < 1024 -> "$size B"
                    size < 1024 * 1024 -> "${size / 1024} KB"
                    else -> "${size / (1024 * 1024)} MB"
                }
            }
        }
    }
}
