// //! Single value search tests
// 
// #[cfg(test)]
// mod tests {
//     use bplustree::BPlusTreeSet;
//     use crate::search::{SearchEngineManager, BPLUS_TREE_ORDER};
//     use crate::search::tests::mock_memory::MockMemory;
//     use crate::search::{SearchValue, ValueType};
//     use crate::wuwa::PageStatusBitmap;
// 
//     #[test]
//     fn test_single_value_search_with_mock_memory() {
//         println!("\n=== Single value search test (using MockMemory) ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x7000000000, 1024 * 1024).unwrap();
// 
//         println!("Allocated memory: 0x{:X}, size: 1MB", base_addr);
// 
//         // Write test data
//         let test_values = vec![
//             (0x1000, 0x12345678u32),
//             (0x2004, 0x12345678u32),
//             (0x3008, 0xABCDEF00u32),
//             (0x8000, 0x12345678u32),
//             (0x10000, 0x12345678u32),
//             (0x20000, 0xDEADBEEFu32),
//             (0x50000, 0x12345678u32),
//         ];
// 
//         for (offset, value) in &test_values {
//             mem.mem_write_u32(base_addr + offset, *value).unwrap();
//             println!("Write: 0x{:X} = 0x{:08X}", base_addr + offset, value);
//         }
// 
//         // Search for 0x12345678
//         let target_value = 0x12345678i128;
//         let search_value = SearchValue::fixed(target_value, ValueType::Dword);
// 
//         println!("\nStart search: 0x{:08X} (DWORD)", target_value);
// 
//         let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
//         let mut matches_checked = 0usize;
// 
//         let chunk_size = 64 * 1024;
//         let mut current = base_addr;
//         let end_addr = base_addr + 1024 * 1024;
// 
//         while current < end_addr {
//             let chunk_end = (current + chunk_size as u64).min(end_addr);
//             let chunk_len = (chunk_end - current) as usize;
// 
//             let mut chunk_buffer = vec![0u8; chunk_len];
//             let mut page_status = PageStatusBitmap::new(chunk_len, current as usize);
// 
//             match mem.mem_read_with_status(current, &mut chunk_buffer, &mut page_status) {
//                 Ok(_) => {
//                     SearchEngineManager::search_in_buffer_with_status(
//                         &chunk_buffer,
//                         current,
//                         base_addr,
//                         end_addr,
//                         4,
//                         &search_value,
//                         ValueType::Dword,
//                         &page_status,
//                         &mut results,
//                         &mut matches_checked,
//                     );
//                 }
//                 Err(e) => {
//                     println!("Read failed: {:?}", e);
//                 }
//             }
// 
//             current = chunk_end;
//         }
// 
//         println!("\n=== Search results ===");
//         println!("Checked {} positions", matches_checked);
//         println!("Found {} matches\n", results.len());
// 
//         for (i, pair) in results.iter().enumerate() {
//             let offset = pair.addr - base_addr;
//             println!("  [{}] Address: 0x{:X} (offset: 0x{:X})", i, pair.addr, offset);
//         }
// 
//         // Verify results
//         assert_eq!(results.len(), 5, "Should find 5 matching values");
// 
//         let expected_offsets = vec![0x1000, 0x2004, 0x8000, 0x10000, 0x50000];
//         for offset in expected_offsets {
//             let expected_addr = base_addr + offset as u64;
//             assert!(
//                 results.iter().any(|pair| pair.addr == expected_addr),
//                 "Should find value at offset 0x{:X}", offset
//             );
//         }
// 
//         println!("\nAll assertions passed!");
//     }
// 
//     #[test]
//     fn test_search_with_page_faults() {
//         println!("\n=== Test search with partial page failures ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x8000000000, 128 * 1024).unwrap();
// 
//         println!("Allocated memory: 0x{:X}, size: 128KB", base_addr);
// 
//         // Write test data (one value every 4KB)
//         for i in 0..32 {
//             let offset = i * 4096 + 0x100;
//             mem.mem_write_u32(base_addr + offset, 0xCAFEBABEu32).unwrap();
//         }
// 
//         // Mark some pages as faulty (pages 1, 3, 5, 7)
//         mem.set_faulty_pages(base_addr, &[1, 3, 5, 7]).unwrap();
//         println!("Marked pages [1, 3, 5, 7] as failed");
// 
//         let search_value = SearchValue::fixed(0xCAFEBABEi128, ValueType::Dword);
// 
//         let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
//         let mut matches_checked = 0usize;
// 
//         let mut buffer = vec![0u8; 128 * 1024];
//         let mut page_status = PageStatusBitmap::new(buffer.len(), base_addr as usize);
// 
//         mem.mem_read_with_status(base_addr, &mut buffer, &mut page_status).unwrap();
// 
//         println!("\nPage status:");
//         println!("  Total pages: {} (real: {})", page_status.num_pages(), buffer.len() / 4096);
//         println!("  Success pages: {}", page_status.success_count());
//         println!("  Failed pages: {} (real: {})", page_status.failed_pages().len(), (buffer.len() / 4096) - page_status.success_count());
// 
//         SearchEngineManager::search_in_buffer_with_status(
//             &buffer,
//             base_addr,
//             base_addr,
//             base_addr + 128 * 1024,
//             4,
//             &search_value,
//             ValueType::Dword,
//             &page_status,
//             &mut results,
//             &mut matches_checked,
//         );
// 
//         println!("\n=== Search results ===");
//         println!("Checked {} positions", matches_checked);
//         println!("Found {} matches", results.len());
// 
//         // Should only find values in successful pages (32 values - 4 failed pages = 28)
//         assert_eq!(results.len(), 28, "Should find 28 values in successful pages");
// 
//         println!("\nPage fault handling test passed!");
//     }
// 
//     #[test]
//     fn test_non_aligned_search() {
//         println!("\n=== Test non-aligned address search ===\n");
// 
//         let mut mem = MockMemory::new();
//         let base_addr = mem.malloc(0x9000000000, 32 * 1024).unwrap();
// 
//         println!("Allocated memory: 0x{:X} (page aligned)", base_addr);
//         println!("Page size: {} bytes", mem.page_size());
// 
//         // Write test values at non-page-aligned but 4-byte aligned offsets
//         // 0x124 is 4-byte aligned but not page aligned (assuming page size is 4096)
//         let non_page_aligned_offset = 0x124;
//         mem.mem_write_u32(base_addr + non_page_aligned_offset, 0xDEADBEEFu32).unwrap();
//         mem.mem_write_u32(base_addr + non_page_aligned_offset + 0x100, 0xDEADBEEFu32).unwrap();
//         mem.mem_write_u32(base_addr + non_page_aligned_offset + 0x200, 0xDEADBEEFu32).unwrap();
// 
//         println!("Wrote 3 values at non-page-aligned offset 0x{:X}", non_page_aligned_offset);
//         println!("Search address range: 0x{:X} - 0x{:X}", base_addr + non_page_aligned_offset, base_addr + non_page_aligned_offset + 0x300);
// 
//         let search_value = SearchValue::fixed(0xDEADBEEFi128, ValueType::Dword);
// 
//         let mut results = BPlusTreeSet::new(BPLUS_TREE_ORDER);
//         let mut matches_checked = 0usize;
// 
//         // Read from non-page-aligned address
//         let search_start = base_addr + non_page_aligned_offset;
//         let search_size = 0x300;
//         let mut buffer = vec![0u8; search_size];
//         let mut page_status = PageStatusBitmap::new(buffer.len(), search_start as usize);
// 
//         mem.mem_read_with_status(search_start, &mut buffer, &mut page_status).unwrap();
// 
//         // Search from non-aligned address
//         SearchEngineManager::search_in_buffer_with_status(
//             &buffer,
//             search_start,
//             search_start,
//             search_start + search_size as u64,
//             4,
//             &search_value,
//             ValueType::Dword,
//             &page_status,
//             &mut results,
//             &mut matches_checked,
//         );
// 
//         println!("\n=== Search results ===");
//         println!("Found {} matches", results.len());
//         for (i, pair) in results.iter().enumerate() {
//             println!("  [{}] 0x{:X}", i + 1, pair.addr);
//         }
// 
//         assert_eq!(results.len(), 3, "Should find 3 values at non-aligned addresses");
// 
//         println!("\nNon-aligned address search test passed!");
//     }
// }