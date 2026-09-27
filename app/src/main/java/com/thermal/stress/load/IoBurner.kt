package com.thermal.stress.load

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * 存储(eMMC/闪存) I/O 烧负载。
 * 每线程在应用专属目录维护一个 128MB 测试文件，循环：随机数据顺序写入(fd.sync) -> 顺序读校验。
 * rwd 模式 + sync 让每次写都落盘，持续放大闪存控制器发热。
 */
class IoBurner(private val appContext: Context) {

    @Volatile var activeThreads: Int = 2
    @Volatile private var running = false
    private val workers = ArrayList<Thread>()

    @Volatile var bytesWritten: Long = 0
        private set
    @Volatile var bytesRead: Long = 0
        private set

    private fun ioDir(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "io_load").apply { mkdirs() }

    fun start() {
        if (running) return
        running = true
        bytesWritten = 0; bytesRead = 0
        for (i in 0 until MAX_THREADS) {
            val idx = i
            val t = Thread({ loop(idx) }, "io-burn-$idx")
            t.isDaemon = true
            t.start()
            workers.add(t)
        }
    }

    fun stop() {
        running = false
        // Linux 允许 unlink 仍被占用的文件，先删再等待线程退出，避免 FUSE 时序问题残留
        cleanupFiles()
        workers.forEach { runCatching { it.join(3000) } }
        workers.clear()
        cleanupFiles() // 再兜底一次
    }

    private fun cleanupFiles() {
        runCatching {
            val dir = ioDir()
            repeat(3) {
                val files = dir.listFiles() ?: return@runCatching
                if (files.isEmpty()) return@runCatching
                files.forEach { runCatching { if (!it.delete()) Thread.sleep(100) } }
                Thread.sleep(150)
            }
        }
    }

    private fun loop(idx: Int) {
        val buf = ByteArray(BLOCK)
        var seed = (idx + 7L) * 2654435761L
        val file = File(ioDir(), "burn_$idx.dat")
        RandomAccessFile(file, "rwd").use { raf ->
            while (running) {
                if (idx >= activeThreads) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return }
                    continue
                }
                // ---- 写阶段 ----
                raf.seek(0)
                var written = 0L
                while (written < FILE_SIZE && running) {
                    seed = seed xor (seed ushr 7); seed *= -7046029254386353131L
                    for (k in buf.indices) buf[k] = ((seed ushr (k % 8 * 8)) and 0xFF).toByte()
                    val len = minOf(BLOCK, (FILE_SIZE - written).toInt())
                    raf.write(buf, 0, len)
                    written += len
                }
                raf.fd.sync()
                bytesWritten += written
                if (!running) break
                // ---- 读阶段 ----
                raf.seek(0)
                var read = 0L
                while (read < FILE_SIZE && running) {
                    val len = minOf(BLOCK, (FILE_SIZE - read).toInt())
                    val n = raf.read(buf, 0, len)
                    if (n <= 0) break
                    read += n
                    seed += buf[0].toLong() // 触碰数据，避免读被当作无效
                }
                bytesRead += read
            }
        }
    }

    companion object {
        const val MAX_THREADS = 4
        private const val BLOCK = 1024 * 1024      // 1MB
        private const val FILE_SIZE = 128L * 1024 * 1024
    }
}
