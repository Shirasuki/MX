use super::*;

/// BFS遍历的路径节点。
/// 存储当前目标地址和从target到此节点的偏移历史。
#[derive(Clone)]
struct PathNode {
    /// 当前正在搜索指向此地址的指针
    current_target: u64,
    /// 偏移历史：offsets[0] 是从深度0到深度1的偏移，依此类推。
    /// 构建链时需要反转以获得 root->target 的顺序
    offset_history: Vec<i64>,
    /// 路径中已访问的地址集合，用于检测循环引用
    visited_addresses: HashSet<u64>,
}

impl PathNode {
    fn new(target: u64) -> Self {
        let mut visited = HashSet::with_capacity(8);
        visited.insert(target);
        Self {
            current_target: target,
            offset_history: Vec::new(),
            visited_addresses: visited,
        }
    }

    fn with_capacity(target: u64, capacity: usize) -> Self {
        let mut visited = HashSet::with_capacity(capacity);
        visited.insert(target);
        Self {
            current_target: target,
            offset_history: Vec::with_capacity(capacity),
            visited_addresses: visited,
        }
    }

    fn depth(&self) -> usize {
        self.offset_history.len()
    }

    /// 检查地址是否已在当前路径中访问过（循环引用）
    fn is_visited(&self, address: u64) -> bool {
        self.visited_addresses.contains(&address)
    }

    /// 创建子节点，带有给定的指针地址和偏移
    fn child(&self, ptr_address: u64, offset: i64) -> Self {
        let mut new_history = self.offset_history.clone();
        new_history.push(offset);
        let mut new_visited = self.visited_addresses.clone();
        new_visited.insert(ptr_address);
        Self {
            current_target: ptr_address,
            offset_history: new_history,
            visited_addresses: new_visited,
        }
    }
}

/// 散射阶段发现的候选指针
struct Candidate {
    /// 指向父节点目标的指针地址
    ptr_address: u64,
    /// 从指针值到父节点目标的偏移
    offset: i64,
    /// 父PathNode在当前层中的索引
    parent_idx: usize,
}

/// 每层最大候选数，防止内存爆炸
const MAX_CANDIDATES_PER_LAYER: usize = 500 * 10000;

/// 每个父节点最大扇出数，限制单个节点产生过多子节点
const MAX_FANOUT_PER_NODE: usize = 10 * 10000;

