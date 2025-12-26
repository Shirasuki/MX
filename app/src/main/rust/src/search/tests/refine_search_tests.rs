// //! Refine search (filter) tests
// //! Tests various combinations of initial search and refine operations
// 
// #[cfg(test)]
// mod tests {
//     use crate::search::engine::ValuePair;
//     use crate::search::tests::mock_memory::MockMemory;
//     use crate::search::{BPLUS_TREE_ORDER, SearchEngineManager, SearchMode, SearchQuery, SearchValue, ValueType};
//     use crate::wuwa::PageStatusBitmap;
//     use anyhow::Result;
//     use bplustree::BPlusTreeSet;
//     use std::collections::{BTreeSet, HashSet, VecDeque};
// 
//     /// Helper function to perform a single value search
//     fn perform_single_search(
//         mem: &MockMemory,
//         search_value: &SearchValue,
//         base_addr: u64,
//         region_size: usize,
//     ) -> Result<BPlusTreeSet<ValuePair>> {
//         let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
//         let mut matches_checked = 0usize;
// 
//         let chunk_size = 64 * 1024;
//         let mut current = base_addr;
//         let end_addr = base_addr + region_size as u64;
//         let value_type = search_value.value_type();
// 
//         while current < end_addr {
//             let chunk_end = (current + chunk_size as u64).min(end_addr);
//             let chunk_len = (chunk_end - current) as usize;
// 
//             let mut chunk_buffer = vec![0u8; chunk_len];
//             let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);
// 
//             if mem
//                 .mem_read_with_status(current, &mut chunk_buffer, &mut page_status)
//                 .is_ok()
//             {
//                 SearchEngineManager::search_in_buffer_with_status(
//                     &chunk_buffer,
//                     current,
//                     base_addr,
//                     end_addr,
//                     value_type.size(),
//                     search_value,
//                     value_type,
//                     &page_status,
//                     &mut results,
//                     &mut matches_checked,
//                 );
//             }
// 
//             current = chunk_end;
//         }
// 
//         Ok(results)
//     }
// 
//     /// Helper function to simulate refine search on existing results
//     fn refine_results_single(
//         mem: &MockMemory,
//         existing_results: &BPlusTreeSet<ValuePair>,
//         search_value: &SearchValue,
//     ) -> Result<Vec<ValuePair>> {
//         let mut refined_results = Vec::with_capacity(existing_results.len());
// 
//         for pair in existing_results.iter() {
//             let addr = pair.addr;
//             let buffer_size = pair.value_type.size();
// 
//             if let Ok(buffer) = mem.mem_read(addr, buffer_size) {
//                 if let Ok(true) = search_value.matched(&buffer) {
//                     refined_results.push(pair.clone());
//                 }
//             }
//         }
// 
//         Ok(refined_results)
//     }
// 
//     /// Benchmark version of refine_results_single with detailed timing
//     ///
//     /// # Performance Optimization
//     /// Uses `Vec` instead of `BPlusTreeSet` for result storage because:
//     /// - Input from `existing_results.iter()` is already sorted (B+tree iteration is ordered)
//     /// - Sequential insertion preserves order naturally
//     /// - `Vec::push` is O(1) amortized vs B+tree insert O(log n)
//     /// - Pre-allocation with `with_capacity` eliminates reallocation overhead
//     fn refine_results_single_with_benchmark(
//         mem: &MockMemory,
//         existing_results: &BPlusTreeSet<ValuePair>,
//         search_value: &SearchValue,
//     ) -> Result<(Vec<ValuePair>, BenchmarkStats)> {
//         use std::time::Instant;
// 
//         // Pre-allocate capacity for best-case scenario (all results match)
//         let mut refined_results = Vec::with_capacity(existing_results.len());
// 
//         // Timing accumulators
//         let mut total_iteration_time = std::time::Duration::ZERO;
//         let mut total_mem_read_time = std::time::Duration::ZERO;
//         let mut total_match_time = std::time::Duration::ZERO;
//         let mut total_insert_time = std::time::Duration::ZERO;
// 
//         let mut iteration_count = 0;
//         let mut mem_read_count = 0;
//         let mut match_count = 0;
//         let mut insert_count = 0;
// 
//         let overall_start = Instant::now();
// 
//         for pair in existing_results.iter() {
//             let iter_start = Instant::now();
//             let addr = pair.addr;
//             let buffer_size = pair.value_type.size();
//             total_iteration_time += iter_start.elapsed();
//             iteration_count += 1;
// 
//             let read_start = Instant::now();
//             let read_result = mem.mem_read(addr, buffer_size);
//             total_mem_read_time += read_start.elapsed();
//             mem_read_count += 1;
// 
//             if let Ok(buffer) = read_result {
//                 let match_start = Instant::now();
//                 let match_result = search_value.matched(&buffer);
//                 total_match_time += match_start.elapsed();
//                 match_count += 1;
// 
//                 if let Ok(true) = match_result {
//                     let insert_start = Instant::now();
//                     refined_results.push(pair.clone());
//                     total_insert_time += insert_start.elapsed();
//                     insert_count += 1;
//                 }
//             }
//         }
// 
//         let total_time = overall_start.elapsed();
// 
//         let stats = BenchmarkStats {
//             total_time,
//             iteration_time: total_iteration_time,
//             mem_read_time: total_mem_read_time,
//             match_time: total_match_time,
//             insert_time: total_insert_time,
//             iteration_count,
//             mem_read_count,
//             match_count,
//             insert_count,
//             result_count: refined_results.len(),
//         };
// 
//         Ok((refined_results, stats))
//     }
// 
//     /// Statistics for benchmark results
//     #[derive(Debug, Clone)]
//     struct BenchmarkStats {
//         total_time: std::time::Duration,
//         iteration_time: std::time::Duration,
//         mem_read_time: std::time::Duration,
//         match_time: std::time::Duration,
//         insert_time: std::time::Duration,
//         iteration_count: usize,
//         mem_read_count: usize,
//         match_count: usize,
//         insert_count: usize,
//         result_count: usize,
//     }
// 
//     impl BenchmarkStats {
//         fn print_report(&self) {
//             println!("\n╔════════════════════════════════════════════════════════╗");
//             println!("║           Refine Search Performance Report            ║");
//             println!("╠════════════════════════════════════════════════════════╣");
// 
//             println!(
//                 "║ Total Time:          {:>8.2} ms  (100.0%)",
//                 self.total_time.as_secs_f64() * 1000.0
//             );
//             println!("╠────────────────────────────────────────────────────────╣");
// 
//             let total_ms = self.total_time.as_secs_f64() * 1000.0;
//             let iter_ms = self.iteration_time.as_secs_f64() * 1000.0;
//             let read_ms = self.mem_read_time.as_secs_f64() * 1000.0;
//             let match_ms = self.match_time.as_secs_f64() * 1000.0;
//             let insert_ms = self.insert_time.as_secs_f64() * 1000.0;
// 
//             println!(
//                 "║ Iteration:           {:>8.2} ms  ({:>5.1}%)",
//                 iter_ms,
//                 (iter_ms / total_ms) * 100.0
//             );
//             println!(
//                 "║ Memory Read:         {:>8.2} ms  ({:>5.1}%)",
//                 read_ms,
//                 (read_ms / total_ms) * 100.0
//             );
//             println!(
//                 "║ Value Matching:      {:>8.2} ms  ({:>5.1}%)",
//                 match_ms,
//                 (match_ms / total_ms) * 100.0
//             );
//             println!(
//                 "║ Result Insert:       {:>8.2} ms  ({:>5.1}%)",
//                 insert_ms,
//                 (insert_ms / total_ms) * 100.0
//             );
//             println!("╠────────────────────────────────────────────────────────╣");
// 
//             println!("║ Operations Count:                                      ║");
//             println!("║   Iterations:        {:>8}", self.iteration_count);
//             println!("║   Memory Reads:      {:>8}", self.mem_read_count);
//             println!("║   Match Checks:      {:>8}", self.match_count);
//             println!("║   Inserts:           {:>8}", self.insert_count);
//             println!("║   Final Results:     {:>8}", self.result_count);
//             println!("╠────────────────────────────────────────────────────────╣");
// 
//             if self.iteration_count > 0 {
//                 println!("║ Average Time per Operation:                            ║");
//                 println!(
//                     "║   Per Iteration:     {:>8.2} µs",
//                     (iter_ms * 1000.0) / self.iteration_count as f64
//                 );
//                 println!(
//                     "║   Per Memory Read:   {:>8.2} µs",
//                     (read_ms * 1000.0) / self.mem_read_count as f64
//                 );
//                 if self.match_count > 0 {
//                     println!(
//                         "║   Per Match Check:   {:>8.2} µs",
//                         (match_ms * 1000.0) / self.match_count as f64
//                     );
//                 }
//                 if self.insert_count > 0 {
//                     println!(
//                         "║   Per Insert:        {:>8.2} µs",
//                         (insert_ms * 1000.0) / self.insert_count as f64
//                     );
//                 }
//             }
// 
//             println!("╚════════════════════════════════════════════════════════╝\n");
//         }
//     }
// 
//     fn refine_results_group_dfs(
//         mem: &MockMemory,
//         existing_results: &BPlusTreeSet<ValuePair>,
//         query: &SearchQuery,
//     ) -> Result<BPlusTreeSet<ValuePair>> {
//         let mut refined_results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
// 
//         if query.values.is_empty() {
//             return Ok(refined_results);
//         }
// 
//         // 读取全部地址与当前值
//         let mut addr_values: Vec<(u64, Vec<u8>)> = Vec::with_capacity(existing_results.len());
//         for pair in existing_results.iter() {
//             let addr = pair.addr;
//             let value_size = pair.value_type.size();
//             if let Ok(buffer) = mem.mem_read(addr, value_size) {
//                 addr_values.push((addr, buffer));
//             }
//         }
// 
//         if addr_values.is_empty() {
//             return Ok(refined_results);
//         }
// 
//         // 找所有锚点
//         let mut anchors: Vec<u64> = Vec::new();
//         for (addr, bytes) in &addr_values {
//             if query.values[0].matched(bytes)? {
//                 anchors.push(*addr);
//             }
//         }
//         if anchors.is_empty() {
//             return Ok(refined_results);
//         }
// 
//         // 为加速：地址已按升序（BPlusTreeSet保证）
//         // 主循环：每个锚点执行 DFS
//         for anchor_addr in anchors {
//             let (min_addr, max_addr) = match query.mode {
//                 SearchMode::Unordered => (
//                     anchor_addr.saturating_sub(query.range as u64),
//                     anchor_addr + query.range as u64,
//                 ),
//                 SearchMode::Ordered => (anchor_addr, anchor_addr + query.range as u64),
//             };
// 
//             // 候选（不含锚点本身，避免重复使用）
//             let mut candidates: Vec<(u64, &Vec<u8>)> = Vec::new();
//             for (addr, bytes) in &addr_values {
//                 if *addr >= min_addr && *addr <= max_addr && *addr != anchor_addr {
//                     candidates.push((*addr, bytes));
//                 }
//             }
// 
//             // 剪枝：如果候选数量 < (query.values.len() - 1) 不可能成功
//             if candidates.len() < query.values.len() - 1 {
//                 continue;
//             }
// 
//             // DFS：寻找所有满足的组合，把地址并入 refined_results
//             // 使用 used 保障地址不重复
//             let mut used: HashSet<u64> = HashSet::new();
//             used.insert(anchor_addr);
// 
//             // 当前选择的地址（含锚点）
//             let mut chosen: Vec<(u64, ValueType)> = Vec::with_capacity(query.values.len());
//             chosen.push((anchor_addr, query.values[0].value_type()));
// 
//             // 回溯函数
//             fn dfs(
//                 cand_idx: usize,
//                 candidates: &[(u64, &Vec<u8>)],
//                 query: &SearchQuery,
//                 chosen: &mut Vec<(u64, ValueType)>,
//                 used: &mut HashSet<u64>,
//                 refined_results: &mut BPlusTreeSet<ValuePair>,
//             ) -> Result<()> {
//                 let need_total = query.values.len();
//                 let have = chosen.len();
// 
//                 // 成功匹配全部查询值
//                 if have == need_total {
//                     for (addr, vt) in chosen.iter() {
//                         refined_results.insert(ValuePair::new(*addr, *vt));
//                     }
//                     return Ok(());
//                 }
// 
//                 // 剩余还需要匹配的查询值数量
//                 let remaining_need = need_total - have;
// 
//                 // 剩余候选是否足够（剪枝）
//                 let remaining_candidates = candidates.len().saturating_sub(cand_idx);
//                 if remaining_candidates < remaining_need {
//                     return Ok(());
//                 }
// 
//                 // 当前要匹配的查询值
//                 let sv = &query.values[have];
// 
//                 // 遍历从 cand_idx 开始的候选
//                 for i in cand_idx..candidates.len() {
//                     let (addr, bytes) = candidates[i];
//                     // 如果不匹配则跳过
//                     if sv.value_type().size() > bytes.len() // 安全检查, 用户搜索出来一堆byte, 然后尝试用dword改善导致的
//                         || !sv.matched(bytes)? {
//                         continue;
//                     }
//                     // 地址唯一约束
//                     if used.contains(&addr) {
//                         continue;
//                     }
//                     // 选择
//                     used.insert(addr);
//                     chosen.push((addr, sv.value_type()));
// 
//                     // 下一层从 i+1 开始（保证组合不重复乱序）
//                     dfs(i + 1, candidates, query, chosen, used, refined_results)?;
// 
//                     // 回溯
//                     chosen.pop();
//                     used.remove(&addr);
//                 }
// 
//                 Ok(())
//             }
// 
//             dfs(0, &candidates, query, &mut chosen, &mut used, &mut refined_results)?;
//         }
// 
//         Ok(refined_results)
//     }
// 
//     fn refine_results_group(
//         mem: &MockMemory,
//         existing_results: &BPlusTreeSet<ValuePair>,
//         query: &SearchQuery,
//     ) -> Result<Vec<ValuePair>> {
//         assert!(query.values.len() > 1, "Group refine requires multiple values in query");
//         let refined_bplus = refine_results_group_dfs(mem, existing_results, query)?;
//         let mut results = Vec::with_capacity(refined_bplus.len());
//         for pair in refined_bplus.into_iter() {
//             results.push(pair.clone());
//         }
//         Ok(results)
//     }
// 
//     #[test]
//     fn test_single_to_single_refine() -> Result<()> {
//         println!("\n=== Test: Single value search → Single value refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7000000000, 1 * 1024 * 1024)?; // 1MB
// 
//         // Write test data: value1 at offset, value2 at offset+4
//         let test_data = vec![
//             (0x1000, 100u32, 200u32), // First: ✓, Refine: ✓
//             (0x2000, 100u32, 300u32), // First: ✓, Refine: ✗
//             (0x3000, 100u32, 200u32), // First: ✓, Refine: ✓
//             (0x4000, 150u32, 200u32), // First: ✗
//             (0x5000, 100u32, 200u32), // First: ✓, Refine: ✓
//             (0x6000, 100u32, 250u32), // First: ✓, Refine: ✗
//         ];
// 
//         for (offset, val1, val2) in &test_data {
//             mem.mem_write_u32(base_addr + offset, *val1)?;
//             mem.mem_write_u32(base_addr + offset + 4, *val2)?;
//             println!("Write: 0x{:X} = {}, +4 = {}", base_addr + offset, val1, val2);
//         }
// 
//         // First search: Find all addresses with value 100
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         results1.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         println!("\nFirst search results: {} matches for value 100", results1.len());
//         assert_eq!(results1.len(), 5, "Should find 5 addresses with value 100");
// 
//         // Modify some values in memory (simulating value changes)
//         mem.mem_write_u32(base_addr + 0x2000, 200u32)?;
//         mem.mem_write_u32(base_addr + 0x6000, 200u32)?;
// 
//         // Refine search
//         let query2 = SearchValue::fixed(200, ValueType::Dword);
//         let results2 = refine_results_single(&mem, &results1, &query2)?;
// 
//         println!("\nRefine search results: {} addresses have value 200", results2.len());
//         assert_eq!(results2.len(), 2, "Should find 2 addresses that have value 200");
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_single_to_group_refine_unordered() -> Result<()> {
//         println!("\n=== Test: Single value search → Group search refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7100000000, 1 * 1024 * 1024)?;
// 
//         // Write test data: single value, then check if followed by a pattern
//         let test_patterns = vec![
//             (0x1000, 100u32),
//             (0x2000, 100u32),
//             (0x3000, 100u32),
//             (0x4000, 150u32),
//             (0x5000, 100u32),
//         ];
// 
//         for (offset, v1) in &test_patterns {
//             mem.mem_write_u32(base_addr + offset, *v1)?;
//             println!("Write: 0x{:X} = [{}]", base_addr + offset, v1);
//         }
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         println!("\nFirst search results: {} matches for value 100", results1.len());
//         assert_eq!(results1.len(), 4, "Should find 4 addresses with value 100");
// 
//         results1.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!(
//             "\nRefine search results: {} addresses have pattern [200, 300] nearby",
//             results2.len()
//         );
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         assert_eq!(results2.len(), 0, "Should find 0 addresses with pattern [200, 300]");
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_single_to_group_refine_unordered2() -> Result<()> {
//         println!("\n=== Test: Single value search → Group search refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7100000000, 1 * 1024 * 1024)?;
// 
//         // Write test data: single value, then check if followed by a pattern
//         let test_patterns = vec![
//             (0x1000, 100u32),
//             (0x2000, 100u32),
//             (0x3000, 100u32),
//             (0x4000, 150u32),
//             (0x5000, 100u32),
//         ];
// 
//         for (offset, v1) in &test_patterns {
//             mem.mem_write_u32(base_addr + offset, *v1)?;
//             println!("Write: 0x{:X} = [{}]", base_addr + offset, v1);
//         }
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         assert_eq!(results1.len(), 4); // 找到4个地址才是正确的
// 
//         results1.iter().enumerate().for_each(|(index, pair)| {
//             println!("Found: 0x{:X}", pair.addr);
//             if index == 0 {
//                 assert_eq!(pair.addr, base_addr + 0x1000);
//                 mem.mem_write_u32(pair.addr, 200u32).unwrap();
//             }
//             if index == 1 {
//                 assert_eq!(pair.addr, base_addr + 0x2000);
//                 mem.mem_write_u32(pair.addr, 300u32).unwrap();
//             }
//             if index == 2 {
//                 assert_eq!(pair.addr, base_addr + 0x3000);
//                 mem.mem_write_u32(pair.addr, 200u32).unwrap();
//             }
//             if index == 3 {
//                 assert_eq!(pair.addr, base_addr + 0x5000);
//                 mem.mem_write_u32(pair.addr, 400u32).unwrap();
//             }
//         });
// 
//         // 这个时候内存发生改变了
//         // Memory Layout Example:
//         //
//         // Initial Write:
//         // ┌─────────────────┬────────┐
//         // │ Address         │ Value  │
//         // ├─────────────────┼────────┤
//         // │ base + 0x1000   │  100   │
//         // │ base + 0x2000   │  100   │
//         // │ base + 0x3000   │  100   │
//         // │ base + 0x4000   │  150   │ ← Not in results1, unchanged
//         // │ base + 0x5000   │  100   │
//         // └─────────────────┴────────┘
//         //
//         // After Modification (results1 processing):
//         // ┌─────────────────┬────────┬──────────────────┐
//         // │ Address         │ Value  │ Modified By      │
//         // ├─────────────────┼────────┼──────────────────┤
//         // │ base + 0x1000   │  200   │ results1[0] ✓    │
//         // │ base + 0x2000   │  300   │ results1[1] ✓    │
//         // │ base + 0x3000   │  200   │ results1[2] ✓    │
//         // │ base + 0x4000   │  150   │ (unchanged)      │
//         // │ base + 0x5000   │  400   │ results1[3] ✓    │
//         // └─────────────────┴────────┴──────────────────┘
// 
//         // 但是这里我们搜索范围只有上下128字节，所以是找不到的
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!(
//             "\nRefine search results: {} addresses have pattern [200, 300]",
//             results2.len()
//         );
//         assert_eq!(0, results2.len());
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_single_to_group_refine_unordered3() -> Result<()> {
//         println!("\n=== Test: Single value search → Group search refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7100000000, 1 * 1024 * 1024)?;
// 
//         // Write test data: single value, then check if followed by a pattern
//         let test_patterns = vec![
//             (0x0, 100u32),
//             (0x4, 100u32),
//             (0x8, 100u32),
//             (0x1000, 150u32),
//             (0x2000, 100u32),
//         ];
// 
//         for (offset, v1) in &test_patterns {
//             mem.mem_write_u32(base_addr + offset, *v1)?;
//             println!("Write: 0x{:X} = [{}]", base_addr + offset, v1);
//         }
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         assert_eq!(results1.len(), 4); // 找到4个地址才是正确的
// 
//         results1.iter().enumerate().for_each(|(index, pair)| {
//             println!("Found: 0x{:X}", pair.addr);
//             if index == 0 {
//                 assert_eq!(pair.addr, base_addr + 0x0);
//                 mem.mem_write_u32(pair.addr, 200u32).unwrap();
//             }
//             if index == 1 {
//                 assert_eq!(pair.addr, base_addr + 0x4);
//                 mem.mem_write_u32(pair.addr, 300u32).unwrap();
//             }
//             if index == 2 {
//                 assert_eq!(pair.addr, base_addr + 0x8);
//                 mem.mem_write_u32(pair.addr, 200u32).unwrap();
//             }
//             if index == 3 {
//                 assert_eq!(pair.addr, base_addr + 0x2000);
//                 mem.mem_write_u32(pair.addr, 400u32).unwrap();
//             }
//         });
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!(
//             "\nRefine search results: {} addresses have pattern [200, 300]",
//             results2.len()
//         );
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
//         assert_eq!(results2.len(), 3);
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_single_to_group_refine_unordered4() -> Result<()> {
//         println!("\n=== Test: Single value search → Group search refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7100000000, 1 * 1024 * 1024)?;
// 
//         // Write test data: single value, then check if followed by a pattern
//         let test_patterns = vec![
//             (0x0, 100u32),
//             (0x4, 100u32),
//             (0x8, 100u32),
//             (12, 100u32),
//             (16, 100u32),
//             (20, 100u32),
//             (0x2000, 100u32),
//         ];
// 
//         for (offset, v1) in &test_patterns {
//             mem.mem_write_u32(base_addr + offset, *v1)?;
//             println!("Write: 0x{:X} = [{}]", base_addr + offset, v1);
//         }
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         assert_eq!(results1.len(), 7);
// 
//         results1.iter().enumerate().for_each(|(index, pair)| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         mem.mem_write_u32(base_addr + 0, 200u32)?;
//         mem.mem_write_u32(base_addr + 4, 300u32)?;
//         mem.mem_write_u32(base_addr + 8, 300u32)?;
//         mem.mem_write_u32(base_addr + 12, 200u32)?;
//         mem.mem_write_u32(base_addr + 0x2000, 400u32)?;
//         // ┌─────────────────┬────────┬──────────────────┐
//         // │ Address         │ Value  │ Modified By      │
//         // ├─────────────────┼────────┼──────────────────┤
//         // │ base + 0        │  200   │ results1[0] ✓    │
//         // │ base + 4        │  300   │ results1[1] ✓    │
//         // │ base + 8        │  300   │ results1[2] ✓    │
//         // │ base + 12       │  150   │ (unchanged)      │
//         // │ base + 0x2000   │  400   │ results1[3] ✓    │
//         // └─────────────────┴────────┴──────────────────┘
//         // 匹配1000,1004,1008,100C
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!(
//             "\nRefine search results: {} addresses have pattern [200, 300]",
//             results2.len()
//         );
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
//         assert_eq!(results2.len(), 4);
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_single_to_group_refine_ordered() -> Result<()> {
//         println!("\n=== Test: Single value search → Group search refine ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7100000000, 1 * 1024 * 1024)?;
// 
//         // Write test data: single value, then check if followed by a pattern
//         let test_patterns = vec![
//             (0x0, 100u32),
//             (0x4, 100u32),
//             (0x8, 100u32),
//             (12, 100u32),
//             (16, 100u32),
//             (20, 100u32),
//             (0x2000, 100u32),
//         ];
// 
//         for (offset, v1) in &test_patterns {
//             mem.mem_write_u32(base_addr + offset, *v1)?;
//             println!("Write: 0x{:X} = [{}]", base_addr + offset, v1);
//         }
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         assert_eq!(results1.len(), 7);
// 
//         results1.iter().enumerate().for_each(|(index, pair)| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         mem.mem_write_u32(base_addr + 0, 200u32)?;
//         mem.mem_write_u32(base_addr + 4, 300u32)?;
//         mem.mem_write_u32(base_addr + 8, 300u32)?;
//         mem.mem_write_u32(base_addr + 12, 200u32)?;
//         mem.mem_write_u32(base_addr + 0x2000, 400u32)?;
//         // ┌─────────────────┬────────┬──────────────────┐
//         // │ Address         │ Value  │ Modified By      │
//         // ├─────────────────┼────────┼──────────────────┤
//         // │ base + 0        │  200   │ results1[0] ✓    │
//         // │ base + 4        │  300   │ results1[1] ✓    │
//         // │ base + 8        │  300   │ results1[2] ✓    │
//         // │ base + 12       │  150   │ (unchanged)      │
//         // │ base + 0x2000   │  400   │ results1[3] ✓    │
//         // └─────────────────┴────────┴──────────────────┘
//         // 匹配1000,1004,1008
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Ordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!(
//             "\nRefine search results: {} addresses have pattern [200, 300]",
//             results2.len()
//         );
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
//         assert_eq!(results2.len(), 3);
// 
//         println!("\nTest completed!");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_empty_results() -> Result<()> {
//         println!("\n=== Test: Refine with empty initial results ===\n");
// 
//         let mem = MockMemory::new();
//         let empty_results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
// 
//         let query = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(100, ValueType::Dword),
//                 SearchValue::fixed(200, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results = refine_results_group(&mem, &empty_results, &query)?;
// 
//         assert_eq!(results.len(), 0, "Empty input should yield empty results");
//         println!("✓ Empty results handled correctly");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_no_matches() -> Result<()> {
//         println!("\n=== Test: Refine when no patterns match ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8000000000, 1 * 1024 * 1024)?;
// 
//         // Write values that won't match the query pattern
//         let test_data = vec![(0x0, 100u32), (0x100, 200u32), (0x200, 300u32), (0x300, 400u32)];
// 
//         for (offset, val) in &test_data {
//             mem.mem_write_u32(base_addr + offset, *val)?;
//         }
// 
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
//         assert_eq!(results1.len(), 1);
// 
//         // Query for pattern [500, 600] which doesn't exist
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(500, ValueType::Dword),
//                 SearchValue::fixed(600, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             512,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         assert_eq!(results2.len(), 0, "No matches should return empty");
//         println!("✓ No-match case handled correctly");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_partial_match() -> Result<()> {
//         println!("\n=== Test: Partial match (not all query values found) ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8100000000, 1 * 1024 * 1024)?;
// 
//         // Write pattern with only 2 out of 3 values
//         mem.mem_write_u32(base_addr + 0x0, 100)?;
//         mem.mem_write_u32(base_addr + 0x4, 200)?;
//         mem.mem_write_u32(base_addr + 0x8, 999)?; // Not 300!
// 
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
//         assert_eq!(results1.len(), 1);
// 
//         // Query for [100, 200, 300] but only 100 and 200 exist
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(100, ValueType::Dword),
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         assert_eq!(results2.len(), 0, "Partial match should not succeed");
//         println!("✓ Partial match rejected correctly");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_multiple_combinations() -> Result<()> {
//         println!("\n=== Test: Multiple valid combinations (DFS verification) ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8200000000, 1 * 1024 * 1024)?;
// 
//         // Create multiple overlapping patterns
//         // Pattern 1: (0x0, 0x4) = (200, 300)
//         // Pattern 2: (0x8, 0xC) = (200, 300)
//         mem.mem_write_u32(base_addr + 0x0, 200)?; // 200 #1
//         mem.mem_write_u32(base_addr + 0x4, 300)?; // 300 #1
//         mem.mem_write_u32(base_addr + 0x8, 200)?; // 200 #2
//         mem.mem_write_u32(base_addr + 0xC, 300)?; // 300 #2
//         mem.mem_write_u32(base_addr + 0x100, 100)?; // Anchor
// 
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let mut results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
// 
//         // Also add the 200 addresses as anchors
//         results1.insert(ValuePair::new(base_addr + 0x0, ValueType::Dword));
//         results1.insert(ValuePair::new(base_addr + 0x8, ValueType::Dword));
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(200, ValueType::Dword),
//                 SearchValue::fixed(300, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             16, // Small range to test precise matching
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         println!("Found {} result addresses", results2.len());
//         for pair in &results2 {
//             println!("  0x{:X}", pair.addr);
//         }
// 
//         // Should find 4 addresses: 0x0, 0x4, 0x8, 0xC
//         assert!(results2.len() == 0);
//         println!("✓ Multiple combinations detected");
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_range_boundary() -> Result<()> {
//         println!("\n=== Test: Range boundary conditions ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8400000000, 1 * 1024 * 1024)?;
// 
//         // Anchor at 0x0, values at exactly range boundary
//         mem.mem_write_u32(base_addr + 0x0, 100)?; // Anchor
//         mem.mem_write_u32(base_addr + 127, 100)?; // At boundary (range=128)
//         mem.mem_write_u32(base_addr + 128, 100)?; // Just outside boundary
// 
//         let query1 = SearchValue::fixed(100, ValueType::Dword);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
//         assert_eq!(results1.len(), 2);
// 
//         results1.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         mem.mem_write_u32(base_addr + 0x0, 100)?; // Anchor
//         mem.mem_write_u32(base_addr + 127, 300)?; // At boundary (range=128)
//         mem.mem_write_u32(base_addr + 128, 300)?; // Just outside boundary
// 
//         println!("0: {:x}", base_addr);
//         println!("1: {:x}", base_addr + 127);
//         println!("2: {:x}", base_addr + 128);
// 
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(100, ValueType::Dword),
//                 SearchValue::fixed(200, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             128, // Exactly 128 bytes range
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
// 
//         results2.iter().for_each(|pair| {
//             println!("Found: 0x{:X}", pair.addr);
//         });
// 
//         Ok(())
//     }
// 
//     #[test]
//     fn test_refine_different_types() -> Result<()> {
//         println!("\n=== Test: Mixed data types in group search ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8500000000, 1 * 1024 * 1024)?;
// 
//         // Mixed types: Byte, Word, Dword
//         mem.mem_write(base_addr + 0x0, &[0x42])?; // Byte
//         mem.mem_write(base_addr + 0x4, &[0x42, 0x42])?; // Word (little-endian)
//         mem.mem_write_u32(base_addr + 0x8, 0x42424242)?; // Dword
// 
//         // Search for byte value first
//         let query1 = SearchValue::fixed(0x42, ValueType::Byte);
//         let results1 = perform_single_search(&mem, &query1, base_addr, 1 * 1024 * 1024)?;
//         assert_eq!(results1.len(), 7);
// 
//         mem.mem_write(base_addr + 0x0, &[0x42])?; // Byte
//         mem.mem_write(base_addr + 0x4, &[0x34, 0x12])?; // Word (little-endian)
//         mem.mem_write_u32(base_addr + 0x8, 0xDEADBEEF)?; // Dword
// 
//         // Refine with mixed types
//         let query2 = SearchQuery::new(
//             vec![
//                 SearchValue::fixed(0x42, ValueType::Byte),
//                 SearchValue::fixed(0x1234, ValueType::Word),
//                 SearchValue::fixed(0xDEADBEEF, ValueType::Dword),
//             ],
//             SearchMode::Unordered,
//             16,
//         );
// 
//         let results2 = refine_results_group(&mem, &results1, &query2)?;
//         for x in &results2 {
//             println!("Found: 0x{:X}", x.addr);
//         }
// 
//         assert_eq!(results2.len(), 0); // 不同类型, 改善不会成功
//         println!("✓ Mixed data types handled correctly");
//         Ok(())
//     }
// }
