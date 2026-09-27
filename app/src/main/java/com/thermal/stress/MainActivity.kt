package com.thermal.stress

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.thermal.stress.gl.GpuView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 终端展示页：仅显示各热区温度（大字号，适配超宽条形屏）。
 * 所有控制在 PC 网页控制台完成；GPU 负载时全屏 GL 视图藏在不透明内容层之下后台渲染。
 */
class MainActivity : Activity() {

    private val ZONE_COLORS = mapOf(
        "cpu" to Color.rgb(0xE5, 0x39, 0x35),
        "gpu" to Color.rgb(0x1E, 0x88, 0xE5),
        "ddr" to Color.rgb(0x43, 0xA0, 0x47),
        "battery" to Color.rgb(0xFB, 0x8C, 0x00)
    )
    private val OTHER_COLORS = intArrayOf(
        Color.rgb(0x8E, 0x24, 0xAA), Color.rgb(0x00, 0xAC, 0xC1),
        Color.rgb(0x6D, 0x4C, 0x41), Color.rgb(0x75, 0x75, 0x75)
    )

    private lateinit var cardRow: LinearLayout
    private lateinit var footer: TextView
    private lateinit var statsLine: TextView
    private lateinit var pcConsole: TextView
    private lateinit var gpuFrame: FrameLayout
    private var gpuView: GpuView? = null

