package moe.fuqiuluo.mamu.floating.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import moe.fuqiuluo.mamu.databinding.ItemSearchHistoryBinding
import moe.fuqiuluo.mamu.floating.data.model.SearchHistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SearchHistoryAdapter(
    private val onItemClick: (SearchHistoryItem) -> Unit = {},
    private val onItemDelete: (SearchHistoryItem) -> Unit = {}
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private val historyItems = mutableListOf<SearchHistoryItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun setItems(items: List<SearchHistoryItem>) {
        historyItems.clear()
        historyItems.addAll(items)
        notifyDataSetChanged()
    }

    fun removeItem(item: SearchHistoryItem) {
        val index = historyItems.indexOfFirst {
            it.expression == item.expression && it.valueType == item.valueType
        }
        if (index >= 0) {
            historyItems.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun isEmpty(): Boolean = historyItems.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(historyItems[position])
    }

    override fun getItemCount(): Int = historyItems.size

    inner class ViewHolder(
        private val binding: ItemSearchHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun bind(item: SearchHistoryItem) {
            // 设置搜索表达式
            binding.textExpression.text = item.expression

            // 设置值类型
            binding.textValueType.text = item.valueType.code
            binding.textValueType.setTextColor(item.valueType.textColor)

            // 设置时间
            binding.textTime.text = dateFormat.format(Date(item.timestamp))

            // 设置点击事件
            binding.root.setOnClickListener {
                onItemClick(item)
            }

            // 设置删除按钮
            binding.btnDelete.setOnClickListener {
                onItemDelete(item)
            }
        }
    }
}