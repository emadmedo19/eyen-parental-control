package app.eyen.child

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class EyenFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmTokenRegistrar.upload(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Command handling is added with the consent-based foreground location workflow.
        Log.i("EyenFCM", "Received an Eyen data message")
    }
}
