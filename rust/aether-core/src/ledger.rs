//! Crash-safe AETHLDG2 binary ledger with per-512 KiB BLAKE3 digests,
//! contiguous verified offset tracking, and strict fdatasync -> msync ordering.

use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::path::Path;

use sha2::{Digest, Sha256};

use crate::storage_and_telemetry::{flush_fdatasync, pwrite_all};

pub const LEDGER_MAGIC: &[u8; 8] = b"AETHLDG2";
pub const LEDGER_VERSION: u32 = 2;
pub const LEDGER_HEADER_LEN: usize = 72;
pub const SLAB_DIGEST_BYTES: usize = 32; // BLAKE3 256-bit digest per 256 KiB I/O slab
// Digest granularity must match the writer's SLAB_BYTES (256 KiB). Previously the
// array was sized for 512 KiB while the writer indexed at 256 KiB, silently
// dropping ~half the digests. Kotlin derives the count from the byte length so
// this stays binary-compatible with AETHLDG2 v2.
pub const SLAB_DIGEST_GRANULARITY: u64 = 256 * 1024;

#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SegmentStatus {
    Pending = 0,
    Leased = 1,
    Done = 2,
}

impl SegmentStatus {
    pub fn from_u8(v: u8) -> Self {
        match v {
            1 => SegmentStatus::Leased,
            2 => SegmentStatus::Done,
            _ => SegmentStatus::Pending,
        }
    }
}

/// Crash-safe AETHLDG2 binary ledger:
/// 0x00 magic (8B "AETHLDG2")
/// 0x08 version (4B = 2) + flags (4B)
/// 0x10 total_len (8B)
/// 0x18 segment_bytes (8B)
/// 0x20 segment_count (4B) + slab_digest_count (4B)
/// 0x28 validator_hash (8B)
/// 0x30 contiguous_verified_offset (8B)
/// 0x38 reserved (16B)
/// 0x48 segment_states (segment_count bytes)
/// 0x48+segment_count slab_blake3_digests (slab_digest_count * 32 bytes)
/// tail crc32c (4B)
#[derive(Debug, Clone)]
pub struct AetherLedger {
    pub total_len: u64,
    pub segment_bytes: u64,
    pub segment_count: u32,
    pub slab_digest_count: u32,
    pub flags: u32,
    pub validator_hash: [u8; 8],
    pub contiguous_verified_offset: u64,
    pub states: Vec<u8>,
    pub slab_blake3: Vec<[u8; SLAB_DIGEST_BYTES]>,
}

impl AetherLedger {
    pub fn compute_validator_hash(etag_or_lastmod: &str) -> [u8; 8] {
        let mut hasher = Sha256::new();
        hasher.update(etag_or_lastmod.as_bytes());
        let digest = hasher.finalize();
        let mut out = [0u8; 8];
        out.copy_from_slice(&digest[..8]);
        out
    }

    pub fn new(total_len: u64, segment_bytes: u64, etag_or_lastmod: &str) -> Self {
        let seg = segment_bytes.max(512 * 1024);
        let count = if total_len == 0 {
            0
        } else {
            total_len.div_ceil(seg) as u32
        };
        let slab_count = if total_len == 0 {
            0
        } else {
            total_len.div_ceil(SLAB_DIGEST_GRANULARITY) as u32
        };
        Self {
            total_len,
            segment_bytes: seg,
            segment_count: count,
            slab_digest_count: slab_count,
            flags: 0,
            validator_hash: Self::compute_validator_hash(etag_or_lastmod),
            contiguous_verified_offset: 0,
            states: vec![SegmentStatus::Pending as u8; count as usize],
            slab_blake3: vec![[0u8; SLAB_DIGEST_BYTES]; slab_count as usize],
        }
    }

