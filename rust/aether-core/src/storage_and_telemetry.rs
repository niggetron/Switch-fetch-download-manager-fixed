use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicPtr, AtomicU64, Ordering};

use crossbeam_queue::ArrayQueue;
#[cfg(unix)]
use rustix::fs::{FallocateFlags, MemfdFlags, fallocate, fdatasync, ftruncate, memfd_create};
#[cfg(unix)]
use rustix::io::pwrite;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

pub const SLAB_BYTES: usize = 512 * 1024; // 512 KiB page-aligned buffer
pub const MAX_SLABS: usize = 16; // 8.0 MiB bounded native RAM pool
pub const MIN_SYSTEM_RESERVE_BYTES: u64 = 64 * 1024 * 1024; // 64 MiB ENOSPC reserve guard

/// 4,096-byte cache-line isolated telemetry page backed by Linux memfd_create().
/// Mapped READ_ONLY in Kotlin and polled at 2–4 Hz (0 Hz on ON_STOP).
#[repr(C, align(64))]
pub struct TelemetryPage {
    pub total_written: AtomicU64,            // 0x000: advanced strictly AFTER pwrite succeeds
    pub total_expected: AtomicU64,           // 0x008: total file length
    pub goodput_bps: AtomicU64,              // 0x010: aggregate EWMA bits/sec
    pub state_flags: AtomicU64,              // 0x018: state + capability/kill-switch bits
    pub wifi_goodput_bps: AtomicU64,         // 0x020: Wi-Fi link bits/sec
    pub cell_goodput_bps: AtomicU64,         // 0x028: 5G/LTE cellular bits/sec
    pub eth_goodput_bps: AtomicU64,          // 0x030: USB-C Ethernet bits/sec
    pub ttfb_ms: AtomicU64,                  // 0x038: latest first-byte latency (ms)
    pub producer_seq: AtomicU64,             // 0x040: network chunk counter
    pub wifi_bytes_total: AtomicU64,         // 0x048: cumulative Wi-Fi bytes committed
    pub cell_bytes_total: AtomicU64,         // 0x050: cumulative Cellular bytes committed
    pub worker_counts_packed: AtomicU64,     // 0x058: [eth:16 | cell:16 | wifi:16 | total:16]
    pub blocked_reason_code: AtomicU64,      // 0x060: last NDK android_getnetworkblockedreason
    pub contiguous_verified_offset: AtomicU64, // 0x068: sequential SHA-256 verified frontier
    pub cellular_quota_remaining: AtomicU64, // 0x070: remaining cellular session budget
    pub _pad1: [u8; 8],                      // 0x078..0x07F
    pub consumer_seq: AtomicU64,             // 0x080: storage flush counter
    pub _pad2: [u8; 56],                     // 0x088..0x0BF
    pub segment_map: [u8; 3904],             // 0x0C0..0xFFF: 0=pending, 1=leased, 2=done
}

const _: () = assert!(core::mem::size_of::<TelemetryPage>() == 4096);

impl TelemetryPage {
    pub const fn new() -> Self {
        Self {
            total_written: AtomicU64::new(0),
            total_expected: AtomicU64::new(0),
            goodput_bps: AtomicU64::new(0),
            state_flags: AtomicU64::new(0),
            wifi_goodput_bps: AtomicU64::new(0),
            cell_goodput_bps: AtomicU64::new(0),
            eth_goodput_bps: AtomicU64::new(0),
            ttfb_ms: AtomicU64::new(0),
            producer_seq: AtomicU64::new(0),
            wifi_bytes_total: AtomicU64::new(0),
            cell_bytes_total: AtomicU64::new(0),
            worker_counts_packed: AtomicU64::new(0),
            blocked_reason_code: AtomicU64::new(0),
            contiguous_verified_offset: AtomicU64::new(0),
            cellular_quota_remaining: AtomicU64::new(u64::MAX),
            _pad1: [0u8; 8],
            consumer_seq: AtomicU64::new(0),
            _pad2: [0u8; 56],
            segment_map: [0u8; 3904],
        }
    }
}

static SHARED_PAGE_PTR: AtomicPtr<TelemetryPage> = AtomicPtr::new(std::ptr::null_mut());
static FALLBACK_PAGE: TelemetryPage = TelemetryPage::new();

/// Returns the active shared-memory `TelemetryPage` backed by `memfd_create()`,
/// or falls back safely to the static page prior to initialization / on non-Unix hosts.
pub fn live_telemetry_page() -> &'static TelemetryPage {
    let ptr = SHARED_PAGE_PTR.load(Ordering::Acquire);
    if ptr.is_null() {
        &FALLBACK_PAGE
    } else {
        unsafe { &*ptr }
    }
}

