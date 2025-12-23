pub mod types;
pub mod lexer;
pub mod parser;
pub mod engine;
pub mod result_manager;

#[cfg(test)]
pub mod tests;

pub use types::{FuzzyCondition, SearchMode, SearchQuery, SearchValue, ValueType};
pub use parser::parse_search_query;
pub use engine::{SearchEngineManager, SEARCH_ENGINE_MANAGER, SearchProgressCallback, BPLUS_TREE_ORDER, PAGE_SIZE, PAGE_MASK, ValuePair};
pub use result_manager::SearchResultItem;