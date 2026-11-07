package app.eyen.child

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class EyenFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmTokenRegistrar.upload(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (DeviceRole.current(this) != DeviceRole.CHILD) return
        val type = message.data["type"]
        if (type == "REMOTE_SESSION_REQUEST") {
            val sessionId = message.data["sessionId"] ?: return
            val mode = message.data["mode"] ?: return
            val open = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_REMOTE_SESSION_ID, sessionId)
            }
            val pending = PendingIntent.getActivity(this, sessionId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val label = when (mode) { "camera" -> "الكاميرا"; "audio" -> "الميكروفون"; "screen" -> "مشاركة الشاشة"; else -> "جلسة جديدة" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "طلبات عين", NotificationManager.IMPORTANCE_HIGH))
            }
            val notification = android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("طلب من ولي الأمر")
                .setContentText("طلب استخدام $label. افتح التطبيق للمراجعة.")
                .setContentIntent(pending).setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_STATUS).build()
            try { manager.notify(sessionId.hashCode(), notification) } catch (error: SecurityException) {
                Log.w("EyenFCM", "Notification permission is not granted", error)
            }
            return
        }
        if (type != "LOCATION_REQUEST") {
            Log.i("EyenFCM", "Ignored unsupported Eyen message")
            return
        }
        val requestId = message.data["requestId"] ?: return
        val deviceId = message.data["deviceId"] ?: return
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_LOCATION_REQUEST_ID, requestId)
            putExtra(MainActivity.EXTRA_LOCATION_DEVICE_ID, deviceId)
        }
        val pending = PendingIntent.getActivity(
            this, requestId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "طلبات عين", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "تنبيهات طلبات ولي الأمر التي تحتاج موافقة الطفل" },
            )
        }
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("طلب مشاركة الموقع")
            .setContentText("ولي الأمر طلب موقعك. اضغط للمراجعة والموافقة أو الرفض.")
            .setStyle(android.app.Notification.BigTextStyle().bigText("لن تتم مشاركة موقعك إلا بعد فتح الطلب والموافقة عليه."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(requestId.hashCode(), notification)
        } catch (error: SecurityException) {
            Log.w("EyenFCM", "Notification permission is not granted", error)
        }
    }

    companion object {
        private const val CHANNEL_ID = "eyen_parent_requests"
    }
}
