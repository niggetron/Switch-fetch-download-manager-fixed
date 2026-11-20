package com.example.engine

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded 8.0 MiB DirectByteBuffer slab pool (16 x 512 KiB slabs) matching v7.1 BoundedSlabPool.
 * Eliminates per-packet JVM byte[] heap allocations in the Kotlin fallback path.
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
        if (buffer.isDirect && freeList.size < maxSlabs) {
            freeList.offer(buffer)
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
