package com.thermal.stress

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.thermal.stress.load.LoadCfg
import com.thermal.stress.load.LoadManager
import java.io.File

/** 定长时间-温度序列（环形缓冲），供实时曲线绘制 */
class Series(private val cap: Int = 1800) {
    private val timeMs = LongArray(cap)
    private val temp = FloatArray(cap)
    private var size = 0
    private var head = 0

    @Synchronized
    fun add(t: Long, v: Float) {
        if (size < cap) { timeMs[size] = t; temp[size] = v; size++ }
        else { timeMs[head] = t; temp[head] = v; head = (head + 1) % cap }
    }

    /** 按时间先后返回 (timeMs, temp) 拷贝 */
    @Synchronized
    fun copy(): Pair<LongArray, FloatArray> {
        val ts = LongArray(size); val vs = FloatArray(size)
        if (size < cap) {
            System.arraycopy(timeMs, 0, ts, 0, size)
            System.arraycopy(temp, 0, vs, 0, size)
        } else {
            val first = head
            System.arraycopy(timeMs, first, ts, 0, cap - first)
            System.arraycopy(timeMs, 0, ts, cap - first, first)
            System.arraycopy(temp, first, vs, 0, cap - first)
            System.arraycopy(temp, 0, vs, cap - first, first)
        }
        return ts to vs
    }
}

/** 阶梯测试配置 */
data class StepCfg(
    var startPct: Int = 25,
    var stepPct: Int = 25,
    var endPct: Int = 100,
    var secondsPerStage: Int = 600,
    var cpuThreads: Int = 4
)

object Engine {

    private lateinit var app: Application
    lateinit var loadManager: LoadManager
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // ---------- 实时状态 ----------
    @Volatile var snapshot: Snapshot? = null
        private set
    val series = LinkedHashMap<String, Series>()
    val cfg: LoadCfg get() = loadManager.cfg

    // ---------- 超温保护 / 温度提醒 ----------
    // protectMode: 0=关闭(不提醒不停止) 1=仅提醒(声光/网页告警，不停负载)
    //              2=提醒并自动停止(超阈值后自动停止全部负载)
    @Volatile var protectMode = 2
    @Volatile var protectThresholdC = 85.0

    /** 活动告警文案；"" 表示当前无告警。终端与 PC 均以此为唯一数据源 */
    @Volatile var protectionTrip: String = ""
        private set
    /** 本次告警是否已自动停止负载（决定终端/PC 提示文案） */
    @Volatile var protectStoppedLoads = false
        private set
    /**
     * 告警事件单调序号：每次 protectionTrip 由空变为非空（一次真正新的越限事件）时 +1；
     * trip 被 PC/插件确认清空、冷却复位都不复用旧序号。终端据此保证“同一事件静音后
     * 不再弹出”，不会因 trip 文本中途短暂清空而把同一次高温误判成新事件再次声光报警。
     */
    @Volatile var protectTripSeq: Long = 0L
        private set

    // 事件锁存：避免每秒重复触发；回落到阈值-2℃后重新武装
    // 可能被监测线程与 HTTP 线程同时访问，统一用 protectLock 保护
    private val protectLock = Any()
    private var alertLatched = false
    private var alertDismissed = false
    private var stopLatched = false

    /** 兼容旧读取：非关闭模式即“保护已启用” */
    val protectEnabled: Boolean get() = protectMode != 0

    /** 切换保护模式（0/1/2）；切到关闭时立即清除当前告警 */
    fun applyProtectMode(mode: Int) {
        protectMode = mode.coerceIn(0, 2)
        if (protectMode == 0) {
            synchronized(protectLock) { resetAlertLocked() }
        }
        notifyChanged()
    }

    /** 用户手动“知道了”：仅隐藏本次告警，仍高温时不再重复弹，直到回落重新武装 */
    fun clearProtectionTrip() {
        synchronized(protectLock) {
            alertDismissed = true
            protectionTrip = ""
        }
        notifyChanged()
    }

    /** 完全复位告警锁存（切到关闭 / 温度回落后调用），调用方需持有 protectLock */
    private fun resetAlertLocked() {
        alertLatched = false
        alertDismissed = false
        stopLatched = false
        protectStoppedLoads = false
        protectionTrip = ""
    }

    /** 置位活动告警；仅当 trip 由空转非空（一次新事件）时递增 protectTripSeq。
     *  调用方需持有 protectLock */
    private fun fireTripLocked(text: String, stopped: Boolean) {
        val isNewEvent = protectionTrip.isEmpty()
        protectionTrip = text
        protectStoppedLoads = stopped
        if (isNewEvent) protectTripSeq++
    }