/// Create a 4 KB `memfd_create()` page and map it `MAP_SHARED` `PROT_READ | PROT_WRITE`
/// inside Rust so every atomic telemetry write is immediately visible zero-JNI in Kotlin.
#[cfg(unix)]
pub fn create_telemetry_memfd() -> io::Result<OwnedFd> {
    let fd = memfd_create(c"aether_telemetry", MemfdFlags::CLOEXEC)
        .map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
    ftruncate(&fd, 4096).map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
    unsafe {
        let mapped = libc::mmap(
            std::ptr::null_mut(),
            4096,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_SHARED,
            fd.as_raw_fd(),
            0,
        );
        if mapped != libc::MAP_FAILED {
            let page_ptr = mapped.cast::<TelemetryPage>();
            page_ptr.write(TelemetryPage::new());
            let prev = SHARED_PAGE_PTR.swap(page_ptr, Ordering::AcqRel);
            if !prev.is_null() {
                libc::munmap(prev.cast(), 4096);
            }
        }
    }
    Ok(fd)
}

/// RAII lease guard returning slab buffer cleanly to the lock-free ArrayQueue and Semaphore.
pub struct SlabLeaseGuard {
    queue: Arc<ArrayQueue<Vec<u8>>>,
    in_use_count: Arc<AtomicU64>,
    buf: Option<Vec<u8>>,
    _permit: OwnedSemaphorePermit,
}

impl SlabLeaseGuard {
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        self.buf.as_mut().expect("slab buffer already released")
    }

    pub fn as_slice(&self) -> &[u8] {
        self.buf.as_ref().expect("slab buffer already released")
    }
}

impl Drop for SlabLeaseGuard {
    fn drop(&mut self) {
        self.in_use_count
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_sub(1)))
            .ok();
        if let Some(mut buf) = self.buf.take() {
            buf.resize(SLAB_BYTES, 0);
            let _ = self.queue.push(buf);
        }
    }
}

/// Lock-free Semaphore-gated BoundedSlabPool (16 × 512 KiB = 8.0 MiB hard cap).
/// Eliminates mutex poisoning and unbounded heap allocation: when all 16 permits are busy,
/// `acquire_guard_async().await` suspends socket reads so the kernel TCP receive window
/// applies natural backpressure to slow flash storage.
pub struct BoundedSlabPool {
    semaphore: Arc<Semaphore>,
    queue: Arc<ArrayQueue<Vec<u8>>>,
    allocated_count: AtomicU64,
    in_use_count: Arc<AtomicU64>,
}

impl BoundedSlabPool {
    pub fn new() -> Self {
        let queue = Arc::new(ArrayQueue::new(MAX_SLABS));
        for _ in 0..MAX_SLABS {
            let _ = queue.push(vec![0u8; SLAB_BYTES]);
        }
        Self {
            semaphore: Arc::new(Semaphore::new(MAX_SLABS)),
            queue,
            allocated_count: AtomicU64::new(MAX_SLABS as u64),
            in_use_count: Arc::new(AtomicU64::new(0)),
        }
    }

    pub async fn acquire_guard_async(&self) -> io::Result<SlabLeaseGuard> {
        let permit = self
            .semaphore
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "Slab pool closed"))?;
        let buf = self
            .queue
            .pop()
            .ok_or_else(|| io::Error::new(io::ErrorKind::OutOfMemory, "Slab queue invariant"))?;
        self.in_use_count.fetch_add(1, Ordering::Relaxed);
        Ok(SlabLeaseGuard {
            queue: self.queue.clone(),
            in_use_count: self.in_use_count.clone(),
            buf: Some(buf),
            _permit: permit,
        })
    }

    pub fn try_acquire_guard(&self) -> Option<SlabLeaseGuard> {
        let permit = self.semaphore.clone().try_acquire_owned().ok()?;
        let buf = self.queue.pop()?;
        self.in_use_count.fetch_add(1, Ordering::Relaxed);
        Some(SlabLeaseGuard {
            queue: self.queue.clone(),
            in_use_count: self.in_use_count.clone(),
            buf: Some(buf),
            _permit: permit,
        })
    }

    /// Synchronous helper used in JNI/unit-test paths; blocks briefly on tokio/spin until permit available.
    pub fn acquire_guard(&self) -> SlabLeaseGuard {
        loop {
            if let Some(g) = self.try_acquire_guard() {
                return g;
            }
            std::thread::yield_now();
        }
    }

    pub fn allocated_bytes(&self) -> u64 {
        self.allocated_count.load(Ordering::Relaxed) * SLAB_BYTES as u64
    }

    pub fn in_use_bytes(&self) -> u64 {
        self.in_use_count.load(Ordering::Relaxed) * SLAB_BYTES as u64
    }
}

