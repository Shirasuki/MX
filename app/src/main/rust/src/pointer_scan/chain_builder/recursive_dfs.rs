use super::*;
use crossbeam_channel::{unbounded, Sender};
use log::{log_enabled, Level};
use std::cmp::max;
use std::thread;
use std::time::{Duration, Instant};

struct DfsContext<'a> {
    pointer_lib: &'a MmapQueue<PointerData>,
    static_modules: &'a [VmStaticData],
    config: &'a PointerScanConfig,
    cancelled: &'a AtomicBool,
}

pub fn build_pointer_chains_dfs<F, C>(
    pointer_lib: &MmapQueue<PointerData>,
    static_modules: &[VmStaticData],
    config: &PointerScanConfig,
    progress_callback: F,
    check_cancelled: C,
) -> Result<Vec<PointerChain>>
where
    F: Fn(u32, i32, i64) + Sync + Send + 'static,
    C: Fn() -> bool + Sync,
{
    if log_enabled!(Level::Debug) {
        info!("构建指针链 目标=0x{:X}", config.target_address);
    }

    let cancelled = AtomicBool::new(false);

    let (tx, rx) = unbounded::<PointerChain>();

    let max_depth = config.max_depth as i32;
    let consumer_handle = thread::spawn(move || {
        let mut results = Vec::new();
        let mut last_report = Instant::now();

        // 不断接收直到所有 Sender 关闭
        while let Ok(chain) = rx.recv() {
            let depth = chain.depth() as u32;
            results.push(chain);

            // 限制回调频率，避免刷新太快拖慢速度 (例如每 100ms 刷新一次)
            if last_report.elapsed() >= Duration::from_millis(100) {
                progress_callback(depth, max_depth, results.len() as i64);
                last_report = Instant::now();
            }
        }

        // 最终报告
        progress_callback(max_depth as u32, max_depth, results.len() as i64);
        results
    });

    // 准备搜索上下文
    let ctx = DfsContext {
        pointer_lib,
        static_modules,
        config,
        cancelled: &cancelled,
    };

    // 获取第一层入口点 (反向搜索第一步)
    let roots = find_pointers_to_range(pointer_lib, config.target_address, config.max_offset);
    if log_enabled!(Level::Debug) {
        info!("第一层入口点数量: {}", roots.len());
    }

    // 并行生产者 (Producers)
    // Rayon 负责并行调度，每个任务持有一个 tx 的克隆
    roots.par_iter().for_each_with(tx, |local_tx, (ptr_addr, offset)| {
        if check_cancelled() {
            cancelled.store(true, AtomicOrdering::Relaxed);
            return;
        }

        // 初始化路径状态
        let mut offset_history = Vec::with_capacity(config.max_depth as usize);
        offset_history.push(*offset);

        // 环路检测：记录路径上的地址
        let mut visited_addrs = Vec::with_capacity(config.max_depth as usize);
        visited_addrs.push(config.target_address); // 目标本身
        visited_addrs.push(*ptr_addr);

        // 开始递归
        dfs_recursive(&ctx, local_tx, *ptr_addr, 1, &mut offset_history, &mut visited_addrs);
    });

    // 所有 Rayon 任务完成后，local_tx 会被自动 Drop。
    // 当所有 Sender 都 Drop 后，rx.recv() 会返回 Err，消费者线程随之结束。

    // 等待结果
    let final_results = consumer_handle.join().map_err(|_| anyhow::anyhow!("消费者线程崩溃"))?;

    if log_enabled!(Level::Debug) {
        info!("DFS 扫描完成，共找到 {} 条链", final_results.len());
    }

    Ok(final_results)
}

/// 核心递归函数
/// 参数中传入 local_tx: &Sender<PointerChain> 用于发送结果
fn dfs_recursive(ctx: &DfsContext, tx: &Sender<PointerChain>, current_address: u64, depth: u32, offset_history: &mut Vec<i64>, visited_addrs: &mut Vec<u64>) {
    // 检查取消
    if ctx.cancelled.load(AtomicOrdering::Relaxed) {
        return;
    }

    let config = ctx.config;
    // 检查是否到达静态基址
    if let Some((mod_name, mod_idx, base_offset)) = classify_pointer(current_address, ctx.static_modules, config.data_start, config.bss_start, config.max_offset)
    {
        // 构建链条
        let mut chain = PointerChain::with_capacity(ctx.config.target_address, offset_history.len() + 1);
        chain.push(PointerChainStep::static_root(mod_name, mod_idx, base_offset as i64));

        // 反向添加偏移
        for off in offset_history.iter().rev() {
            chain.push(PointerChainStep::dynamic_offset(*off));
        }

        // 发送结果 (无锁，仅内存拷贝)
        // 如果通道已断开（极其罕见），忽略错误
        let _ = tx.send(chain);
        return; // 这里的 return 意味着：如果一个指针指向了静态基址，这一枝就到此为止。
    }

    // 深度限制
    if depth >= ctx.config.max_depth {
        return;
    }

    // 查找父节点
    // 这里是性能关键点：大量的随机 IO 读取
    let parents = find_pointers_to_range(ctx.pointer_lib, current_address, ctx.config.max_offset);

    for (parent_addr, offset) in parents {
        // 环路检测
        // 线性扫描小数组非常快
        if visited_addrs.contains(&parent_addr) {
            continue;
        }

        // 压栈 (Push State)
        offset_history.push(offset);
        visited_addrs.push(parent_addr);

        // 递归
        dfs_recursive(ctx, tx, parent_addr, depth + 1, offset_history, visited_addrs);

        // 弹栈 (Pop State / Backtrack)
        visited_addrs.pop();
        offset_history.pop();
    }
}