    pub fn record_slab_digest(&mut self, slab_index: usize, digest: [u8; 32]) {
        if slab_index < self.slab_blake3.len() {
            self.slab_blake3[slab_index] = digest;
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let digests_len = self.slab_blake3.len() * SLAB_DIGEST_BYTES;
        let body_len = LEDGER_HEADER_LEN + self.states.len() + digests_len;
        let mut buf = Vec::with_capacity(body_len + 4);
        buf.extend_from_slice(LEDGER_MAGIC);
        buf.extend_from_slice(&LEDGER_VERSION.to_le_bytes());
        buf.extend_from_slice(&self.flags.to_le_bytes());
        buf.extend_from_slice(&self.total_len.to_le_bytes());
        buf.extend_from_slice(&self.segment_bytes.to_le_bytes());
        buf.extend_from_slice(&self.segment_count.to_le_bytes());
        buf.extend_from_slice(&(self.slab_blake3.len() as u32).to_le_bytes());
        buf.extend_from_slice(&self.validator_hash);
        buf.extend_from_slice(&self.contiguous_verified_offset.to_le_bytes());
        buf.extend_from_slice(&[0u8; 16]);
        buf.extend_from_slice(&self.states);
        for d in &self.slab_blake3 {
            buf.extend_from_slice(d);
        }
        let crc = crc32c::crc32c(&buf);
        buf.extend_from_slice(&crc.to_le_bytes());
        buf
    }

    pub fn decode(raw: &[u8]) -> Option<Self> {
        if raw.len() < LEDGER_HEADER_LEN + 4 {
            return None;
        }
        if &raw[0..8] != LEDGER_MAGIC {
            return None;
        }
        let version = u32::from_le_bytes(raw[8..12].try_into().ok()?);
        if version != LEDGER_VERSION {
            return None;
        }
        let flags = u32::from_le_bytes(raw[12..16].try_into().ok()?);
        let total_len = u64::from_le_bytes(raw[16..24].try_into().ok()?);
        let segment_bytes = u64::from_le_bytes(raw[24..32].try_into().ok()?);
        let segment_count = u32::from_le_bytes(raw[32..36].try_into().ok()?);
        let slab_digest_count = u32::from_le_bytes(raw[36..40].try_into().ok()?);
        let mut validator_hash = [0u8; 8];
        validator_hash.copy_from_slice(&raw[40..48]);
        let contiguous_verified_offset = u64::from_le_bytes(raw[48..56].try_into().ok()?);

        let digests_offset = LEDGER_HEADER_LEN + segment_count as usize;
        let digests_bytes = (slab_digest_count as usize).checked_mul(SLAB_DIGEST_BYTES)?;
        let expected_len = digests_offset + digests_bytes + 4;
        if raw.len() != expected_len {
            return None;
        }
        let body = &raw[..digests_offset + digests_bytes];
        let stored_crc = u32::from_le_bytes(raw[expected_len - 4..expected_len].try_into().ok()?);
        if crc32c::crc32c(body) != stored_crc {
            return None;
        }

        // Reset any crash-interrupted Leased (1) segments cleanly back to Pending (0)
        let states = raw[LEDGER_HEADER_LEN..digests_offset]
            .iter()
            .map(|&s| if s == SegmentStatus::Done as u8 { s } else { SegmentStatus::Pending as u8 })
            .collect();

        let mut slab_blake3 = Vec::with_capacity(slab_digest_count as usize);
        for chunk in raw[digests_offset..digests_offset + digests_bytes].chunks_exact(SLAB_DIGEST_BYTES) {
            let mut d = [0u8; SLAB_DIGEST_BYTES];
            d.copy_from_slice(chunk);
            slab_blake3.push(d);
        }

        Some(Self {
            total_len,
            segment_bytes,
            segment_count,
            slab_digest_count,
            flags,
            validator_hash,
            contiguous_verified_offset,
            states,
            slab_blake3,
        })
    }

    pub fn load_or_create(
        path: &Path,
        total_len: u64,
        segment_bytes: u64,
        etag_or_lastmod: &str,
    ) -> io::Result<Self> {
        if path.exists() {
            let mut file = File::open(path)?;
            let mut raw = Vec::new();
            file.read_to_end(&mut raw)?;
            if let Some(parsed) = Self::decode(&raw) {
                let expected_hash = Self::compute_validator_hash(etag_or_lastmod);
                if parsed.total_len == total_len
                    && (etag_or_lastmod.is_empty() || parsed.validator_hash == expected_hash)
                {
                    return Ok(parsed);
                }
            }
        }
        let fresh = Self::new(total_len, segment_bytes, etag_or_lastmod);
        fresh.persist(path, None)?;
        Ok(fresh)
    }

    /// Enforces strict flush ordering: fdatasync(data_fd) BEFORE ledger write + fdatasync/msync.
    #[cfg(unix)]
    pub fn persist(&self, path: &Path, data_file: Option<&File>) -> io::Result<()> {
        if let Some(df) = data_file {
            flush_fdatasync(df)?;
        }
        let encoded = self.encode();
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(true)
            .open(path)?;
        pwrite_all(&file, &encoded, 0)?;
        flush_fdatasync(&file)?;
        // Also flush an MS_SYNC mmap barrier when sized cleanly on disk
        unsafe {
            use std::os::fd::AsRawFd;
            let mapped = libc::mmap(
                std::ptr::null_mut(),
                encoded.len(),
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                file.as_raw_fd(),
                0,
            );
            if mapped != libc::MAP_FAILED {
                libc::msync(mapped, encoded.len(), libc::MS_SYNC);
                libc::munmap(mapped, encoded.len());
            }
        }
        Ok(())
    }

    #[cfg(not(unix))]
    pub fn persist(&self, path: &Path, _data_file: Option<&File>) -> io::Result<()> {
        std::fs::write(path, self.encode())
    }
}
