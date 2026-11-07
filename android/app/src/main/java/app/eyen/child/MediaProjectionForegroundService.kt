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

class MediaProjectionForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "مشاركة الشاشة", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("عين • مشاركة الشاشة")
            .setContentText("الشاشة تتم مشاركتها بموافقة Android. اضغط إيقاف لإنهاء الجلسة من التطبيق.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val ready = intent?.getParcelableExtra<ResultReceiver>(EXTRA_READY_RECEIVER)
        // onCreate has already called startForeground. Only now may the activity
        // obtain a MediaProjection on Android 14 and later.
        ready?.send(0, null)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_READY_RECEIVER = "eyen_screen_ready"
        private const val CHANNEL_ID = "eyen_screen_capture"
        private const val NOTIFICATION_ID = 7402
    }
}
