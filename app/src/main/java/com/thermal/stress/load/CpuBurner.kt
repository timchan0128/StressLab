package com.thermal.stress.load

/**
 * CPU 烧核器。
 * 常驻 4 个工作线程（设备为 4 核），通过线程数开关选择参与负载的核心数；
 * 负载强度用 50ms 时间窗内的"忙等占空比"精确控制 0~100%。
 * 忙等阶段混合整数(xorshift)与浮点(sqrt/sin)运算，结果写入 @Volatile 防止被 JIT 优化掉。
 */
class CpuBurner {

    @Volatile var activeThreads: Int = 4
    @Volatile var loadPercent: Int = 100
    @Volatile private var running = false
    private val workers = ArrayList<Thread>()

    // 防止计算被优化消除的汇
    @Volatile var sinkLong: Long = 0
        private set
    @Volatile var sinkDouble: Double = 0.0
        private set

    private val WINDOW_NS = 50_000_000L

    fun start() {
        if (running) return
        running = true
        synchronized(workers) {
            workers.clear()
            for (i in 0 until MAX_THREADS) {
                val idx = i
                val t = Thread({ loop(idx) }, "cpu-burn-$idx")
                t.isDaemon = true
                t.priority = Thread.MAX_PRIORITY
                t.start()
                workers.add(t)
            }
        }
    }

    fun stop() {
        running = false
        synchronized(workers) {
            workers.forEach { runCatching { it.join(800) } }
            workers.clear()
        }
    }

    private fun loop(idx: Int) {
        var x = (-7046029254386353131L) xor (idx * 0x1234567L + 1)
        var d = idx.toDouble() * 0.731 + 1.0
        var accL = 0L
        var accD = 0.0
        while (running) {
            val work = idx < activeThreads
            val pct = if (work) loadPercent.coerceIn(0, 100) else 0
            val busyNs = WINDOW_NS * pct / 100
            val start = System.nanoTime()
            val busyEnd = start + busyNs
            if (pct > 0) {
                while (System.nanoTime() < busyEnd) {
                    // xorshift* 整数链，制造持续 ALU 依赖
                    x = x xor (x ushr 12)
                    x = x xor (x shl 25)
                    x = x xor (x ushr 27)
                    val m = x * 0x2545F4914F6CDD1DL
                    accL += m
                    // 浮点链，占用 VFP/NEON 流水线
                    d = Math.sqrt(d * 1.0000001 + 3.0) + Math.sin(d)
                    accD += d
                    if (accL == Long.MIN_VALUE) accL = 1L
                    if (accD.isNaN()) accD = 1.0
                }
            }
            sinkLong = accL
            sinkDouble = accD
            val elapsed = System.nanoTime() - start
            val rest = (WINDOW_NS - elapsed) / 1_000_000
            if (rest > 1) {
                try { Thread.sleep(rest) } catch (_: InterruptedException) { return }
            }
        }
    }

    companion object {
        const val MAX_THREADS = 4
    }
}
