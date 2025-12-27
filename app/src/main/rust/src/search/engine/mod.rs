//! Search engine implementation modules.

mod batch_reader;
pub mod filter;
pub mod fuzzy_search;
pub mod group_search;
pub mod manager;
pub mod shared_buffer;
pub mod single_search;
mod memchr_ext;

pub use filter::SearchFilter;
pub use manager::{SearchEngineManager, SearchProgressCallback, ValuePair, BPLUS_TREE_ORDER, PAGE_MASK, PAGE_SIZE, SEARCH_ENGINE_MANAGER};
pub use shared_buffer::{SearchErrorCode, SearchStatus, SharedBuffer, SHARED_BUFFER_SIZE};
