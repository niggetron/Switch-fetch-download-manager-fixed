//! Content-Addressed Storage (CAS) verification via FastCDC boundaries, BLAKE3, and SHA-256.

use sha2::{Digest, Sha256};

pub const FASTCDC_MIN: usize = 512 * 1024;
pub const FASTCDC_AVG: usize = 2 * 1024 * 1024;
pub const FASTCDC_MAX: usize = 4 * 1024 * 1024;

pub fn blake3_digest_hex(data: &[u8]) -> String {
    blake3::hash(data).to_hex().to_string()
}

pub fn sha256_digest_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{:02x}", b))
        .collect::<String>()
}

pub fn verify_extent_blake3(data: &[u8], expected_hex: &str) -> bool {
    blake3_digest_hex(data).eq_ignore_ascii_case(expected_hex)
}
