package com.example.engine

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-budget DirectByteBuffer slab pool (256 KB slabs, capped at 6.0 MB active budget)
 * to avoid per-packet JVM byte[] heap allocations during high-throughput streaming.
 */
class SlabBufferPool(
    val slabSizeBytes: Int = 256 * 1024,
    val maxSlabs: Int = 24, // 24 * 256 KB = 6.0 MB
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
        // Fallback direct buffer if all 24 slabs are simultaneously checked out
        return ByteBuffer.allocateDirect(slabSizeBytes)
    }

    fun release(buffer: ByteBuffer) {
        activeInUseSlabs.decrementAndGet().coerceAtLeast(0)
        buffer.clear()
        if (freeList.size < maxSlabs) {
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
