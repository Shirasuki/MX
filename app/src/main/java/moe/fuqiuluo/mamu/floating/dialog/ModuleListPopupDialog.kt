package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogModuleListPopupBinding
import moe.fuqiuluo.mamu.floating.data.model.DisplayMemRegionEntry

/**
 * 模块列表弹窗
 * 用于在新窗口中展示模块列表
 */
class ModuleListPopupDialog(
    context: Context,
    private val title: String,
    private val modules: List<DisplayMemRegionEntry>,
    private val onModuleSelected: (DisplayMemRegionEntry) -> Unit
) : BaseDialog(context) {

    private var showPermission = true
    private var showMemory = true
    private var showPath = true
    private var showStart = true
    private var enableSearch = false
    private var searchKeyword = ""

    private var filteredModules: List<DisplayMemRegionEntry> = modules
    private var adapter: ModuleListAdapter? = null

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        val binding = DialogModuleListPopupBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        // 设置标题
        binding.tvTitle.text = title
        updateCount(binding)

        // 设置列表适配器
        adapter = ModuleListAdapter(
            context = context,
            modules = filteredModules,
            showPermission = showPermission,
            showMemory = showMemory,
            showPath = showPath,
            showStart = showStart
        )
        binding.moduleList.adapter = adapter
        binding.moduleList.choiceMode = ListView.CHOICE_MODE_NONE

        binding.moduleList.setOnItemClickListener { _, _, position, _ ->
            val selectedModule = filteredModules[position]
            onModuleSelected(selectedModule)
            dialog.dismiss()
        }

        // 设置筛选复选框
        setupFilterCheckboxes(binding)

        // 关闭按钮
        binding.btnClose.setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun updateCount(binding: DialogModuleListPopupBinding) {
        binding.tvCount.text = "${filteredModules.size}/${modules.size}"
    }

    private fun setupFilterCheckboxes(binding: DialogModuleListPopupBinding) {
        binding.cbPermission.isChecked = showPermission
        binding.cbMemory.isChecked = showMemory
        binding.cbPath.isChecked = showPath
        binding.cbStart.isChecked = showStart
        binding.cbSearch.isChecked = enableSearch

        val updateFilters = {
            showPermission = binding.cbPermission.isChecked
            showMemory = binding.cbMemory.isChecked
            showPath = binding.cbPath.isChecked
            showStart = binding.cbStart.isChecked
            enableSearch = binding.cbSearch.isChecked
            
            // 显示/隐藏搜索框
            binding.searchContainer.visibility = if (enableSearch) View.VISIBLE else View.GONE
            
            applyFilters(binding)
        }

        binding.cbPermission.setOnCheckedChangeListener { _, _ -> updateFilters() }
        binding.cbMemory.setOnCheckedChangeListener { _, _ -> updateFilters() }
        binding.cbPath.setOnCheckedChangeListener { _, _ -> updateFilters() }
        binding.cbStart.setOnCheckedChangeListener { _, _ -> updateFilters() }
        binding.cbSearch.setOnCheckedChangeListener { _, _ -> updateFilters() }

        // 搜索输入监听
        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchKeyword = s?.toString()?.trim() ?: ""
                applyFilters(binding)
            }
        })
    }

    private fun applyFilters(binding: DialogModuleListPopupBinding) {
        filteredModules = if (enableSearch && searchKeyword.isNotEmpty()) {
            modules.filter { module ->
                // 搜索名称
                module.name.contains(searchKeyword, ignoreCase = true) ||
                // 搜索地址（十六进制）
                module.start.toString(16).contains(searchKeyword, ignoreCase = true) ||
                module.end.toString(16).contains(searchKeyword, ignoreCase = true) ||
                // 搜索权限
                module.permissionString.contains(searchKeyword, ignoreCase = true) ||
                // 搜索类型代码
                module.range.code.contains(searchKeyword, ignoreCase = true) ||
                module.range.displayName.contains(searchKeyword, ignoreCase = true)
            }
        } else {
            modules
        }

        adapter?.updateData(filteredModules, showPermission, showMemory, showPath, showStart)
        updateCount(binding)
    }

    /**
     * 模块列表适配器
     */
    private class ModuleListAdapter(
        private val context: Context,
        private var modules: List<DisplayMemRegionEntry>,
        private var showPermission: Boolean,
        private var showMemory: Boolean,
        private var showPath: Boolean,
        private var showStart: Boolean
    ) : BaseAdapter() {

        fun updateData(
            newModules: List<DisplayMemRegionEntry>,
            showPermission: Boolean,
            showMemory: Boolean,
            showPath: Boolean,
            showStart: Boolean
        ) {
            this.modules = newModules
            this.showPermission = showPermission
            this.showMemory = showMemory
            this.showPath = showPath
            this.showStart = showStart
            notifyDataSetChanged()
        }

        override fun getCount(): Int = modules.size
        override fun getItem(position: Int): DisplayMemRegionEntry = modules[position]
        override fun getItemId(position: Int): Long = position.toLong()

        @SuppressLint("SetTextI18n")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_module_list_simple, parent, false)

            val module = modules[position]
            val holder = view.tag as? ViewHolder ?: ViewHolder(view).also { view.tag = it }

            val typeColor = module.range.color
            val spannable = SpannableStringBuilder()

            // 内存范围类型（使用类型颜色）
            val rangeCode = module.range.code
            val rangeStart = spannable.length
            spannable.append("$rangeCode: ")
            spannable.setSpan(
                ForegroundColorSpan(typeColor),
                rangeStart,
                spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 地址范围（使用类型颜色）
            if (showStart) {
                val addrStart = spannable.length
                spannable.append(String.format("%X-%X ", module.start, module.end))
                spannable.setSpan(
                    ForegroundColorSpan(typeColor),
                    addrStart,
                    spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 权限（使用类型颜色）
            if (showPermission) {
                val permStart = spannable.length
                spannable.append("${module.permissionString} ")
                spannable.setSpan(
                    ForegroundColorSpan(typeColor),
                    permStart,
                    spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 内存大小（使用类型颜色）
            if (showMemory) {
                val sizeStart = spannable.length
                spannable.append("${formatSize(module.size)} ")
                spannable.setSpan(
                    ForegroundColorSpan(typeColor),
                    sizeStart,
                    spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 路径/名称（使用稍暗的类型颜色）
            if (showPath) {
                val pathStart = spannable.length
                val displayName = if (module.name.startsWith("[")) {
                    module.name
                } else {
                    "'${module.name}'"
                }
                spannable.append(displayName)
                spannable.setSpan(
                    ForegroundColorSpan(adjustAlpha(typeColor, 0.7f) or 0xFF000000.toInt()),
                    pathStart,
                    spannable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            holder.contentText.text = spannable
            // 去除背景色，使用透明
            holder.itemContainer.setBackgroundColor(Color.TRANSPARENT)

            return view
        }

        private fun formatSize(size: Long): String {
            return when {
                size >= 1024 * 1024 * 1024 -> String.format("%.1fG", size / (1024.0 * 1024.0 * 1024.0))
                size >= 1024 * 1024 -> String.format("%.1fM", size / (1024.0 * 1024.0))
                size >= 1024 -> String.format("%.1fK", size / 1024.0)
                else -> "${size}B"
            }
        }

        private fun adjustAlpha(color: Int, factor: Float): Int {
            val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
            val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
            val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
            return Color.rgb(r, g, b)
        }

        private class ViewHolder(view: View) {
            val itemContainer: View = view.findViewById(R.id.item_container)
            val contentText: TextView = view.findViewById(R.id.content_text)
        }
    }
}