    /**
     * 负载从“全部停止”变为“有负载启动”（手动/插件/阶梯新一轮烧机）时重新武装保护：
     * 清除已确认/已停止锁存，由下一监测周期重新判定——此刻仍超阈值会立即再次触发并
     * 停止负载。堵上“超温后先点‘知道了’/经插件重新下发，即可在高温下绕过自动停止”
     * 的漏洞。本函数不产生告警、不递增序号；序号留给下一周期真正置位 trip 时使用。
     */
    private fun rearmProtectionForNewRun() {
        if (protectMode == 0) return
        synchronized(protectLock) {
            alertLatched = false
            alertDismissed = false
            stopLatched = false
            protectStoppedLoads = false
            protectionTrip = ""
        }
    }

    /**
     * 每监测周期（1s）调用：按 protectMode 判定温度提醒 / 自动停止。
     * 仅在监测线程触发状态变更，stopAllLoads 在锁外执行。
     */
    private fun evaluateProtection(snap: Snapshot) {
        val mode = protectMode
        val max = snap.maxTemp
        if (mode == 0 || max.isNaN()) return
        val threshold = protectThresholdC
        val shouldStop = synchronized(protectLock) {
            val cooled = max < threshold - 2.0
            if (cooled) {
                // 回落到阈值-2℃ 以下：重新武装
                resetAlertLocked()
                return@synchronized false
            }
            // 模式2：只要仍高温且有负载在跑，且本次事件尚未停过 → 停止
            val stopNow = mode == 2 && !stopLatched &&
                max >= threshold && anyLoadActive()
            if (stopNow) {
                stopLatched = true
                alertLatched = true
                alertDismissed = false
                fireTripLocked(
                    String.format("超温保护触发 @%.1f℃（已自动停止负载）", max),
                    stopped = true
                )
                return@synchronized true
            }
            // 模式1（或模式2下当前无负载）：仅提醒一次
            if (!alertLatched && !alertDismissed && max >= threshold) {
                alertLatched = true
                val text = if (mode == 1)
                    String.format("温度提醒 @%.1f℃（仅提醒，负载继续）", max)
                else
                    String.format("超温保护触发 @%.1f℃", max)
                fireTripLocked(text, stopped = false)
            }
            false
        }
        if (shouldStop) stopAllLoads("OVER_TEMP_PROTECTION", endCsvSession = false)
    }

    /** 环境/室温（℃），设备无环境传感器，由 PC 控制台手动填写；NaN=未设置 */
    @Volatile var ambientC: Double = Double.NaN

    // 各热区历史最高温（由监测循环维护，供终端大屏与网页共用）
    val maxTemps = java.util.concurrent.ConcurrentHashMap<String, Double>()

    // ---------- 阶梯测试 ----------
    val stepCfg = StepCfg()
    @Volatile var stepRunning = false
        private set
    @Volatile var stepPct = 0
        private set
    @Volatile var stageRemainingSec = 0
        private set

    // ---------- 记录会话 ----------
    @Volatile var sessionStartMs = 0L
        private set
    private var csv: CsvLogger? = null
    @Volatile var currentCsvPath: String? = null
        private set
    val savedCsvPaths = ArrayList<String>()
    @Volatile var lastEvent = ""

