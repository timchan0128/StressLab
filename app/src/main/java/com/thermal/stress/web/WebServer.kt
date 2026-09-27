package com.thermal.stress.web

import android.content.Context
import android.os.Build
import com.thermal.stress.Engine
import com.thermal.stress.load.LoadCfg
import com.thermal.stress.root.RootControl
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 零依赖嵌入式 HTTP 控制台：
 *  - GET  /                 网页控制台（assets/web/index.html）
 *  - GET  /api/state        全部实时状态 JSON
 *  - POST /api/config       下发负载配置（form 表单）
 *  - POST /api/stop         一键停止
 *  - POST /api/step/start | /api/step/abort
 *  - POST /api/protect      超温保护设置
 *  - POST /api/trip/clear
 *  - POST /api/root/detect | /root/lock | /root/unlock
 *  - GET  /api/csv?name=xx  下载测试记录
 */
class WebServer(private val app: Context, private val port: Int = 8080) {

    @Volatile var running = false
        private set
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 首选局域网 IPv4，跳过热点/回环等网段，用于界面提示访问地址 */
    val lanAddress: String
        get() {
            val candidates = ArrayList<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                for (nif in ifaces.toList()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (addr in nif.inetAddresses.toList()) {
                        val h = addr.hostAddress?.substringBefore('%') ?: continue
                        if (addr.isLoopbackAddress || !h.contains('.') || h.contains(':')) continue
                        candidates.add(h)
                    }
                }
            } catch (_: Exception) { }
            // 过滤 Android 软热点/虚拟网段，优先真实 WLAN
            val preferred = candidates.firstOrNull { h ->
                !h.startsWith("192.168.43.") && !h.startsWith("192.168.49.") &&
                !(h.startsWith("172.") && h.removePrefix("172.").substringBefore('.').toIntOrNull() in 16..31)
            }
            return preferred ?: candidates.firstOrNull() ?: "127.0.0.1"
        }

    /** 实际监听端口：port=0 时由系统分配，start() 后从这里取真实端口 */
    @Volatile var actualPort: Int = port
        private set

    val accessUrl: String get() = "http://$lanAddress:$actualPort"

    fun start() {
        if (running) return
        val srv = ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"))
        srv.reuseAddress = true
        server = srv
        actualPort = srv.localPort
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try { srv.accept() } catch (e: Exception) { break }
                Thread({ runCatching { handle(client) } }, "http-conn").also { it.isDaemon = true }.start()
            }
        }, "http-accept").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread = null
    }

    // ================= 请求处理 =================
    private fun readLine(input: BufferedInputStream): String? {
        val bos = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (prev == '\r'.code && b == '\n'.code) {
                val arr = bos.toByteArray()
                return String(arr, 0, arr.size - 1, Charsets.UTF_8)
            }
            bos.write(b)
            prev = b
            if (bos.size() > 16 * 1024) return null
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val method = parts[0]
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
            var body = ""
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 0 && len < 64 * 1024) {
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
                body = String(buf, 0, off, Charsets.UTF_8)
            }
            val path = target.substringBefore('?')
            val query = parseForm(target.substringAfter('?', ""))
            val form = parseForm(body)
            route(s, method, path, query, form)
        }
    }

    private fun parseForm(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isBlank()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private data class Resp(
        val status: Int, val reason: String,
        val contentType: String, val bytes: ByteArray,
        val disposition: String? = null
    )

    private fun json(s: String) =
        Resp(200, "OK", "application/json; charset=utf-8", s.toByteArray(Charsets.UTF_8))

    private fun text(s: String, code: Int = 200) =
        Resp(code, if (code == 200) "OK" else "ERROR", "text/plain; charset=utf-8", s.toByteArray())

    private fun route(s: Socket, method: String, path: String,
                      q: Map<String, String>, f: Map<String, String>) {
        val resp: Resp = try {
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> assetIndex()
                method == "GET" && (path == "/favicon.png" || path == "/favicon64.png") ->
                    Resp(200, "OK", "image/png",
                        app.assets.open("web${path}").use { it.readBytes() })
                method == "GET" && path == "/api/state" -> json(buildState())
                method == "GET" && path == "/api/plugin" -> json(buildPluginInfo())
                method == "GET" && path == "/api/csv" -> downloadCsv(q["name"] ?: "")
                method == "GET" && path == "/api/csv/zip" -> zipCsv(q["names"] ?: "")
                method == "POST" && path == "/api/csv/delete" -> deleteCsv(f["names"] ?: "")
                method == "POST" && path == "/api/config" -> { applyConfig(f); json("""{"ok":true}""") }
                method == "POST" && path == "/api/stop" -> {
                    Engine.stopAllLoads("WEB_STOP"); json("""{"ok":true}""")
                }
                method == "POST" && path == "/api/trip/clear" -> {
                    Engine.clearProtectionTrip(); json("""{"ok":true}""")
                }
                method == "POST" && path == "/api/protect" -> {
                    // 优先用三态 mode（0关 1仅提醒 2提醒并停止）；兼容旧 enabled：1→2，0→0
                    val modeParam = f["mode"]?.toIntOrNull()
                    val mode = modeParam ?: when (f["enabled"]) {
                        null -> Engine.protectMode
                        "1", "true", "2" -> 2
                        else -> 0
                    }
                    Engine.applyProtectMode(mode)
                    f["threshold"]?.toDoubleOrNull()?.let {
                        Engine.protectThresholdC = it.coerceIn(40.0, 120.0)
                    }
                    json("""{"ok":true}""")
                }
                method == "POST" && path == "/api/ambient" -> {
                    val v = f["temp"]?.trim()
                    Engine.ambientC = when {
                        v.isNullOrEmpty() || v.equals("nan", true) -> Double.NaN
                        else -> (v.toDoubleOrNull() ?: Double.NaN).coerceIn(-10.0, 80.0)
                    }
                    json("""{"ok":true}""")
                }
                method == "POST" && path == "/api/step/start" -> {
                    f["start"]?.toIntOrNull()?.let { Engine.stepCfg.startPct = it.coerceIn(0, 100) }
                    f["end"]?.toIntOrNull()?.let { Engine.stepCfg.endPct = it.coerceIn(0, 100) }
                    f["step"]?.toIntOrNull()?.let { Engine.stepCfg.stepPct = it.coerceIn(1, 100) }
                    f["seconds"]?.toIntOrNull()?.let { Engine.stepCfg.secondsPerStage = it.coerceIn(5, 86400) }
                    f["threads"]?.toIntOrNull()?.let { Engine.stepCfg.cpuThreads = it.coerceIn(1, 4) }
                    val err = Engine.startStep()
                    if (err == null) json("""{"ok":true}""")
                    else json("""{"ok":false,"error":${esc(err)}}""")
                }
                method == "POST" && path == "/api/step/abort" -> {
                    Engine.stopAllLoads("WEB_STEP_ABORT"); json("""{"ok":true}""")
                }
                method == "POST" && path == "/api/root/detect" -> {
                    RootControl.detect()
                    json("""{"ok":${RootControl.state == RootControl.State.AVAILABLE},"state":"${RootControl.state.name.lowercase()}"}""")
                }
                method == "POST" && path == "/api/root/unlock" -> {
                    val ok = RootControl.unlock()
                    json("""{"ok":$ok}""")
                }
                method == "POST" && path == "/api/root/lock" -> {
                    val freq = f["freq"]?.toLongOrNull()
                    val ok = if (freq != null) RootControl.lock(freq) else false
                    json("""{"ok":$ok}""")
                }
                else -> text("Not Found", 404)
            }
        } catch (e: Exception) {
            text("Server Error: ${e.message}", 500)
        }
        writeResp(s, resp)
    }

    private fun applyConfig(f: Map<String, String>) {
        val c = LoadCfg().also { it.copyFrom(Engine.cfg) }
        f["cpu"]?.let { c.cpuOn = it == "1" || it == "true" }
        f["threads"]?.toIntOrNull()?.let { c.cpuThreads = it.coerceIn(1, 4) }
        f["load"]?.toIntOrNull()?.let { c.cpuLoad = it.coerceIn(0, 100) }
        f["gpu"]?.let { c.gpuOn = it == "1" || it == "true" }
        f["gpuload"]?.toIntOrNull()?.let { c.gpuLoad = it.coerceIn(1, 100) }
        f["mem"]?.let { c.memOn = it == "1" || it == "true" }
        f["memmb"]?.toIntOrNull()?.let { c.memMb = it.coerceIn(64, 3072) }
        f["io"]?.let { c.ioOn = it == "1" || it == "true" }
        f["iothreads"]?.toIntOrNull()?.let { c.ioThreads = it.coerceIn(1, 4) }
        Engine.applyConfig(c)
    }

    private fun downloadCsv(name: String): Resp {
        val file = Engine.csvFile(name) ?: return text("file not found", 404)
        val data = file.readBytes()
        return Resp(200, "OK", "text/csv; charset=utf-8", data,
            disposition = "attachment; filename=\"${file.name}\"")
    }

    /** names=逗号分隔文件名；为空则打包全部记录 */
    private fun zipCsv(names: String): Resp {
        val files = if (names.isBlank()) Engine.listCsvFiles()
        else names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { Engine.csvFile(it) }
        if (files.isEmpty()) return text("no records", 404)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                zip.write(f.readBytes())
                zip.closeEntry()
            }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return Resp(200, "OK", "application/zip", bos.toByteArray(),
            disposition = "attachment; filename=\"stresslab_records_$stamp.zip\"")
    }

    /** names=逗号分隔文件名；正在写入的当前记录跳过不删 */
    private fun deleteCsv(names: String): Resp {
        val req = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (req.isEmpty()) return json("""{"ok":false,"error":"names required"}""")
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val current = Engine.currentCsvPath
        for (n in req) {
            val f = Engine.csvFile(n)
            when {
                f == null -> skipped.add(n)
                current != null && f.absolutePath == current -> skipped.add(n)  // 正在写入
                f.delete() -> deleted.add(n)
                else -> skipped.add(n)
            }
        }
        val sb = StringBuilder("""{"ok":true,"deleted":${deleted.size},"skipped":[""")
        sb.append(skipped.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" })
        sb.append("]}")
        return json(sb.toString())
    }

    private fun assetIndex(): Resp {
        val bytes = app.assets.open("web/index.html").use { it.readBytes() }
        return Resp(200, "OK", "text/html; charset=utf-8", bytes)
    }

    private fun writeResp(s: Socket, r: Resp) {
        val out = s.getOutputStream()
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${r.status} ${r.reason}\r\n")
        sb.append("Content-Type: ${r.contentType}\r\n")
        sb.append("Content-Length: ${r.bytes.size}\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        if (r.disposition != null) sb.append("Content-Disposition: ${r.disposition}\r\n")
        // 允许从任意页面/设备访问（局域网内网工具）
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.write(r.bytes)
        out.flush()
    }

    // ================= 状态 JSON =================
    private fun buildState(): String {
        val snap = Engine.snapshot
        val c = Engine.cfg
        val sb = StringBuilder(4096)
        sb.append('{')
        sb.append(""" "device": {"model":${esc("${Build.MANUFACTURER} ${Build.MODEL}")},"android":${esc(Build.VERSION.RELEASE)},"abi":${esc(Build.SUPPORTED_ABIS.firstOrNull() ?: "")},"url":${esc(accessUrl)}}, """)
        sb.append(""" "time": ${System.currentTimeMillis()}, """)

        // 热区
        sb.append("\"zones\":[")
        val zones = snap?.zones ?: emptyList()
        zones.forEachIndexed { i, z ->
            if (i > 0) sb.append(',')
            sb.append("""{"key":${esc(z.key)},"name":${esc(z.name)},"temp":${fmt(z.tempC)},"max":${fmt(Engine.maxTemps[z.key] ?: z.tempC)}}""")
        }
        sb.append("],")

        // 硬件
        sb.append(""" "cpuFreqMhz":${if (snap != null && snap.cpuFreqKhz > 0) snap.cpuFreqKhz / 1000 else -1}, """)
        sb.append(""" "cpuMaxMhz":${if (snap != null && snap.cpuMaxFreqKhz > 0) snap.cpuMaxFreqKhz / 1000 else -1}, """)
        sb.append("\"cooling\":[")
        snap?.cooling?.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("""{"type":${esc(p.first)},"state":${p.second}}""")
        }
        sb.append("],")
        sb.append(""" "memAvailMb":${snap?.memAvailableMb ?: -1},"memTotalMb":${snap?.memTotalMb ?: -1}, """)

        // 实时占用率 / 供电
        val ex = snap?.ex
        sb.append("\"cpuUtil\":").append(ex?.cpuUtilPct ?: -1)
        sb.append(",\"cores\":[")
        ex?.coreUtilPct?.forEachIndexed { i, v -> if (i > 0) sb.append(','); sb.append(v) }
        sb.append("],")
        sb.append("\"gpuUtil\":").append(ex?.gpuUtilPct ?: -1).append(',')
        sb.append("\"gpuMemMb\":").append(ex?.gpuMemMb ?: -1).append(',')
        sb.append("\"power\":{")
            .append("\"battPresent\":").append(ex?.battPresent ?: false).append(',')
            .append("\"battCapacity\":").append(ex?.battCapacity ?: -1).append(',')
            .append("\"battVoltageMv\":").append(ex?.battVoltageMv ?: -1).append(',')
            .append("\"battStatus\":").append(esc(ex?.battStatus ?: ""))
            .append(",\"battHealth\":").append(esc(ex?.battHealth ?: ""))
            .append(",\"usbOnline\":").append(ex?.usbOnline ?: false)
            .append("}, ")

        // 负载配置
        sb.append("\"cfg\":{")
            .append(""" "cpuOn":${c.cpuOn},"cpuThreads":${c.cpuThreads},"cpuLoad":${c.cpuLoad}, """)
            .append(""" "gpuOn":${c.gpuOn},"gpuLoad":${c.gpuLoad}, """)
            .append(""" "memOn":${c.memOn},"memMb":${c.memMb}, """)
            .append(""" "ioOn":${c.ioOn},"ioThreads":${c.ioThreads}, "anyOn":${c.anyOn()} }, """)

        // 阶梯
        sb.append(""" "stepCfg":{"start":${Engine.stepCfg.startPct},"end":${Engine.stepCfg.endPct},"step":${Engine.stepCfg.stepPct},"seconds":${Engine.stepCfg.secondsPerStage},"threads":${Engine.stepCfg.cpuThreads}}, """)
        sb.append(""" "step":{"running":${Engine.stepRunning},"pct":${Engine.stepPct},"remaining":${Engine.stageRemainingSec}}, """)

        // 保护：mode 0关/1仅提醒/2提醒并停止；stopped 标记本次告警是否已自动停负载
        sb.append(""" "protect":{"enabled":${Engine.protectEnabled},"mode":${Engine.protectMode},"threshold":${fmt(Engine.protectThresholdC)},"stopped":${Engine.protectStoppedLoads},"trip":${esc(Engine.protectionTrip)}}, """)
        // 环境温度（手动填写）
        sb.append(""" "ambientC":${if (Engine.ambientC.isNaN()) "null" else fmt(Engine.ambientC)}, """)

        // Root（先从 sysfs 同步真实锁频状态，进程重启后内核锁定仍在）
        RootControl.syncFromSysfs()
        sb.append("\"root\":{\"state\":\"").append(RootControl.state.name.lowercase())
            .append("\",\"detail\":").append(esc(RootControl.detail))
            .append(",\"lockedKhz\":").append(RootControl.lockedFreqKhz)
            .append(",\"freqs\":[").append(RootControl.availableFrequencies().joinToString(","))
            .append("]}, ")

        // 记录
        sb.append("\"csv\":[")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        Engine.listCsvFiles().take(50).forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(esc(f.name))
                .append(",\"size\":").append(f.length())
                .append(",\"mtime\":").append(esc(df.format(Date(f.lastModified()))))
                .append(",\"current\":").append(f.absolutePath == Engine.currentCsvPath)
                .append('}')
        }
        sb.append("],")
        sb.append("\"currentCsv\":")
        if (Engine.currentCsvPath != null) sb.append(esc(File(Engine.currentCsvPath).name))
        else sb.append("null")
        sb.append('}')
        return sb.toString()
    }

    /**
     * LineOS 插件注册信息发现端点（供 PC 端获取动态控制台地址）。
     * 本端点所在的内部 WebServer 端口也是系统动态分配的（见 Backend.port），
     * PC 端应经 LineOS 插件端口访问本端点（插件端口会代理 /api/... 到这里）。
     * GET /api/plugin →
     * {"registered":true,"sessionId":"...","debugPort":54176,
     *  "deviceIp":"192.168.x.x",
     *  "consoleUrl":"http://<ip>:54176/",   ← PC 控制台（动态插件端口，无需 token）
     *  "debugUrl":"http://<ip>:54176/"}     ← 同上；/debug 也直接出控制台
     * 未注册时回退到内部动态端口控制台。
     */
    private fun buildPluginInfo(): String {
        val st = com.thermal.stress.plugin.PluginState
        val sb = StringBuilder(448)
        sb.append('{')
        sb.append("\"package\":").append(esc("com.thermal.stress")).append(',')
        sb.append("\"registered\":").append(st.registered).append(',')
        sb.append("\"sessionId\":").append(esc(st.sessionId)).append(',')
        sb.append("\"debugPort\":").append(st.debugPort)
        if (st.registered && st.debugPort > 0) {
            val root = "http://${lanAddress}:${st.debugPort}/"
            sb.append(",\"deviceIp\":").append(esc(lanAddress))
            // PC 端正式入口：LineOS 动态端口上的完整控制台（/ 与 /debug 均出控制台）
            sb.append(",\"consoleUrl\":").append(esc(root))
            sb.append(",\"debugUrl\":").append(esc(root))
        } else {
            sb.append(",\"consoleUrl\":").append(esc(accessUrl))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        sb.append('"')
        return sb.toString()
    }
}
