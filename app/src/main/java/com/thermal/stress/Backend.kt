package com.thermal.stress

import android.app.Application
import android.content.Context
import android.util.Log
import com.thermal.stress.web.WebServer

/**
 * 进程级后端单例。
 *
 * LineOS 可能只通过 manifest 拉起 PluginService 而从不打开 MainActivity，
 * 因此温度监测循环与内部控制台 HTTP 服务都在这里统一 ensure，
 * 进程内只启动一次，生命周期与进程相同。
 *
 * 端口策略：内部 WebServer 使用系统分配的随机空闲端口（避免与其他 APP
 * 的固定端口冲突），仅作进程内回环代理目标；对外的 PC 控制台入口是
 * LineOS 分配的动态插件端口（PluginDebugServer），两者都由本类暴露。
 */
object Backend {
    private const val TAG = "ThermalBackend"

    @Volatile private var webServer: WebServer? = null
    private val lock = Any()

    /** 内部控制台实际端口（系统动态分配；未启动时为 0） */
    val port: Int get() = webServer?.actualPort ?: 0

    /** 幂等：初始化 Engine + 监测循环 + 内部控制台 */
    fun ensure(context: Context) {
        val app = context.applicationContext as Application
        Engine.init(app)
        Engine.startMonitoring()
        synchronized(lock) {
            if (webServer != null) return
            val srv = WebServer(app, 0)   // 0 = 系统分配空闲端口
            try {
                srv.start()
                webServer = srv
                Log.i(TAG, "Web console on ${srv.accessUrl}")
            } catch (e: Exception) {
                Log.w(TAG, "web server start failed: ${e.message}")
            }
        }
    }

    /** 供终端大屏页脚与 /api/plugin 显示控制台地址 */
    fun webUrl(): String = webServer?.accessUrl ?: "http://<设备IP>:<动态端口>"

    fun lanIp(): String = webServer?.lanAddress ?: "127.0.0.1"
}
