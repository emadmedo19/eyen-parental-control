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
import android.widget.ScrollView
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
    private lateinit var cameraSetupButton: Button
    private lateinit var cameraTestButton: Button
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
        shareLocationButton = Button(this).apply { text = "إعداد مشاركة الموقع المستمرة"; visibility = View.GONE }
        cameraSetupButton = Button(this).apply { text = "إعداد صلاحيات الجهاز مرة واحدة" }
        cameraTestButton = Button(this).apply { text = "اختبار الكاميرا ومعاينتها"; visibility = View.GONE }

        root.addView(codeInput)
        root.addView(pairButton)
        root.addView(statusView)
        root.addView(deviceView)
        root.addView(cameraSetupButton)
        root.addView(shareLocationButton)
        root.addView(cameraTestButton)
        root.addView(text(
            "من هنا يمكنك منح أذونات الكاميرا والميكروفون والإشعارات مرة واحدة. الموقع المستمر له إعداد منفصل اختياري. Android يطلب موافقة نظامية جديدة عند كل جلسة مشاركة شاشة، وقد يقيّد تشغيل الكاميرا والميكروفون من الخلفية.",
            13f, Color.LTGRAY
        ))
        root.addView(text(
            "يمكنك تفعيل مشاركة موقعك باستمرار بموافقتك. ستظهر علامة إشعار دائمة أثناء المشاركة، ويمكنك إيقافها من هنا في أي وقت.",
            13f, Color.LTGRAY
        ))
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scrollView)

        pairButton.setOnClickListener { claimCode() }
        cameraSetupButton.setOnClickListener { startPermissionSetup() }
        cameraTestButton.setOnClickListener { openCameraTest() }
        shareLocationButton.setOnClickListener {
            if (prefs.getBoolean("continuous_location_enabled", false)) stopContinuousLocation() else confirmLocationShare()
        }

        if (prefs.contains("child_token")) showPairedState()
        handleLocationRequestIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLocationRequestIntent(intent)
    }


    private fun checkPendingRemoteSession() {
        val deviceId = prefs.getString("child_device_id", null) ?: return
        val token = prefs.getString("child_token", null) ?: return
        executor.execute {
            try {
                val session = ApiClient.pendingRemoteSession(deviceId, token).optJSONObject("session") ?: return@execute
                val id = session.optString("id")
                if (prefs.getString("last_remote_prompt_id", null) == id) return@execute
                prefs.edit().putString("last_remote_prompt_id", id).apply()
                val mode = session.optString("mode")
                runOnUiThread {
                    val label = when (mode) { "camera" -> "الكاميرا"; "audio" -> "الميكروفون"; "screen" -> "مشاركة الشاشة"; else -> "ميزة الجهاز" }
                    AlertDialog.Builder(this)
                        .setTitle("طلب من ولي الأمر")
                        .setMessage("ولي الأمر يطلب استخدام $label. لن يبدأ أي تشغيل قبل موافقتك، ويمكنك الرفض.")
                        .setNegativeButton("رفض") { _, _ -> sendRemoteDecision(id, "rejected") }
                        .setPositiveButton("موافقة") { _, _ -> sendRemoteDecision(id, "approved", mode) }
                        .setOnCancelListener { sendRemoteDecision(id, "rejected") }
                        .show()
                }
            } catch (error: Exception) {
                android.util.Log.w("EyenRemote", "Pending session check failed", error)
            }
        }
    }

    private fun sendRemoteDecision(id: String, decision: String, mode: String = "camera") {
        val token = prefs.getString("child_token", null) ?: return
        executor.execute {
            try {
                ApiClient.decideRemoteSession(id, token, decision)
                runOnUiThread {
                    statusView.text = if (decision == "approved") {
                        "تمت الموافقة. افتح جلسة المشاركة الظاهرة الآن."
                    } else "تم رفض طلب ولي الأمر."
                    if (decision == "approved") {
                        startActivity(Intent(this@MainActivity, RemoteSessionActivity::class.java)
                            .putExtra("session_id", id).putExtra("mode", mode))
                    }
                }
            } catch (error: Exception) {
                prefs.edit().remove("last_remote_prompt_id").apply()
                runOnUiThread { statusView.text = "تعذر تسجيل الرد: " + (error.message ?: "خطأ اتصال") }
            }
        }
    }

    private fun handleLocationRequestIntent(intent: Intent?) {
        val requestId = intent?.getStringExtra(EXTRA_LOCATION_REQUEST_ID) ?: return
        val deviceId = intent.getStringExtra(EXTRA_LOCATION_DEVICE_ID) ?: return
        if (deviceId != prefs.getString("child_device_id", null)) return

        // A prior explicit opt-in to continuous location sharing also authorizes
        // subsequent parent-initiated one-shot location refreshes. The foreground
        // location service keeps its persistent Android notification visible.
        if (prefs.getBoolean("continuous_location_enabled", false) && hasBackgroundLocation()) {
            prefs.edit().putString("active_location_request_id", requestId).apply()
            intent.removeExtra(EXTRA_LOCATION_REQUEST_ID)
            intent.removeExtra(EXTRA_LOCATION_DEVICE_ID)
            requestCurrentLocation()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("طلب مشاركة الموقع")
            .setMessage("لم يتم تفعيل مشاركة الموقع مسبقًا. يمكنك تفعيلها من إعدادات مشاركة الموقع، وستظل حالة المشاركة ظاهرة بإشعار مستمر ويمكنك إيقافها في أي وقت.")
            .setNegativeButton("ليس الآن") { _, _ ->
                statusView.text = "لم تتم مشاركة الموقع."
                intent.removeExtra(EXTRA_LOCATION_REQUEST_ID)
                intent.removeExtra(EXTRA_LOCATION_DEVICE_ID)
            }
            .setPositiveButton("إعداد المشاركة") { _, _ ->
                intent.removeExtra(EXTRA_LOCATION_REQUEST_ID)
                intent.removeExtra(EXTRA_LOCATION_DEVICE_ID)
                confirmLocationShare()
            }
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
        cameraTestButton.visibility = View.VISIBLE
        updateLocationButton()
        updateCameraSetupButton()
        val model = prefs.getString("device_name", "جهاز الطفل") ?: "جهاز الطفل"
        statusView.text = "الجهاز مرتبط بحساب ولي الأمر."
        deviceView.text = "الجهاز: $model\nتحديث الحالة يعمل أثناء فتح التطبيق."
    }

    private fun openCameraTest() {
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            statusView.text = "فعّل إذن الكاميرا أولاً من زر إعداد الأذونات."
            startPermissionSetup()
            return
        }
        startActivity(Intent(this, CameraPreviewActivity::class.java))
    }

    private fun startPermissionSetup() {
        val requested = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requested.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requested.add(Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requested.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isEmpty()) {
            statusView.text = "الأذونات المتاحة مفعّلة بالفعل. الموقع المستمر يُفعّل من زر مشاركة الموقع، ومشاركة الشاشة تتطلب موافقة Android لكل جلسة."
            updateCameraSetupButton()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("إعداد صلاحيات جهاز الطفل")
            .setMessage("بموافقتك، سيُسمح لتطبيق عين باستخدام الكاميرا والميكروفون عند بدء جلسة معلنة، وإظهار إشعارات حالة التشغيل. منح الأذونات لا يشغّل الكاميرا أو الميكروفون تلقائيًا. يمكنك سحب أي إذن لاحقًا من إعدادات Android. مشاركة الشاشة تتطلب موافقة نظامية جديدة لكل جلسة.")
            .setNegativeButton("ليس الآن", null)
            .setPositiveButton("موافقة ومتابعة") { _, _ ->
                requestPermissions(requested.toTypedArray(), CAMERA_PERMISSION_REQUEST)
            }
            .show()
    }

    private fun updateCameraSetupButton() {
        if (!::cameraSetupButton.isInitialized) return
        val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        cameraSetupButton.text = if (camera && audio && notifications)
            "إعداد الصلاحيات: مكتملة" else "إعداد صلاحيات الجهاز مرة واحدة"
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
        if (prefs.getBoolean("awaiting_background_location", false)) {
            if (hasBackgroundLocation()) {
                prefs.edit().remove("awaiting_background_location").putBoolean("continuous_location_enabled", true).apply()
                startLocationService()
            } else {
                statusView.text = "لإكمال المشاركة المستمرة، اسمح للموقع طوال الوقت من إعدادات التطبيق."
            }
            updateLocationButton()
        }
        if (prefs.contains("child_token")) {
            checkPendingRemoteSession()
            updateCameraSetupButton()
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
             .setTitle("تفعيل مشاركة الموقع المستمرة")
            .setMessage("بموافقتك، سيشارك تطبيق عين موقع هذا الجهاز مع ولي الأمر بشكل دوري أثناء تشغيل خدمة ظاهرة بإشعار دائم. يمكنك إيقاف المشاركة في أي وقت من هذا التطبيق. قد يؤخر Android التحديثات لتوفير البطارية.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة") { _, _ ->
                val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) continueBackgroundPermissionFlow() else requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
            }
            .show()
    }


    private fun hasBackgroundLocation(): Boolean = android.os.Build.VERSION.SDK_INT < 29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun continueBackgroundPermissionFlow() {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !hasBackgroundLocation()) {
            prefs.edit().putBoolean("awaiting_background_location", true).apply()
            AlertDialog.Builder(this)
                .setTitle("السماح بالموقع طوال الوقت")
                .setMessage("لإرسال التحديثات عندما لا يكون التطبيق مفتوحاً، افتح إعدادات أذونات الموقع واختر السماح طوال الوقت. هذا الإذن اختياري ويمكنك رفضه.")
                .setNegativeButton("إلغاء") { _, _ -> prefs.edit().remove("awaiting_background_location").apply() }
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }.show()
        } else if (android.os.Build.VERSION.SDK_INT == 29 && !hasBackgroundLocation()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_PERMISSION_REQUEST)
        } else {
            prefs.edit().putBoolean("continuous_location_enabled", true).apply()
            startLocationService()
        }
    }

    private fun startLocationService() {
        try {
            val serviceIntent = Intent(this, LocationSharingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            statusView.text = "مشاركة الموقع المستمرة مفعّلة. يمكنك إيقافها في أي وقت."
            updateLocationButton()
        } catch (error: Exception) {
            prefs.edit().putBoolean("continuous_location_enabled", false).apply()
            statusView.text = "تعذر تشغيل مشاركة الموقع: " + (error.message ?: "خطأ")
        }
    }

    private fun stopContinuousLocation() {
        stopService(Intent(this, LocationSharingService::class.java).setAction(LocationSharingService.ACTION_STOP))
        prefs.edit().putBoolean("continuous_location_enabled", false).apply()
        statusView.text = "تم إيقاف مشاركة الموقع."
        updateLocationButton()
    }

    private fun updateLocationButton() {
        if (::shareLocationButton.isInitialized) shareLocationButton.text =
            if (prefs.getBoolean("continuous_location_enabled", false)) "إيقاف مشاركة الموقع" else "إعداد مشاركة الموقع المستمرة"
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
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            updateCameraSetupButton()
            val notificationsGranted = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            statusView.text = when {
                cameraGranted && audioGranted && notificationsGranted -> "اكتمل إعداد أذونات الكاميرا والميكروفون والإشعارات. مشاركة الشاشة تظل بموافقة Android لكل جلسة."
                cameraGranted && audioGranted -> "تم منح الكاميرا والميكروفون، لكن إذن الإشعارات لم يُمنح."
                cameraGranted -> "تم منح إذن الكاميرا فقط. إذن الميكروفون لم يُمنح."
                audioGranted -> "تم منح إذن الميكروفون فقط. إذن الكاميرا لم يُمنح."
                else -> "لم يتم منح أذونات الكاميرا والميكروفون."
            }
            return
        }
        if (requestCode == BACKGROUND_PERMISSION_REQUEST) {
            if (hasBackgroundLocation()) {
                prefs.edit().putBoolean("continuous_location_enabled", true).apply()
                startLocationService()
            } else statusView.text = "لم يتم منح إذن الموقع طوال الوقت؛ لم يتم تفعيل المشاركة المستمرة."
            return
        }
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                continueBackgroundPermissionFlow()
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
        const val EXTRA_REMOTE_SESSION_ID = "eyen_remote_session_id"
        const val EXTRA_LOCATION_REQUEST_ID = "eyen_location_request_id"
        const val EXTRA_LOCATION_DEVICE_ID = "eyen_location_device_id"
        private const val LOCATION_PERMISSION_REQUEST = 41
        private const val NOTIFICATION_PERMISSION_REQUEST = 42
        private const val BACKGROUND_PERMISSION_REQUEST = 43
        private const val CAMERA_PERMISSION_REQUEST = 44
    }
}
