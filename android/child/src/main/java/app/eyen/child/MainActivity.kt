package app.eyen.child

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(12, 24, 44))
        }
        val title = TextView(this).apply {
            text = "عين  |  جهاز الطفل"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "\nالجهاز غير مرتبط حالياً\n\nسيظهر هنا وضع الإدارة وحالة جلسات المشاركة."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(title)
        layout.addView(subtitle)
        setContentView(layout)
    }
}
