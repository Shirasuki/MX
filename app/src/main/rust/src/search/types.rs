use std::fmt;
use anyhow::anyhow;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ValueType {
    Byte,
    Word,
    Dword,
    Qword,
    Float,
    Double,
    Auto,
    Xor,
}

impl ValueType {
    #[inline]
    pub fn from_id(id: i32) -> Option<Self> {
        match id {
            0 => Self::Byte.into(),
            1 => Self::Word.into(),
            2 => Self::Dword.into(),
            3 => Self::Qword.into(),
            4 => Self::Float.into(),
            5 => Self::Double.into(),
            6 => Self::Auto.into(),
            7 => Self::Xor.into(),
            _ => None,
        }
    }
    
    #[inline]
    pub fn to_id(&self) -> i32 {
        match self {
            ValueType::Byte => 0,
            ValueType::Word => 1,
            ValueType::Dword => 2,
            ValueType::Qword => 3,
            ValueType::Float => 4,
            ValueType::Double => 5,
            ValueType::Auto => 6,
            ValueType::Xor => 7,
        }
    }

    #[inline]
    pub fn from_char(c: char) -> Option<Self> {
        match c.to_ascii_uppercase() {
            'B' => Some(ValueType::Byte),
            'W' => Some(ValueType::Word),
            'D' => Some(ValueType::Dword),
            'Q' => Some(ValueType::Qword),
            'F' => Some(ValueType::Float),
            'E' => Some(ValueType::Double),
            'A' => Some(ValueType::Auto),
            'X' => Some(ValueType::Xor),
            _ => None,
        }
    }

    #[inline]
    pub fn size(&self) -> usize {
        match self {
            ValueType::Byte => 1,
            ValueType::Word => 2,
            ValueType::Dword => 4,
            ValueType::Qword => 8,
            ValueType::Float => 4,
            ValueType::Double => 8,
            ValueType::Auto => 4,
            ValueType::Xor => 4,
        }
    }

    #[inline]
    pub fn is_float_type(&self) -> bool {
        matches!(self, ValueType::Float | ValueType::Double)
    }
}

impl fmt::Display for ValueType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ValueType::Byte => write!(f, "Byte"),
            ValueType::Word => write!(f, "Word"),
            ValueType::Dword => write!(f, "Dword"),
            ValueType::Qword => write!(f, "Qword"),
            ValueType::Float => write!(f, "Float"),
            ValueType::Double => write!(f, "Double"),
            ValueType::Auto => write!(f, "Auto"),
            ValueType::Xor => write!(f, "Xor"),
        }
    }
}

#[derive(Debug, Clone)]
pub enum SearchValue {
    /// 精确值搜索，存储实际字节表示
    FixedInt {
        value: [u8; 16],
        value_type: ValueType,
    },
    FixedFloat {
        value: f64,
        value_type: ValueType,
    },
    /// 范围搜索，存储起始和结束的字节表示
    RangeInt {
        start: i128,
        end: i128,
        value_type: ValueType,
        exclude: bool,
    },
    RangeFloat {
        start: f64,
        end: f64,
        value_type: ValueType,
        exclude: bool,
    },
}

impl SearchValue {
    #[inline]
    pub fn fixed(value: i128, value_type: ValueType) -> Self {
        SearchValue::FixedInt {
            value: i128::to_le_bytes(value),
            value_type,
        }
    }

    #[inline]
    pub fn fixed_float(value: f64, value_type: ValueType) -> Self {
        SearchValue::FixedFloat {
            value,
            value_type,
        }
    }

    #[inline]
    pub fn range(start: i128, end: i128, value_type: ValueType, exclude: bool) -> Self {
        SearchValue::RangeInt {
            start,
            end,
            value_type,
            exclude,
        }
    }

    #[inline]
    pub fn range_float(start: f64, end: f64, value_type: ValueType, exclude: bool) -> Self {
        SearchValue::RangeFloat {
            start,
            end,
            value_type,
            exclude,
        }
    }

    #[inline]
    pub fn value_type(&self) -> ValueType {
        match self {
            SearchValue::FixedInt { value_type, .. } => *value_type,
            SearchValue::RangeInt { value_type, .. } => *value_type,
            SearchValue::FixedFloat { value_type, .. } => *value_type,
            SearchValue::RangeFloat { value_type, .. } => *value_type,
        }
    }

