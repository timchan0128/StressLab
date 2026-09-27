package com.thermal.stress.plugin

import android.content.Context
import android.content.Intent
import android.util.Log
import com.thermal.stress.Engine
import com.thermal.stress.MainActivity
import com.thermal.stress.load.LoadCfg
import com.ultrabar.plugin.PluginClient
import com.ultrabar.plugin.callback.CallResponder
import com.ultrabar.plugin.callback.DescribeResponder
import com.ultrabar.plugin.callback.OptionsResponder
import com.ultrabar.plugin.callback.PluginListener
import com.ultrabar.plugin.model.ActionSummary
import com.ultrabar.plugin.model.ActionsPayload
import com.ultrabar.plugin.model.ActionsResultPayload
import com.ultrabar.plugin.model.CallPayload
import com.ultrabar.plugin.model.DescribePayload
import com.ultrabar.plugin.model.DescribeResultPayload
import com.ultrabar.plugin.model.GetOptionsPayload
import com.ultrabar.plugin.model.GetOptionsResultPayload
import com.ultrabar.plugin.model.Label
import com.ultrabar.plugin.model.OptionProvider
import com.ultrabar.plugin.model.ParameterType
import com.ultrabar.plugin.model.RegisterPayload
import com.ultrabar.plugin.model.RegisterResultPayload
import java.util.Locale

/**
 * StressLab 的 LineOS 插件桥。
 *
 * 协议：明文 TCP 连本机 127.0.0.1:39001（PluginClient 默认），一行一条 JSON。
 * Action（4 个，对齐 APP 自身能力）：
 *   thermal.cpu_burn(level)        SELECT REMOTE，档位 25/50/75/100%，固定 4 线程；
 *   thermal.mixed_burn(profile)    SELECT REMOTE，CPU 满载叠加 内存 / IO；
 *   thermal.stop                   无参数，一键停止全部负载；
 *   thermal.status                 无参数，返回实时温度/负载。
 *
 * GPU 负载依赖 Activity 上的 GL 视图，不由插件无头开启。
 * 执行链路：onCall → Engine（业务状态层），成功后尝试拉起大屏 UI
 *（后台启动被 BAL 拦截也无碍——负载本身已在 Service 进程中运行）。
 */
class ThermalPluginBridge(context: Context) {

