package com.thermal.stress.plugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.thermal.stress.Backend

/**
 * LineOS 插件服务。
 *
 * 被 LineOS 通过 manifest 扫描发现并自动拉起（MainActivity 打开时也会主动 start）。
 * 生命周期内：
 *   1) 提升为前台服务（specialUse，常驻插件桥；失败降级为普通前台服务）；
 *   2) 确保进程级后端（Engine 监测循环 + PC 控制台，系统动态分配端口）就绪，
 *      即使 MainActivity 从未打开，PC 端仍可访问 /api/plugin 发现调试地址；
 *   3) 启动 ThermalPluginBridge，连接 127.0.0.1:39001。
 *
 * exported=true 且由 com.ultrabar.plugin.SERVER_REGISER_PERMISSION 保护，
 * 只有声明/持有该权限的 LineOS 才能启动。
 */
class PluginService : Service() {

    companion object {
        private const val TAG = "ThermalPluginSvc"
        private const val CHANNEL_ID = "thermal_plugin"
        private const val NOTIFICATION_ID = 2002
    }

    private var bridge: ThermalPluginBridge? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "PluginService onStartCommand")

        // 1) 必须在被拉起后数秒内 startForeground
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            // 部分定制 ROM 可能对 specialUse 有限制；退回无类型前台服务
            Log.w(TAG, "typed startForeground failed, retrying plain", t)
            try {
                startForeground(NOTIFICATION_ID, n)
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground failed completely", t2)
            }
        }

        // 2) 进程级后端（温度监测 + PC 控制台，动态端口）
        try {
            Backend.ensure(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Backend.ensure failed", t)
        }

        // 3) 插件桥（SDK 内部自带约 3 秒断线重连）
        if (bridge == null) {
            val b = ThermalPluginBridge(this)
            bridge = b
            b.start()
        }

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "StressLab Plugin",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "LineOS plugin bridge"
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createChannel failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("StressLab")
            .setContentText("LineOS plugin bridge")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "PluginService destroyed")
        bridge?.let {
            it.stop()
            bridge = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