    #[inline]
    pub fn is_fixed(&self) -> bool {
        matches!(self, SearchValue::FixedInt { .. } | SearchValue::FixedFloat { .. })
    }

    #[inline]
    pub fn matched(&self, other: &[u8]) -> anyhow::Result<bool> {
        match self {
            SearchValue::FixedInt { value, value_type } => {
                let size = value_type.size();
                if other.len() < size {
                    return Err(anyhow!("Input slice too small: expected at least {} bytes, got {}", size, other.len()));
                }
                Ok(&value[..size] == &other[..size])
            }
            SearchValue::FixedFloat { value, value_type } => {
                let size = value_type.size();
                if other.len() < size {
                    return Err(anyhow!("Input slice too small: expected at least {} bytes, got {}", size, other.len()));
                }
                let other_value = match size {
                    4 => {
                        let bytes = other[..4].try_into()?;
                        f32::from_le_bytes(bytes) as f64
                    }
                    8 => {
                        let bytes = other[..8].try_into()?;
                        f64::from_le_bytes(bytes)
                    }
                    _ => return Err(anyhow!("Invalid float size: {}", size)),
                };
                Ok((*value - other_value).abs() < f64::EPSILON)
            }
            SearchValue::RangeInt { start, end, value_type, exclude } => {
                let size = value_type.size();
                if other.len() < size {
                    return Err(anyhow!("Input slice too small: expected at least {} bytes, got {}", size, other.len()));
                }
                let other_value = match size {
                    1 => i128::from(other[0] as i8),
                    2 => {
                        let bytes: [u8; 2] = other[..2].try_into()?;
                        i128::from(i16::from_le_bytes(bytes))
                    }
                    4 => {
                        let bytes: [u8; 4] = other[..4].try_into()?;
                        i128::from(i32::from_le_bytes(bytes))
                    }
                    8 => {
                        let bytes: [u8; 8] = other[..8].try_into()?;
                        i128::from(i64::from_le_bytes(bytes))
                    }
                    16 => {
                        let bytes: [u8; 16] = other[..16].try_into()?;
                        i128::from_le_bytes(bytes)
                    }
                    _ => return Err(anyhow!("Invalid integer size: {}", size)),
                };
                if *exclude {
                    Ok(other_value < *start || other_value > *end)
                } else {
                    Ok(other_value >= *start && other_value <= *end)
                }
            }
            SearchValue::RangeFloat { start, end, value_type, exclude } => {
                let size = value_type.size();
                if other.len() < size {
                    return Err(anyhow!("Input slice too small: expected at least {} bytes, got {}", size, other.len()));
                }
                let other_value = match size {
                    4 => {
                        let bytes = other[..4].try_into()?;
                        f32::from_le_bytes(bytes) as f64
                    }
                    8 => {
                        let bytes = other[..8].try_into()?;
                        f64::from_le_bytes(bytes)
                    }
                    _ => return Err(anyhow!("Invalid float size: {}", size)),
                };
                if *exclude {
                    Ok(other_value < *start || other_value > *end)
                } else {
                    Ok(other_value >= *start && other_value <= *end)
                }
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchMode {
    Unordered,
    Ordered,
}

#[derive(Debug, Clone)]
pub struct SearchQuery {
    pub values: Vec<SearchValue>,
    pub mode: SearchMode,
    pub range: u16,
}

impl SearchQuery {
    #[inline]
    pub fn new(values: Vec<SearchValue>, mode: SearchMode, range: u16) -> Self {
        SearchQuery {
            values,
            mode,
            range,
        }
    }

    pub fn total_size(&self) -> usize {
        let sz: usize = self.values.iter().map(|v| v.value_type().size()).sum();
        (sz + 3) & !3
    }

    pub fn total_size_align_page(&self, page_size: usize) -> usize {
        let total_size = self.total_size();
        (total_size + page_size - 1) & !(page_size - 1)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.values.is_empty() {
            return Err("No values specified".to_string());
        }

        if self.values.len() > 64 {
            return Err("Maximum 64 values allowed".to_string());
        }

        if self.values.len() >= 2 && self.range < 2 {
            return Err("Range must be at least 2 for group search".to_string());
        }

        Ok(())
    }
}

#[cfg(test)]
mod test {

}