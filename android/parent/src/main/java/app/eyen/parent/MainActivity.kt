package app.eyen.parent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var deviceView: TextView
    private lateinit var batteryView: TextView
    private lateinit var lastSeenView: TextView
    private lateinit var locationView: TextView
    private lateinit var createButton: Button
    private lateinit var checkButton: Button
    private lateinit var refreshButton: Button
    private lateinit var mapButton: Button
    private var latestLatitude: Double? = null
    private var latestLongitude: Double? = null
    private val prefs by lazy { getSharedPreferences("eyen_parent", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        content.addView(text("عين", 32f, Color.WHITE))
        content.addView(text("لوحة ولي الأمر", 17f, Color.LTGRAY))
        codeView = text("------", 36f, Color.WHITE)
        statusView = text("أنشئ كوداً مؤقتاً لربط جهاز الطفل", 15f, Color.LTGRAY)
        createButton = Button(this).apply { text = "إنشاء كود ربط جديد" }
        checkButton = Button(this).apply { text = "التحقق من الربط"; isEnabled = false }
        content.addView(codeView)
        content.addView(createButton)
        content.addView(checkButton)
        content.addView(statusView)

        content.addView(text("الجهاز المرتبط", 20f, Color.WHITE))
        deviceView = text("لم يتم ربط جهاز بعد", 16f, Color.WHITE)
        batteryView = text("البطارية: —", 15f, Color.LTGRAY)
        lastSeenView = text("آخر ظهور: —", 15f, Color.LTGRAY)
        locationView = text("الموقع: لم تتم مشاركته بعد", 15f, Color.LTGRAY)
        refreshButton = Button(this).apply { text = "تحديث حالة الجهاز"; isEnabled = false }
        mapButton = Button(this).apply { text = "فتح آخر موقع على الخريطة"; isEnabled = false }
        content.addView(deviceView)
        content.addView(batteryView)
        content.addView(lastSeenView)
        content.addView(locationView)
        content.addView(refreshButton)
        content.addView(mapButton)
        content.addView(text(
            "الموقع لا يُرسل إلا عندما يضغط الطفل على مشاركة موقعي الآن ويوافق على إذن Android. تحديث البطارية وآخر ظهور يعمل أثناء فتح تطبيق الطفل.",
            13f, Color.LTGRAY
        ))

        setContentView(ScrollView(this).apply { addView(content) })
        createButton.setOnClickListener { createPairingCode() }
        checkButton.setOnClickListener { checkPairing() }
        refreshButton.setOnClickListener { refreshDeviceStatus() }
        mapButton.setOnClickListener { openLastLocation() }

        if (prefs.contains("session_id")) checkButton.isEnabled = true
        if (prefs.contains("child_device_id")) {
            refreshButton.isEnabled = true
            deviceView.text = "جهاز مرتبط: " + (prefs.getString("child_device_name", "جهاز الطفل") ?: "جهاز الطفل")
            refreshDeviceStatus()
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(8, 10, 8, 10)
    }

    private fun busy(value: Boolean) {
        createButton.isEnabled = !value
        checkButton.isEnabled = !value && prefs.contains("session_id")
        refreshButton.isEnabled = !value && prefs.contains("child_device_id")
    }

    private fun createPairingCode() {
        busy(true)
        statusView.text = "جارٍ إنشاء كود آمن..."
        executor.execute {
            try {
                val result = ApiClient.post("/v1/pairing-sessions", JSONObject())
                prefs.edit()
                    .putString("session_id", result.getString("sessionId"))
                    .putString("poll_token", result.getString("pollToken"))
                    .apply()
                runOnUiThread {
                    codeView.text = result.getString("pairingCode")
                    statusView.text = "الكود صالح لمدة 5 دقائق. أدخله على جهاز الطفل."
                    checkButton.isEnabled = true
                    createButton.isEnabled = true
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun checkPairing() {
        val sessionId = prefs.getString("session_id", null) ?: return
        val token = prefs.getString("poll_token", null) ?: return
        busy(true)
        executor.execute {
            try {
                val result = ApiClient.get("/v1/pairing-sessions/$sessionId", token)
                var deviceStatus: JSONObject? = null
                if (result.optString("status") == "paired") {
                    val childId = result.optString("childDeviceId")
                    if (childId.isNotBlank()) {
                        prefs.edit()
                            .putString("child_device_id", childId)
                            .putString("child_device_name", result.optString("childDeviceName", "جهاز الطفل"))
                            .apply()
                        deviceStatus = runCatching {
                            ApiClient.get("/v1/devices/$childId/status", token)
                        }.getOrNull()
                    }
                }
                val snapshot = deviceStatus
                runOnUiThread {
                    statusView.text = when (result.optString("status")) {
                        "paired" -> "تم الربط بنجاح مع: " + result.optString("childDeviceName", "جهاز الطفل")
                        "expired" -> "انتهت صلاحية الكود. أنشئ كوداً جديداً."
                        else -> "في انتظار إدخال الكود على جهاز الطفل..."
                    }
                    if (snapshot != null) renderDeviceStatus(snapshot)
                    createButton.isEnabled = true
                    checkButton.isEnabled = result.optString("status") == "waiting"
                    refreshButton.isEnabled = prefs.contains("child_device_id")
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun refreshDeviceStatus() {
        val childId = prefs.getString("child_device_id", null) ?: return
        val token = prefs.getString("poll_token", null) ?: return
        refreshButton.isEnabled = false
        statusView.text = "جارٍ تحديث بيانات الجهاز..."
        executor.execute {
            try {
                val result = ApiClient.get("/v1/devices/$childId/status", token)
                runOnUiThread {
                    renderDeviceStatus(result)
                    statusView.text = "تم تحديث بيانات الجهاز."
                    refreshButton.isEnabled = true
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusView.text = "تعذر تحديث الجهاز: " + (error.message ?: "خطأ اتصال")
                    refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun renderDeviceStatus(data: JSONObject) {
        val name = data.optString("deviceName", "جهاز الطفل")
        deviceView.text = "الجهاز: $name"
        val battery = if (data.has("batteryPercent") && !data.isNull("batteryPercent"))
            data.optInt("batteryPercent").toString() + "%" else "لم يصل تحديث بعد"
        val charging = if (data.optBoolean("isCharging")) " — على الشاحن" else ""
        batteryView.text = "البطارية: $battery$charging"
        lastSeenView.text = "آخر ظهور: " + formatTime(data.optString("lastSeenAt", ""))

        val location = data.optJSONObject("location")
        if (location != null) {
            latestLatitude = location.optDouble("latitude")
            latestLongitude = location.optDouble("longitude")
            val accuracy = if (location.has("accuracyMeters") && !location.isNull("accuracyMeters"))
                " — دقة تقريبية " + location.optInt("accuracyMeters") + " متر" else ""
            locationView.text = "آخر موقع تمت مشاركته$accuracy"
            mapButton.isEnabled = true
        } else {
            latestLatitude = null
            latestLongitude = null
            locationView.text = "الموقع: لم تتم مشاركته بعد"
            mapButton.isEnabled = false
        }
    }

    private fun formatTime(value: String): String {
        if (value.isBlank() || value == "null") return "لم يصل تحديث بعد"
        return try {
            val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy  HH:mm")
                .withZone(ZoneId.systemDefault())
            formatter.format(Instant.parse(value))
        } catch (_: Exception) {
            value
        }
    }

    private fun openLastLocation() {
        val lat = latestLatitude ?: return
        val lon = latestLongitude ?: return
        val label = Uri.encode(prefs.getString("child_device_name", "جهاز الطفل") ?: "جهاز الطفل")
        val uri = Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(" + label + ")")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun showError(error: Exception) {
        runOnUiThread {
            statusView.text = error.message ?: "حدث خطأ غير متوقع"
            createButton.isEnabled = true
            checkButton.isEnabled = prefs.contains("session_id")
            refreshButton.isEnabled = prefs.contains("child_device_id")
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
