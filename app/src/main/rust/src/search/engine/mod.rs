//! Search engine implementation modules.

pub mod filter;
pub mod single_search;
pub mod group_search;
pub mod fuzzy_search;
pub mod manager;
pub mod shared_buffer;

pub use manager::{SearchEngineManager, SEARCH_ENGINE_MANAGER, SearchProgressCallback, ValuePair, BPLUS_TREE_ORDER, PAGE_SIZE, PAGE_MASK};
pub use filter::SearchFilter;
pub use shared_buffer::{SharedBuffer, SearchStatus, SearchErrorCode, SHARED_BUFFER_SIZE};