/// 使用分层BFS + rayon并行构建指针链。
///
/// 算法流程：
/// 1. 从目标地址初始化CurrentLayer
/// 2. 对于每个深度层级：
///    a. 散射阶段：并行扫描CurrentLayer中所有节点，查找候选指针
///    b. 过滤循环引用：每个路径内部不允许重复访问同一地址
///    c. 检查静态根并构建完整链
///    d. 同层去重：相同目标地址只保留一条路径，避免指数爆炸
///    e. 将非静态候选移动到NextLayer
/// 3. 重复直到达到max_depth或没有更多候选
///
/// 内存优化：
/// - 路径内循环检测：使用 PathNode.visited_addresses 防止 A→B→C→B 类型的循环
/// - 扇出限制：每个节点最多产生 MAX_FANOUT_PER_NODE 个子节点
/// - 层级限制：每层最多 MAX_CANDIDATES_PER_LAYER 个节点
pub fn build_pointer_chains_layered_bfs<F, C>(
    pointer_lib: &MmapQueue<PointerData>,
    static_modules: &[VmStaticData],
    config: &PointerScanConfig,
    progress_callback: F,
    check_cancelled: C,
) -> Result<Vec<PointerChain>>
where
    F: Fn(u32, i32, i64) + Sync,
    C: Fn() -> bool + Sync,
{
    info!(
        "构建指针链 (分层BFS) 目标=0x{:X}, 最大深度={}, 最大偏移=0x{:X}",
        config.target_address, config.max_depth, config.max_offset
    );

    let mut results: Vec<PointerChain> = Vec::new();

    // 用目标地址初始化
    let mut current_layer = vec![PathNode::new(config.target_address)];

    let cancelled = AtomicBool::new(false);
    let chains_found = AtomicUsize::new(0);

    for depth in 0..config.max_depth {
        if check_cancelled() {
            cancelled.store(true, AtomicOrdering::Relaxed);
            break;
        }

        if current_layer.is_empty() {
            debug!("深度 {} 没有更多候选", depth);
            break;
        }

        info!("处理深度 {}, 当前层 {} 个节点", depth, current_layer.len());

        // 并行扫描：每个线程处理current_layer的一个分块
        // 并将候选收集到线程局部缓冲区
        let candidates: Vec<Candidate> = current_layer
            .par_iter()
            .enumerate()
            .flat_map(|(parent_idx, node)| {
                if cancelled.load(AtomicOrdering::Relaxed) {
                    return Vec::new();
                }

                let pointers = find_pointers_to_range(pointer_lib, node.current_target, config.max_offset);

                // 过滤掉循环引用的候选，并限制扇出数量
                pointers
                    .into_iter()
                    .filter(|(ptr_address, _)| !node.is_visited(*ptr_address))
                    .take(MAX_FANOUT_PER_NODE)
                    .map(|(ptr_address, offset)| Candidate {
                        ptr_address,
                        offset,
                        parent_idx,
                    })
                    .collect::<Vec<_>>()
            })
            .collect();

        if cancelled.load(AtomicOrdering::Relaxed) {
            break;
        }

        debug!("散射阶段在深度 {} 发现 {} 个候选", depth, candidates.len());

        // 遍历所有候选
        // 注意：循环引用检查已在散射阶段通过 is_visited 完成
        let mut next_layer: Vec<PathNode> = Vec::new();

        for candidate in candidates {
            let parent = &current_layer[candidate.parent_idx];

            // 检查此指针是否来自静态模块
            if let Some((module_name, module_index, base_offset)) =
                classify_pointer(candidate.ptr_address, static_modules, config.data_start, config.bss_start, config.max_offset)
            {
                // 找到一条完整链！
                let mut chain = PointerChain::with_capacity(config.target_address, parent.depth() + 2);

                // 添加静态根
                chain.push(PointerChainStep::static_root(module_name, module_index, base_offset as i64));

                // 添加从静态指针到其目标的偏移（即使是0也要添加，代表一次指针解引用）
                chain.push(PointerChainStep::dynamic_offset(candidate.offset));

                // 按反序添加中间偏移 (parent -> ... -> target)
                for &offset in parent.offset_history.iter().rev() {
                    chain.push(PointerChainStep::dynamic_offset(offset));
                }

                results.push(chain);
                chains_found.fetch_add(1, AtomicOrdering::Relaxed);
            }

            // 如果未达到最大深度，继续向上搜索
            if depth + 1 < config.max_depth {
                // 只将非静态指针添加到下一层（或者如果不是scan_static_only则全部添加）
                if classify_pointer(candidate.ptr_address, static_modules, config.data_start, config.bss_start, config.max_offset).is_none() {
                    next_layer.push(parent.child(candidate.ptr_address, candidate.offset));
                }
            }
        }

        // 剪枝：如果候选过多，只保留一部分
        if next_layer.len() > MAX_CANDIDATES_PER_LAYER {
            warn!("[候选裁剪] 在深度 {} 将候选从 {} 剪枝到 {}", depth, next_layer.len(), MAX_CANDIDATES_PER_LAYER);
            next_layer.truncate(MAX_CANDIDATES_PER_LAYER);
        }

        // 报告进度
        progress_callback(depth + 1, config.max_depth as i32, chains_found.load(AtomicOrdering::Relaxed) as i64);

        current_layer = next_layer;
    }

    // 最终进度报告
    progress_callback(config.max_depth, config.max_depth as i32, results.len() as i64);

    info!("指针链构建 (分层BFS) 完成。找到 {} 条链", results.len());

    // 按深度排序（短链优先），然后按模块名排序
    results.par_sort_by(|a, b| {
        let depth_cmp = a.depth().cmp(&b.depth());
        if depth_cmp != Ordering::Equal {
            return depth_cmp;
        }
        let a_name = a.steps.first().and_then(|s| s.module_name.as_ref());
        let b_name = b.steps.first().and_then(|s| s.module_name.as_ref());
        a_name.cmp(&b_name)
    });

    Ok(results)
}