    // 超温警告：仅 CPU 卡片内 350ms 闪烁 + 连续合成警报音，10 秒自动停止，点击卡片停止
    private val warnHandler = Handler(Looper.getMainLooper())
    private var warnView: TextView? = null
    private var warnBlinkOn = false
    private var warnDismissed = false
    private var warnWasActive = false
    private var warnStartMs = 0L
    @Volatile private var soundCancelled = false
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private val warnBlinkTask = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() - warnStartMs >= 10000L) {
                dismissWarning()
                return
            }
            warnBlinkOn = !warnBlinkOn
            warnView?.visibility = if (warnBlinkOn) View.VISIBLE else View.INVISIBLE
            warnHandler.postDelayed(this, 350)
        }
    }

    private val valueLabels = HashMap<String, TextView>()
    private val subLabels = HashMap<String, TextView>()
    private val maxLabels = HashMap<String, TextView>()
    private val freqLabels = HashMap<String, TextView>()
    private val loadToggles = HashMap<String, TextView>()
    private val refresh: () -> Unit = { refreshUi() }
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Engine.init(application)
        startMonitorService()
        setContentView(buildUi())
        Engine.addListener(refresh)
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        Engine.removeListener(refresh)
        warnHandler.removeCallbacks(warnBlinkTask)
        stopWarningSound()
        detachGpu()
    }

    override fun onResume() { super.onResume(); gpuView?.onResume() }
    override fun onPause() { super.onPause(); gpuView?.onPause() }

    private fun startMonitorService() {
        val i = Intent(this, StressService::class.java).setAction(StressService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        // 同时启动 LineOS 插件桥（LineOS 也会经 manifest 自动拉起；主动启动可在打开 APP 时立即注册）
        val pi = Intent(this, com.thermal.stress.plugin.PluginService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(pi) else startService(pi)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this)

        // GPU 渲染层：全屏 VISIBLE 位于最底层（SurfaceView 默认在窗口表面之后），
        // 被上方不透明内容层完全遮盖，但 GL 线程仍全速渲染，产生真实 GPU 负载。
        gpuFrame = FrameLayout(this)
        root.addView(gpuFrame, FrameLayout.LayoutParams(MATCH, MATCH))

        // 温度展示层
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
            setBackgroundColor(Color.rgb(0x10, 0x14, 0x18))
        }
        cardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(cardRow, LinearLayout.LayoutParams(MATCH, 0, 1f))

        footer = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(0x78, 0x90, 0x9C))
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))

        // 最底行：左侧显存/供电/室温，右侧大号高亮 PC Console 地址（动态端口）
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsLine = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(0x90, 0xA4, 0xAE))
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(statsLine, LinearLayout.LayoutParams(0, WRAP, 1f))
        pcConsole = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(0x4F, 0xB6, 0xFF))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        bottomRow.addView(pcConsole, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(bottomRow, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun zoneColor(key: String, ordinal: Int): Int =
        ZONE_COLORS[key] ?: OTHER_COLORS[ordinal % OTHER_COLORS.size]

    private fun utilColor(pct: Int) = when {
        pct < 0 -> Color.rgb(0x78, 0x90, 0x9C)
        pct >= 85 -> Color.rgb(0xE5, 0x39, 0x35)
        pct >= 50 -> Color.rgb(0xFB, 0x8C, 0x00)
        else -> Color.rgb(0x43, 0xA0, 0x47)
    }

    private fun zoneTitle(key: String, fallback: String) = when (key) {
        "cpu" -> "CPU"
        "gpu" -> "GPU"
        "ddr" -> "DDR / MEM"
        "battery" -> "BATTERY"
        else -> fallback
    }

    private fun refreshUi() {
        if (isFinishing) return
        val snap = Engine.snapshot
        val zones = snap?.zones ?: emptyList()
        val ex = snap?.ex

        // 实时负载数据（卡片副行用）
        val cpuPct = ex?.cpuUtilPct ?: -1
        val coreStr = ex?.coreUtilPct?.joinToString("/") { if (it >= 0) "$it" else "-" } ?: ""
        val gpuPct = ex?.gpuUtilPct ?: -1
        val memTotal = snap?.memTotalMb ?: -1
        val memAvail = snap?.memAvailableMb ?: -1
        val memPct = if (memTotal > 0 && memAvail >= 0) (((memTotal - memAvail) * 100 / memTotal).toInt()) else -1

        val present = zones.map { it.key }.toSet()
        // 移除消失的热区卡片
        valueLabels.keys.filterNot { it in present }.forEach { k ->
            cardRow.findViewWithTag<View>("card_$k")?.let { cardRow.removeView(it) }
            valueLabels.remove(k); subLabels.remove(k); maxLabels.remove(k); freqLabels.remove(k)
        }

        zones.forEachIndexed { idx, z ->
            val color = zoneColor(z.key, idx)
            var valueTv = valueLabels[z.key]
            if (valueTv == null) {
                val card = FrameLayout(this).apply {
                    tag = "card_${z.key}"
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.rgb(0x18, 0x1E, 0x24))
                        setStroke(2, color)
                    }
                }
                val inner = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                val nameTv = TextView(this).apply {
                    text = zoneTitle(z.key, z.name); textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(0xEC, 0xEF, 0xF1))
                    letterSpacing = 0.08f
                }
                valueTv = TextView(this).apply {
                    textSize = 56f
                    setTextColor(color)
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    gravity = Gravity.CENTER
                }
                val subTv = TextView(this).apply {
                    textSize = 20f
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    setTextColor(Color.rgb(0x90, 0xA4, 0xAE))
                }
                val freqTv = TextView(this).apply {
                    textSize = 17f
                    setTextColor(Color.rgb(0x90, 0xA4, 0xAE))
                }
                val maxTv = TextView(this).apply {
                    textSize = 17f
                    setTextColor(Color.rgb(0x90, 0xA4, 0xAE))
                }
                inner.addView(nameTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -dp(6) })
                inner.addView(valueTv)
                inner.addView(subTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
                inner.addView(freqTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
                inner.addView(maxTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
                card.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))

                // 卡片右上角/右下角：负载开关（大号胶囊按钮，CPU/GPU 无标题）
                val swKeys = when (z.key) {
                    "cpu" -> listOf("cpu")
                    "gpu" -> listOf("gpu")
                    "ddr" -> listOf("mem", "io")
                    else -> emptyList()
                }
                swKeys.forEachIndexed { swIdx, key ->
                    val btnName = when (key) {
                        "cpu", "gpu" -> "RUN"
                        "mem" -> "MEM"
                        "io" -> "STO"
                        else -> key.uppercase()
                    }
                    val btn = TextView(this).apply {
                        tag = "btn_$key"
                        text = btnName
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setStroke(2, Color.rgb(0x90, 0xA4, 0xAE))
                            setColor(Color.TRANSPARENT)
                        }
                        setOnClickListener {
                            val c = Engine.cfg
                            when (key) {
                                "cpu" -> Engine.mutate { cpuOn = !c.cpuOn }
                                "gpu" -> Engine.mutate { gpuOn = !c.gpuOn }
                                "mem" -> Engine.mutate { memOn = !c.memOn }
                                "io" -> Engine.mutate { ioOn = !c.ioOn }
                            }
                        }
                    }
                    val grav = if (z.key == "ddr" && swIdx == 1)
                        Gravity.BOTTOM or Gravity.END
                    else
                        Gravity.TOP or Gravity.END
                    val lp = FrameLayout.LayoutParams(WRAP, WRAP, grav).apply {
                        topMargin = dp(8); bottomMargin = dp(8); marginEnd = dp(8)
                    }
                    card.addView(btn, lp)
                    loadToggles[key] = btn
                }
                // CPU 卡：超温警告覆盖层（仅在卡片内闪烁，点击停止）
                if (z.key == "cpu") {
                    val wv = TextView(this).apply {
                        visibility = View.GONE
                        gravity = Gravity.CENTER
                        textSize = 19f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setBackgroundColor(Color.rgb(0xB7, 0x1C, 0x1C))
                        setOnClickListener { dismissWarning() }
                    }
                    card.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
                    card.setOnClickListener { dismissWarning() }
                    warnView = wv
                }
                val lp = LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(6), 0, dp(6), 0) }
                cardRow.addView(card, lp)
                valueLabels[z.key] = valueTv
                subLabels[z.key] = subTv
                freqLabels[z.key] = freqTv
                maxLabels[z.key] = maxTv
            }
            val noBatt = z.key == "battery" && ex?.battPresent == false
            valueTv.text = if (noBatt) "--" else String.format(Locale.US, "%.1f°C", z.tempC)
            // 副行：该部件的实时占用
            subLabels[z.key]?.let { sub ->
                when {
                    noBatt -> { sub.text = "Not installed"; sub.setTextColor(Color.rgb(0x78, 0x90, 0x9C)) }
                    z.key == "cpu" -> {
                        sub.text = "LOAD " + (if (cpuPct >= 0) "$cpuPct%" else "--") +
                            (if (coreStr.isNotEmpty()) "  $coreStr" else "")
                        sub.setTextColor(utilColor(cpuPct))
                    }
                    z.key == "gpu" -> {
                        sub.text = "LOAD " + (if (gpuPct >= 0) "$gpuPct%" else "--")
                        sub.setTextColor(utilColor(gpuPct))
                    }
                    z.key == "ddr" -> {
                        sub.text = "MEM " + (if (memPct >= 0) "$memPct%" else "--") +
                            (if (memTotal > 0) "  ${memTotal - memAvail}/${memTotal}MB" else "")
                        sub.setTextColor(utilColor(memPct))
                    }
                    else -> sub.text = ""
                }
            }
            // 频率行（仅 CPU 卡显示）
            if (z.key == "cpu") {
                val cur = snap?.cpuFreqKhz?.takeIf { it > 0 }
                val mx = snap?.cpuMaxFreqKhz?.takeIf { it > 0 }
                freqLabels[z.key]?.text = if (cur != null && mx != null)
                    String.format(Locale.US, "FREQ %dMHz  (max %dMHz)", cur / 1000, mx / 1000) else "FREQ --"
            } else {
                freqLabels[z.key]?.text = ""
            }
            val maxV = Engine.maxTemps[z.key] ?: z.tempC
            maxLabels[z.key]?.text =
                if (noBatt) "" else String.format(Locale.US, "MAX %.1f℃", maxV)
        }

        // 页脚：时间 / 状态 / 网页控制台地址（CPU 频率已挪入 CPU 卡片）
        val c = Engine.cfg
        // 按钮状态与 Engine 配置同步
        updateToggleBtn("cpu", c.cpuOn)
        updateToggleBtn("gpu", c.gpuOn)
        updateToggleBtn("mem", c.memOn)
        updateToggleBtn("io", c.ioOn)
        val loadDesc = buildList {
            if (c.cpuOn) add("CPU ${c.cpuLoad}%×${c.cpuThreads}")
            if (c.gpuOn) add("GPU ${c.gpuLoad}%")
            if (c.memOn) add("MEM ${c.memMb}MB")
            if (c.ioOn) add("IO×${c.ioThreads}")
            if (Engine.stepRunning) add("STEP ${Engine.stepPct}%")
        }.joinToString("  ")
        footer.text = "${clockFmt.format(Date())}   " +
            (if (loadDesc.isNotEmpty()) "● $loadDesc" else "○ Idle")
        // 底部右侧大号显示 PC 控制台地址（后端启动后自动从不完整变为真实动态端口）
        pcConsole.text = "PC Console  ${StressService.webUrl()}"

        // 第二行：显存 / 供电 / 室温
        val gpuMem = if (ex != null && ex.gpuMemMb >= 0) "${ex.gpuMemMb}MB" else "--"
        val power = if (ex != null) {
            when {
                ex.battPresent && ex.battCapacity >= 0 ->
                    "Battery ${ex.battCapacity}%" +
                        (if (ex.battVoltageMv >= 0) " " + String.format(Locale.US, "%.2fV", ex.battVoltageMv / 1000.0) else "") +
                        " " + ex.battStatus
                ex.usbOnline -> "USB/DC power"
                else -> "Power unknown"
            }
        } else "--"
        statsLine.text = buildString {
            append("VRAM $gpuMem    $power")
            if (!Engine.ambientC.isNaN())
                append("    Ambient " + String.format(Locale.US, "%.1f", Engine.ambientC) + "℃")
        }

        // GPU 后台渲染挂载
        if (c.gpuOn && gpuView == null) attachGpu(c.gpuLoad)
        gpuView?.loadPercent = c.gpuLoad
        if (!c.gpuOn && gpuView != null) detachGpu()

        updateWarning(snap)
    }

    /** 超温警告：仅在 Engine 存在活动告警（protectionTrip 非空，即模式≠关闭且超阈值）时显示，
     *  彻底解决“关闭保护后终端仍提醒”。由 Handler 驱动 CPU 卡片连续闪烁 10 秒（期间温度
     *  波动不影响），10 秒到或点击卡片仅在终端静音/隐藏；温度回落到阈值-2℃ 后 Engine
     *  清空 trip，本地重新武装，下次超温再次提醒。 */
    private fun updateWarning(snap: Snapshot?) {
        val maxT = snap?.maxTemp ?: Double.NaN
        val threshold = Engine.protectThresholdC
        val tripActive = Engine.protectionTrip.isNotEmpty()

        // 告警结束（温度回落 / 被关闭 / PC 端已知道）：复位本地状态并隐藏
        if (!tripActive) {
            warnDismissed = false
            warnWasActive = false
            warnHandler.removeCallbacks(warnBlinkTask)
            stopWarningSound()
            warnView?.visibility = View.GONE
            return
        }

        // 终端已被本地静音（10 秒到或点击卡片）：保持隐藏，等 Engine 回落复位
        if (warnDismissed) {
            warnView?.visibility = View.GONE
            return
        }

        val msg = if (Engine.protectStoppedLoads)
            String.format(Locale.US,
                "⚠ OVER-TEMP %.1f℃ ≥ %.0f℃\nLOADS STOPPED · tap to mute", maxT, threshold)
        else
            String.format(Locale.US,
                "⚠ OVER-TEMP %.1f℃ ≥ %.0f℃\nALERT ONLY · loads running · tap to mute",
                maxT, threshold)

        if (warnWasActive) {
            // 周期进行中：可见性与声音由 warnBlinkTask 控制，这里只刷新文字
            warnView?.text = msg
            return
        }

        // 新告警：启动闪烁 + 警报音
        warnWasActive = true
        warnStartMs = SystemClock.uptimeMillis()
        warnBlinkOn = false
        warnHandler.removeCallbacks(warnBlinkTask)
        warnHandler.post(warnBlinkTask)
        startWarningSound()
        warnView?.text = msg
    }

    /** 点击卡片或满 10 秒：仅停止终端闪烁与声音（不影响 Engine/PC 侧告警） */
    private fun dismissWarning() {
        warnDismissed = true
        warnHandler.removeCallbacks(warnBlinkTask)
        warnView?.visibility = View.GONE
        stopWarningSound()
    }

    /** 流式播放双音警报（880/1175Hz，350ms 交替，边界 10ms 包络）。
     *  针对 USB 外置喇叭优化（其 isochronous 传输对喂音中断极敏感，烧机高负载下
     *  普通线程会被饿死导致 underrun 爆音）：
     *  ① 播放线程提升到 URGENT_AUDIO(-19) 实时优先级；
     *  ② play() 前预卷 150ms 数据，避免启动即断流；
     *  ③ 缓冲取 ~400ms 深缓冲抗调度抖动，同时限制停止尾音长度；
     *  ④ 停止时先写 15ms 淡出，再补 200ms 零电平静音，让 USB DAC 在静音中拆流，
     *     避开廉价 USB 声卡在流关闭瞬间自动 mute 功放的硬件爆音。 */
    private fun startWarningSound() {
        stopWarningSound()
        soundCancelled = false
        val th = Thread {
            var track: AudioTrack? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                // 与系统原生输出（内置/USB 均为 48kHz）一致，避免 AudioFlinger 重采样
                // 给 USB 声卡 5ms 级 HAL 缓冲的混音线程增加抖动
                val sampleRate = 48000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                // 目标缓冲约 400ms，且不小于 HAL 下限的 2 倍
                val bufFrames = (sampleRate * 400 / 1000)
                    .coerceAtLeast(minBuf / 2)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufFrames * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (soundCancelled) { runCatching { t.release() }; return@Thread }
                // 报警音强制走内置扬声器：外置 USB 声卡（实测 Full-Speed 杰理 AIMIC-M4
                // 经 HUB 接 sunxi-ehci）在高负载/链路抖动时 URB 提交失败（error -27），
                // 会产生无法从 APP 侧消除的爆破音；安全警告必须稳定可靠
                runCatching {
                    val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { t.preferredDevice = it }
                }
                track = t
                audioTrack = t

                val chunk = 512                       // ~23ms 一块
                val toneLen = sampleRate * 350 / 1000
                val fade = sampleRate * 10 / 1000     // 每段首尾 10ms 淡变
                val startFade = sampleRate * 20 / 1000 // 整条流开头 20ms 淡入
                val stopFade = sampleRate * 15 / 1000 // 停止前 15ms 淡出
                val tailSilence = sampleRate * 200 / 1000 // 尾部 200ms 零电平静音
                val buf = ShortArray(chunk)
                var i = 0
                var phase = 0.0

                fun genSample(idx: Int, gain: Double): Short {
                    val f = if ((idx / toneLen) % 2 == 0) 880.0 else 1175.0
                    val pos = idx % toneLen
                    val env = when {
                        pos < fade -> pos.toDouble() / fade
                        pos > toneLen - fade -> (toneLen - pos).toDouble() / fade
                        else -> 1.0
                    }
                    val g0 = if (idx < startFade) idx.toDouble() / startFade else 1.0
                    return (Short.MAX_VALUE.toDouble() * 0.45 * env * g0 * gain *
                            kotlin.math.sin(phase)).toInt().toShort()
                        .also { phase += 2.0 * Math.PI * f / sampleRate }
                }

                fun writeBlocking(data: ShortArray, pos: Int, len: Int) {
                    var off = pos
                    var end = pos + len
                    while (off < end && !soundCancelled) {
                        val n = t.write(data, off, end - off)
                        if (n <= 0) break
                        off += n
                    }
                }

                // 预卷 150ms 后再 play，USB 通路启动慢，防止开头 underrun
                val preRoll = ShortArray(sampleRate * 150 / 1000)
                for (k in preRoll.indices) preRoll[k] = genSample(i++, 1.0)
                writeBlocking(preRoll, 0, preRoll.size)
                if (soundCancelled) { runCatching { t.release() }; return@Thread }
                t.play()

                while (!soundCancelled) {
                    for (k in 0 until chunk) buf[k] = genSample(i++, 1.0)
                    writeBlocking(buf, 0, chunk)
                }
                // 停止淡出：15ms 从当前电平渐弱到零
                val out = ShortArray(stopFade + tailSilence)
                for (k in 0 until stopFade) {
                    out[k] = genSample(i, 1.0 - k.toDouble() / stopFade); i++
                }
                // 其后保持零电平静音（phase 不再推进），让 DAC 在静音状态下拆流
                writeBlocking(out, 0, out.size)
                runCatching { t.stop() }
            } catch (_: Throwable) { } finally {
                runCatching { track?.release() }
                if (audioTrack === track) audioTrack = null
            }
        }
        audioThread = th
        th.start()
    }

    private fun stopWarningSound() {
        soundCancelled = true   // 播放线程检测后自行淡出并 stop/release
        val th = audioThread
        if (th != null && th != Thread.currentThread())
            runCatching { th.join(400) }  // 等淡出+尾部静音播完，避免新旧音轨叠加
    }

    /** 刷新胶囊按钮 ON/OFF 视觉 */
    private fun updateToggleBtn(key: String, on: Boolean) {
        val btn = loadToggles[key] ?: return
        val activeColor = when (key) {
            "cpu" -> Color.rgb(0xE5, 0x39, 0x35)
            "gpu" -> Color.rgb(0x1E, 0x88, 0xE5)
            "mem", "io" -> Color.rgb(0x43, 0xA0, 0x47)
            else -> Color.rgb(0xFB, 0x8C, 0x00)
        }
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (on) { setColor(activeColor); setStroke(0, 0) }
            else { setColor(Color.TRANSPARENT); setStroke(2, Color.rgb(0x90, 0xA4, 0xAE)) }
        }
        btn.background = bg
        btn.setTextColor(if (on) Color.WHITE else Color.rgb(0xB0, 0xBE, 0xC5))
        // 所有按钮始终显示固定名称（RUN/MEM/STO），仅靠填充色区分运行状态
        btn.text = when (key) {
            "cpu", "gpu" -> "RUN"
            "mem" -> "MEM"
            "io" -> "STO"
            else -> key.uppercase()
        }
    }

    private fun attachGpu(load: Int) {
        val v = GpuView(this)
        v.loadPercent = load
        gpuFrame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.onResume()
        gpuView = v
    }

    private fun detachGpu() {
        val v = gpuView ?: return
        runCatching { v.onPause() }
        gpuFrame.removeAllViews()
        gpuView = null
    }
}
