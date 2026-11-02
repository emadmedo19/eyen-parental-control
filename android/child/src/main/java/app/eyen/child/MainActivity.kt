package app.eyen.child

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var codeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var pairButton: Button
    private lateinit var shareLocationButton: Button
    private lateinit var deviceView: TextView
    private val prefs by lazy { getSharedPreferences("eyen_child", MODE_PRIVATE) }
    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (prefs.contains("child_token")) {
                sendHeartbeat()
                handler.postDelayed(this, 60_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        root.addView(text("عين", 32f, Color.WHITE))
        root.addView(text("ربط جهاز الطفل", 18f, Color.LTGRAY))

        codeInput = EditText(this).apply {
            hint = "أدخل كود الربط المكوّن من 6 أرقام"
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        pairButton = Button(this).apply { text = "ربط الجهاز" }
        statusView = text("اطلب كود الربط من ولي الأمر. الكود صالح لمدة 5 دقائق.", 15f, Color.LTGRAY)
        deviceView = text("", 14f, Color.WHITE)
        shareLocationButton = Button(this).apply { text = "مشاركة موقعي الآن"; visibility = View.GONE }

        root.addView(codeInput)
        root.addView(pairButton)
        root.addView(statusView)
        root.addView(deviceView)
        root.addView(shareLocationButton)
        root.addView(text(
            "حالة البطارية تُرسل أثناء فتح التطبيق فقط. الموقع لا يُرسل تلقائياً؛ يلزم ضغط الزر والموافقة على إذن Android.",
            13f, Color.LTGRAY
        ))
        setContentView(root)
        requestNotificationPermissionIfNeeded()

        pairButton.setOnClickListener { claimCode() }
        shareLocationButton.setOnClickListener { confirmLocationShare() }

        if (prefs.contains("child_token")) showPairedState()
        handleLocationRequestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLocationRequestIntent(intent)
    }

    private fun handleLocationRequestIntent(intent: Intent?) {
        val requestId = intent?.getStringExtra(EXTRA_LOCATION_REQUEST_ID) ?: return
        val deviceId = intent.getStringExtra(EXTRA_LOCATION_DEVICE_ID) ?: return
        if (deviceId != prefs.getString("child_device_id", null)) return
        AlertDialog.Builder(this)
            .setTitle("طلب مشاركة الموقع")
            .setMessage("ولي الأمر طلب موقعك الحالي. هل توافق على مشاركة موقعك مرة واحدة؟")
            .setNegativeButton("رفض") { _, _ ->
                statusView.text = "تم رفض طلب مشاركة الموقع."
                intent.removeExtra(EXTRA_LOCATION_REQUEST_ID)
                intent.removeExtra(EXTRA_LOCATION_DEVICE_ID)
            }
            .setPositiveButton("موافقة") { _, _ ->
                prefs.edit().putString("active_location_request_id", requestId).apply()
                intent.removeExtra(EXTRA_LOCATION_REQUEST_ID)
                intent.removeExtra(EXTRA_LOCATION_DEVICE_ID)
                confirmLocationShareAfterRemoteApproval()
            }
            .setOnCancelListener { statusView.text = "لم تتم مشاركة الموقع." }
            .show()
    }

    private fun confirmLocationShareAfterRemoteApproval() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            requestCurrentLocation()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(8, 12, 8, 12)
    }

    private fun showPairedState() {
        codeInput.visibility = View.GONE
        pairButton.visibility = View.GONE
        shareLocationButton.visibility = View.VISIBLE
        val model = prefs.getString("device_name", "جهاز الطفل") ?: "جهاز الطفل"
        statusView.text = "الجهاز مرتبط بحساب ولي الأمر."
        deviceView.text = "الجهاز: $model\nتحديث الحالة يعمل أثناء فتح التطبيق."
    }

    private fun claimCode() {
        val code = codeInput.text.toString().trim()
        if (!code.matches(Regex("\\d{6}"))) {
            statusView.text = "اكتب الكود المكوّن من 6 أرقام."
            return
        }
        pairButton.isEnabled = false
        statusView.text = "جارٍ التحقق من الكود..."
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(codeInput.windowToken, 0)
        val deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
        executor.execute {
            try {
                val result = ApiClient.claim(code, deviceName)
                prefs.edit()
                    .putString("child_device_id", result.getString("childDeviceId"))
                    .putString("child_token", result.getString("childToken"))
                    .putString("device_name", deviceName)
                    .apply()
                runOnUiThread {
                    showPairedState()
                    statusView.text = "تم الربط بنجاح. يمكنك إرسال موقعك الحالي بموافقتك."
                    sendHeartbeat()
                    FcmTokenRegistrar.refresh(this@MainActivity)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusView.text = error.message ?: "تعذر إتمام الربط"
                    pairButton.isEnabled = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.contains("child_token")) {
            FcmTokenRegistrar.refresh(this)
            handler.removeCallbacks(heartbeatTask)
            handler.post(heartbeatTask)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(heartbeatTask)
        super.onPause()
    }

    private fun batterySnapshot(): Pair<Int, Boolean> {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL
        return Pair(percent, charging)
    }

    private fun sendHeartbeat(location: Location? = null) {
        val deviceId = prefs.getString("child_device_id", null) ?: return
        val token = prefs.getString("child_token", null) ?: return
        val (battery, charging) = batterySnapshot()
        val body = JSONObject()
            .put("batteryPercent", battery)
            .put("isCharging", charging)
        if (location != null) {
            body.put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracy.toDouble())
        }
        executor.execute {
            try {
                ApiClient.heartbeat(deviceId, token, body)
                runOnUiThread {
                    if (location != null) statusView.text = "تم إرسال موقعك الحالي لولي الأمر."
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (location != null) statusView.text = "تعذر إرسال الموقع: " + (error.message ?: "خطأ اتصال")
                }
            }
        }
    }

    private fun sendLocationRequestResult(requestId: String, location: Location) {
        val deviceId = prefs.getString("child_device_id", null) ?: return
        val token = prefs.getString("child_token", null) ?: return
        statusView.text = "جارٍ إرسال الموقع الذي وافقت على مشاركته..."
        executor.execute {
            try {
                ApiClient.submitLocationResult(deviceId, token, requestId, location)
                prefs.edit().remove("active_location_request_id").apply()
                runOnUiThread { statusView.text = "تم إرسال موقعك بموافقتك إلى ولي الأمر." }
            } catch (error: Exception) {
                runOnUiThread { statusView.text = "تعذر إرسال الموقع: " + (error.message ?: "خطأ اتصال") }
            }
        }
    }

    private fun confirmLocationShare() {
        AlertDialog.Builder(this)
            .setTitle("مشاركة الموقع مع ولي الأمر")
            .setMessage("سيتم إرسال موقعك الحالي مرة واحدة فقط. لن يتم تتبع موقعك في الخلفية.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة") { _, _ ->
                val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) {
                    requestCurrentLocation()
                } else {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        LOCATION_PERMISSION_REQUEST
                    )
                }
            }
            .show()
    }

    private fun requestCurrentLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = manager.getProviders(true)
        val provider = when {
            fine && providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            providers.isNotEmpty() -> providers.first()
            else -> null
        }
        if (provider == null) {
            statusView.text = "فعّل خدمة الموقع في إعدادات الجهاز ثم حاول مرة أخرى."
            return
        }
        try {
            statusView.text = "جارٍ تحديد موقعك..."
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    val requestId = prefs.getString("active_location_request_id", null)
                    if (requestId != null) {
                        sendLocationRequestResult(requestId, location)
                    } else {
                        sendHeartbeat(location)
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            statusView.text = "لم يتم منح إذن الموقع. يمكنك المحاولة مرة أخرى."
        } catch (error: Exception) {
            statusView.text = "تعذر تحديد الموقع: " + (error.message ?: "خطأ غير معروف")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                requestCurrentLocation()
            } else {
                statusView.text = "لم يتم منح إذن الموقع. لن تتم مشاركة موقعك."
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeatTask)
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LOCATION_REQUEST_ID = "eyen_location_request_id"
        const val EXTRA_LOCATION_DEVICE_ID = "eyen_location_device_id"
        private const val LOCATION_PERMISSION_REQUEST = 41
        private const val NOTIFICATION_PERMISSION_REQUEST = 42
    }
}
