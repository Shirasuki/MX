//! ARM instruction disassembler using Capstone engine.

mod pseudo;

use anyhow::{anyhow, Result};
use capstone::prelude::*;
pub use pseudo::generate_pseudo_code;

/// Architecture modes for disassembly.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Architecture {
    ARM32 = 0,
    THUMB = 1,
    ARM64 = 2,
}

impl Architecture {
    pub fn from_i32(value: i32) -> Result<Self> {
        match value {
            0 => Ok(Architecture::ARM32),
            1 => Ok(Architecture::THUMB),
            2 => Ok(Architecture::ARM64),
            _ => Err(anyhow!("Invalid architecture value: {}", value)),
        }
    }
}

/// Disassembly result item.
#[derive(Debug, Clone)]
pub struct DisassemblyResult {
    pub address: u64,
    pub bytes: Vec<u8>,
    pub mnemonic: String,
    pub operands: String,
    pub pseudo_code: Option<String>,
}

/// Disassembles instructions using Capstone.
///
/// # Arguments
/// * `arch` - Architecture mode (ARM32, THUMB, or ARM64)
/// * `bytes` - Instruction bytes to disassemble
/// * `address` - Starting address for the instructions
/// * `count` - Maximum number of instructions to disassemble (0 = all)
///
/// # Returns
/// Vector of disassembly results
pub fn disassemble(
    arch: Architecture,
    bytes: &[u8],
    address: u64,
    count: usize,
) -> Result<Vec<DisassemblyResult>> {
    let cs = create_capstone(arch)?;

    let instructions = if count > 0 {
        cs.disasm_count(bytes, address, count)?
    } else {
        cs.disasm_all(bytes, address)?
    };

    let mut results = Vec::with_capacity(instructions.len());

    for insn in instructions.iter() {
        results.push(DisassemblyResult {
            address: insn.address(),
            bytes: insn.bytes().to_vec(),
            mnemonic: insn.mnemonic().unwrap_or("???").to_string(),
            operands: insn.op_str().unwrap_or("").to_string(),
            pseudo_code: None,
        });
    }

    Ok(results)
}

/// Disassembles instructions with pseudo-code generation.
///
/// # Arguments
/// * `arch` - Architecture mode
/// * `bytes` - Instruction bytes
/// * `address` - Starting address
/// * `count` - Maximum number of instructions (0 = all)
///
/// # Returns
/// Vector of disassembly results with pseudo-code
pub fn disassemble_with_pseudo(
    arch: Architecture,
    bytes: &[u8],
    address: u64,
    count: usize,
) -> Result<Vec<DisassemblyResult>> {
    let mut cs = create_capstone(arch)?;
    cs.set_detail(true)?;

    let instructions = if count > 0 {
        cs.disasm_count(bytes, address, count)?
    } else {
        cs.disasm_all(bytes, address)?
    };

    let mut results = Vec::with_capacity(instructions.len());

    for insn in instructions.iter() {
        let mnemonic = insn.mnemonic().unwrap_or("???");
        let operands = insn.op_str().unwrap_or("");

        let pseudo = generate_pseudo_code(arch, mnemonic, operands, &cs, &insn);

        results.push(DisassemblyResult {
            address: insn.address(),
            bytes: insn.bytes().to_vec(),
            mnemonic: mnemonic.to_string(),
            operands: operands.to_string(),
            pseudo_code: Some(pseudo),
        });
    }

    Ok(results)
}

/// Creates a Capstone instance for the specified architecture.
fn create_capstone(arch: Architecture) -> Result<Capstone> {
    let cs = match arch {
        Architecture::ARM32 => {
            Capstone::new()
                .arm()
                .mode(arch::arm::ArchMode::Arm)
                .build()
        }
        Architecture::THUMB => {
            Capstone::new()
                .arm()
                .mode(arch::arm::ArchMode::Thumb)
                .build()
        }
        Architecture::ARM64 => {
            Capstone::new()
                .arm64()
                .mode(arch::arm64::ArchMode::Arm)
                .build()
        }
    };

    cs.map_err(|e| anyhow!("Failed to create Capstone instance: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_arm64_disassemble() {
        // mov x0, #0x1234
        let bytes = vec![0x80, 0x46, 0x82, 0xd2];
        let results = disassemble(Architecture::ARM64, &bytes, 0x1000, 0).unwrap();

        assert_eq!(results.len(), 1);
        assert_eq!(results[0].mnemonic, "mov");
    }

    #[test]
    fn test_thumb_disassemble() {
        // movs r0, #42
        let bytes = vec![0x2a, 0x20];
        let results = disassemble(Architecture::THUMB, &bytes, 0x1000, 0).unwrap();

        assert_eq!(results.len(), 1);
        assert_eq!(results[0].mnemonic, "movs");
    }
}
