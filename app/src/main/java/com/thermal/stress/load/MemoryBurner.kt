package com.thermal.stress.load

import java.nio.ByteBuffer

/**
 * 内存(DDR) 烧负载。
 * 使用 DirectByteBuffer（堆外原生内存，绕开 App 堆限制），按 64MB 分块；
 * 两个工作线程持续对整块内存做 64 位写入 + 读回校验，制造持续 DDR 带宽压力。
 * 压测时可直接对照设备的 DDR 热区温度。
 */
class MemoryBurner {

    @Volatile var sizeMb: Int = 256
    @Volatile private var running = false
    private var buffers: List<ByteBuffer> = emptyList()
    @Volatile private var allocatedMb = 0
    private val workers = ArrayList<Thread>()

    @Volatile var checksum: Long = 0
        private set
    @Volatile private var lastOomMs = 0L

    private fun reallocate(mb: Int) {
        synchronized(this) {
            if (allocatedMb == mb && buffers.isNotEmpty()) return
            // OOM 后 3 秒内不重复尝试，避免 logcat 刷屏
            if (System.currentTimeMillis() - lastOomMs < 3000 && buffers.isNotEmpty()) return
            buffers = emptyList()   // 先释放旧块引用，避免瞬时可分配量翻倍
            allocatedMb = 0
            val target = mb.coerceAtLeast(CHUNK_MB)
            val n = target / CHUNK_MB
            val list = ArrayList<ByteBuffer>(n)
            var got = 0
            repeat(n) {
                try {
                    list.add(ByteBuffer.allocateDirect(CHUNK_MB * 1024 * 1024))
                    got += CHUNK_MB
                } catch (e: OutOfMemoryError) {
                    lastOomMs = System.currentTimeMillis()
                    return@repeat   // 内存紧张时保留已分配块继续跑，不崩溃
                }
            }
            buffers = list
            allocatedMb = got
        }
    }

    fun start() {
        if (running) return
        running = true
        for (i in 0 until 2) {
            val id = i
            val t = Thread({ loop(id) }, "mem-burn-$i")
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY
            t.start()
            workers.add(t)
        }
    }

    fun stop() {
        running = false
        workers.forEach { runCatching { it.join(1000) } }
        workers.clear()
        synchronized(this) {
            buffers = emptyList()
            allocatedMb = 0
        }
    }

    private fun loop(id: Int) {
        var seed = 0x1234567L * (id + 3)
        var cs = 0L
        while (running) {
            reallocate(sizeMb)
            val snapshot: List<ByteBuffer> = synchronized(this) { buffers }
            if (snapshot.isEmpty()) { Thread.sleep(300); continue }
            for ((bi, buf) in snapshot.withIndex()) {
                if (!running) return
                if (bi % 2 != id) continue
                val longs = buf.asLongBuffer()
                // 写：伪随机数据
                var v = seed + bi.toLong() * 0x9E3779B1L
                val cap = longs.capacity()
                longs.position(0)
                while (longs.remaining() >= 8) {
                    v = v xor (v ushr 13); v *= -668265263L
                    longs.put(v); longs.put(v + 1); longs.put(v + 2); longs.put(v + 3)
                    longs.put(v + 4); longs.put(v + 5); longs.put(v + 6); longs.put(v + 7)
                }
                // 读回：遍历求和，制造读带宽
                longs.position(0)
                var sum = 0L
                while (longs.hasRemaining()) sum += longs.get()
                cs += sum
                seed = v
            }
            checksum = cs
        }
    }

    companion object {
        const val CHUNK_MB = 64
        const val MAX_MB = 3072
    }
}
