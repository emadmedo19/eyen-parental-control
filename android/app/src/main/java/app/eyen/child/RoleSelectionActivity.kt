package app.eyen.child

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class RoleSelectionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = DeviceRole.current(this)
        if (!intent.getBooleanExtra(EXTRA_CHOOSE_ROLE, false) && role != null) {
            openDashboard(role)
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(28, 40, 28, 40)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 20)
        }
        root.addView(label("عين", 36f))
        root.addView(label("اختاري نوع هذا الجهاز", 22f))
        root.addView(label("ثبّتي نفس التطبيق على الهاتفين. اختاري ولي الأمر على هاتفك، وجهاز الطفل على الهاتف الآخر.", 17f))
        root.addView(Button(this).apply {
            id = R.id.choose_parent
            text = "هذا هاتف ولي الأمر"
            setOnClickListener { choose(DeviceRole.PARENT) }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("أنشئي كود الربط وتابعي البطارية والموقع وطلبات المشاركة.", 15f))
        root.addView(Button(this).apply {
            id = R.id.choose_child
            text = "هذا جهاز الطفل"
            setOnClickListener { choose(DeviceRole.CHILD) }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("أدخلي كود ولي الأمر لربط الجهاز. مشاركة الكاميرا والميكروفون والشاشة تحتاج الأذونات والموافقة المطلوبة.", 15f))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun choose(role: String) {
        DeviceRole.select(this, role)
        openDashboard(role)
    }

    private fun openDashboard(role: String) {
        val target = if (role == DeviceRole.PARENT) app.eyen.parent.MainActivity::class.java
            else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    companion object {
        const val EXTRA_CHOOSE_ROLE = "eyen_choose_role"
    }
}
