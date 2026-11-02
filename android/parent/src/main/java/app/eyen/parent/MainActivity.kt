package app.eyen.parent

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var codeView: TextView
    private lateinit var statusView: TextView
    private lateinit var createButton: Button
    private lateinit var checkButton: Button
    private val prefs by lazy { getSharedPreferences("eyen_parent", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        val title = text("عين", 32f, Color.WHITE)
        val subtitle = text("لوحة ولي الأمر", 17f, Color.LTGRAY)
        codeView = text("------", 38f, Color.WHITE)
        statusView = text("أنشئ كوداً مؤقتاً لربط جهاز الطفل", 15f, Color.LTGRAY)
        createButton = Button(this).apply { text = "إنشاء كود ربط جديد" }
        checkButton = Button(this).apply { text = "التحقق من الربط"; isEnabled = false }
        root.addView(title)
        root.addView(subtitle)
        root.addView(codeView)
        root.addView(createButton)
        root.addView(checkButton)
        root.addView(statusView)
        setContentView(root)

        createButton.setOnClickListener { createPairingCode() }
        checkButton.setOnClickListener { checkPairing() }
        if (prefs.contains("session_id")) checkButton.isEnabled = true
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(8, 14, 8, 14)
    }

    private fun busy(value: Boolean) {
        createButton.isEnabled = !value
        checkButton.isEnabled = !value && prefs.contains("session_id")
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
                runOnUiThread {
                    statusView.text = when (result.optString("status")) {
                        "paired" -> "تم الربط بنجاح مع: " + result.optString("childDeviceName", "جهاز الطفل")
                        "expired" -> "انتهت صلاحية الكود. أنشئ كوداً جديداً."
                        else -> "في انتظار إدخال الكود على جهاز الطفل..."
                    }
                    createButton.isEnabled = true
                    checkButton.isEnabled = result.optString("status") == "waiting"
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun showError(error: Exception) {
        runOnUiThread {
            statusView.text = error.message ?: "حدث خطأ غير متوقع"
            createButton.isEnabled = true
            checkButton.isEnabled = prefs.contains("session_id")
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
