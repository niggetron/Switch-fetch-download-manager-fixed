package com.example.engine

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-budget DirectByteBuffer slab pool (512 KB slabs, capped at ~6 MB active budget)
 * to avoid per-packet JVM byte[] heap allocations during high-throughput streaming.
 * If all tracked slabs are checked out, a short-lived heap buffer is used instead so
 * allocation accounting never drifts from the actual direct-buffer population.
 */
class SlabBufferPool(
    val slabSizeBytes: Int = 512 * 1024,
    val maxSlabs: Int = 12, // 12 * 512 KB = 6.0 MB
) {
    private val freeList = ConcurrentLinkedQueue<ByteBuffer>()
    private val totalAllocatedSlabs = AtomicInteger(0)
    private val activeInUseSlabs = AtomicInteger(0)

    fun acquire(): ByteBuffer {
        activeInUseSlabs.incrementAndGet()
        freeList.poll()?.let { pooled ->
            pooled.clear()
            return pooled
        }
        val current = totalAllocatedSlabs.get()
        if (current < maxSlabs && totalAllocatedSlabs.compareAndSet(current, current + 1)) {
            return ByteBuffer.allocateDirect(slabSizeBytes)
        }
        // Overflow: do not add to the pool or the accounting would drift on trimIdle().
        return ByteBuffer.allocate(slabSizeBytes)
    }

    fun release(buffer: ByteBuffer) {
        activeInUseSlabs.decrementAndGet().coerceAtLeast(0)
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