    companion object {
        private const val TAG = "ThermalPlugin"
        const val PKG = "com.thermal.stress"

        const val ACT_CPU_BURN = "thermal.cpu_burn"
        const val ACT_MIXED_BURN = "thermal.mixed_burn"
        const val ACT_STOP = "thermal.stop"
        const val ACT_STATUS = "thermal.status"

        const val PARAM_LEVEL = "level"
        const val PARAM_PROFILE = "profile"

        private val CPU_LEVELS = listOf(25, 50, 75, 100)
        private const val MIX_MEM_MB = 1024

        fun json(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder(s.length + 2)
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

    val appContext: Context = context.applicationContext
    private var client: PluginClient? = null
    private var debugServer: PluginDebugServer? = null

    @Synchronized
    fun start() {
        if (client != null) {
            Log.w(TAG, "start() ignored — already running")
            return
        }
        Log.i(TAG, "Starting plugin bridge -> 127.0.0.1:39001")

        val c = PluginClient()

        val rp = RegisterPayload()
        rp.name = "StressLab"
        rp.packageName = PKG
        c.setRegisterConfig(rp)

        val actions = listOf(
            ActionSummary().apply {
                actionId = ACT_CPU_BURN
                name = "CPU Burn"
                description = "Start 4-thread CPU stress at 25/50/75/100% load"
            },
            ActionSummary().apply {
                actionId = ACT_MIXED_BURN
                name = "Mixed Burn"
                description = "Full CPU burn plus memory and/or storage I/O load"
            },
            ActionSummary().apply {
                actionId = ACT_STOP
                name = "Stop All Loads"
                description = "Stop every running stress load immediately"
            },
            ActionSummary().apply {
                actionId = ACT_STATUS
                name = "Thermal Status"
                description = "Report current temperatures and active loads"
            }
        )
        c.setActionsConfig(ActionsPayload(actions))

        c.setPluginListener(object : PluginListener {
            override fun onRegisterSuccess(result: RegisterResultPayload) {
                val port = result.configServer?.port ?: -1
                Log.i(TAG, "Register OK: sessionId=${result.sessionId} debugPort=$port")
                PluginState.onRegistered(result.sessionId, result.sessionToken, port)
                startDebugServer(port)
            }

            override fun onRegisterFailed(t: Throwable?) {
                Log.w(TAG, "Register failed (SDK will retry): $t")
                PluginState.onDisconnected()
            }

            override fun onActionsFailed(t: Throwable?) {
                Log.w(TAG, "Actions report failed: $t")
            }

            override fun onActionsAck(ack: ActionsResultPayload) {
                Log.i(TAG, "Actions ACK: success=${ack.success} received=${ack.receivedCount}")
            }

            override fun onActionsUpdate(update: ActionsPayload?) {
                Log.i(TAG, "Actions update pushed by host")
            }

            override fun onDescribe(describe: DescribePayload, responder: DescribeResponder) {
                Log.i(TAG, "onDescribe: ${describe.actionId}")
                val result = DescribeResultPayload()
                result.actionId = describe.actionId
                result.success = true
                result.parameters = ArrayList()

                when (describe.actionId) {
                    ACT_CPU_BURN -> {
                        val spec = DescribeResultPayload.ParameterSpec()
                        spec.id = PARAM_LEVEL
                        spec.name = "CPU Load"
                        spec.placeholder = "Select load level"
                        spec.required = true
                        spec.type = ParameterType.SELECT
                        spec.options = DescribeResultPayload.OptionSpec().apply {
                            provider = OptionProvider.REMOTE
                            searchable = false
                        }
                        result.parameters.add(spec)
                    }
                    ACT_MIXED_BURN -> {
                        val spec = DescribeResultPayload.ParameterSpec()
                        spec.id = PARAM_PROFILE
                        spec.name = "Mixed Profile"
                        spec.placeholder = "Select stress profile"
                        spec.required = true
                        spec.type = ParameterType.SELECT
                        spec.options = DescribeResultPayload.OptionSpec().apply {
                            provider = OptionProvider.REMOTE
                            searchable = false
                        }
                        result.parameters.add(spec)
                    }
                }
                responder.sendSuccess(result)
            }

            override fun onOptions(request: GetOptionsPayload, responder: OptionsResponder) {
                Log.i(TAG, "onOptions: ${request.actionId}/${request.describeId} search='${request.searchText}'")
                val result = GetOptionsResultPayload()
                result.success = true
                result.hasMore = false
                result.nextCursor = null
                result.items = when (request.actionId) {
                    ACT_CPU_BURN -> buildCpuLevelOptions(request.searchText)
                    ACT_MIXED_BURN -> buildMixedProfileOptions(request.searchText)
                    else -> ArrayList()
                }
                responder.sendSuccess(result)
            }

            override fun onCall(call: CallPayload, responder: CallResponder) {
                val actionId = call.actionId
                @Suppress("UNCHECKED_CAST")
                val params = call.params as? Map<String, Any?>
                Log.i(TAG, "onCall: $actionId params=$params")
                try {
                    responder.sendSuccess(dispatch(actionId, params))
                } catch (e: Exception) {
                    Log.e(TAG, "onCall failed: $actionId", e)
                    responder.sendError("INTERNAL_ERROR", e.message ?: "", false, null)
                }
            }
            // 注：SDK 1.0.12 的 PluginListener 尚无 onTopicUpdate（main 分支才有）。
        })

        client = c
        c.startAsync()
    }

    @Synchronized
    fun stop() {
        Log.i(TAG, "Stopping plugin bridge")
        debugServer?.let {
            it.stopSafe()
            debugServer = null
        }
        client?.let {
            try { it.stop() } catch (t: Throwable) { Log.w(TAG, "client.stop failed", t) }
            client = null
        }
        PluginState.onDisconnected()
    }

    // ==================================================================
    //  Action 执行（全部落在 Engine 业务层，不直接触碰 UI 控件）
    // ==================================================================

    private fun dispatch(actionId: String, params: Map<String, Any?>?): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        data["actionId"] = actionId

        when (actionId) {
            ACT_CPU_BURN -> {
                val raw = params?.get(PARAM_LEVEL)?.toString()
                    ?: throw IllegalArgumentException("missing parameter: $PARAM_LEVEL")
                val level = raw.trim().toIntOrNull()
                    ?: throw IllegalArgumentException("invalid level: $raw")
                if (level !in CPU_LEVELS) {
                    throw IllegalArgumentException("level must be one of $CPU_LEVELS")
                }
                startCpuBurn(level)
                launchDashboard()
                data["status"] = "ok"
                data["level"] = level
                data += liveSummary()
            }
            ACT_MIXED_BURN -> {
                val profile = params?.get(PARAM_PROFILE)?.toString()?.trim()
                    ?: throw IllegalArgumentException("missing parameter: $PARAM_PROFILE")
                if (profile !in setOf("cpu_mem", "cpu_io", "cpu_mem_io")) {
                    throw IllegalArgumentException("invalid profile: $profile")
                }
                startMixedBurn(profile)
                launchDashboard()
                data["status"] = "ok"
                data["profile"] = profile
                data += liveSummary()
            }
            ACT_STOP -> {
                Engine.stopAllLoads("PLUGIN_STOP")
                data["status"] = "ok"
                data += liveSummary()
            }
            ACT_STATUS -> {
                data["status"] = "ok"
                data += liveSummary()
            }
            else -> throw IllegalArgumentException("unknown actionId: $actionId")
        }
        return data
    }

    private fun startCpuBurn(level: Int) {
        Engine.clearProtectionTrip()
        val c = LoadCfg().also { it.copyFrom(Engine.cfg) }
        c.cpuOn = true
        c.cpuThreads = 4
        c.cpuLoad = level
        Engine.applyConfig(c)
    }

    private fun startMixedBurn(profile: String) {
        Engine.clearProtectionTrip()
        val c = LoadCfg().also { it.copyFrom(Engine.cfg) }
        c.cpuOn = true
        c.cpuThreads = 4
        c.cpuLoad = 100
        c.memOn = profile == "cpu_mem" || profile == "cpu_mem_io"
        if (c.memOn) c.memMb = MIX_MEM_MB
        c.ioOn = profile == "cpu_io" || profile == "cpu_mem_io"
        if (c.ioOn) c.ioThreads = 2
        Engine.applyConfig(c)
    }

    /** 拉起终端大屏（singleTask；已在前台时仅 bring-to-front）。失败不影响负载运行。 */
    private fun launchDashboard() {
        try {
            val intent = Intent(appContext, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "launch dashboard failed (loads already running headless)", t)
        }
    }

    private fun liveSummary(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        val snap = Engine.snapshot
        m["running"] = Engine.anyLoadActive()
        m["maxTempC"] = if (snap != null && !snap.maxTemp.isNaN())
            String.format(Locale.US, "%.1f", snap.maxTemp) else null
        val zones = ArrayList<Map<String, Any?>>()
        snap?.zones?.forEach { z ->
            zones.add(mapOf(
                "key" to z.key, "name" to z.name,
                "tempC" to String.format(Locale.US, "%.1f", z.tempC)
            ))
        }
        m["zones"] = zones
        val c = Engine.cfg
        m["loads"] = mapOf(
            "cpu" to c.cpuOn, "cpuLoad" to c.cpuLoad, "cpuThreads" to c.cpuThreads,
            "mem" to c.memOn, "memMb" to c.memMb,
            "io" to c.ioOn, "ioThreads" to c.ioThreads
        )
        m["protectThresholdC"] = String.format(Locale.US, "%.0f", Engine.protectThresholdC)
        return m
    }

    // ==================================================================
    //  选项（每次实时构建，不缓存）
    // ==================================================================

    private fun buildCpuLevelOptions(searchText: String?): List<Label> {
        val kw = searchText?.trim()?.lowercase(Locale.US) ?: ""
        return CPU_LEVELS.mapNotNull { lvl ->
            val label = "CPU $lvl% × 4 threads"
            if (kw.isEmpty() || label.lowercase(Locale.US).contains(kw) ||
                kw in lvl.toString()) {
                Label(label, lvl.toString())
            } else null
        }
    }

    private fun buildMixedProfileOptions(searchText: String?): List<Label> {
        val kw = searchText?.trim()?.lowercase(Locale.US) ?: ""
        val all = listOf(
            Label("CPU 100% × 4 + Memory ${MIX_MEM_MB}MB", "cpu_mem"),
            Label("CPU 100% × 4 + Storage I/O × 2", "cpu_io"),
            Label("CPU 100% × 4 + Memory + I/O", "cpu_mem_io")
        )
        return if (kw.isEmpty()) all
        else all.filter {
            it.label.lowercase(Locale.US).contains(kw) ||
                (it.value ?: "").lowercase(Locale.US).contains(kw)
        }
    }

    // ==================================================================
    //  调试页
    // ==================================================================

    private fun startDebugServer(port: Int) {
        if (port <= 0) {
            Log.i(TAG, "Host assigned no debug port; skip debug server")
            return
        }
        synchronized(this) {
            debugServer?.let {
                it.stopSafe()
                debugServer = null
            }
            try {
                // 回调在 Netty IO 线程，stop/start 用同一把锁保护
                val srv = PluginDebugServer(port, this, appContext)
                srv.start()
                debugServer = srv
                Log.i(TAG, "Debug page listening on 0.0.0.0:$port (token required)")
            } catch (e: Exception) {
                Log.w(TAG, "Debug server start failed on port $port", e)
            }
        }
    }

    /** 供调试页按钮直接触发（已通过 token 校验） */
    fun invokeForDebug(actionId: String, param: String?): Map<String, Any?> {
        val params = LinkedHashMap<String, Any?>()
        when (actionId) {
            ACT_CPU_BURN -> if (!param.isNullOrEmpty()) params[PARAM_LEVEL] = param
            ACT_MIXED_BURN -> if (!param.isNullOrEmpty()) params[PARAM_PROFILE] = param
        }
        return dispatch(actionId, params)
    }

    fun debugCpuLevelOptions(): List<Label> = buildCpuLevelOptions(null)
    fun debugMixedProfileOptions(): List<Label> = buildMixedProfileOptions(null)

    /** 调试页 /api/status：插件信息 + 实时业务状态 */
    fun debugStateJson(): String {
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"package\":").append(json(PKG)).append(',')
        sb.append("\"registered\":").append(PluginState.registered).append(',')
        sb.append("\"sessionId\":").append(json(PluginState.sessionId)).append(',')
        sb.append("\"debugPort\":").append(PluginState.debugPort).append(',')
        sb.append("\"consoleUrl\":").append(json(com.thermal.stress.Backend.webUrl())).append(',')
        val snap = Engine.snapshot
        sb.append("\"running\":").append(Engine.anyLoadActive()).append(',')
        sb.append("\"maxTempC\":")
        if (snap != null && !snap.maxTemp.isNaN())
            sb.append(String.format(Locale.US, "%.1f", snap.maxTemp)) else sb.append("null")
        sb.append('}')
        return sb.toString()
    }
}
