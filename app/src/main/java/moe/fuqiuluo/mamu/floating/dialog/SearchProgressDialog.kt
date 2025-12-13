package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.databinding.DialogSearchProgressBinding
import moe.fuqiuluo.mamu.data.settings.floatingOpacity
import kotlin.math.max
import kotlin.random.Random

/**
 * 搜索进度数据
 * 对应native层的共享内存结构（20字节）
 */
data class SearchProgressData(
    val currentProgress: Int,      // 0-100
    val regionsOrAddrsSearched: Int,       // 已搜索的区域数/地址数
    val totalFound: Long,           // 当前找到的结果数
    val heartbeat: Int              // 心跳随机数（用于检测是否卡死）
)

/**
 * 搜索进度对话框
 * 显示实时搜索进度（通过共享内存从native层读取）
 */
class SearchProgressDialog(
    context: Context,
    private val isRefineSearch: Boolean,
    private val onHideClick: (() -> Unit)? = null
) : BaseDialog(context) {
    private lateinit var binding: DialogSearchProgressBinding

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        binding = DialogSearchProgressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)
        dialog.setCancelable(false)

        // 应用透明度设置
        val opacity = MMKV.defaultMMKV().floatingOpacity
        binding.root.background?.alpha = (max(opacity, 0.95f) * 255).toInt()

        // 随机显示一个萌系标题
        binding.progressTitle.text = MOE_TITLES.random()

        if (isRefineSearch) {
            binding.tvCounter.setText(R.string.address_searched)
        }

        // 设置隐藏按钮点击事件
        binding.btnHide.setOnClickListener {
            onHideClick?.invoke()
        }

        // 初始状态
        updateProgress(SearchProgressData(0, 0, 0, 0))
    }

    /**
     * 更新进度显示
     */
    @SuppressLint("SetTextI18n", "DefaultLocale")
    fun updateProgress(data: SearchProgressData) {
        if (!::binding.isInitialized) return

        binding.progressBar.progress = data.currentProgress
        binding.tvProgress.text = "${data.currentProgress}%"
        binding.tvRegions.text = "${data.regionsOrAddrsSearched}"
        binding.tvResults.text = String.format("%,d", data.totalFound)
        binding.progressTitle.text = MOE_TITLES.random(Random(data.heartbeat))
    }
}

private val MOE_TITLES = arrayOf(
    "搜索中...",
    "正在寻找小可爱~",
    "努力翻找中( •̀ ω •́ )✧",
    "嗅探数据ing...",
    "正在召唤内存精灵✨",
    "数据猎人出动！",
    "跟踪目标中(๑•̀ㅂ•́)و✧",
    "内存大冒险开始！",
    "正在解析神秘代码...",
    "挖掘宝藏中~⛏️",
    "数据侦探工作中🔍",
    "扫描银河系...",
    "追踪比特流中...",
    "内存扫雷进行时💣",
    "正在破译密码...",
    "搜寻关键线索中🎯",
    "数据考古中...",
    "内存探险队出发！🚀",
    "追寻数据足迹...",
    "正在拼图中🧩",
    "努力拷打XIN！！！",
    "少女加油中.....",
    "异次元在路上.....",
)