/// Probes whether Android Bionic seccomp allows io_uring_setup on this kernel/SELinux context.
#[cfg(target_os = "android")]
pub fn probe_io_uring_supported() -> bool {
    let fd = unsafe { libc::syscall(425, 4u32, std::ptr::null_mut::<libc::c_void>()) };
    if fd >= 0 {
        unsafe { libc::close(fd as i32) };
        true
    } else {
        false
    }
}

#[cfg(not(target_os = "android"))]
pub fn probe_io_uring_supported() -> bool {
    false
}

/// Enforces fstatvfs available_bytes >= total_len + 64 MiB before file allocation (ENOSPC guard).
#[cfg(unix)]
pub fn check_free_space_for_fd(fd: i32, required_bytes: u64) -> io::Result<()> {
    if required_bytes == 0 {
        return Ok(());
    }
    let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::fstatvfs(fd, &mut stat) };
    if rc != 0 {
        return Ok(()); // Some virtual memfd/pipe FDs don't report fstatvfs; let fallocate verify
    }
    let avail = (stat.f_bavail as u64).saturating_mul(stat.f_frsize as u64);
    let min_needed = required_bytes.saturating_add(MIN_SYSTEM_RESERVE_BYTES);
    if avail > 0 && avail < min_needed {
        return Err(io::Error::new(
            io::ErrorKind::StorageFull,
            format!(
                "ENOSPC guard: available {} MiB < required {} MiB + 64 MiB margin",
                avail / (1024 * 1024),
                required_bytes / (1024 * 1024)
            ),
        ));
    }
    Ok(())
}

#[cfg(not(unix))]
pub fn check_free_space_for_fd(_fd: i32, _required_bytes: u64) -> io::Result<()> {
    Ok(())
}

/// Pre-allocates extents via fallocate() with ftruncate/set_len fallback for FUSE paths.
/// Distinguishes ESPIPE (non-seekable SAF provider) cleanly so caller can trigger staging fallback.
pub fn preallocate_file(file: &File, total_len: u64) -> io::Result<()> {
    if total_len == 0 {
        return Ok(());
    }
    #[cfg(unix)]
    {
        check_free_space_for_fd(file.as_raw_fd(), total_len)?;
        match fallocate(file.as_fd(), FallocateFlags::empty(), 0, total_len) {
            Ok(()) => return Ok(()),
            Err(err) if err.raw_os_error() == libc::ESPIPE => {
                return Err(io::Error::from_raw_os_error(libc::ESPIPE));
            }
            Err(_) => {
                // FUSE often returns EOPNOTSUPP on fallocate; use ftruncate cleanly.
            }
        }
    }
    file.set_len(total_len)
}

/// Crash-safe positional pwrite partial-write loop (never advances shared file cursor).
#[cfg(unix)]
pub fn pwrite_all(file: &File, mut buf: &[u8], mut offset: u64) -> io::Result<()> {
    while !buf.is_empty() {
        let written = pwrite(file.as_fd(), buf, offset)
            .map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
        if written == 0 {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "pwrite returned 0"));
        }
        buf = &buf[written..];
        offset += written as u64;
    }
    Ok(())
}

#[cfg(unix)]
pub fn flush_fdatasync(file: &File) -> io::Result<()> {
    fdatasync(file.as_fd()).map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))
}

#[cfg(target_os = "android")]
pub fn advise_dontneed(file: &File, offset: u64, len: u64) {
    unsafe {
        libc::posix_fadvise64(
            file.as_raw_fd(),
            offset as libc::off64_t,
            len as libc::off64_t,
            libc::POSIX_FADV_DONTNEED,
        );
    }
}

#[cfg(not(target_os = "android"))]
pub fn advise_dontneed(_file: &File, _offset: u64, _len: u64) {}

/// Fallback staging copy helper for non-seekable ESPIPE SAF providers or >= 1 GiB F2FS promotion.
#[cfg(unix)]
pub fn stream_copy_staging_to_fd(
    staging_path: &Path,
    dest_raw_fd: i32,
    pool: &BoundedSlabPool,
) -> io::Result<u64> {
    let mut src = OpenOptions::new().read(true).open(staging_path)?;
    let mut dst_file = unsafe { File::from_raw_fd(dest_raw_fd) };
    let mut guard = pool.acquire_guard();
    let buf = guard.as_mut_slice();
    let mut total_copied = 0u64;

    let res = (|| -> io::Result<u64> {
        loop {
            let n = src.read(buf)?;
            if n == 0 {
                break;
            }
            dst_file.write_all(&buf[..n])?;
            total_copied += n as u64;
        }
        dst_file.flush()?;
        Ok(total_copied)
    })();

    std::mem::forget(dst_file);
    res
}
