package app.eyen.child

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

internal object FcmTokenRegistrar {
    fun refresh(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> upload(context.applicationContext, token) }
            .addOnFailureListener { error -> Log.w("EyenFCM", "Could not obtain FCM token", error) }
    }

    fun upload(context: Context, fcmToken: String) {
        if (DeviceRole.current(context) != DeviceRole.CHILD) return
        val prefs = context.getSharedPreferences("eyen_child", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("child_device_id", null) ?: return
        val childToken = prefs.getString("child_token", null) ?: return
        Thread {
            try {
                ApiClient.registerFcmToken(deviceId, childToken, fcmToken)
                Log.i("EyenFCM", "FCM token registered with Eyen API")
            } catch (error: Exception) {
                Log.w("EyenFCM", "FCM token registration failed", error)
            }
        }.start()
    }
}
