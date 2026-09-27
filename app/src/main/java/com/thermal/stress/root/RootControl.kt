package com.thermal.stress.root

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 高级控制（可选）：
 * 通过 su 把 CPU 调度器切到 performance，并把 policy 的 min/max 频率锁成同一档，
 * 排除调频/降频对散热测试的干扰；退出时自动还原原始值。
 * 本机无 GPU devfreq 标准节点，GPU 频率不支持锁定。
 */
object RootControl {

    enum class State { UNKNOWN, AVAILABLE, UNAVAILABLE }

    @Volatile var state: State = State.UNKNOWN
        private set
    @Volatile var detail: String = "未检测"
        private set
    @Volatile var lockedFreqKhz: Long = -1L
        private set

    // 设备上的 su 方言：AOSP userdebug 用 "su 0 sh"，Magisk/SuperSU 用 "su"
    private const val MODE_AOSP = 0
    private const val MODE_MAGISK = 1
    private var suMode = MODE_MAGISK

    private val policyDir: File by lazy {
        File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") }
            ?.sortedBy { it.name }?.firstOrNull()
            ?: File("/sys/devices/system/cpu/cpu0/cpufreq")
    }

    private var bakGovernor: String? = null
    private var bakMin: String? = null
    private var bakMax: String? = null
    /** 硬件物理上下限（cpuinfo_*），用于纠正降频期间被压低的备份值 */
    private var hwMin: String? = null
    private var hwMax: String? = null

    val isLocked: Boolean get() = lockedFreqKhz > 0

    /** 从 sysfs 实际状态同步锁频标记（节点 0444 免 root 可读）。
     *  APP 进程重启后内存状态丢失，但内核里 min==max 的锁定仍然生效，
     *  网页端每次拉状态时调用本方法即可反映真实锁频状态。 */
    fun syncFromSysfs() {
        val min = readNode("scaling_min_freq")?.toLongOrNull() ?: return
        val max = readNode("scaling_max_freq")?.toLongOrNull() ?: return
        lockedFreqKhz = if (min == max) min else -1L
    }

    private fun readNode(name: String): String? =
        File(policyDir, name).takeIf { it.canRead() }?.readText()?.trim()

    /** 检测 su 是否可用，超时 4 秒 */
    fun detect(): Boolean {
        // AOSP userdebug/eng 自带 su: "su 0 id"；Magisk 等: "su -c id"
        val probes = listOf(
            MODE_AOSP to listOf("su", "0", "id"),
            MODE_MAGISK to listOf("su", "-c", "id")
        )
        for ((mode, argv) in probes) {
            try {
                val p = ProcessBuilder(argv).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val finished = p.waitFor(4, TimeUnit.SECONDS)
                if (!finished) { p.destroyForcibly(); continue }
                if (p.exitValue() == 0 && out.contains("uid=0")) {
                    suMode = mode
                    state = State.AVAILABLE
                    detail = "已授权 (${out.trim().take(40)})"
                    return true
                }
            } catch (_: Exception) { }
        }
        state = State.UNAVAILABLE
        detail = "su 不可用或被拒绝"
        return false
    }

    private fun suExec(cmds: List<String>): Boolean {
        if (state != State.AVAILABLE) return false
        return try {
            val argv = if (suMode == MODE_AOSP) arrayOf("su", "0", "sh") else arrayOf("su")
            val p = Runtime.getRuntime().exec(argv)
            val w = p.outputStream.bufferedWriter()
            cmds.forEach { w.write(it); w.newLine() }
            w.write("exit"); w.newLine()
            w.flush(); w.close()
            // 读掉错误流，避免管道阻塞
            val err = p.errorStream.bufferedReader().readText()
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); false }
            else p.exitValue() == 0
        } catch (e: Exception) {
            detail = "su 执行失败: ${e.message}"
            false
        }
    }

    /** 锁定到指定频率(kHz)。写入后回读校验，没生效直接报失败 */
    fun lock(freqKhz: Long): Boolean {
        if (!detect()) return false
        val dir = policyDir.absolutePath
        if (bakGovernor == null) {
            bakGovernor = readNode("scaling_governor")
            hwMin = readNode("cpuinfo_min_freq")
            hwMax = readNode("cpuinfo_max_freq")
            // 仅当未处于内核降频（scaling_max==cpuinfo_max）时才把当前值作为用户默认值备份
            val curMax = readNode("scaling_max_freq")
            bakMax = if (hwMax != null && curMax == hwMax) curMax else hwMax
            bakMin = hwMin
        }
        // 先放开 max 到硬件上限（清掉可能残留的降频/锁频值），切 performance，再 min==max 锁死
        val ceiling = hwMax ?: freqKhz.toString()
        val f = freqKhz.toString()
        val ok = suExec(
            listOf(
                "chmod 664 $dir/scaling_min_freq $dir/scaling_max_freq $dir/scaling_governor 2>/dev/null",
                "echo $ceiling > $dir/scaling_max_freq",
                "echo ${hwMin ?: f} > $dir/scaling_min_freq",
                "echo performance > $dir/scaling_governor",
                "echo $f > $dir/scaling_min_freq",
                "echo $f > $dir/scaling_max_freq"
            )
        )
        // 回读验证：min==max==目标值 才算锁定成功（shell exit code 反映不出单条 echo 失败）
        val verified = ok &&
            readNode("scaling_min_freq") == f &&
            readNode("scaling_max_freq") == f
        lockedFreqKhz = if (verified) freqKhz else -1L
        if (!verified) detail = if (ok) "锁频写入未生效" else detail
        return verified
    }

    /** 还原调度器与频率范围（恢复到硬件全范围，避免降频期间备份值残留导致永久限频） */
    fun unlock(): Boolean {
        if (state != State.AVAILABLE && !detect()) return false
        val dir = policyDir.absolutePath
        // 每次都取一次硬件上下限，即使从未 lock 过也能纠正外部/残留的限频
        val lo = readNode("cpuinfo_min_freq") ?: hwMin
        val hi = readNode("cpuinfo_max_freq") ?: hwMax
        val gov = bakGovernor ?: readNode("scaling_governor")
        val cmds = ArrayList<String>()
        // 先抬 max 再放 min，避免中间态非法
        hi?.let { cmds.add("echo $it > $dir/scaling_max_freq") }
        lo?.let { cmds.add("echo $it > $dir/scaling_min_freq") }
        gov?.let { cmds.add("echo $it > $dir/scaling_governor") }
        val ok = if (cmds.isEmpty()) true else suExec(cmds)
        // 回读验证：min/max 已恢复到硬件全范围才算成功
        val verified = ok &&
            (lo == null || readNode("scaling_min_freq") == lo) &&
            (hi == null || readNode("scaling_max_freq") == hi)
        if (verified) lockedFreqKhz = -1L
        return verified
    }

    fun availableFrequencies(): List<Long> =
        readNode("scaling_available_frequencies")?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }?.sorted() ?: emptyList()
}
