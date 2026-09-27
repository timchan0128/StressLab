package com.thermal.stress.plugin

/**
 * 插件注册信息（进程内共享，volatile 静态字段）。
 * WebServer 的 /api/plugin 端点读取这里的内容，向 PC 端暴露调试页地址。
 */
object PluginState {

    /** 是否已向 LineOS 注册成功 */
    @Volatile var registered = false
    /** LineOS 分配的会话 ID */
    @Volatile var sessionId: String = ""
    /** 调试会话令牌（LineOS 打开调试页时携带，插件侧必须自行校验） */
    @Volatile var sessionToken: String = ""
    /** LineOS 分配的调试 HTTP 端口；-1 表示尚未分配 */
    @Volatile var debugPort: Int = -1
    /** 最后一次注册成功的时间戳 (ms) */
    @Volatile var registeredAtMs: Long = 0L

    fun onRegistered(sid: String?, token: String?, port: Int) {
        registered = true
        sessionId = sid ?: ""
        sessionToken = token ?: ""
        debugPort = port
        registeredAtMs = System.currentTimeMillis()
    }

    fun onDisconnected() {
        // 断线重连由 SDK 自动完成；保留端口/令牌，仅置注册标志，避免 UI 侧误清理。
        registered = false
    }
}
