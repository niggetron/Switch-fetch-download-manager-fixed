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

pub const SLAB_BYTES: usize = 512 * 1024; // 512 KiB page-aligned slab buffer
pub const MAX_SLABS: usize = 24; // 12.0 MiB bounded native RAM pool for 16 workers + async disk writer
pub const MIN_SYSTEM_RESERVE_BYTES: u64 = 64 * 1024 * 1024; // 64 MiB ENOSPC reserve guard

/// 4,096-byte cache-line isolated Quad-Link telemetry page backed by Linux memfd_create().
/// Mapped READ_ONLY in Kotlin and polled at 4 Hz (0 Hz on ON_STOP).
///
/// Exact byte offsets shared with `MemfdTelemetryReader.kt`:
/// - `0x000`: total_written
/// - `0x008`: total_expected
/// - `0x010`: goodput_bps (aggregate bits/sec)
/// - `0x018`: state_flags
/// - `0x020`: wifi_goodput_bps (Wi-Fi 1 bits/sec)
/// - `0x028`: cell_goodput_bps (Mobile 5G/LTE bits/sec)
/// - `0x030`: eth_goodput_bps (USB Tethering / USB-C Ethernet bits/sec)
/// - `0x038`: ttfb_ms
/// - `0x040`: producer_seq
/// - `0x048`: wifi_bytes_total (Wi-Fi 1 cumulative bytes)
/// - `0x050`: cell_bytes_total (Mobile 5G/LTE cumulative bytes)
/// - `0x058`: worker_counts_packed `[usb:12 | cell:12 | wifi2:12 | wifi1:12 | total:16]`
/// - `0x060`: blocked_reason_code
/// - `0x068`: contiguous_verified_offset
/// - `0x070`: cellular_quota_remaining
/// - `0x078`: wifi2_goodput_bps (Wi-Fi 2 bits/sec)
/// - `0x080`: consumer_seq (storage flush batches)
/// - `0x088`: wifi2_bytes_total (Wi-Fi 2 cumulative bytes)
/// - `0x090`: eth_bytes_total (USB / Ethernet cumulative bytes)
/// - `0x098`: anti_dip_rescues (count of live range bisections & stall rescues)
#[repr(C, align(64))]
pub struct TelemetryPage {
    pub total_written: AtomicU64,              // 0x000
    pub total_expected: AtomicU64,             // 0x008
    pub goodput_bps: AtomicU64,                // 0x010
    pub state_flags: AtomicU64,                // 0x018
    pub wifi_goodput_bps: AtomicU64,           // 0x020
    pub cell_goodput_bps: AtomicU64,           // 0x028
    pub eth_goodput_bps: AtomicU64,            // 0x030
    pub ttfb_ms: AtomicU64,                    // 0x038
    pub producer_seq: AtomicU64,               // 0x040
    pub wifi_bytes_total: AtomicU64,           // 0x048
    pub cell_bytes_total: AtomicU64,           // 0x050
    pub worker_counts_packed: AtomicU64,       // 0x058: [usb:12 | cell:12 | wifi2:12 | wifi1:12 | total:16]
    pub blocked_reason_code: AtomicU64,        // 0x060
    pub contiguous_verified_offset: AtomicU64, // 0x068
    pub cellular_quota_remaining: AtomicU64,   // 0x070
    pub wifi2_goodput_bps: AtomicU64,          // 0x078
    pub consumer_seq: AtomicU64,               // 0x080
    pub wifi2_bytes_total: AtomicU64,          // 0x088
    pub eth_bytes_total: AtomicU64,            // 0x090
    pub anti_dip_rescues: AtomicU64,           // 0x098
    pub _pad2: [u8; 32],                       // 0x0A0..0x0BF
    pub segment_map: [u8; 3904],               // 0x0C0..0xFFF
}

const _: () = assert!(core::mem::size_of::<TelemetryPage>() == 4096);

/// Packs 4-link worker counts into 64 bits: `[usb:12 | cell:12 | wifi2:12 | wifi1:12 | total:16]`.
pub const fn pack_quad_worker_counts(
    total: u64,
    wifi1: u64,
    wifi2: u64,
    cell: u64,
    usb: u64,
) -> u64 {
    ((usb & 0x0FFF) << 52)
        | ((cell & 0x0FFF) << 40)
        | ((wifi2 & 0x0FFF) << 28)
        | ((wifi1 & 0x0FFF) << 16)
        | (total & 0xFFFF)
}

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
            wifi2_goodput_bps: AtomicU64::new(0),
            consumer_seq: AtomicU64::new(0),
            wifi2_bytes_total: AtomicU64::new(0),
            eth_bytes_total: AtomicU64::new(0),
            anti_dip_rescues: AtomicU64::new(0),
            _pad2: [0u8; 32],
            segment_map: [0u8; 3904],
        }
    }
}

static SHARED_PAGE_PTR: AtomicPtr<TelemetryPage> = AtomicPtr::new(std::ptr::null_mut());
static FALLBACK_PAGE: TelemetryPage = TelemetryPage::new();

pub fn live_telemetry_page() -> &'static TelemetryPage {
    let ptr = SHARED_PAGE_PTR.load(Ordering::Acquire);
    if ptr.is_null() {
        &FALLBACK_PAGE
    } else {
        unsafe { &*ptr }
    }
}

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
        loop {
            let cur = self.in_use_count.load(Ordering::Relaxed);
            if cur == 0
                || self
                    .in_use_count
                    .compare_exchange_weak(cur, cur - 1, Ordering::Relaxed, Ordering::Relaxed)
                    .is_ok()
            {
                break;
            }
        }
        if let Some(mut buf) = self.buf.take() {
            if buf.len() != SLAB_BYTES {
                buf.resize(SLAB_BYTES, 0);
            }
            let _ = self.queue.push(buf);
        }
    }
}

/// Lock-free Semaphore-gated BoundedSlabPool (24 × 512 KiB = 12.0 MiB hard cap).
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

#[cfg(unix)]
pub fn check_free_space_for_fd(fd: i32, required_bytes: u64) -> io::Result<()> {
    if required_bytes == 0 {
        return Ok(());
    }
    let mut stat: libc::statvfs = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::fstatvfs(fd, &mut stat) };
    if rc != 0 {
        return Ok(());
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
            Err(_) => {}
        }
    }
    file.set_len(total_len)
}

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

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn advise_dontneed(file: &File, offset: u64, len: u64) {
    const LINUX_POSIX_FADV_DONTNEED: libc::c_int = 4;
    unsafe {
        libc::posix_fadvise(
            file.as_raw_fd(),
            offset as libc::off_t,
            len as libc::off_t,
            LINUX_POSIX_FADV_DONTNEED,
        );
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn advise_dontneed(_file: &File, _offset: u64, _len: u64) {}

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
