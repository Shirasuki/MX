package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import com.google.android.material.slider.Slider
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.FloatingSettingsLayoutBinding
import moe.fuqiuluo.mamu.driver.ProcessDeathMonitor
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.adapter.ProcessListAdapter
import moe.fuqiuluo.mamu.floating.dialog.MemoryRangeDialog
import moe.fuqiuluo.mamu.floating.dialog.customDialog
import moe.fuqiuluo.mamu.floating.ext.autoPause
import moe.fuqiuluo.mamu.floating.ext.chunkSize
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.filterLinuxProcess
import moe.fuqiuluo.mamu.floating.ext.filterSystemProcess
import moe.fuqiuluo.mamu.floating.ext.floatingOpacity
import moe.fuqiuluo.mamu.floating.ext.freezeInterval
import moe.fuqiuluo.mamu.floating.ext.hideMode1
import moe.fuqiuluo.mamu.floating.ext.hideMode2
import moe.fuqiuluo.mamu.floating.ext.hideMode3
import moe.fuqiuluo.mamu.floating.ext.hideMode4
import moe.fuqiuluo.mamu.floating.ext.keyboardType
import moe.fuqiuluo.mamu.floating.ext.languageSelection
import moe.fuqiuluo.mamu.floating.ext.memoryAccessMode
import moe.fuqiuluo.mamu.floating.ext.memoryBufferSize
import moe.fuqiuluo.mamu.floating.ext.saveListUpdateInterval
import moe.fuqiuluo.mamu.floating.ext.selectedMemoryRanges
import moe.fuqiuluo.mamu.floating.ext.skipMemoryOption
import moe.fuqiuluo.mamu.floating.ext.tabSwitchAnimation
import moe.fuqiuluo.mamu.floating.ext.topMostLayer
import moe.fuqiuluo.mamu.floating.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.floating.model.MemoryRange
import moe.fuqiuluo.mamu.utils.ApplicationUtils
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.utils.RootShellExecutor
import moe.fuqiuluo.mamu.utils.onError
import moe.fuqiuluo.mamu.utils.onSuccess
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

private const val TAG = "SettingsController"

