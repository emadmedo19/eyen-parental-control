package app.eyen.child

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity

class CameraPreviewActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var messageView: TextView
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        messageView = TextView(this).apply {
            text = "معاينة مباشرة من كاميرا هذا الجهاز فقط"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
        }
        val stopButton = Button(this).apply {
            text = "إيقاف الكاميرا والعودة"
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(messageView)
            addView(previewView)
            addView(stopButton)
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("إذن الكاميرا مطلوب")
                .setMessage("ارجع إلى الشاشة السابقة وفعّل إذن الكاميرا أولاً.")
                .setPositiveButton("حسنًا") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else startPreview()
    }

    private fun startPreview() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                messageView.text = "الكاميرا تعمل الآن على هذا الجهاز. لا يتم إرسال أو تسجيل أي صورة."
            } catch (error: Exception) {
                messageView.text = "تعذر تشغيل الكاميرا: " + (error.message ?: "خطأ غير معروف")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
