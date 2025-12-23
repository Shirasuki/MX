package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.data.settings.selectedMemoryRanges
import moe.fuqiuluo.mamu.databinding.DialogFuzzySearchBinding
import moe.fuqiuluo.mamu.driver.FuzzyCondition
import moe.fuqiuluo.mamu.driver.SearchEngine
import moe.fuqiuluo.mamu.driver.SearchMode
import moe.fuqiuluo.mamu.driver.WuwaDriver
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.floating.ext.divideToSimpleMemoryRange
import moe.fuqiuluo.mamu.floating.ext.formatElapsedTime
import moe.fuqiuluo.mamu.widget.NotificationOverlay
import moe.fuqiuluo.mamu.widget.simpleSingleChoiceDialog

private const val TAG = "FuzzySearchDialog"

/**
 * 模糊搜索对话框
 */
class FuzzySearchDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val onSearchCompleted: ((ranges: List<DisplayMemRegionEntry>, totalFound: Long) -> Unit)? = null,
    private val onRefineCompleted: ((totalFound: Long) -> Unit)? = null
) : BaseDialog(context) {
    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var binding: DialogFuzzySearchBinding
    private lateinit var searchRanges: List<DisplayMemRegionEntry>

    // 当前选中的数据类型
    private var currentValueType: DisplayValueType = DisplayValueType.DWORD

    // 当前模式：true=初始扫描, false=细化搜索
    private var isInitialMode = true

    // 进度相关
    private var progressDialog: SearchProgressDialog? = null
    var isSearching = false
    private var searchStartTime = 0L

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        binding = DialogFuzzySearchBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 初始化搜索范围
        val selectedRanges = mmkv.selectedMemoryRanges
        searchRanges = WuwaDriver.queryMemRegionsWithRetry().divideToSimpleMemoryRange().filter {
            selectedRanges.contains(it.range)
        }

        setupUI()

        // 每次显示对话框时都检查是否应该进入细化模式
        dialog.setOnShowListener {
            checkAndUpdateMode()
        }

        updateModeUI()
    }

    private fun setupUI() {
        // 数据类型选择
        binding.btnSelectType.text = currentValueType.displayName
        binding.btnSelectType.setOnClickListener {
            showValueTypeSelectionDialog()
        }

        // 底部按钮
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSearch.setOnClickListener {
            if (!WuwaDriver.isProcessBound) {
                notification.showError("请先选择进程")
                return@setOnClickListener
            }
            startFuzzyInitialSearch()
        }

        binding.btnNewSearch.setOnClickListener {
            resetToInitialMode()
        }

        // 高级选项展开/折叠
        binding.btnExpandAdvanced.setOnClickListener {
            toggleAdvancedOptions()
        }

        // 细化模式按钮
        setupRefineButtons()

        // 更新当前结果统计
        updateCurrentResults()
    }

    /**
     * 设置细化模式所有按钮
     */
    private fun setupRefineButtons() {
        // 基础条件
        binding.btnUnchanged.setOnClickListener { startRefineSearch(FuzzyCondition.UNCHANGED) }
        binding.btnChanged.setOnClickListener { startRefineSearch(FuzzyCondition.CHANGED) }
        binding.btnIncreased.setOnClickListener { startRefineSearch(FuzzyCondition.INCREASED) }
        binding.btnDecreased.setOnClickListener { startRefineSearch(FuzzyCondition.DECREASED) }

        // 增加指定值
        binding.btnIncreasedBy1.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY,
                1
            )
        }
        binding.btnIncreasedBy10.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY,
                10
            )
        }
        binding.btnIncreasedBy100.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY,
                100
            )
        }
        binding.btnIncreasedByCustom.setOnClickListener { showCustomValueDialog(FuzzyCondition.INCREASED_BY) }

        // 减少指定值
        binding.btnDecreasedBy1.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY,
                1
            )
        }
        binding.btnDecreasedBy10.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY,
                10
            )
        }
        binding.btnDecreasedBy100.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY,
                100
            )
        }
        binding.btnDecreasedByCustom.setOnClickListener { showCustomValueDialog(FuzzyCondition.DECREASED_BY) }

        // 增加百分比
        binding.btnIncreasedBy10Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY_PERCENT,
                10
            )
        }
        binding.btnIncreasedBy50Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY_PERCENT,
                50
            )
        }
        binding.btnIncreasedBy100Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.INCREASED_BY_PERCENT,
                100
            )
        }
        binding.btnIncreasedByPercentCustom.setOnClickListener {
            showCustomPercentDialog(
                FuzzyCondition.INCREASED_BY_PERCENT
            )
        }

        // 减少百分比
        binding.btnDecreasedBy10Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY_PERCENT,
                10
            )
        }
        binding.btnDecreasedBy50Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY_PERCENT,
                50
            )
        }
        binding.btnDecreasedBy100Percent.setOnClickListener {
            startRefineSearch(
                FuzzyCondition.DECREASED_BY_PERCENT,
                100
            )
        }
        binding.btnDecreasedByPercentCustom.setOnClickListener {
            showCustomPercentDialog(
                FuzzyCondition.DECREASED_BY_PERCENT
            )
        }
    }

    /**
     * 切换高级选项显示/隐藏
     */
    private fun toggleAdvancedOptions() {
        val isExpanded = binding.layoutAdvancedOptions.visibility == View.VISIBLE

        if (isExpanded) {
            // 折叠
            binding.layoutAdvancedOptions.visibility = View.GONE
            animateExpandIcon(0f)
        } else {
            // 展开
            binding.layoutAdvancedOptions.visibility = View.VISIBLE
            animateExpandIcon(180f)
        }
    }

    /**
     * 旋转展开图标动画
     */
    private fun animateExpandIcon(toRotation: Float) {
        val rotate = RotateAnimation(
            binding.ivExpandIcon.rotation,
            toRotation,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotate.duration = 200
        rotate.fillAfter = true
        binding.ivExpandIcon.startAnimation(rotate)
        binding.ivExpandIcon.rotation = toRotation
    }

    /**
     * 显示数据类型选择对话框
     */
    private fun showValueTypeSelectionDialog() {
        val allValueTypes = DisplayValueType.entries.filter { !it.isDisabled }.toTypedArray()
        val valueTypeNames = allValueTypes.map { it.displayName }.toTypedArray()
        val valueTypeColors = allValueTypes.map { it.textColor }.toTypedArray()

        val currentIndex = allValueTypes.indexOf(currentValueType)

        context.simpleSingleChoiceDialog(
            title = context.getString(R.string.fuzzy_search_select_type),
            options = valueTypeNames,
            textColors = valueTypeColors,
            selected = currentIndex,
            showTitle = true,
            showRadioButton = false,
            onSingleChoice = { which ->
                currentValueType = allValueTypes[which]
                binding.btnSelectType.text = currentValueType.displayName
            }
        )
    }

    /**
     * 显示自定义数值输入对话框
     */
    private fun showCustomValueDialog(condition: FuzzyCondition) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "输入数值"
        }

        AlertDialog.Builder(context)
            .setTitle("输入数值")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString().toLongOrNull()
                if (value == null) {
                    notification.showError("输入的数值格式错误")
                    return@setPositiveButton
                }
                startRefineSearch(condition, value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示自定义百分比输入对话框
     */
    private fun showCustomPercentDialog(condition: FuzzyCondition) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "输入百分比 (0-100)"
        }

        AlertDialog.Builder(context)
            .setTitle("输入百分比")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val percent = input.text.toString().toLongOrNull()
                if (percent == null || percent < 0 || percent > 100) {
                    notification.showError("百分比必须在 0-100 之间")
                    return@setPositiveButton
                }
                startRefineSearch(condition, percent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 更新模式UI
     */
    @SuppressLint("SetTextI18n")
    private fun updateModeUI() {
        if (isInitialMode) {
            // 初始扫描模式
            binding.tvSubtitle.text = context.getString(R.string.fuzzy_search_initial_subtitle)
            binding.layoutInitialMode.visibility = View.VISIBLE
            binding.layoutRefineMode.visibility = View.GONE
            binding.layoutBottomButtons.visibility = View.VISIBLE
            binding.dividerBottom.visibility = View.VISIBLE
            binding.btnNewSearch.visibility = View.GONE
            binding.btnSearch.visibility = View.VISIBLE
            binding.btnRefine.visibility = View.GONE
        } else {
            // 细化搜索模式
            binding.tvSubtitle.text = context.getString(R.string.fuzzy_search_refine_subtitle)
            binding.layoutInitialMode.visibility = View.GONE
            binding.layoutRefineMode.visibility = View.VISIBLE
            binding.layoutBottomButtons.visibility = View.GONE
            binding.dividerBottom.visibility = View.GONE
            binding.btnNewSearch.visibility = View.VISIBLE
            binding.btnSearch.visibility = View.GONE
            binding.btnRefine.visibility = View.VISIBLE
        }
    }

    /**
     * 更新当前结果统计
     */
    @SuppressLint("SetTextI18n")
    private fun updateCurrentResults() {
        val totalCount = SearchEngine.getTotalResultCount()
        if (totalCount > 0) {
            binding.tvCurrentResults.visibility = View.VISIBLE
            binding.tvCurrentResults.text = context.getString(
                R.string.fuzzy_search_current_results,
                totalCount
            )
        } else {
            binding.tvCurrentResults.visibility = View.GONE
        }
    }

    /**
     * 开始模糊初始扫描
     */
    private fun startFuzzyInitialSearch() {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未选中任何进程")
            return
        }

        if (searchRanges.isEmpty()) {
            notification.showError("未选择内存范围")
            return
        }

        searchScope.launch {
            val nativeRegions = mutableListOf<Long>()
            searchRanges.forEach { region ->
                nativeRegions.add(region.start)
                nativeRegions.add(region.end)
            }

            val success = SearchEngine.startFuzzySearchAsync(
                type = currentValueType,
                ranges = MMKV.defaultMMKV().selectedMemoryRanges,
                keepResult = false
            )

            withContext(Dispatchers.Main) {
                if (success) {
                    isSearching = true
                    searchStartTime = System.currentTimeMillis()
                    showProgressDialog(false)
                    startProgressMonitoring(false)
                } else {
                    notification.showError("启动模糊搜索失败")
                }
            }
        }
    }

    /**
     * 开始细化搜索
     */
    private fun startRefineSearch(condition: FuzzyCondition, param1: Long = 0, param2: Long = 0) {
        if (!WuwaDriver.isProcessBound) {
            notification.showError("未选中任何进程")
            return
        }

        val currentCount = SearchEngine.getTotalResultCount()
        if (currentCount == 0L) {
            notification.showError(context.getString(R.string.fuzzy_search_no_results))
            return
        }

        searchScope.launch {
            val success = SearchEngine.startFuzzyRefineAsync(
                condition = condition,
                param1 = param1,
                param2 = param2
            )

            withContext(Dispatchers.Main) {
                if (success) {
                    isSearching = true
                    searchStartTime = System.currentTimeMillis()
                    showProgressDialog(true)
                    startProgressMonitoring(true)
                } else {
                    notification.showError("启动细化搜索失败")
                }
            }
        }
    }

    /**
     * 重置到初始模式
     */
    private fun resetToInitialMode() {
        isInitialMode = true
        // 清空搜索结果
        SearchEngine.clearSearchResults()
        updateModeUI()
        updateCurrentResults()
    }

    /**
     * 检查并更新对话框模式
     * 如果当前有模糊搜索结果，则进入细化模式；否则进入初始模式
     */
    private fun checkAndUpdateMode() {
        val hasResults = SearchEngine.getTotalResultCount() > 0
        val isFuzzyMode = SearchEngine.getCurrentSearchMode() == SearchMode.FUZZY

        // 如果有模糊搜索结果，则进入细化模式
        isInitialMode = !(hasResults && isFuzzyMode)

        updateModeUI()
        updateCurrentResults()
    }

    /**
     * 显示进度对话框
     */
    private fun showProgressDialog(isRefineSearch: Boolean) {
        progressDialog = SearchProgressDialog(
            context = context,
            isRefineSearch = isRefineSearch,
            onCancelClick = {
                cancelSearch()
            },
            onHideClick = {
                // 隐藏对话框但保持搜索继续
                dismiss()
            }
        ).apply {
            show()
        }
    }

    /**
     * 启动进度监控
     */
    private fun startProgressMonitoring(isRefineSearch: Boolean) {
        searchScope.launch(Dispatchers.Main) {
            while (isActive && isSearching) {
                val status = SearchEngine.getStatus()
                val data = SearchProgressData(
                    currentProgress = SearchEngine.getProgress(),
                    regionsOrAddrsSearched = SearchEngine.getRegionsDone(),
                    totalFound = SearchEngine.getFoundCount(),
                    heartbeat = SearchEngine.getHeartbeat()
                )

                progressDialog?.updateProgress(data)

                when (status) {
                    SearchEngine.Status.COMPLETED -> {
                        val elapsed = System.currentTimeMillis() - searchStartTime
                        onSearchFinished(isRefineSearch, data.totalFound, elapsed)
                        break
                    }

                    SearchEngine.Status.CANCELLED -> {
                        onSearchCancelled()
                        break
                    }

                    SearchEngine.Status.ERROR -> {
                        onSearchError(SearchEngine.getErrorCode())
                        break
                    }

                    else -> {
                        // 继续监控
                    }
                }

                delay(100)
            }
        }
    }

    /**
     * 搜索完成
     */
    private fun onSearchFinished(isRefineSearch: Boolean, totalFound: Long, elapsed: Long) {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null

        val message = context.getString(
            R.string.success_search_complete,
            totalFound,
            formatElapsedTime(elapsed)
        )
        notification.showSuccess(message)

        // 更新UI
        if (!isRefineSearch) {
            // 初始扫描完成，切换到细化模式
            isInitialMode = false
            updateModeUI()
        }
        updateCurrentResults()

        // 回调
        if (isRefineSearch) {
            onRefineCompleted?.invoke(totalFound)
        } else {
            onSearchCompleted?.invoke(searchRanges, totalFound)
        }
    }

    /**
     * 搜索被取消
     */
    private fun onSearchCancelled() {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showWarning(context.getString(R.string.search_cancelled))
    }

    /**
     * 搜索错误
     */
    private fun onSearchError(errorCode: Int) {
        isSearching = false
        progressDialog?.dismiss()
        progressDialog = null
        notification.showError("搜索出错: $errorCode")
    }

    /**
     * 取消搜索
     */
    private fun cancelSearch() {
        if (isSearching) {
            // 通过共享内存请求取消（零延迟）
            SearchEngine.requestCancelViaBuffer()
            // 也通过 CancellationToken 请求取消
            SearchEngine.requestCancel()
        }
    }

    /**
     * 隐藏进度对话框（但保持搜索继续）
     * 用于退出全屏时隐藏进度 UI
     */
    fun hideProgressDialog() {
        progressDialog?.dismiss()
    }

    /**
     * 如果正在搜索，重新显示进度对话框
     * 用于重新进入全屏时恢复进度 UI
     */
    fun showProgressDialogIfSearching() {
        if (isSearching && progressDialog == null) {
            showProgressDialog(isRefineSearch = !isInitialMode)
        }
    }

    fun release() {
        progressDialog?.dismiss()
        progressDialog = null
        searchScope.cancel()
    }

    override fun dismiss() {
        if (!isSearching) {
            release()
        }
        super.dismiss()
    }
}
