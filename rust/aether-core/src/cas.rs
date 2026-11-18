//! Content-Addressed Storage (CAS) verification via FastCDC boundaries and BLAKE3 digests.

pub const FASTCDC_MIN: usize = 512 * 1024;
pub const FASTCDC_AVG: usize = 2 * 1024 * 1024;
pub const FASTCDC_MAX: usize = 4 * 1024 * 1024;

pub fn blake3_digest_hex(data: &[u8]) -> String {
    blake3::hash(data).to_hex().to_string()
}

pub fn verify_extent_blake3(data: &[u8], expected_hex: &str) -> bool {
    blake3_digest_hex(data).eq_ignore_ascii_case(expected_hex)
}
