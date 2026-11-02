package app.eyen.child

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var codeInput: EditText
    private lateinit var statusView: TextView
    private lateinit var pairButton: Button
    private val prefs by lazy { getSharedPreferences("eyen_child", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        val title = text("عين", 32f, Color.WHITE)
        val subtitle = text("ربط جهاز الطفل", 18f, Color.LTGRAY)
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
        root.addView(title)
        root.addView(subtitle)
        root.addView(codeInput)
        root.addView(pairButton)
        root.addView(statusView)
        setContentView(root)

        if (prefs.contains("child_token")) {
            codeInput.visibility = android.view.View.GONE
            pairButton.visibility = android.view.View.GONE
            statusView.text = "هذا الجهاز مرتبط بالفعل بحساب ولي الأمر."
        }
        pairButton.setOnClickListener { claimCode() }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(8, 14, 8, 14)
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
        executor.execute {
            try {
                val result = ApiClient.claim(code, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                prefs.edit()
                    .putString("child_device_id", result.getString("childDeviceId"))
                    .putString("child_token", result.getString("childToken"))
                    .apply()
                runOnUiThread {
                    statusView.text = "تم ربط الجهاز بنجاح. سيظهر الجهاز لدى ولي الأمر."
                    codeInput.visibility = android.view.View.GONE
                    pairButton.visibility = android.view.View.GONE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    statusView.text = error.message ?: "تعذر إتمام الربط"
                    pairButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
