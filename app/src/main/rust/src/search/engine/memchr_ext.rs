use memchr::*;

pub trait MemchrExt {
    fn find_aligned(&self, needle: &[u8], align: usize) -> Vec<usize>;
}

impl MemchrExt for [u8] {
    fn find_aligned(&self, needle: &[u8], align: usize) -> Vec<usize> {
        find_aligned_internal(self, needle, align)
    }
}

fn find_aligned_internal(haystack: &[u8], needle: &[u8], align: usize) -> Vec<usize> {
    if needle.is_empty() {
        return vec![];
    }
    if needle.len() > haystack.len() {
        return vec![];
    }

    let first = needle[0];
    let mut out = Vec::new();

    for pos in memchr_iter(first, haystack) {
        if pos % align != 0 {
            continue;
        }
        let end = pos + needle.len();
        if end > haystack.len() {
            continue;
        }
        if &haystack[pos..end] == needle {
            out.push(pos);
        }
    }
    out
}
