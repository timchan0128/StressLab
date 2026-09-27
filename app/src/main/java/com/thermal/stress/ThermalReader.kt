package com.thermal.stress

import java.io.File

/** 单个温度热区的一次采样 */
data class ZoneTemp(
    val key: String,       // 稳定标识，如 cpu / gpu / ddr / battery / other_3
    val type: String,      // 节点原始 type
    val name: String,      // 展示名
    val tempC: Double      // 摄氏度
)

/** 整机硬件一次采样快照 */
data class Snapshot(
    val uptimeMs: Long,
    val zones: List<ZoneTemp>,
    val cpuFreqKhz: Long,
    val cpuMaxFreqKhz: Long,
    val availableFreqs: List<Long>,
    val cooling: List<Pair<String, Int>>, // 散热/降频设备名称 -> 当前档位
    val memTotalMb: Long,
    val memAvailableMb: Long,
    val ex: SystemStats.SnapshotEx = SystemStats.SnapshotEx()
) {
    fun zone(key: String): Double? = zones.firstOrNull { it.key == key }?.tempC
    val maxTemp: Double get() = zones.maxOfOrNull { it.tempC } ?: Double.NaN
}

/**
 * 直接读取 sysfs / proc 节点，全部为世界可读节点，不需要 root。
 *  - 热区: /sys/class/thermal/thermal_zone*  (type + temp，温度为千分之一摄氏度)
 *  - CPU频率: /sys/devices/system/cpu/cpufreq/policy*
 *  - 散热档位: /sys/class/thermal/cooling_device*
 *  - 内存: /proc/meminfo
 */
object ThermalReader {

    private const val THERMAL_ROOT = "/sys/class/thermal"
    private const val CPUFREQ_ROOT = "/sys/devices/system/cpu/cpufreq"

    /** 热区 key -> 展示名，按重要性排序 */
    private fun nameFor(type: String, index: Int): Pair<String, String> {
        val t = type.lowercase()
        return when {
            t.contains("cpu") -> "cpu" to "CPU"
            t.contains("gpu") -> "gpu" to "GPU"
            t.contains("ddr") || t.contains("mem") -> "ddr" to "DDR"
            t.contains("bat") -> "battery" to "电池"
            t.contains("pa") -> "pa_$index" to "功放$index"
            t.contains("nand") || t.contains("flash") -> "nand_$index" to "闪存$index"
            else -> "other_$index" to type.ifBlank { "热区$index" }
        }
    }

    private fun parseTemp(raw: String): Double? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        // 大多数内核用千分之一摄氏度；少数直接给摄氏度
        return if (kotlin.math.abs(v) > 1000.0) v / 1000.0 else v
    }

    private fun readZones(): List<ZoneTemp> {
        val dir = File(THERMAL_ROOT)
        val files = dir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?: return emptyList()
        val zones = ArrayList<ZoneTemp>()
        for ((idx, f) in files.sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }.withIndex()) {
            val type = File(f, "type").let { if (it.canRead()) it.readText().trim() else "" }
            val temp = File(f, "temp").takeIf { it.canRead() }?.let { parseTemp(it.readText()) }
                ?: continue
            val (key, name) = nameFor(type, idx)
            zones.add(ZoneTemp(key, type, name, temp))
        }
        // 按固定优先级排序: cpu, gpu, ddr, battery, 其它
        val priority = listOf("cpu", "gpu", "ddr", "battery")
        return zones.sortedBy { z ->
            val base = z.key.substringBefore("_")
            val p = priority.indexOf(base)
            if (p >= 0) p else 100 + z.key.hashCode()
        }
    }

    private fun readLong(f: File): Long? =
        f.takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()

    private data class CpuPolicy(
        val cur: Long, val max: Long, val available: List<Long>
    )

    private fun readCpuPolicy(): CpuPolicy? {
        val root = File(CPUFREQ_ROOT)
        val policies = root.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
            ?: return null
        val p = policies.sortedBy { it.name }.firstOrNull() ?: return null
        val cur = readLong(File(p, "scaling_cur_freq")) ?: return null
        val max = readLong(File(p, "scaling_max_freq"))
            ?: readLong(File(p, "cpuinfo_max_freq")) ?: cur
        val avail = File(p, "scaling_available_frequencies").takeIf { it.canRead() }
            ?.readText()?.trim()?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?: (408000L..1416000L step 100000L).toList()
        return CpuPolicy(cur, max, avail.sorted())
    }

    private fun readCooling(): List<Pair<String, Int>> {
        val dir = File(THERMAL_ROOT)
        val files = dir.listFiles { f -> f.isDirectory && f.name.startsWith("cooling_device") }
            ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { f ->
            val type = File(f, "type").takeIf { it.canRead() }?.readText()?.trim() ?: return@mapNotNull null
            val state = File(f, "cur_state").takeIf { it.canRead() }?.readText()
                ?.trim()?.toIntOrNull() ?: 0
            type to state
        }
    }

    private fun readMem(): Pair<Long, Long> {
        var total = 0L
        var avail = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> total = line.filter(Char::isDigit).toLongOrNull() ?: 0L
                    line.startsWith("MemAvailable:") || line.startsWith("MemFree:") ->
                        if (avail == 0L) avail = line.filter(Char::isDigit).toLongOrNull() ?: 0L
                }
            }
        } catch (_: Exception) { }
        return total / 1024 to avail / 1024
    }

    fun read(): Snapshot {
        val policy = readCpuPolicy()
        val (memTotal, memAvail) = readMem()
        return Snapshot(
            uptimeMs = System.currentTimeMillis(),
            zones = readZones(),
            cpuFreqKhz = policy?.cur ?: -1L,
            cpuMaxFreqKhz = policy?.max ?: -1L,
            availableFreqs = policy?.available ?: emptyList(),
            cooling = readCooling(),
            memTotalMb = memTotal,
            memAvailableMb = memAvail,
            ex = SystemStats.read()
        )
    }
}
