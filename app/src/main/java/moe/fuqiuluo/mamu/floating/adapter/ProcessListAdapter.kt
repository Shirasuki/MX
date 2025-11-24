package moe.fuqiuluo.mamu.floating.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import moe.fuqiuluo.mamu.databinding.ItemProcessListBinding
import moe.fuqiuluo.mamu.floating.model.DisplayProcessInfo
import moe.fuqiuluo.mamu.utils.ByteFormatUtils.formatBytes

class ProcessListAdapter(
    private val context: Context,
    private val processList: List<DisplayProcessInfo>
): RecyclerView.Adapter<ProcessListAdapter.ProcessViewHolder>() {

    var onItemClick: ((Int) -> Unit)? = null

    override fun getItemCount(): Int = processList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProcessViewHolder {
        val binding = ItemProcessListBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )
        return ProcessViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProcessViewHolder, position: Int) {
        holder.bind(processList[position], position)
    }

    inner class ProcessViewHolder(
        private val binding: ItemProcessListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick?.invoke(position)
                }
            }
        }

        fun bind(processInfo: DisplayProcessInfo, position: Int) {
            binding.apply {
                processRss.text = formatBytes(processInfo.rss * 4096, 0)
                processName.text = processInfo.validName
                processDetails.text = "[${processInfo.pid}]"
                processIcon.setImageDrawable(processInfo.icon)
            }
        }
    }
}