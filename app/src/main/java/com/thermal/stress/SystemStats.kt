package com.thermal.stress

import java.io.File

/**
 * CPU/GPU 实时占用率与供电信息读取。
 *  - CPU 占用: /proc/stat 聚合行与 cpu0..N 行，按两次采样差值计算
 *  - GPU 占用: PowerVR /sys/kernel/debug/pvr/status 的 "GPU Utilisation: NN%"
 *  - GPU 显存: driver_stats 中 MemoryUsageAllocGPUMemUMA（UMA 共享内存，字节）
 *  - 电池/供电: /sys/class/power_supply/axp2202-battery|axp2202-usb
 * 本机实测以上节点对普通应用可读（userdebug 固件 debugfs 为 0444）。
 */
object SystemStats {

    data class SnapshotEx(
        val cpuUtilPct: Int = -1,          // 整机 CPU 占用率 0-100，-1=未知
        val coreUtilPct: IntArray = IntArray(0), // 各核心占用率
        val gpuUtilPct: Int = -1,          // GPU 实时占用率 0-100
        val gpuMemMb: Long = -1,           // GPU UMA 显存占用
        val battPresent: Boolean = false,
        val battCapacity: Int = -1,        // 电量百分比
        val battVoltageMv: Int = -1,       // 电池电压 mV
        val battStatus: String = "",       // Charging/Discharging/Full/Unknown
        val battHealth: String = "",
        val usbOnline: Boolean = false
    )

    private var prevBusy = -1L
    private var prevTotal = -1L
    private val prevCoreBusy = ArrayList<Long>()
    private val prevCoreTotal = ArrayList<Long>()

    private fun readTextOrNull(path: String): String? =
        try {
            val f = File(path)
            if (f.canRead()) f.readText().trim() else null
        } catch (_: Exception) { null }

    /** /proc/stat 一行 -> (busyJiffies, totalJiffies)，idle 与 iowait 算空闲 */
    private fun parseCpuLine(fields: List<String>): Pair<Long, Long> {
        val nums = fields.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return 0L to 0L
        val total = nums.sum()
        val idle = nums[3] + (nums.getOrElse(4) { 0L }) // idle + iowait
        return (total - idle) to total
    }

    @Synchronized
    private fun readCpuUtil(): Pair<Int, IntArray> {
        val lines = try {
            File("/proc/stat").readLines().filter { it.startsWith("cpu") }
        } catch (_: Exception) { return -1 to IntArray(0) }
        val agg = lines.firstOrNull { it.startsWith("cpu ") }
            ?.split(Regex("\\s+")) ?: return -1 to IntArray(0)
        val (busy, total) = parseCpuLine(agg)
        var aggPct = -1
        if (prevTotal >= 0 && total > prevTotal) {
            aggPct = ((busy - prevBusy) * 100 / (total - prevTotal)).toInt().coerceIn(0, 100)
        }
        prevBusy = busy; prevTotal = total

        val cores = lines.filter { it.startsWith("cpu") && it.removePrefix("cpu").take(1).all(Char::isDigit) }
            .sortedBy { it.removePrefix("cpu").takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val out = IntArray(cores.size)
        cores.forEachIndexed { i, line ->
            val (b, t) = parseCpuLine(line.split(Regex("\\s+")))
            if (i < prevCoreTotal.size && t > prevCoreTotal[i]) {
                out[i] = ((b - prevCoreBusy[i]) * 100 / (t - prevCoreTotal[i])).toInt().coerceIn(0, 100)
            } else out[i] = -1
            if (i >= prevCoreTotal.size) { prevCoreBusy.add(b); prevCoreTotal.add(t) }
            else { prevCoreBusy[i] = b; prevCoreTotal[i] = t }
        }
        return aggPct to out
    }

    private val gpuUtilRegex = Regex("GPU Utilisation:\\s*(\\d+)\\s*%")
    private val gpuMemRegex = Regex("MemoryUsageAllocGPUMemUMA\\s+(\\d+)")

    private fun readGpuUtil(): Int =
        readTextOrNull("/sys/kernel/debug/pvr/status")
            ?.let { gpuUtilRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: -1

    private fun readGpuMemMb(): Long =
        try {
            File("/sys/kernel/debug/pvr/driver_stats").useLines { seq ->
                seq.mapNotNull { gpuMemRegex.find(it)?.groupValues?.get(1)?.toLongOrNull() }.firstOrNull()
            }?.let { it / (1024 * 1024) } ?: -1L
        } catch (_: Exception) { -1L }

    private fun readPower(): SnapshotEx {
        val bDir = "/sys/class/power_supply/axp2202-battery"
        val uDir = "/sys/class/power_supply/axp2202-usb"
        val present = readTextOrNull("$bDir/present") == "1"
        // 电压节点单位通常为 µV
        val voltageUv = readTextOrNull("$bDir/voltage_now")?.toLongOrNull() ?: 0L
        val voltageMv = when {
            voltageUv > 1_000_000L -> (voltageUv / 1000).toInt() // µV -> mV
            voltageUv > 0 -> voltageUv.toInt()                  // 已经是 mV
            else -> -1
        }
        return SnapshotEx(
            battPresent = present,
            battCapacity = readTextOrNull("$bDir/capacity")?.toIntOrNull() ?: -1,
            battVoltageMv = voltageMv,
            battStatus = readTextOrNull("$bDir/status") ?: "",
            battHealth = readTextOrNull("$bDir/health") ?: "",
            usbOnline = readTextOrNull("$uDir/online") == "1"
        )
    }

    fun read(): SnapshotEx {
        val (cpu, cores) = readCpuUtil()
        val p = readPower()
        return p.copy(
            cpuUtilPct = cpu,
            coreUtilPct = cores,
            gpuUtilPct = readGpuUtil(),
            gpuMemMb = readGpuMemMb()
        )
    }
}
