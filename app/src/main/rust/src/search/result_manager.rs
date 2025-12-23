mod exact;
mod fuzzy;

use super::types::ValueType;
pub use crate::search::result_manager::exact::ExactSearchResultItem;
use crate::search::result_manager::exact::ExactSearchResultManager;
pub use crate::search::result_manager::fuzzy::{FuzzySearchResultItem, FuzzySearchResultManager};
use anyhow::{Result, anyhow};
use log::{debug, error, info};
use std::path::PathBuf;
use crate::search::engine::ValuePair;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchResultMode {
    Exact,
    Fuzzy,
}

pub enum SearchResultItem {
    Exact(ExactSearchResultItem),
    Fuzzy(FuzzySearchResultItem),
}

impl SearchResultItem {
    pub fn new_exact(address: u64, value_type: ValueType) -> Self {
        SearchResultItem::Exact(ExactSearchResultItem::new(address, value_type))
    }

    pub fn new_fuzzy(address: u64, value: [u8; 8], value_type: ValueType) -> Self {
        SearchResultItem::Fuzzy(FuzzySearchResultItem::new(address, value, value_type))
    }

    pub fn new_fuzzy_from_bytes(address: u64, bytes: &[u8], value_type: ValueType) -> Self {
        SearchResultItem::Fuzzy(FuzzySearchResultItem::from_bytes(address, bytes, value_type))
    }
}

impl From<(u64, ValueType)> for SearchResultItem {
    fn from(tuple: (u64, ValueType)) -> Self {
        SearchResultItem::Exact(ExactSearchResultItem::from(tuple))
    }
}

impl From<&ValuePair> for SearchResultItem {
    fn from(pair: &ValuePair) -> Self {
        SearchResultItem::Exact(ExactSearchResultItem::from((pair.addr, pair.value_type)))
    }
}

pub(crate) struct SearchResultManager {
    current_mode: SearchResultMode,
    exact: ExactSearchResultManager,
    fuzzy: FuzzySearchResultManager,
}

impl SearchResultManager {
    pub fn new(memory_buffer_size: usize, cache_dir: PathBuf) -> Self {
        Self {
            current_mode: SearchResultMode::Exact,
            exact: ExactSearchResultManager::new(memory_buffer_size, cache_dir.clone()),
            fuzzy: FuzzySearchResultManager::new(memory_buffer_size, cache_dir),
        }
    }

    pub fn clear(&mut self) -> Result<()> {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.clear(),
            SearchResultMode::Fuzzy => self.fuzzy.clear(),
        }
    }

    pub fn set_mode(&mut self, mode: SearchResultMode) -> Result<()> {
        if mode != self.current_mode {
            // 清理旧模式的磁盘资源
            match self.current_mode {
                SearchResultMode::Exact => {
                    self.exact.clear()?;
                    if let Err(e) = self.exact.clear_disk() {
                        error!("clear_disk failed for exact: {:?}", e);
                    }
                },
                SearchResultMode::Fuzzy => {
                    self.fuzzy.clear()?;
                    if let Err(e) = self.fuzzy.clear_disk() {
                        error!("clear_disk failed for fuzzy: {:?}", e);
                    }
                },
            }
        }
        self.current_mode = mode;
        Ok(())
    }

    pub fn add_result(&mut self, item: SearchResultItem) -> Result<()> {
        match (self.current_mode, item) {
            (SearchResultMode::Exact, SearchResultItem::Exact(exact_item)) => {
                self.exact.add_result(exact_item)
            },
            (SearchResultMode::Fuzzy, SearchResultItem::Fuzzy(fuzzy_item)) => {
                self.fuzzy.add_result(fuzzy_item)
            },
            _ => Err(anyhow!("Mismatched SearchResultMode and SearchResultItem type")),
        }
    }

    pub fn add_results_batch(&mut self, results: Vec<SearchResultItem>) -> Result<()> {
        for result in results {
            self.add_result(result)?;
        }
        Ok(())
    }

    /// 添加模糊搜索结果（直接使用 FuzzySearchResultItem）
    pub fn add_fuzzy_result(&mut self, item: FuzzySearchResultItem) -> Result<()> {
        if self.current_mode != SearchResultMode::Fuzzy {
            return Err(anyhow!("Not in fuzzy mode"));
        }
        self.fuzzy.add_result(item)
    }

    /// 批量添加模糊搜索结果
    pub fn add_fuzzy_results_batch(&mut self, results: Vec<FuzzySearchResultItem>) -> Result<()> {
        if self.current_mode != SearchResultMode::Fuzzy {
            return Err(anyhow!("Not in fuzzy mode"));
        }
        for item in results {
            self.fuzzy.add_result(item)?;
        }
        Ok(())
    }

    pub fn get_results(&self, start: usize, size: usize) -> Result<Vec<SearchResultItem>> {
        match self.current_mode {
            SearchResultMode::Exact => {
                let exact_results = self.exact.get_results(start, size)?;
                Ok(exact_results.into_iter().map(SearchResultItem::Exact).collect())
            },
            SearchResultMode::Fuzzy => {
                let fuzzy_results = self.fuzzy.get_results(start, size)?;
                Ok(fuzzy_results.into_iter().map(SearchResultItem::Fuzzy).collect())
            },
        }
    }

    pub fn total_count(&self) -> usize {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.total_count(),
            SearchResultMode::Fuzzy => self.fuzzy.total_count(),
        }
    }

    pub fn remove_result(&mut self, index: usize) -> Result<()> {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.remove_result(index),
            SearchResultMode::Fuzzy => self.fuzzy.remove_result(index),
        }
    }

    pub fn remove_results_batch(&mut self, indices: Vec<usize>) -> Result<()> {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.remove_results_batch(indices),
            SearchResultMode::Fuzzy => self.fuzzy.remove_results_batch(indices),
        }
    }

    pub fn keep_only_results(&mut self, keep_indices: Vec<usize>) -> Result<()> {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.keep_only_results(keep_indices),
            SearchResultMode::Fuzzy => self.fuzzy.keep_only_results(keep_indices),
        }
    }

    pub fn get_mode(&self) -> SearchResultMode {
        self.current_mode
    }

    pub fn get_all_exact_results(&self) -> Result<Vec<ExactSearchResultItem>> {
        match self.current_mode {
            SearchResultMode::Exact => self.exact.get_all_results(),
            SearchResultMode::Fuzzy => Err(anyhow!("Cannot get exact results in fuzzy mode")),
        }
    }

    /// 获取所有模糊搜索结果
    pub fn get_all_fuzzy_results(&self) -> Result<Vec<FuzzySearchResultItem>> {
        match self.current_mode {
            SearchResultMode::Exact => Err(anyhow!("Cannot get fuzzy results in exact mode")),
            SearchResultMode::Fuzzy => self.fuzzy.get_all_results(),
        }
    }

    /// 批量替换所有模糊搜索结果（用于细化搜索后）
    pub fn replace_all_fuzzy_results(&mut self, results: Vec<FuzzySearchResultItem>) -> Result<()> {
        if self.current_mode != SearchResultMode::Fuzzy {
            return Err(anyhow!("Not in fuzzy mode"));
        }
        self.fuzzy.replace_all(results)
    }
}
