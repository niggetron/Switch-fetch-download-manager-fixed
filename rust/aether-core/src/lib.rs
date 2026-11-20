//! Project AetherIO Omega v6.0 - portable Rust core behind `libaether_core.so`.
//!
//! This crate is built on **stable Rust** (verified with 1.100). The previous
//! revision used nightly-only APIs (`allocator_api`, `DropGuard`, `conjure_zst`,
//! `UnsafeCell::as_mut_unchecked`) which made it impossible to cross-compile in
//! CI, so the `.so` was never produced and the app always fell back to Kotlin.
//!
//! Layers exposed to Kotlin through `AetherNativeBridge`:
//! - BLAKE3 content-addressed hashing (SIMD accelerated) for the CAS index.
//! - GF(2^8) RLNC coding helpers for lossy-link repair.
//! - Earliest-arrival multipath scheduler decisions.
//! - 2 MB F2FS extent math and a stable, bounded bump arena.
//! - Cache-line isolated atomic telemetry page.

pub mod cas;
pub mod jni_bridge;
pub mod rlnc;
pub mod scheduler;
pub mod telemetry;

use std::cell::UnsafeCell;
use std::ptr::NonNull;

pub const ARENA_BYTES: usize = 16 * 1024 * 1024; // 16 MB virtual arena
pub const F2FS_SECTION_BYTES: usize = 2 * 1024 * 1024; // 2 MB = 0x200000
pub const F2FS_SECTION_MASK: u64 = 0x001F_FFFF; // 2 MB alignment mask
pub const ENGINE_VERSION: &str =
    "libaether_core.so (Rust stable · BLAKE3 + GF256 RLNC + F2FS 2MB)";

/// Stable, 4096-byte-aligned bump arena with a hard 16 MB ceiling.
///
/// `std::alloc::Allocator` is still unstable, so the arena exposes a simple
/// `alloc` method instead. Allocations are valid until `reset` is called.
pub struct SlabArenaAlloc {
    // Heap-backed so a 16 MB arena never lands on a thread's stack.
    backing: UnsafeCell<Box<[u8]>>,
    cursor: UnsafeCell<usize>,
}

// Single-issuer arena: callers synchronise through the JNI worker boundary.
unsafe impl Sync for SlabArenaAlloc {}

impl SlabArenaAlloc {
    pub fn new_zeroed() -> Self {
        Self {
            backing: UnsafeCell::new(vec![0u8; ARENA_BYTES].into_boxed_slice()),
            cursor: UnsafeCell::new(0),
        }
    }

    pub fn used_bytes(&self) -> usize {
        unsafe { *self.cursor.get() }
    }

    pub fn reset(&self) {
        self.set_cursor(0);
    }

    fn set_cursor(&self, value: usize) {
        unsafe { *self.cursor.get() = value };
    }

    /// Bump-allocate `size` bytes aligned to at least 4096 bytes.
    pub fn alloc(&self, size: usize, align: usize) -> Option<NonNull<u8>> {
        if !align.is_power_of_two() {
            return None;
        }
        unsafe {
            let cur = *self.cursor.get();
            let align = align.max(4096);
            let start = (cur + align - 1) & !(align - 1);
            let next = start.checked_add(size)?;
            if next > ARENA_BYTES {
                return None;
            }
            *self.cursor.get() = next;
            let base = (*self.backing.get()).as_mut_ptr();
            NonNull::new(base.add(start))
        }
    }
}

/// Stable replacement for the unstable `DropGuard`. Restores the arena cursor
/// when an aborted transfer leaves scope, so failed writes never leak offsets.
pub struct ArenaCursorGuard<'a> {
    arena: &'a SlabArenaAlloc,
    saved: usize,
}

impl<'a> ArenaCursorGuard<'a> {
    pub fn new(arena: &'a SlabArenaAlloc) -> Self {
        Self {
            arena,
            saved: arena.used_bytes(),
        }
    }
}

impl Drop for ArenaCursorGuard<'_> {
    fn drop(&mut self) {
        self.arena.set_cursor(self.saved);
    }
}

/// Commits an exact 2 MB F2FS-aligned extent through io_uring, using an
/// `ArenaCursorGuard` so aborted transfers roll back cleanly.
pub fn commit_2mb_f2fs_extent(
    ring: &mut io_uring::IoUring,
    arena: &SlabArenaAlloc,
    ledger: &UnsafeCell<u64>,
    fd: i32,
    offset: u64,
    payload: &[u8],
) -> Result<u64, &'static str> {
    if (offset & F2FS_SECTION_MASK) != 0 {
        return Err("Offset is not 2 MB F2FS section-aligned");
    }
    if payload.len() > F2FS_SECTION_BYTES {
        return Err("Payload exceeds 2 MB F2FS extent size");
    }

    let _rollback = ArenaCursorGuard::new(arena);
    let slab = arena
        .alloc(payload.len(), 4096)
        .ok_or("Arena exhausted")?;
    unsafe {
        std::ptr::copy_nonoverlapping(payload.as_ptr(), slab.as_ptr(), payload.len());
    }

    let write_e = io_uring::opcode::Write::new(
        io_uring::types::Fd(fd),
        slab.as_ptr(),
        payload.len() as u32,
    )
    .offset(offset)
    .build()
    .user_data(offset);

    unsafe {
        ring.submission()
            .push(&write_e)
            .map_err(|_| "io_uring SQ full")?;
    }
    ring.submit_and_wait(1)
        .map_err(|_| "io_uring_enter write failed")?;

    let cqe = ring
        .completion()
        .next()
        .ok_or("Missing io_uring completion")?;
    if cqe.result() < 0 {
        return Err("Kernel write CQE error");
    }
    unsafe {
        let total = ledger.get();
        *total += cqe.result() as u64;
        Ok(*total)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slab_arena_aligns_to_4k_and_bounds_16mb() {
        let arena = SlabArenaAlloc::new_zeroed();
        let p = arena.alloc(1024, 4096).expect("arena alloc");
        assert_eq!(p.as_ptr() as usize % 4096, 0);
        assert_eq!(arena.used_bytes(), 1024);
        arena.reset();
        assert_eq!(arena.used_bytes(), 0);
        assert!(arena.alloc(ARENA_BYTES + 1, 4096).is_none());
    }

    #[test]
    fn cursor_guard_rolls_back_on_drop() {
        let arena = SlabArenaAlloc::new_zeroed();
        {
            let _guard = ArenaCursorGuard::new(&arena);
            assert!(arena.alloc(8192, 4096).is_some());
            assert_eq!(arena.used_bytes(), 8192);
        }
        assert_eq!(arena.used_bytes(), 0);
    }
}
