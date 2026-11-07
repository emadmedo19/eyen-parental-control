package app.eyen.child

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver

class RemoteMediaForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "جلسات الكاميرا والميكروفون", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "camera"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val label = if (mode == "audio") "الميكروفون" else "الكاميرا والميكروفون"
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("عين • جلسة مشاركة نشطة")
            .setContentText("يستخدم التطبيق $label بموافقة مسبقة. افتح عين لإيقاف المشاركة.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (mode == "audio") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            val type = if (mode == "audio") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        @Suppress("DEPRECATION")
        val ready = intent?.getParcelableExtra<ResultReceiver>(EXTRA_READY_RECEIVER)
        ready?.send(0, null)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_MODE = "eyen_media_mode"
        const val EXTRA_READY_RECEIVER = "eyen_media_ready"
        private const val CHANNEL_ID = "eyen_remote_media"
        private const val NOTIFICATION_ID = 7403
    }
}
