package com.thermal.stress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 前台保活服务：
 *  - 烧核线程 / 监测循环 / 唤醒锁在息屏后台持续运行
 *  - 内置 PC 网页控制台（HTTP 8080）
 */
class StressService : Service() {

    companion object {
        const val ACTION_START = "com.thermal.stress.START"
        const val ACTION_STOP_LOADS = "com.thermal.stress.STOP_LOADS"
        /**
         * ADB 无头烧机指令，示例：
         * am startservice -n com.thermal.stress/.StressService -a com.thermal.stress.BURN \
         *   --ei cpu 1 --ei threads 4 --ei load 100 \
         *   --ei mem 1 --ei memmb 256 --ei io 1 --ei iothreads 2
         * 停止：am startservice -n com.thermal.stress/.StressService -a com.thermal.stress.STOP_LOADS
         */
        const val ACTION_BURN = "com.thermal.stress.BURN"
        private const val CH_ID = "thermal_stress"
        private const val NID = 1001

        /** 供终端大屏页脚显示控制台地址（进程级 WebServer，动态端口） */
        fun webUrl(): String = Backend.webUrl()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NID, buildNotification("监测运行中"))
        // 进程级后端：温度监测循环 + PC 控制台（PluginService 也会 ensure，幂等）
        Backend.ensure(applicationContext)
        getSystemService(NotificationManager::class.java)
            .notify(NID, buildNotification("PC 控制台：${Backend.webUrl()}"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_LOADS -> {
                Engine.stopAllLoads("USER_STOP")
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NID, buildNotification("已停止负载"))
            }
            ACTION_BURN -> {
                val c = com.thermal.stress.load.LoadCfg()
                c.copyFrom(Engine.cfg)
                intent.extras?.let {
                    if (it.containsKey("cpu")) c.cpuOn = it.getInt("cpu") == 1
                    if (it.containsKey("threads")) c.cpuThreads = it.getInt("threads").coerceIn(1, 4)
                    if (it.containsKey("load")) c.cpuLoad = it.getInt("load").coerceIn(0, 100)
                    if (it.containsKey("mem")) c.memOn = it.getInt("mem") == 1
                    if (it.containsKey("memmb")) c.memMb = it.getInt("memmb").coerceIn(64, 3072)
                    if (it.containsKey("io")) c.ioOn = it.getInt("io") == 1
                    if (it.containsKey("iothreads")) c.ioThreads = it.getInt("iothreads").coerceIn(1, 4)
                    // GPU 需要终端应用在前台（GL 视图由大屏页观察配置自动挂载）
                    if (it.containsKey("gpu")) c.gpuOn = it.getInt("gpu") == 1
                    if (it.containsKey("gpuload")) c.gpuLoad = it.getInt("gpuload").coerceIn(1, 100)
                }
                Engine.applyConfig(c)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NID, buildNotification("烧机中：CPU ${c.cpuLoad}%×${c.cpuThreads}" +
                    (if (c.gpuOn) " +GPU" else "") +
                    (if (c.memOn) " +内存" else "") + (if (c.ioOn) " +IO" else "")))
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Engine.stopAllLoads("TASK_REMOVED")
        stopForeground(true)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 监测循环与 8091 WebServer 是进程级后端（Backend 单例），
        // PluginService 可能仍在运行，不能随本服务销毁而停止。
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "热测试服务", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StressService::class.java).setAction(ACTION_STOP_LOADS),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("StressLab 整机热测试")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止负载", stop)
            .build()
    }
}
