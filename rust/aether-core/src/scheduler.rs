use crate::F2FS_SECTION_BYTES;

#[derive(Debug, Clone, Copy)]
pub struct LinkMetrics {
    pub id: u8,
    pub queue_bytes: f64,
    pub rate_bytes_per_sec: f64,
    pub rtt_min_secs: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RangeExtent {
    pub index: usize,
    pub start: u64,
    pub end: u64,
}

/// Earliest-Arrival Multipath Scheduler:
/// k* = argmin[(Q_k + S_b) / R_k(t) + RTT_min / 2]
pub fn select_earliest_arrival_link(links: &[LinkMetrics], block_bytes: f64) -> Option<u8> {
    links
        .iter()
        .filter(|l| l.rate_bytes_per_sec > 0.0)
        .min_by(|a, b| {
            let eta_a = (a.queue_bytes + block_bytes) / a.rate_bytes_per_sec + (a.rtt_min_secs * 0.5);
            let eta_b = (b.queue_bytes + block_bytes) / b.rate_bytes_per_sec + (b.rtt_min_secs * 0.5);
            eta_a.total_cmp(&eta_b)
        })
        .map(|l| l.id)
}

/// Splits a total byte size into exact 2 MB (0x200000) F2FS-aligned byte ranges.
pub fn build_f2fs_extents(total_bytes: u64) -> Vec<RangeExtent> {
    if total_bytes == 0 {
        return Vec::new();
    }
    let seg = F2FS_SECTION_BYTES as u64;
    let count = total_bytes.div_ceil(seg) as usize;
    (0..count)
        .map(|i| {
            let start = (i as u64) * seg;
            let end = (start + seg - 1).min(total_bytes - 1);
            RangeExtent { index: i, start, end }
        })
        .collect()
}

/// Bisects a lagging extent on exact 2 MB boundaries when possible, or mirrors tail hedge.
pub fn bisect_f2fs_range(start: u64, end: u64) -> Option<(u64, u64)> {
    let len = end.saturating_sub(start) + 1;
    let seg = F2FS_SECTION_BYTES as u64;
    if len >= seg * 2 {
        let half_blocks = (len / seg) / 2;
        let split = start + half_blocks * seg;
        Some((split, end))
    } else {
        None
    }
}
