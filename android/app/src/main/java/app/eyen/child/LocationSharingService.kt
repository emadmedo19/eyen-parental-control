package app.eyen.child

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

class LocationSharingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var manager: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            sendLocation(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopSelf(); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        try {
            val providers = lm.getProviders(true)
            val selected = buildList {
                if (fine && providers.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (providers.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }.distinct()
            if (selected.isEmpty()) { updateNotification("فعّل خدمة الموقع على الجهاز"); return }
            selected.forEach { provider ->
                try {
                    lm.requestLocationUpdates(provider, 60_000L, 0f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(provider)?.let { sendLocation(it) }
                } catch (_: SecurityException) { }
            }
        } catch (_: Exception) {
            updateNotification("تعذر تشغيل تحديث الموقع")
        }
    }

    private fun sendLocation(location: Location) {
        val prefs = getSharedPreferences("eyen_child", MODE_PRIVATE)
        val deviceId = prefs.getString("child_device_id", null) ?: return
        val token = prefs.getString("child_token", null) ?: return
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val state = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = state == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            state == android.os.BatteryManager.BATTERY_STATUS_FULL
        val body = JSONObject().put("batteryPercent", percent).put("isCharging", charging)
            .put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy.toDouble())
        executor.execute {
            try {
                ApiClient.heartbeat(deviceId, token, body)
                updateNotification("مشاركة الموقع مفعّلة • آخر تحديث تم إرساله")
            } catch (_: Exception) {
                updateNotification("مشاركة الموقع مفعّلة • في انتظار الاتصال")
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "مشاركة الموقع", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String = "مشاركة الموقع مفعّلة بموافقة الطفل"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("عين • مشاركة الموقع")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("eyen_child", MODE_PRIVATE).edit().putBoolean("continuous_location_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager?.removeUpdates(listener)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "app.eyen.child.STOP_LOCATION_SHARING"
        private const val CHANNEL_ID = "eyen_location_sharing"
        private const val NOTIFICATION_ID = 7401
    }
}
