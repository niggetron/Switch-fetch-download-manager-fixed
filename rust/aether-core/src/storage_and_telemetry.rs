use std::fs::{File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::{AsFd, AsRawFd, FromRawFd, OwnedFd};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

#[cfg(unix)]
use rustix::fs::{FallocateFlags, MemfdFlags, fallocate, fdatasync, ftruncate, memfd_create};
#[cfg(unix)]
use rustix::io::pwrite;

pub const SLAB_BYTES: usize = 512 * 1024; // 512 KiB page-aligned buffer
pub const MAX_SLABS: usize = 16; // 8.0 MiB bounded native RAM pool

/// 4,096-byte cache-line isolated telemetry page backed by Linux memfd_create()
/// Mapped READ_ONLY in Kotlin and polled at 2–4 Hz (0 Hz on ON_STOP) via VarHandle/ByteBuffer.
#[repr(C, align(64))]
pub struct TelemetryPage {
    pub total_written: AtomicU64,  // 0x000: advanced strictly AFTER pwrite succeeds
    pub total_expected: AtomicU64, // 0x008: total file length
    pub goodput_bps: AtomicU64,    // 0x010: 5-second EWMA bits/sec
    pub state_flags: AtomicU64,    // 0x018: low byte = State, bit 8 = QuotaGuard, bit 9 = Metered
    pub _pad0: [u8; 32],           // 0x020..0x03F
    pub producer_seq: AtomicU64,   // 0x040: network chunk counter
    pub _pad1: [u8; 56],           // 0x048..0x07F
    pub consumer_seq: AtomicU64,   // 0x080: storage flush counter
    pub _pad2: [u8; 56],           // 0x088..0x0BF
    pub segment_map: [u8; 3904],   // 0x0C0..0xFFF: 0=pending, 1=leased, 2=done
}

const _: () = assert!(core::mem::size_of::<TelemetryPage>() == 4096);

impl TelemetryPage {
    pub const fn new() -> Self {
        Self {
            total_written: AtomicU64::new(0),
            total_expected: AtomicU64::new(0),
            goodput_bps: AtomicU64::new(0),
            state_flags: AtomicU64::new(0),
            _pad0: [0u8; 32],
            producer_seq: AtomicU64::new(0),
            _pad1: [0u8; 56],
            consumer_seq: AtomicU64::new(0),
            _pad2: [0u8; 56],
            segment_map: [0u8; 3904],
        }
    }
}

/// Create a 4 KB memfd page for zero-JNI reads in Kotlin.
#[cfg(unix)]
pub fn create_telemetry_memfd() -> io::Result<OwnedFd> {
    let fd = memfd_create(c"aether_telemetry", MemfdFlags::CLOEXEC)
        .map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
    ftruncate(&fd, 4096).map_err(|e| io::Error::from_raw_os_error(e.raw_os_error()))?;
    Ok(fd)
}

/// RAII lease guard ensuring any acquired slab buffer returns cleanly to BoundedSlabPool on drop/unwind.
pub struct SlabLeaseGuard<'a> {
    pool: &'a BoundedSlabPool,
    buf: Option<Vec<u8>>,
}

impl<'a> SlabLeaseGuard<'a> {
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
        self.buf.as_mut().expect("slab buffer already released")
    }

    pub fn as_slice(&self) -> &[u8] {
        self.buf.as_ref().expect("slab buffer already released")
    }
}

impl Drop for SlabLeaseGuard<'_> {
    fn drop(&mut self) {
        if let Some(buf) = self.buf.take() {
            self.pool.release(buf);
        }
    }
}

/// Thread-safe bounded slab pool (16 × 512 KiB = 8.0 MiB) replacing unsound UnsafeCell bump allocators.
pub struct BoundedSlabPool {
    free: Mutex<Vec<Vec<u8>>>,
    allocated_count: AtomicU64,
    in_use_count: AtomicU64,
}

impl BoundedSlabPool {
    pub fn new() -> Self {
        let mut slabs = Vec::with_capacity(MAX_SLABS);
        for _ in 0..MAX_SLABS {
            slabs.push(vec![0u8; SLAB_BYTES]);
        }
        Self {
            free: Mutex::new(slabs),
            allocated_count: AtomicU64::new(MAX_SLABS as u64),
            in_use_count: AtomicU64::new(0),
        }
    }

    pub fn acquire_guard(&self) -> SlabLeaseGuard<'_> {
        let buf = self.acquire();
        SlabLeaseGuard {
            pool: self,
            buf: Some(buf),
        }
    }

    pub fn acquire(&self) -> Vec<u8> {
        self.in_use_count.fetch_add(1, Ordering::Relaxed);
        let mut guard = self.free.lock().unwrap_or_else(|p| p.into_inner());
        guard.pop().unwrap_or_else(|| {
            self.allocated_count.fetch_add(1, Ordering::Relaxed);
            vec![0u8; SLAB_BYTES]
        })
    }

    pub fn release(&self, mut buf: Vec<u8>) {
        self.in_use_count
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_sub(1)))
            .ok();
        buf.resize(SLAB_BYTES, 0);
        let mut guard = self.free.lock().unwrap_or_else(|p| p.into_inner());
        if guard.len() < MAX_SLABS {
            guard.push(buf);
        } else {
            self.allocated_count
                .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_sub(1)))
                .ok();
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
/// Stock Android apps receive ENOSYS; falls back cleanly to fallocate + pwrite + fdatasync.
#[cfg(target_os = "android")]
pub fn probe_io_uring_supported() -> bool {
    // Syscall number for io_uring_setup on aarch64/x86_64 is 425
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

/// Pre-allocates contiguous extents on F2FS/UFS 4.0 via fallocate() with set_len fallback.
pub fn preallocate_file(file: &File, total_len: u64) -> io::Result<()> {
    if total_len == 0 {
        return Ok(());
    }
    #[cfg(unix)]
    {
        if fallocate(file.as_fd(), FallocateFlags::empty(), 0, total_len).is_ok() {
            return Ok(());
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

/// Streams an app-private verified staging file into an Android SAF/MediaStore file descriptor
/// in 512 KiB BoundedSlabPool chunks, avoiding mid-run SAF EBADF descriptor revocations.
#[cfg(unix)]
pub fn stream_copy_staging_to_fd(
    staging_path: &Path,
    dest_raw_fd: i32,
    pool: &BoundedSlabPool,
) -> io::Result<u64> {
    let mut src = OpenOptions::new().read(true).open(staging_path)?;
    // Borrow raw fd without taking ownership so Android ParcelFileDescriptor manages closing
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

    // Prevent File::drop from closing the caller-owned ParcelFileDescriptor fd
    std::mem::forget(dst_file);
    res
}
