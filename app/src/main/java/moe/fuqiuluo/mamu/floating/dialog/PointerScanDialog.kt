package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.searchPageSize
import moe.fuqiuluo.mamu.databinding.DialogPointerScanInputBinding
import moe.fuqiuluo.mamu.driver.MemoryRegionInfo
import moe.fuqiuluo.mamu.driver.PointerChainResult
import moe.fuqiuluo.mamu.driver.PointerScanner
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.local.InputHistoryManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.MemoryRange
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import java.lang.Long.parseUnsignedLong
import kotlin.math.min

/**
 * 指针扫描对话框
 * 用于输入目标地址并启动指针扫描，扫描完成后将结果作为 PointerChainResultItem 返回
 */
class PointerScanDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val onScanCompleted: ((ranges: List<DisplayMemRegionEntry>, results: List<PointerChainResult>) -> Unit)? = null
) : BaseDialog(context) {
    companion object {
        private const val TAG = "PointerScanDialog"
    }

    private val scanScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 扫描状态
    var isScanning = false

    private lateinit var cacheMemoryRanges: List<DisplayMemRegionEntry>

    // 进度对话框
    private var progressDialog: PointerScanProgressDialog? = null

    override fun setupDialog() {
        val binding = DialogPointerScanInputBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 恢复上次输入内容并全选
        InputHistoryManager.restoreAndSelectAll(
            binding.inputTargetAddress,
            InputHistoryManager.Keys.POINTER_SCAN_ADDRESS
        )
        InputHistoryManager.restoreAndSelectAll(
            binding.inputMaxDepth,
            InputHistoryManager.Keys.POINTER_SCAN_DEPTH,
            "5"
        )
        InputHistoryManager.restoreAndSelectAll(
            binding.inputMaxOffset,
            InputHistoryManager.Keys.POINTER_SCAN_OFFSET,
            "1000"
        )

        binding.btnCancel.setOnClickListener {
            // 保存输入内容
            InputHistoryManager.saveFromEditText(binding.inputTargetAddress, InputHistoryManager.Keys.POINTER_SCAN_ADDRESS)
            InputHistoryManager.saveFromEditText(binding.inputMaxDepth, InputHistoryManager.Keys.POINTER_SCAN_DEPTH)
            InputHistoryManager.saveFromEditText(binding.inputMaxOffset, InputHistoryManager.Keys.POINTER_SCAN_OFFSET)
            onCancel?.invoke()
            dialog.dismiss()
        }

        binding.btnScan.setOnClickListener {
            // 读取目标地址
            val addressText = binding.inputTargetAddress.text?.toString()?.trim() ?: ""
            if (addressText.isEmpty()) {
                notification.showError(context.getString(R.string.error_invalid_address))
                return@setOnClickListener
            }

            val targetAddress = try {
                parseUnsignedLong(addressText, 16)
            } catch (e: NumberFormatException) {
                notification.showError(context.getString(R.string.error_invalid_address))
                return@setOnClickListener
            }

            // 读取扫描深度
            val maxDepth = try {
                binding.inputMaxDepth.text?.toString()?.toIntOrNull() ?: 5
            } catch (e: Exception) {
                5
            }.coerceIn(1, 100)

            // 读取最大偏移（16进制）
            val maxOffset = try {
                val offsetText = binding.inputMaxOffset.text?.toString()?.trim() ?: "1000"
                parseUnsignedLong(offsetText, 16).toInt()
            } catch (e: Exception) {
                0x1000
            }.coerceIn(0x100, 0x100000)

            // 读取结果数量限制（0 表示无限制）
            val maxResults = try {
                binding.inputMaxResults.text?.toString()?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }.coerceIn(0, Int.MAX_VALUE)

            val isLayerBFS = binding.switchLayerBfs.isChecked

            if (!WuwaDriver.isProcessBound) {
                notification.showError(context.getString(R.string.error_process_not_bound))
                return@setOnClickListener
            }

            // 保存输入内容
            InputHistoryManager.save(InputHistoryManager.Keys.POINTER_SCAN_ADDRESS, addressText)
            InputHistoryManager.saveFromEditText(binding.inputMaxDepth, InputHistoryManager.Keys.POINTER_SCAN_DEPTH)
            InputHistoryManager.saveFromEditText(binding.inputMaxOffset, InputHistoryManager.Keys.POINTER_SCAN_OFFSET)

            dialog.dismiss()
            startPointerScan(targetAddress, maxDepth, maxOffset, maxResults, isLayerBFS)
        }
    }

    private fun startPointerScan(
        targetAddress: Long, maxDepth: Int, maxOffset: Int, maxResults: Int, isLayerBFS: Boolean
    ) {
        scanScope.launch {
            // 获取内存区域
            val memRegions =
                WuwaDriver.queryMemRegionsWithRetry().divideToSimpleMemoryRange().filter {
                    it.range == MemoryRange.Oa ||
                            it.range == MemoryRange.O ||
                            it.range == MemoryRange.Ca ||
                            it.range == MemoryRange.Cd ||
                            it.range == MemoryRange.Cb ||
                            it.range == MemoryRange.Ch ||
                            it.range == MemoryRange.An ||
                            it.range == MemoryRange.As ||
                            it.range == MemoryRange.Xa ||
                            it.range == MemoryRange.Xs ||
                            it.range == MemoryRange.Jc ||
                            it.range == MemoryRange.Jh ||
                            it.range == MemoryRange.Xx
                }
            if (memRegions.isEmpty()) {
                withContext(Dispatchers.Main) {
                    notification.showError(context.getString(R.string.error_no_memory_regions))
                }
                return@launch
            }

            cacheMemoryRanges = memRegions

            // 转换为 MemoryRegionInfo
            val regions = memRegions.map { region ->
                MemoryRegionInfo(
                    start = region.start,
                    end = region.end,
                    name = region.name,
                    isStatic = (region.range == MemoryRange.Cd || region.range == MemoryRange.Cb || region.range == MemoryRange.Oa || region.range == MemoryRange.Xs || region.range == MemoryRange.Xa || region.range == MemoryRange.Xx),
                    permFlags = region.type
                )
            }

            // 启动扫描
            val success = PointerScanner.startScan(
                targetAddress = targetAddress,
                maxDepth = maxDepth,
                maxOffset = maxOffset,
                align = 4, // 对齐固定为4
                regions = regions,
                isLayerBFS = isLayerBFS,
                maxResults = maxResults
            )

            if (success) {
                withContext(Dispatchers.Main) {
                    notification.showSuccess(context.getString(R.string.success_pointer_scan_started))
                    isScanning = true
                    startProgressMonitoring(maxResults)
                }
            } else {
                withContext(Dispatchers.Main) {
                    notification.showError(
                        context.getString(
                            R.string.error_pointer_scan_failed, PointerScanner.getErrorString()
                        )
                    )
                }
            }
        }
    }

    private fun startProgressMonitoring(maxResults: Int) {
        // 显示进度对话框
        progressDialog = PointerScanProgressDialog(context = context, onCancelClick = {
            cancelScan()
        }, onHideClick = {
            FloatingEventBus.tryEmitUIAction(UIActionEvent.HideFloatingWindow)
        }).apply {
            show()
        }

        // 监控进度
        scanScope.launch(Dispatchers.Main) {
            while (isActive && isScanning) {
                val phase = PointerScanner.getPhase()
                val progress = PointerScanner.getProgress()
                val pointersFound = PointerScanner.getPointersFound()
                val chainsFound = PointerScanner.getChainsFound()

                progressDialog?.updateProgress(phase, progress, pointersFound, chainsFound)

                when (phase) {
                    PointerScanner.Phase.COMPLETED -> {
                        onScanCompleted(maxResults)
                        break
                    }

                    PointerScanner.Phase.CANCELLED -> {
                        onScanCancelled()
                        break
                    }

                    PointerScanner.Phase.ERROR -> {
                        onScanError()
                        break
                    }
                }

                delay(100)
            }
        }
    }

    private fun onScanCompleted(maxResults: Int) {
        isScanning = false
        progressDialog?.dismiss()
        progressDialog = null

        val chainCount = PointerScanner.getChainCount()
        val outputFile = PointerScanner.getOutputFilePath()

        // 使用通知显示结果
        notification.showSuccess("扫描完成: 找到 $chainCount 条指针链\n结果保存至: $outputFile")

        // 通知回调（传递空列表，因为结果已写入文件）
        onScanCompleted?.invoke(cacheMemoryRanges, emptyList())
    }

    private fun onScanCancelled() {
        isScanning = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showWarning(context.getString(R.string.warning_pointer_scan_cancelled))
    }

    private fun onScanError() {
        isScanning = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showError(
            context.getString(
                R.string.error_pointer_scan_failed, PointerScanner.getErrorString()
            )
        )
    }

    private fun cancelScan() {
        if (isScanning) {
            PointerScanner.requestCancel()
            PointerScanner.requestCancelViaBuffer()
        }
    }

    fun release() {
        progressDialog?.dismiss()
        progressDialog = null
        scanScope.cancel()
        if (isScanning) {
            cancelScan()
        }
    }

    fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    fun showProgressDialogIfScanning() {
        if (isScanning && progressDialog == null) {
            progressDialog = PointerScanProgressDialog(
                context = context,
                onCancelClick = { cancelScan() },
                onHideClick = {
                    FloatingEventBus.tryEmitUIAction(UIActionEvent.HideFloatingWindow)
                }).apply {
                show()
                updateProgress(
                    PointerScanner.getPhase(),
                    PointerScanner.getProgress(),
                    PointerScanner.getPointersFound(),
                    PointerScanner.getChainsFound()
                )
            }
        }
    }
}
