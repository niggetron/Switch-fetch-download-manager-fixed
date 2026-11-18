use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::path::Path;

use sha2::{Digest, Sha256};

use crate::storage_and_telemetry::{flush_fdatasync, pwrite_all};

pub const LEDGER_MAGIC: &[u8; 8] = b"AETHLDG1";
pub const LEDGER_VERSION: u32 = 1;
pub const LEDGER_HEADER_LEN: usize = 64; // 0x40 bytes before segment_states

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

/// Crash-safe AETHLDG1 binary ledger (v7.1 Section 07 Table 7.1):
/// 0x00 magic (8B), 0x08 version (4B) + pad (4B), 0x10 total_len (8B),
/// 0x18 segment_bytes (8B), 0x20 segment_count (4B) + flags (4B),
/// 0x28 validator_hash (8B) + reserved (16B),
/// 0x40 segment_states (N bytes), 0x40+N crc32c (4B).
#[derive(Debug, Clone)]
pub struct AetherLedger {
    pub total_len: u64,
    pub segment_bytes: u64,
    pub segment_count: u32,
    pub flags: u32,
    pub validator_hash: [u8; 8],
    pub states: Vec<u8>,
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
        let seg = segment_bytes.max(1024 * 1024);
        let count = if total_len == 0 {
            0
        } else {
            total_len.div_ceil(seg) as u32
        };
        Self {
            total_len,
            segment_bytes: seg,
            segment_count: count,
            flags: 0,
            validator_hash: Self::compute_validator_hash(etag_or_lastmod),
            states: vec![SegmentStatus::Pending as u8; count as usize],
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let body_len = LEDGER_HEADER_LEN + self.states.len();
        let mut buf = Vec::with_capacity(body_len + 4);
        buf.extend_from_slice(LEDGER_MAGIC);
        buf.extend_from_slice(&LEDGER_VERSION.to_le_bytes());
        buf.extend_from_slice(&0u32.to_le_bytes());
        buf.extend_from_slice(&self.total_len.to_le_bytes());
        buf.extend_from_slice(&self.segment_bytes.to_le_bytes());
        buf.extend_from_slice(&self.segment_count.to_le_bytes());
        buf.extend_from_slice(&self.flags.to_le_bytes());
        buf.extend_from_slice(&self.validator_hash);
        buf.extend_from_slice(&[0u8; 16]);
        buf.extend_from_slice(&self.states);
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
        let total_len = u64::from_le_bytes(raw[16..24].try_into().ok()?);
        let segment_bytes = u64::from_le_bytes(raw[24..32].try_into().ok()?);
        let segment_count = u32::from_le_bytes(raw[32..36].try_into().ok()?);
        let flags = u32::from_le_bytes(raw[36..40].try_into().ok()?);
        let mut validator_hash = [0u8; 8];
        validator_hash.copy_from_slice(&raw[40..48]);

        let expected_len = LEDGER_HEADER_LEN + segment_count as usize + 4;
        if raw.len() != expected_len {
            return None;
        }
        let body = &raw[..LEDGER_HEADER_LEN + segment_count as usize];
        let stored_crc = u32::from_le_bytes(raw[expected_len - 4..expected_len].try_into().ok()?);
        if crc32c::crc32c(body) != stored_crc {
            return None;
        }

        // On crash recovery, revert any in-flight Leased (1) segments back to Pending (0)
        let states = raw[LEDGER_HEADER_LEN..LEDGER_HEADER_LEN + segment_count as usize]
            .iter()
            .map(|&s| if s == SegmentStatus::Done as u8 { s } else { SegmentStatus::Pending as u8 })
            .collect();

        Some(Self {
            total_len,
            segment_bytes,
            segment_count,
            flags,
            validator_hash,
            states,
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
        fresh.persist(path)?;
        Ok(fresh)
    }

    #[cfg(unix)]
    pub fn persist(&self, path: &Path) -> io::Result<()> {
        let encoded = self.encode();
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(path)?;
        pwrite_all(&file, &encoded, 0)?;
        flush_fdatasync(&file)
    }

    #[cfg(not(unix))]
    pub fn persist(&self, path: &Path) -> io::Result<()> {
        std::fs::write(path, self.encode())
    }
}
