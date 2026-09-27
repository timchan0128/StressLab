package com.thermal.stress.load

import android.content.Context

/** 全部负载模块的实时配置（可在运行中动态调节） */
data class LoadCfg(
    var cpuOn: Boolean = false,
    var cpuThreads: Int = 4,
    var cpuLoad: Int = 100,
    var gpuOn: Boolean = false,
    var gpuLoad: Int = 60,
    var memOn: Boolean = false,
    var memMb: Int = 256,
    var ioOn: Boolean = false,
    var ioThreads: Int = 2
) {
    fun copyFrom(o: LoadCfg) {
        cpuOn = o.cpuOn; cpuThreads = o.cpuThreads; cpuLoad = o.cpuLoad
        gpuOn = o.gpuOn; gpuLoad = o.gpuLoad
        memOn = o.memOn; memMb = o.memMb
        ioOn = o.ioOn; ioThreads = o.ioThreads
    }
    fun anyOn(): Boolean = cpuOn || gpuOn || memOn || ioOn
}

/**
 * 统一管理 CPU / GPU / 内存 / IO 四类负载。
 * GPU 负载依赖 Activity 上的 GLSurfaceView（见 GpuView），
 * 这里只保存开关与强度，GL 视图的挂载由 UI 层观察配置完成。
 */
class LoadManager(appContext: Context) {

    private val app = appContext.applicationContext
    val cfg = LoadCfg()

    val cpu = CpuBurner()
    val mem = MemoryBurner()
    val io = IoBurner(app)

    /** 应用一份（可能被部分修改过的）配置，按需启停各模块，运行中调节不打断负载 */
    @Synchronized
    fun apply(newCfg: LoadCfg) {
        // ---- CPU ----
        if (newCfg.cpuOn) {
            cpu.activeThreads = newCfg.cpuThreads.coerceIn(1, CpuBurner.MAX_THREADS)
            cpu.loadPercent = newCfg.cpuLoad
            if (!cpuRunning) { cpu.start(); cpuRunning = true }
        } else if (cpuRunning) {
            cpu.stop(); cpuRunning = false
        }

        // ---- 内存 ----
        if (newCfg.memOn) {
            mem.sizeMb = newCfg.memMb
            if (!memRunning) { mem.start(); memRunning = true }
        } else if (memRunning) {
            mem.stop(); memRunning = false
        }

        // ---- IO ----
        if (newCfg.ioOn) {
            io.activeThreads = newCfg.ioThreads.coerceIn(1, IoBurner.MAX_THREADS)
            if (!ioRunning) { io.start(); ioRunning = true }
        } else if (ioRunning) {
            io.stop(); ioRunning = false
        }

        cfg.copyFrom(newCfg)
    }

    @Synchronized
    fun stopAll() {
        if (cpuRunning) { cpu.stop(); cpuRunning = false }
        if (memRunning) { mem.stop(); memRunning = false }
        if (ioRunning) { io.stop(); ioRunning = false }
        cfg.cpuOn = false; cfg.gpuOn = false; cfg.memOn = false; cfg.ioOn = false
    }

    @Synchronized
    fun shutdown() {
        stopAll()
    }

    var cpuRunning = false
        private set
    var memRunning = false
        private set
    var ioRunning = false
        private set
}
