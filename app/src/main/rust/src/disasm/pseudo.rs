//! Pseudo-code generation for ARM instructions.
//!
//! Generates simplified, high-level representations of ARM assembly instructions.

use super::Architecture;
use capstone::Insn;
use capstone::prelude::*;

/// Generates pseudo-code for an instruction.
///
/// This provides a simplified representation that's easier to understand than
/// raw assembly, focusing on the high-level operation rather than low-level details.
pub fn generate_pseudo_code(
    arch: Architecture,
    mnemonic: &str,
    operands: &str,
    _cs: &Capstone,
    _insn: &Insn,
) -> String {
    match arch {
        Architecture::ARM64 => generate_arm64_pseudo(mnemonic, operands),
        Architecture::ARM32 | Architecture::THUMB => generate_arm32_pseudo(mnemonic, operands),
    }
}

/// Generates pseudo-code for ARM64 instructions.
fn generate_arm64_pseudo(mnemonic: &str, operands: &str) -> String {
    let ops: Vec<&str> = operands.split(',').map(|s| s.trim()).collect();

    match mnemonic {
        // Data movement
        "mov" | "movz" | "movk" | "movn" => {
            if ops.len() >= 2 {
                format!("{} = {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Load instructions
        "ldr" | "ldrb" | "ldrh" | "ldrsb" | "ldrsh" | "ldrsw" => {
            if ops.len() >= 2 {
                let size = match mnemonic {
                    "ldrb" | "ldrsb" => "byte",
                    "ldrh" | "ldrsh" => "word",
                    "ldrsw" => "dword",
                    _ => "qword",
                };
                format!("{} = *({})_{}", ops[0], ops[1], size)
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "ldp" => {
            if ops.len() >= 3 {
                format!("{} = *{}; {} = *({}+8)", ops[0], ops[2], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Store instructions
        "str" | "strb" | "strh" => {
            if ops.len() >= 2 {
                let size = match mnemonic {
                    "strb" => "byte",
                    "strh" => "word",
                    _ => "qword",
                };
                format!("*({})_{} = {}", ops[1], size, ops[0])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "stp" => {
            if ops.len() >= 3 {
                format!("*{} = {}; *({}+8) = {}", ops[2], ops[0], ops[2], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Arithmetic operations
        "add" | "adds" => {
            if ops.len() >= 3 {
                format!("{} = {} + {}", ops[0], ops[1], ops[2])
            } else if ops.len() == 2 {
                format!("{} += {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "sub" | "subs" => {
            if ops.len() >= 3 {
                format!("{} = {} - {}", ops[0], ops[1], ops[2])
            } else if ops.len() == 2 {
                format!("{} -= {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "mul" | "madd" => {
            if ops.len() >= 3 {
                format!("{} = {} * {}", ops[0], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "sdiv" | "udiv" => {
            if ops.len() >= 3 {
                format!("{} = {} / {}", ops[0], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Logical operations
        "and" | "ands" => {
            if ops.len() >= 3 {
                format!("{} = {} & {}", ops[0], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "orr" => {
            if ops.len() >= 3 {
                format!("{} = {} | {}", ops[0], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "eor" => {
            if ops.len() >= 3 {
                format!("{} = {} ^ {}", ops[0], ops[1], ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "mvn" | "not" => {
            if ops.len() >= 2 {
                format!("{} = ~{}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Shift operations
        "lsl" | "lsr" | "asr" | "ror" => {
            if ops.len() >= 3 {
                let op = match mnemonic {
                    "lsl" => "<<",
                    "lsr" => ">>",
                    "asr" => ">>",
                    "ror" => ">>>",
                    _ => "?",
                };
                format!("{} = {} {} {}", ops[0], ops[1], op, ops[2])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Compare and test
        "cmp" | "cmn" => {
            if ops.len() >= 2 {
                let op = if mnemonic == "cmp" { "-" } else { "+" };
                format!("flags = {} {} {}", ops[0], op, ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "tst" => {
            if ops.len() >= 2 {
                format!("flags = {} & {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        // Branch instructions
        "b" => format!("goto {}", operands),
        "bl" => format!("call {}", operands),
        "br" => format!("goto {}", operands),
        "blr" => format!("call {}", operands),
        "ret" => "return".to_string(),

        // Conditional branches
        "b.eq" | "beq" => format!("if (equal) goto {}", operands),
        "b.ne" | "bne" => format!("if (not_equal) goto {}", operands),
        "b.gt" | "bgt" => format!("if (greater) goto {}", operands),
        "b.ge" | "bge" => format!("if (greater_equal) goto {}", operands),
        "b.lt" | "blt" => format!("if (less) goto {}", operands),
        "b.le" | "ble" => format!("if (less_equal) goto {}", operands),

        // System/special
        "nop" => "// no operation".to_string(),
        "dmb" | "dsb" | "isb" => format!("{}() // memory barrier", mnemonic),

        // Default fallback
        _ => format!("{} {}", mnemonic, operands),
    }
}

/// Generates pseudo-code for ARM32/Thumb instructions.
fn generate_arm32_pseudo(mnemonic: &str, operands: &str) -> String {
    let ops: Vec<&str> = operands.split(',').map(|s| s.trim()).collect();

    match mnemonic {
        // Similar patterns to ARM64, but with register names
        "mov" | "movs" | "movw" | "movt" => {
            if ops.len() >= 2 {
                format!("{} = {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "ldr" | "ldrb" | "ldrh" | "ldrsb" | "ldrsh" => {
            if ops.len() >= 2 {
                let size = match mnemonic {
                    "ldrb" | "ldrsb" => "byte",
                    "ldrh" | "ldrsh" => "word",
                    _ => "dword",
                };
                format!("{} = *({})_{}", ops[0], ops[1], size)
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "str" | "strb" | "strh" => {
            if ops.len() >= 2 {
                let size = match mnemonic {
                    "strb" => "byte",
                    "strh" => "word",
                    _ => "dword",
                };
                format!("*({})_{} = {}", ops[1], size, ops[0])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "add" | "adds" => {
            if ops.len() >= 3 {
                format!("{} = {} + {}", ops[0], ops[1], ops[2])
            } else if ops.len() == 2 {
                format!("{} += {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "sub" | "subs" => {
            if ops.len() >= 3 {
                format!("{} = {} - {}", ops[0], ops[1], ops[2])
            } else if ops.len() == 2 {
                format!("{} -= {}", ops[0], ops[1])
            } else {
                format!("{} {}", mnemonic, operands)
            }
        },

        "b" => format!("goto {}", operands),
        "bl" | "blx" => format!("call {}", operands),
        "bx" => format!("goto {}", operands),
        "pop" => format!("restore {}", operands),
        "push" => format!("save {}", operands),

        _ => format!("{} {}", mnemonic, operands),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_arm64_pseudo() {
        assert_eq!(generate_arm64_pseudo("mov", "x0, x1"), "x0 = x1");
        assert_eq!(generate_arm64_pseudo("ldr", "x0, [x1]"), "x0 = *([x1])_qword");
        assert_eq!(generate_arm64_pseudo("add", "x0, x1, x2"), "x0 = x1 + x2");
        assert_eq!(generate_arm64_pseudo("b", "#0x1000"), "goto #0x1000");
    }

    #[test]
    fn test_arm32_pseudo() {
        assert_eq!(generate_arm32_pseudo("mov", "r0, r1"), "r0 = r1");
        assert_eq!(generate_arm32_pseudo("ldr", "r0, [r1]"), "r0 = *([r1])_dword");
        assert_eq!(generate_arm32_pseudo("add", "r0, r1, r2"), "r0 = r1 + r2");
    }
}
