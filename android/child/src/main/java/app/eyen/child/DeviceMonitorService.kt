package app.eyen.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/** Visible, user-approved background status monitor. It sends status and battery only. */
class DeviceMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val heartbeat = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("eyen_child", MODE_PRIVATE)
            val deviceId = prefs.getString("child_device_id", null)
            val token = prefs.getString("child_token", null)
            if (deviceId.isNullOrBlank() || token.isNullOrBlank()) {
                stopSelf()
                return
            }
            sendHeartbeat(deviceId, token)
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("المتابعة تعمل في الخلفية"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(heartbeat)
        heartbeat.run()
        return START_STICKY
    }

    private fun sendHeartbeat(deviceId: String, token: String) {
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        executor.execute {
            try {
                ApiClient.heartbeat(deviceId, token, JSONObject()
                    .put("batteryPercent", percent)
                    .put("isCharging", charging))
                updateNotification("المتابعة تعمل • آخر تحديث ناجح")
            } catch (error: Exception) {
                Log.w("EyenMonitor", "Heartbeat failed", error)
                updateNotification("المتابعة تعمل • في انتظار الاتصال")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "متابعة جهاز الطفل", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("عين — حماية الجهاز والمتابعة مفعّلة")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "eyen_device_monitor"
        private const val NOTIFICATION_ID = 7404
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
