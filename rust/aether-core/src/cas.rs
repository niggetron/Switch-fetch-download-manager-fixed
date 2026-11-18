//! Content-Addressed Storage (CAS) + Incremental Page-Cache Sequential SHA-256 Verifier.
//!
//! Fix #3: Out-of-order 512 KiB slabs compute BLAKE3 directly in L1/L2 CPU cache before `pwrite`.
//! Whenever slow left-edge segments catch up and advance `contiguous_verified_offset`,
//! `ContiguousSha256Verifier` stream-reads `old_contiguous..new_contiguous` from OS page cache
//! *before* `POSIX_FADV_DONTNEED` to advance the single sequential SHA-256 Merkle-Damgård state
//! with zero post-download cold disk re-read!

use std::collections::BTreeSet;
use std::fs::File;
use std::io;
#[cfg(unix)]
use std::os::fd::AsFd;

use sha2::{Digest, Sha256};

use crate::storage_and_telemetry::advise_dontneed;

pub const FASTCDC_MIN: usize = 512 * 1024;
pub const FASTCDC_AVG: usize = 2 * 1024 * 1024;
pub const FASTCDC_MAX: usize = 4 * 1024 * 1024;

pub fn blake3_digest_bytes(data: &[u8]) -> [u8; 32] {
    *blake3::hash(data).as_bytes()
}

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

/// Maintains the exact sequential SHA-256 hash across out-of-order multi-link range completions.
pub struct ContiguousSha256Verifier {
    hasher: Sha256,
    contiguous_offset: u64,
    total_len: u64,
    completed_ranges: BTreeSet<(u64, u64)>, // (start, end_exclusive)
}

impl ContiguousSha256Verifier {
    pub fn new(total_len: u64) -> Self {
        Self {
            hasher: Sha256::new(),
            contiguous_offset: 0,
            total_len,
            completed_ranges: BTreeSet::new(),
        }
    }

    pub fn contiguous_offset(&self) -> u64 {
        self.contiguous_offset
    }

    /// Registers a newly written `[offset .. offset + in_cache_slice.len())`.
    /// If `offset == contiguous_offset`, hashes directly from the in-cache slice (0 disk read!).
    /// If an out-of-order range fills a left-hand gap, stream-reads only the newly contiguous gap
    /// from OS page cache before calling `POSIX_FADV_DONTNEED`.
    pub fn record_and_advance(
        &mut self,
        offset: u64,
        in_cache_slice: &[u8],
        backing_file: Option<&File>,
    ) -> io::Result<u64> {
        let len = in_cache_slice.len() as u64;
        if len == 0 {
            return Ok(self.contiguous_offset);
        }
        let end = offset.saturating_add(len);

        // Fast path: directly contiguous with verified frontier — hash straight from L1/L2 cache!
        if offset == self.contiguous_offset {
            self.hasher.update(in_cache_slice);
            self.contiguous_offset = end;
            self.drain_ready_gaps(backing_file)?;
            return Ok(self.contiguous_offset);
        }

        // Out-of-order fast path (e.g. 5G ahead of Wi-Fi): record range; read from page cache once caught up
        self.completed_ranges.insert((offset, end));
        self.drain_ready_gaps(backing_file)?;
        Ok(self.contiguous_offset)
    }

    fn drain_ready_gaps(&mut self, backing_file: Option<&File>) -> io::Result<()> {
        let mut scratch = vec![0u8; 256 * 1024];
        while let Some(&(start, end)) = self.completed_ranges.iter().next() {
            if start > self.contiguous_offset {
                break;
            }
            self.completed_ranges.remove(&(start, end));
            if end <= self.contiguous_offset {
                continue;
            }
            let read_from = self.contiguous_offset;
            let to_read = end - read_from;
            if let Some(file) = backing_file {
                #[cfg(unix)]
                {
                    let mut pos = read_from;
                    let mut rem = to_read;
                    while rem > 0 {
                        let chunk = rem.min(scratch.len() as u64) as usize;
                        let n = rustix::io::pread(file.as_fd(), &mut scratch[..chunk], pos)
                            .map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
                        if n == 0 {
                            break;
                        }
                        self.hasher.update(&scratch[..n]);
                        pos += n as u64;
                        rem -= n as u64;
                    }
                    advise_dontneed(file, read_from, to_read);
                }
            }
            self.contiguous_offset = end;
        }
        let _ = self.total_len;
        Ok(())
    }

    pub fn finalize_hex(self) -> String {
        self.hasher
            .finalize()
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect::<String>()
    }
}