class SettingsController(
    context: Context,
    binding: FloatingSettingsLayoutBinding,
    notification: NotificationOverlay,
    private val packageManager: PackageManager,
    private val onUpdateTopIcon: (DisplayProcessInfo?) -> Unit,
    private val onUpdateSearchProcessDisplay: (DisplayProcessInfo?) -> Unit,
    private val onClearSearchResults: () -> Unit,
    private val onUpdateMemoryRangeSummary: () -> Unit,
    private val onApplyOpacity: () -> Unit,
    private val processDeathCallback: ProcessDeathMonitor.Callback
) : FloatingController<FloatingSettingsLayoutBinding>(context, binding, notification) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun initialize() {
        val mmkv = MMKV.defaultMMKV()

        setupProcessSelection()
        setupMemoryRange()
        setupHideMamu(mmkv)
        setupSkipMemory()
        setupAutoPause(mmkv)
        setupFreezeInterval(mmkv)
        setupFilterOptions(mmkv)
        setupListUpdateInterval(mmkv)
        setupMemoryRwMode()
        setupOpacityControl(mmkv)
        setupMemoryBufferSizeControl()
        setupChunkSizeControl()
        setupKeyboard()
        setupLanguage()
        setupTopMostLayer(mmkv)
        setupTabAnimation(mmkv)
    }

    private fun setupProcessSelection() {
        binding.settingSelectProcess.setOnClickListener {
            showProcessSelectionDialog()
        }
        updateCurrentProcessDisplay(null)

        binding.btnTerminateProc.setOnClickListener {
            if (WuwaDriver.isProcessBound) {
                RootShellExecutor.exec(suCmd = RootConfigManager.getCustomRootCommand(), "kill -9 ${WuwaDriver.currentBindPid}", 1000).onSuccess {
                    notification.showSuccess(context.getString(R.string.success_process_terminated))
                    updateCurrentProcessDisplay(null)
                }.onError {
                    notification.showError(context.getString(R.string.error_terminate_failed))
                }

                WuwaDriver.unbindProcess()
                ProcessDeathMonitor.stop()
            } else {
                notification.showError(context.getString(R.string.error_no_bound_process))
            }
        }

        binding.btnExitOverlay.setOnClickListener {
            // Service 需要处理停止
        }
    }

    private fun setupMemoryRange() {
        binding.settingMemoryRange.setOnClickListener {
            showMemoryRangeDialog()
        }
        updateMemoryRangeSummary()
    }

    private fun setupHideMamu(mmkv: MMKV) {
        with(binding) {
            cbHideMode1.apply {
                isChecked = mmkv.hideMode1
                setOnCheckedChangeListener { _, isChecked -> mmkv.hideMode1 = isChecked }
            }
            cbHideMode2.apply {
                isChecked = mmkv.hideMode2
                setOnCheckedChangeListener { _, isChecked -> mmkv.hideMode2 = isChecked }
            }
            cbHideMode3.apply {
                isChecked = mmkv.hideMode3
                setOnCheckedChangeListener { _, isChecked -> mmkv.hideMode3 = isChecked }
            }
            cbHideMode4.apply {
                isChecked = mmkv.hideMode4
                setOnCheckedChangeListener { _, isChecked -> mmkv.hideMode4 = isChecked }
            }

            var isExpanded = false
            settingHideMamu.setOnClickListener {
                isExpanded = !isExpanded
                hideMamuOptions.visibility = if (isExpanded) View.VISIBLE else View.GONE
                hideMamuExpandIcon.rotation = if (isExpanded) 180f else 0f
            }
        }
    }

    private fun setupSkipMemory() {
        binding.settingSkipMemory.setOnClickListener {
            showSkipMemoryDialog()
        }
        updateSkipMemoryDisplay()
    }

    private fun setupAutoPause(mmkv: MMKV) {
        binding.switchAutoPause.apply {
            isChecked = mmkv.autoPause
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.autoPause = isChecked
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupFreezeInterval(mmkv: MMKV) {
        with(binding) {
            val currentValue = mmkv.freezeInterval
            seekbarFreezeInterval.value = currentValue.toFloat()
            freezeIntervalValue.text = "$currentValue μs"

            seekbarFreezeInterval.addOnChangeListener { _, value, fromUser ->
                val intValue = value.toInt()
                freezeIntervalValue.text = "$intValue μs"
                if (fromUser) {
                    mmkv.freezeInterval = intValue
                }
            }
        }
    }

    private fun setupFilterOptions(mmkv: MMKV) {
        binding.switchFilterSystemProcess.apply {
            isChecked = mmkv.filterSystemProcess
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.filterSystemProcess = isChecked
            }
        }

        binding.switchFilterLinuxProcess.apply {
            isChecked = mmkv.filterLinuxProcess
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.filterLinuxProcess = isChecked
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupListUpdateInterval(mmkv: MMKV) {
        val currentValue = mmkv.saveListUpdateInterval
        binding.seekbarListUpdateInterval.value = currentValue.toFloat()
        binding.listUpdateIntervalValue.text = "$currentValue ms"

        binding.seekbarListUpdateInterval.addOnChangeListener { _, value, fromUser ->
            val intValue = value.toInt()
            binding.listUpdateIntervalValue.text = "$intValue ms"
            if (fromUser) {
                mmkv.saveListUpdateInterval = intValue
            }
        }
    }

    private fun setupMemoryRwMode() {
        binding.settingMemoryRwMode.setOnClickListener {
            showMemoryRwModeDialog()
        }
        updateMemoryRwModeDisplay()
    }

    @SuppressLint("SetTextI18n")
    private fun setupOpacityControl(mmkv: MMKV) {
        val currentOpacity = mmkv.floatingOpacity
        val progress = (currentOpacity * 100).toInt()
        binding.opacitySeekbar.value = progress.toFloat()
        binding.opacityValue.text = "$progress%"

        binding.opacitySeekbar.addOnChangeListener { _, value, fromUser ->
            val intValue = value.toInt()
            binding.opacityValue.text = "$intValue%"

            if (fromUser) {
                val opacity = intValue / 100f
                mmkv.floatingOpacity = opacity
                onApplyOpacity()
            }
        }
    }

    private fun setupMemoryBufferSizeControl() {
        binding.settingMemoryBufferSize.setOnClickListener {
            showMemoryBufferSizeDialog()
        }
        updateMemoryBufferSizeDisplay()
    }

    private fun setupChunkSizeControl() {
        binding.settingChunkSize.setOnClickListener {
            showChunkSizeDialog()
        }
        updateChunkSizeDisplay()
    }

    private fun setupKeyboard() {
        binding.settingKeyboard.setOnClickListener {
            showKeyboardDialog()
        }
        updateKeyboardDisplay()
    }

    private fun setupLanguage() {
        binding.settingLanguage.setOnClickListener {
            showLanguageDialog()
        }
        updateLanguageDisplay()
    }

    private fun setupTopMostLayer(mmkv: MMKV) {
        binding.switchTopMostLayer.apply {
            isChecked = mmkv.topMostLayer
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.topMostLayer = isChecked
                val status = context.getString(if (isChecked) R.string.topmost_enabled else R.string.topmost_disabled)
                notification.showSuccess(context.getString(R.string.success_topmost_changed, status))
            }
        }
    }

    private fun setupTabAnimation(mmkv: MMKV) {
        binding.switchTabAnimation.apply {
            isChecked = mmkv.tabSwitchAnimation
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.tabSwitchAnimation = isChecked
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateCurrentProcessDisplay(process: DisplayProcessInfo?) {
        onClearSearchResults()
        process?.let { process ->
            binding.currentProcessName.text = "${process.name} (PID: ${process.pid})"
            onUpdateTopIcon(process)
            onUpdateSearchProcessDisplay(process)
        } ?: run {
            binding.currentProcessName.text = context.getString(R.string.settings_no_process_selected)
            onUpdateTopIcon(null)
            onUpdateSearchProcessDisplay(null)
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    fun showProcessSelectionDialog() {
        coroutineScope.launch {
            runCatching {
                val mmkv = MMKV.defaultMMKV()
                val filterSystem = mmkv.filterSystemProcess
                val filterLinux = mmkv.filterLinuxProcess

                // 后台线程处理耗时操作
                val processList = withContext(Dispatchers.IO) {
                    WuwaDriver.listProcessesWithInfo()
                        .filter { process ->
                            when {
                                filterSystem && ApplicationUtils.isSystemApp(context, process.uid) -> false
                                filterLinux && process.uid < 1000 -> false
                                else -> true
                            }
                        }
                        .map { process ->
                            when {
                                process.name.isEmpty() || ApplicationUtils.isSystemApp(context, process.uid) -> {
                                    DisplayProcessInfo(
                                        icon = ApplicationUtils.getAndroidIcon(context),
                                        name = process.name,
                                        packageName = null,
                                        pid = process.pid,
                                        uid = process.uid,
                                        prio = 1,
                                        rss = process.rss,
                                        cmdline = process.name
                                    )
                                }
                                else -> {
                                    val packageName = process.name.split(":").first()
                                    var prio = 3

                                    val appIcon = ApplicationUtils.getAppIconByPackageName(context, packageName)
                                        ?: ApplicationUtils.getAppIconByUid(context, process.uid)
                                        ?: ApplicationUtils.getAndroidIcon(context).also { prio-- }

                                    val appName = ApplicationUtils.getAppNameByPackageName(context, packageName)
                                        ?: ApplicationUtils.getAppNameByUid(context, process.uid)
                                        ?: process.name.also { prio-- }

                                    DisplayProcessInfo(
                                        icon = appIcon,
                                        name = appName,
                                        packageName = packageName,
                                        pid = process.pid,
                                        uid = process.uid,
                                        prio = prio,
                                        rss = process.rss,
                                        cmdline = process.name
                                    )
                                }
                            }
                        }
                        .sortedByDescending { it.prio }
                }

                // 回到主线程显示对话框
                val adapter = ProcessListAdapter(context, processList)
                context.customDialog(
                    title = context.getString(R.string.settings_select_process),
                    adapter = adapter,
                    onItemClick = { position ->
                        val selectedProcess = processList[position]

                        runCatching {
                            val success = WuwaDriver.bindProcess(selectedProcess.pid)
                            if (!success) {
                                notification.showError(context.getString(R.string.error_bind_process_failed))
                                return@customDialog
                            }

                            if (ProcessDeathMonitor.isMonitoring) {
                                ProcessDeathMonitor.stop()
                            }
                            ProcessDeathMonitor.start(selectedProcess.pid, processDeathCallback)
                        }.onFailure {
                            it.printStackTrace()
                            notification.showError(context.getString(R.string.error_bind_process_failed_with_reason, it.message.orEmpty()))
                        }.onSuccess {
                            updateCurrentProcessDisplay(selectedProcess)
                            notification.showSuccess(context.getString(R.string.success_process_selected, selectedProcess.name))
                        }
                    }
                )
            }.onFailure {
                Log.e(TAG, it.stackTraceToString())
                notification.showError("加载进程列表失败: ${it.message}")
            }
        }
    }

    fun showMemoryRangeDialog() {
        val mmkv = MMKV.defaultMMKV()
        val allRanges = MemoryRange.entries.toTypedArray()
        val selectedRanges = mmkv.selectedMemoryRanges
        val checkedItems = allRanges.map { selectedRanges.contains(it) }.toBooleanArray()

        // 默认选中的内存范围（与 FloatingConfig 中的默认值一致）
        val defaultRanges = setOf(
            MemoryRange.Jh,
            MemoryRange.Ch,
            MemoryRange.Ca,
            MemoryRange.Cd,
            MemoryRange.Cb,
            MemoryRange.Ps,
            MemoryRange.An
        )
        val defaultCheckedItems = allRanges.map { defaultRanges.contains(it) }.toBooleanArray()

        val memorySizes = if (WuwaDriver.isProcessBound) runCatching {
            val regions = WuwaDriver.queryMemRegions()
                .divideToSimpleMemoryRange()
            regions.groupBy { it.range }.mapValues { (_, entries) ->
                entries.sumOf { it.end - it.start }
            }
        }.getOrNull() else {
            null
        }

        val dialog = MemoryRangeDialog(
            context = context,
            memoryRanges = allRanges,
            checkedItems = checkedItems,
            memorySizes = memorySizes,
            defaultCheckedItems = defaultCheckedItems
        )

        dialog.onMultiChoice = { newCheckedItems ->
            val newRanges = allRanges.filterIndexed { index, _ -> newCheckedItems[index] }.toSet()
            mmkv.selectedMemoryRanges = newRanges
            updateMemoryRangeSummary()
            notification.showSuccess(context.getString(R.string.success_memory_range_saved))
        }

        dialog.show()
    }

    private fun updateMemoryRangeSummary() {
        val mmkv = MMKV.defaultMMKV()
        val selectedRanges = mmkv.selectedMemoryRanges
        binding.memoryRangeSummary.text = if (selectedRanges.isEmpty()) {
            context.getString(R.string.settings_memory_range_summary)
        } else {
            context.getString(R.string.memory_range_count, selectedRanges.size)
        }
        onUpdateMemoryRangeSummary()
    }

    private fun showSkipMemoryDialog() {
        val memoryRangeOptions by lazy {
            arrayOf(
                context.getString(R.string.settings_skip_memory_none),
                context.getString(R.string.settings_skip_memory_empty),
                context.getString(R.string.settings_skip_memory_empty_zygote)
            )
        }

        val mmkv = MMKV.defaultMMKV()
        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.dialog_skip_memory_title),
            options = memoryRangeOptions,
            selected = mmkv.skipMemoryOption,
            onSingleChoice = { which ->
                mmkv.skipMemoryOption = which
                updateSkipMemoryDisplay(which)
                notification.showSuccess(context.getString(R.string.success_skip_memory_saved))
            }
        )
    }

    private fun updateSkipMemoryDisplay(mode: Int = MMKV.defaultMMKV().skipMemoryOption) {
        val text = when (mode) {
            0 -> context.getString(R.string.settings_skip_memory_none)
            1 -> context.getString(R.string.settings_skip_memory_empty)
            2 -> context.getString(R.string.settings_skip_memory_empty_zygote)
            else -> context.getString(R.string.settings_skip_memory_none)
        }
        binding.skipMemoryValue.text = text
    }

    private fun showMemoryRwModeDialog() {
        val memRwModeOptions by lazy {
            arrayOf(
                context.getString(R.string.settings_memory_rw_mode_none),
                context.getString(R.string.settings_memory_rw_mode_writethrough),
                context.getString(R.string.settings_memory_rw_mode_nocache),
                context.getString(R.string.settings_memory_rw_mode_normal),
                context.getString(R.string.settings_memory_rw_mode_pgfault),
            )
        }

        val mmkv = MMKV.defaultMMKV()
        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.settings_memory_rw_mode),
            options = memRwModeOptions,
            selected = mmkv.memoryAccessMode,
            onSingleChoice = { which ->
                mmkv.memoryAccessMode = which
                updateMemoryRwModeDisplay(which)
                notification.showSuccess(context.getString(R.string.success_memory_rw_mode_saved))
            }
        )
    }

    private fun updateMemoryRwModeDisplay(mode: Int = MMKV.defaultMMKV().memoryAccessMode) {
        val text = when (mode) {
            0 -> context.getString(R.string.settings_memory_rw_mode_none)
            1 -> context.getString(R.string.settings_memory_rw_mode_writethrough)
            2 -> context.getString(R.string.settings_memory_rw_mode_nocache)
            3 -> context.getString(R.string.settings_memory_rw_mode_normal)
            4 -> context.getString(R.string.settings_memory_rw_mode_pgfault)
            else -> context.getString(R.string.settings_memory_rw_mode_normal)
        }
        binding.memoryRwModeValue.text = text
    }

    private fun showMemoryBufferSizeDialog() {
        val mmkv = MMKV.defaultMMKV()
        val options = arrayOf(
            context.getString(R.string.memory_empty_region),
            "64 MB", "128 MB", "256 MB", "512 MB", "1024 MB", "2048 MB"
        )
        val values = arrayOf(0, 64, 128, 256, 512, 1024, 2048)
        val currentSize = mmkv.memoryBufferSize
        val selected = values.indexOf(currentSize).takeIf { it >= 0 } ?: 4

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.settings_memory_buffer_size),
            options = options,
            selected = selected,
            onSingleChoice = { which ->
                val newSize = values[which]
                mmkv.memoryBufferSize = newSize
                updateMemoryBufferSizeDisplay(newSize)
                notification.showSuccess(context.getString(R.string.success_memory_buffer_size_saved))
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateMemoryBufferSizeDisplay(sizeMB: Int = MMKV.defaultMMKV().memoryBufferSize) {
        binding.memoryBufferSizeValue.text = if (sizeMB == 0) context.getString(R.string.memory_empty_region) else "$sizeMB MB"
    }

    private fun showKeyboardDialog() {
        val mmkv = MMKV.defaultMMKV()
        val options = arrayOf(
            context.getString(R.string.settings_keyboard_builtin),
            context.getString(R.string.settings_keyboard_system)
        )

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.settings_keyboard),
            options = options,
            selected = mmkv.keyboardType,
            onSingleChoice = { which ->
                mmkv.keyboardType = which
                updateKeyboardDisplay(which)
                notification.showSuccess(context.getString(R.string.success_keyboard_saved))
            }
        )
    }

    private fun updateKeyboardDisplay(mode: Int = MMKV.defaultMMKV().keyboardType) {
        val text = when (mode) {
            0 -> context.getString(R.string.settings_keyboard_builtin)
            1 -> context.getString(R.string.settings_keyboard_system)
            else -> context.getString(R.string.settings_keyboard_builtin)
        }
        binding.keyboardValue.text = text
    }

    private fun showLanguageDialog() {
        val mmkv = MMKV.defaultMMKV()
        val options = arrayOf(
            context.getString(R.string.settings_language_zh_cn),
            context.getString(R.string.settings_language_en)
        )

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.settings_language),
            options = options,
            selected = mmkv.languageSelection,
            onSingleChoice = { which ->
                mmkv.languageSelection = which
                updateLanguageDisplay(which)
                notification.showSuccess(context.getString(R.string.success_language_saved))
            }
        )
    }

    private fun updateLanguageDisplay(lang: Int = MMKV.defaultMMKV().languageSelection) {
        val text = when (lang) {
            0 -> context.getString(R.string.settings_language_zh_cn)
            1 -> context.getString(R.string.settings_language_en)
            else -> context.getString(R.string.settings_language_zh_cn)
        }
        binding.languageValue.text = text
    }

    private fun showChunkSizeDialog() {
        val mmkv = MMKV.defaultMMKV()
        val options = arrayOf(
            context.getString(R.string.chunk_size_128_kb),
            context.getString(R.string.chunk_size_512_kb),
            context.getString(R.string.chunk_size_1_mb),
            context.getString(R.string.chunk_size_4_mb)
        )
        val values = arrayOf(128, 512, 1024, 4096)
        val currentSize = mmkv.chunkSize
        val selected = values.indexOf(currentSize).takeIf { it >= 0 } ?: 1

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.settings_chunk_size),
            options = options,
            selected = selected,
            onSingleChoice = { which ->
                val newSize = values[which]
                mmkv.chunkSize = newSize
                updateChunkSizeDisplay(newSize)
                notification.showSuccess(context.getString(R.string.success_chunk_size_saved))
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateChunkSizeDisplay(sizeKB: Int = MMKV.defaultMMKV().chunkSize) {
        val text = when (sizeKB) {
            128 -> context.getString(R.string.chunk_size_128_kb)
            512 -> context.getString(R.string.chunk_size_512_kb)
            1024 -> context.getString(R.string.chunk_size_1_mb)
            4096 -> context.getString(R.string.chunk_size_4_mb)
            else -> context.getString(R.string.chunk_size_512_kb)
        }
        binding.chunkSizeValue.text = text
    }

    fun requestExitOverlay(): Boolean {
        // 返回 true 表示允许退出
        return true
    }

    override fun cleanup() {
        super.cleanup()
        coroutineScope.cancel()
    }
}