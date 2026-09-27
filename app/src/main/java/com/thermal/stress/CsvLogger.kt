package com.thermal.stress

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测试数据 CSV 记录器。
 * 文件保存在应用专属外部目录: Android/data/com.thermal.stress/files/logs
 * 不需要存储权限，可通过 adb pull 或应用内分享导出。
 */
class CsvLogger(dir: File) {

    val file: File
    private val writer: BufferedWriter
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var zoneKeys: List<String>? = null

    init {
        dir.mkdirs()
        val name = "thermal_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        file = File(dir, name)
        writer = BufferedWriter(FileWriter(file))
    }

    @Synchronized
    fun log(snap: Snapshot, e: Engine) {
        if (zoneKeys == null) {
            zoneKeys = snap.zones.map { it.key }
            val header = StringBuilder("time,elapsed_s,stage,")
            zoneKeys!!.forEach { header.append(it).append("_c,") }
            header.append("cpu_freq_mhz,cooling_state,cpu_load_pct,cpu_threads,")
                .append("gpu_on,gpu_load_pct,mem_on,mem_mb,io_on,io_threads,")
                .append("mem_avail_mb,cpu_util_pct,gpu_util_pct,gpu_mem_mb,")
                .append("batt_present,batt_capacity_pct,batt_voltage_mv,batt_status,usb_online,ambient_c,event")
            writer.write(header.toString()); writer.newLine()
        }
        val elapsed = (snap.uptimeMs - e.sessionStartMs) / 1000.0
        val sb = StringBuilder()
        sb.append(tsFmt.format(Date(snap.uptimeMs))).append(',')
        sb.append(String.format(Locale.US, "%.0f", elapsed)).append(',')
        val stage = when {
            e.stepRunning -> "step_${e.stepPct}pct"
            e.anyLoadActive() -> "manual"
            else -> "idle"
        }
        sb.append(stage).append(',')
        zoneKeys!!.forEach { k ->
            sb.append(String.format(Locale.US, "%.1f", snap.zone(k) ?: Double.NaN)).append(',')
        }
        sb.append(snap.cpuFreqKhz / 1000).append(',')
        sb.append(snap.cooling.firstOrNull()?.second ?: 0).append(',')
        sb.append(e.cfg.cpuLoad).append(',').append(e.cfg.cpuThreads).append(',')
        sb.append(if (e.cfg.gpuOn) 1 else 0).append(',').append(e.cfg.gpuLoad).append(',')
        sb.append(if (e.cfg.memOn) 1 else 0).append(',').append(e.cfg.memMb).append(',')
        sb.append(if (e.cfg.ioOn) 1 else 0).append(',').append(e.cfg.ioThreads).append(',')
        sb.append(snap.memAvailableMb).append(',')
        // 实时占用率与供电信息
        sb.append(if (snap.ex.cpuUtilPct >= 0) snap.ex.cpuUtilPct else "").append(',')
        sb.append(if (snap.ex.gpuUtilPct >= 0) snap.ex.gpuUtilPct else "").append(',')
        sb.append(if (snap.ex.gpuMemMb >= 0) snap.ex.gpuMemMb else "").append(',')
        sb.append(if (snap.ex.battPresent) 1 else 0).append(',')
        sb.append(if (snap.ex.battCapacity >= 0) snap.ex.battCapacity else "").append(',')
        sb.append(if (snap.ex.battVoltageMv >= 0) snap.ex.battVoltageMv else "").append(',')
        sb.append(snap.ex.battStatus).append(',')
        sb.append(if (snap.ex.usbOnline) 1 else 0).append(',')
        sb.append(if (Engine.ambientC.isNaN()) "" else (Math.round(Engine.ambientC * 10) / 10.0).toString()).append(',')
        sb.append(e.lastEvent)
        writer.write(sb.toString()); writer.newLine(); writer.flush()
        e.lastEvent = ""
    }

    @Synchronized
    fun close() { try { writer.flush(); writer.close() } catch (_: Exception) {} }
}
