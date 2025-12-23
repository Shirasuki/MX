package moe.fuqiuluo.mamu.floating.controller

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.floating.event.FloatingEventBus
import moe.fuqiuluo.mamu.floating.event.ProcessStateEvent
import moe.fuqiuluo.mamu.floating.event.UIActionEvent
import moe.fuqiuluo.mamu.databinding.FloatingSettingsLayoutBinding
import moe.fuqiuluo.mamu.data.settings.autoPause
import moe.fuqiuluo.mamu.data.settings.chunkSize
import moe.fuqiuluo.mamu.data.settings.compatibilityMode
import moe.fuqiuluo.mamu.data.settings.dialogTransparencyEnabled
import moe.fuqiuluo.mamu.data.settings.filterLinuxProcess
import moe.fuqiuluo.mamu.data.settings.filterSystemProcess
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import moe.fuqiuluo.mamu.data.settings.freezeInterval
import moe.fuqiuluo.mamu.data.settings.hideMode1
import moe.fuqiuluo.mamu.data.settings.hideMode2
import moe.fuqiuluo.mamu.data.settings.hideMode3
import moe.fuqiuluo.mamu.data.settings.hideMode4
import moe.fuqiuluo.mamu.data.settings.keyboardType
import moe.fuqiuluo.mamu.data.settings.languageSelection
import moe.fuqiuluo.mamu.data.settings.memoryAccessMode
import moe.fuqiuluo.mamu.data.settings.memoryBufferSize
import moe.fuqiuluo.mamu.data.settings.saveListUpdateInterval
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.data.settings.skipMemoryOption
import moe.fuqiuluo.mamu.data.settings.tabSwitchAnimation
import moe.fuqiuluo.mamu.data.settings.topMostLayer
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.floating.data.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

class SettingsController(
    context: Context,
    binding: FloatingSettingsLayoutBinding,
    notification: NotificationOverlay
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
        setupDialogTransparency(mmkv)
        setupCompatibilityMode(mmkv)
        setupMemoryBufferSizeControl()
        setupChunkSizeControl()
        setupKeyboard()
        setupLanguage()
        setupTopMostLayer(mmkv)
        setupTabAnimation(mmkv)

        subscribeToProcessStateEvents()
        subscribeToMemoryRangeChangedEvents()
    }

    /**
     * 订阅进程状态变更事件
     */
    private fun subscribeToProcessStateEvents() {
        coroutineScope.launch {
            FloatingEventBus.processStateEvents.collect { event ->
                when (event.type) {
                    ProcessStateEvent.Type.BOUND -> {
                        updateCurrentProcessDisplay(event.process)
                    }

                    ProcessStateEvent.Type.UNBOUND,
                    ProcessStateEvent.Type.DIED -> {
                        updateCurrentProcessDisplay(null)
                    }
                }
            }
        }
    }

    /**
     * 订阅内存范围配置变更事件
     */
    private fun subscribeToMemoryRangeChangedEvents() {
        coroutineScope.launch {
            FloatingEventBus.memoryRangeChangedEvents.collect {
                updateMemoryRangeSummary()
            }
        }
    }

    private fun setupProcessSelection() {
        binding.settingSelectProcess.setOnClickListener {
            // 发送 UI 操作事件，由 Service 统一处理
            coroutineScope.launch {
                FloatingEventBus.emitUIAction(UIActionEvent.ShowProcessSelectionDialog)
            }
        }
        updateCurrentProcessDisplay(null)

        binding.btnTerminateProc.setOnClickListener {
            // 发送解绑进程请求事件，由 Service 统一处理
            coroutineScope.launch {
                FloatingEventBus.emitUIAction(UIActionEvent.UnbindProcessRequest)
            }
        }

        binding.btnExitOverlay.setOnClickListener {
            // 发送退出悬浮窗请求事件，由 Service 统一处理
            coroutineScope.launch {
                FloatingEventBus.emitUIAction(UIActionEvent.ExitOverlayRequest)
            }
        }
    }

    private fun setupMemoryRange() {
        binding.settingMemoryRange.setOnClickListener {
            // 发送显示内存范围对话框事件，由 Service 统一处理
            coroutineScope.launch {
                FloatingEventBus.emitUIAction(UIActionEvent.ShowMemoryRangeDialog)
            }
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
                // 发送应用透明度事件
                coroutineScope.launch {
                    FloatingEventBus.emitUIAction(UIActionEvent.ApplyOpacityRequest)
                }
            }
        }
    }

    private fun setupDialogTransparency(mmkv: MMKV) {
        binding.switchDialogTransparency.apply {
            isChecked = mmkv.dialogTransparencyEnabled
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.dialogTransparencyEnabled = isChecked
            }
        }
    }

    private fun setupCompatibilityMode(mmkv: MMKV) {
        binding.switchCompatibilityMode.apply {
            isChecked = mmkv.compatibilityMode
            setOnCheckedChangeListener { _, isChecked ->
                mmkv.compatibilityMode = isChecked
                SearchEngine.setCompatibilityMode(isChecked)
                notification.showSuccess(
                    context.getString(
                        if (isChecked) R.string.success_compatibility_mode_enabled
                        else R.string.success_compatibility_mode_disabled
                    )
                )
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
                val status =
                    context.getString(if (isChecked) R.string.topmost_enabled else R.string.topmost_disabled)
                notification.showSuccess(
                    context.getString(
                        R.string.success_topmost_changed,
                        status
                    )
                )
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
    private fun updateCurrentProcessDisplay(process: DisplayProcessInfo?) {
        process?.let { proc ->
            binding.currentProcessName.text = "${proc.name} (PID: ${proc.pid})"
        } ?: run {
            binding.currentProcessName.text =
                context.getString(R.string.settings_no_process_selected)
        }
    }

    private fun updateMemoryRangeSummary() {
        val mmkv = MMKV.defaultMMKV()
        val selectedRanges = mmkv.selectedMemoryRanges
        binding.memoryRangeSummary.text = if (selectedRanges.isEmpty()) {
            context.getString(R.string.settings_memory_range_summary)
        } else {
            context.getString(R.string.memory_range_count, selectedRanges.size)
        }
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
        binding.memoryBufferSizeValue.text =
            if (sizeMB == 0) context.getString(R.string.memory_empty_region) else "$sizeMB MB"
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

    override fun cleanup() {
        super.cleanup()
        coroutineScope.cancel()
    }
}