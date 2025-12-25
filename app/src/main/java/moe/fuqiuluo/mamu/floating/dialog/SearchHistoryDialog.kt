package moe.fuqiuluo.mamu.floating.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import moe.fuqiuluo.mamu.databinding.DialogSearchHistoryBinding
import moe.fuqiuluo.mamu.floating.adapter.SearchHistoryAdapter
import moe.fuqiuluo.mamu.floating.data.local.SearchHistoryRepository
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType
import moe.fuqiuluo.mamu.widget.NotificationOverlay

/**
 * 搜索历史对话框
 */
class SearchHistoryDialog(
    context: Context,
    private val notification: NotificationOverlay,
    private val onHistorySelected: (expression: String, valueType: DisplayValueType) -> Unit
) : BaseDialog(context) {

    private lateinit var binding: DialogSearchHistoryBinding
    private lateinit var adapter: SearchHistoryAdapter

    override fun setupDialog() {
        binding = DialogSearchHistoryBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)

        // 设置透明度
        val mmkv = MMKV.defaultMMKV()
        val opacity = mmkv.getDialogOpacity()
        binding.rootContainer.background?.alpha = (opacity * 255).toInt()

        setupRecyclerView()
        setupButtons()
        loadHistory()
    }

    private fun setupRecyclerView() {
        adapter = SearchHistoryAdapter(
            onItemClick = { item ->
                onHistorySelected(item.expression, item.valueType)
                dialog.dismiss()
            },
            onItemDelete = { item ->
                SearchHistoryRepository.deleteHistory(item)
                adapter.removeItem(item)
                updateEmptyState()
                notification.showSuccess(context.getString(R.string.search_history_deleted))
            }
        )

        binding.historyList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SearchHistoryDialog.adapter
        }
    }

    private fun setupButtons() {
        // 清空全部按钮
        binding.btnClearAll.setOnClickListener {
            SearchHistoryRepository.clearHistory()
            adapter.setItems(emptyList())
            updateEmptyState()
            notification.showSuccess(context.getString(R.string.search_history_cleared))
        }

        // 取消按钮
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dialog.dismiss()
        }
    }

    private fun loadHistory() {
        val history = SearchHistoryRepository.getHistory()
        adapter.setItems(history)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (adapter.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.historyList.visibility = View.GONE
            binding.btnClearAll.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.historyList.visibility = View.VISIBLE
            binding.btnClearAll.visibility = View.VISIBLE
        }
    }
}