    @Volatile private var monitorRunning = false
    private var monitorThread: Thread? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    // ================= 初始化 =================
    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        loadManager = LoadManager(app)
        val pm = app.getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "thermal:stress")
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { runCatching { it() } } }
    }

    // ================= 配置修改 =================
    /** 直接下发一份完整配置（UI 与 ADB 自动化指令统一入口） */
    fun applyConfig(c: LoadCfg) {
        val wasActive = loadManager.cfg.anyOn()
        loadManager.apply(c)
        updateWakeLock()
        if (!wasActive && c.anyOn()) {
            startSession()
            // 新一轮烧机：重新武装超温保护（高温下重启负载仍会被立即保护）
            rearmProtectionForNewRun()
        }
        if (wasActive && !c.anyOn() && !stepRunning) endSession()
        notifyChanged()
    }

    /** 在当前配置上做任意修改并立即下发（UI 滑杆/开关统一走这里） */
    fun mutate(action: LoadCfg.() -> Unit) {
        val c = LoadCfg()
        c.copyFrom(loadManager.cfg)
        c.action()
        applyConfig(c)
    }

    fun anyLoadActive(): Boolean = loadManager.cfg.anyOn()

    fun stopAllLoads(reason: String = "", endCsvSession: Boolean = true) {
        if (reason.isNotEmpty()) lastEvent = reason
        if (stepRunning) finishStep(aborted = true)
        loadManager.stopAll()          // CPU/内存/IO 立即停
        // GPU 视图由 UI 观察 cfg.gpuOn=false 后卸载
        updateWakeLock()
        if (endCsvSession) endSession()
        notifyChanged()
    }

    // ================= 阶梯测试 =================
    fun startStep(): String? {
        val s = stepCfg
        if (s.startPct !in 0..100 || s.endPct !in 0..100 || s.startPct > s.endPct)
            return "负载范围无效"
        if (s.stepPct <= 0) return "步进必须大于 0"
        if (s.secondsPerStage < 5) return "每档时长至少 5 秒"
        stepRunning = true
        stepPct = s.startPct
        stageRemainingSec = s.secondsPerStage
        lastEvent = "STEP_TEST_START"
        mutate {
            cpuOn = true
            cpuThreads = s.cpuThreads.coerceIn(1, 4)
            cpuLoad = s.startPct
        }
        return null
    }

    private fun tickStep() {
        if (!stepRunning) return
        if (stageRemainingSec > 0) stageRemainingSec--
        if (stageRemainingSec <= 0) {
            val next = stepPct + stepCfg.stepPct
            if (next > stepCfg.endPct) {
                finishStep(aborted = false)
            } else {
                stepPct = next
                stageRemainingSec = stepCfg.secondsPerStage
                lastEvent = "STAGE_${next}pct"
                mutate { cpuLoad = next }
            }
        }
    }

    private fun finishStep(aborted: Boolean) {
        if (!stepRunning) return
        stepRunning = false
        lastEvent = if (aborted) "STEP_TEST_ABORTED" else "STEP_TEST_DONE"
        // 阶梯自然结束：自动停止全部负载
        if (!aborted) {
            loadManager.stopAll()
            updateWakeLock()
            endSession()
        }
        notifyChanged()
    }

    // ================= CSV 会话 =================
    private fun startSession() {
        if (csv != null) return
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        val logger = CsvLogger(dir)
        csv = logger
        currentCsvPath = logger.file.absolutePath
        sessionStartMs = System.currentTimeMillis()
        lastEvent = "SESSION_START"
    }

    private fun endSession() {
        val l = csv ?: return
        l.close()
        synchronized(savedCsvPaths) {
            if (currentCsvPath !in savedCsvPaths) currentCsvPath?.let { savedCsvPaths.add(0, it) }
        }
        csv = null
        currentCsvPath = null
    }

    fun listCsvFiles(): List<File> {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        return dir.listFiles { f -> f.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 按文件名安全获取 CSV（防止路径穿越） */
    fun csvFile(name: String): File? {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs")
        val f = File(dir, name)
        return try {
            if (f.exists() && f.canRead() &&
                f.canonicalPath.startsWith(dir.canonicalPath + File.separator) &&
                f.extension.equals("csv", ignoreCase = true)) f else null
        } catch (e: Exception) { null }
    }

    // ================= 监测循环 =================
    fun startMonitoring() {
        if (monitorRunning) return
        monitorRunning = true
        monitorThread = Thread({
            while (monitorRunning) {
                try {
                    val snap = ThermalReader.read()
                    snapshot = snap
                    for (z in snap.zones) {
                        series.getOrPut(z.key) { Series() }.add(snap.uptimeMs, z.tempC.toFloat())
                        maxTemps.merge(z.key, z.tempC) { a, b -> if (b > a) b else a }
                    }
                    // 先处理温度提醒/保护（事件标记写入当行 CSV），再落盘
                    evaluateProtection(snap)
                    csv?.log(snap, this)
                    if (csv != null && !anyLoadActive() && !stepRunning) endSession()
                    tickStep()
                    notifyChanged()
                } catch (_: Exception) { }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "thermal-monitor").also { it.isDaemon = true; it.start() }
    }

    fun stopMonitoring() {
        monitorRunning = false
        monitorThread?.interrupt()
        if (anyLoadActive()) stopAllLoads("MONITOR_STOP")
        updateWakeLock()
    }

    private fun updateWakeLock() {
        if (anyLoadActive()) {
            if (!wakeLock.isHeld) wakeLock.acquire(10 * 60 * 60 * 1000L)
        } else {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
