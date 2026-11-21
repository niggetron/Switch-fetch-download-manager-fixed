package com.example.engine

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * DirectByteBuffer slab pool for the Kotlin OkHttp engine: 96 x 256 KiB = 24 MiB
 * direct-memory ceiling. Slabs are reused across ranges to avoid per-packet
 * heap allocations. Overflow beyond `maxSlabs` uses GC-managed heap buffers so
 * 128 workers cannot exhaust native memory on a phone. Mirrors the Rust
 * `BoundedSlabPool` (64 x 256 KiB = 16 MiB); see ARCHITECTURE.md.
 */
class SlabBufferPool(
    val slabSizeBytes: Int = 256 * 1024,
    val maxSlabs: Int = 96, // 96 * 256 KiB = 24 MiB direct ceiling
) {
    private val freeList = ConcurrentLinkedQueue<ByteBuffer>()
    private val totalAllocatedSlabs = AtomicInteger(0)
    private val activeInUseSlabs = AtomicInteger(0)

    fun acquire(): ByteBuffer {
        activeInUseSlabs.incrementAndGet()
        val pooled = freeList.poll()
        if (pooled != null) {
            pooled.clear()
            return pooled
        }
        val currentTotal = totalAllocatedSlabs.get()
        if (currentTotal < maxSlabs && totalAllocatedSlabs.compareAndSet(currentTotal, currentTotal + 1)) {
            return ByteBuffer.allocateDirect(slabSizeBytes)
        }
        // Never grow the direct/off-heap budget past maxSlabs. Overflow uses
        // heap memory so 128 workers cannot exhaust native memory on a phone.
        return ByteBuffer.allocate(slabSizeBytes)
    }

    fun release(buffer: ByteBuffer) {
        activeInUseSlabs.updateAndGet { (it - 1).coerceAtLeast(0) }
        buffer.clear()
        if (buffer.isDirect) {
            if (freeList.size < maxSlabs) {
                freeList.offer(buffer)
            } else {
                // C10 audit: a direct slab dropped because the free list is full must
                // give its slot back to the accounting. Otherwise totalAllocatedSlabs
                // stays pinned at maxSlabs and every later acquire() skips direct
                // allocation and falls back to heap ByteBuffers forever, adding GC
                // churn that depresses the stable average speed.
                totalAllocatedSlabs.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun trimIdle() {
        while (freeList.size > 4) {
            if (freeList.poll() != null) {
                totalAllocatedSlabs.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun allocatedBytes(): Long = totalAllocatedSlabs.get().toLong() * slabSizeBytes
    fun inUseBytes(): Long = activeInUseSlabs.get().coerceAtLeast(0).toLong() * slabSizeBytes
    fun inUseCount(): Int = activeInUseSlabs.get().coerceAtLeast(0)
    fun totalCount(): Int = totalAllocatedSlabs.get()
}
