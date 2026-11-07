package app.eyen.child

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ApiClient {
    private const val BASE_URL = "https://aggzyhtlnlazmpxbczbd.supabase.co/functions/v1/eyen-api"
    private const val API_KEY = "sb_publishable_Y1n87MhlPCdG200J_UU5BA_s7nCsEhf"

    fun claim(code: String, deviceName: String): JSONObject =
        request("POST", "/v1/pairing-sessions/claim",
            JSONObject().put("code", code).put("deviceName", deviceName), null)

    fun heartbeat(deviceId: String, token: String, body: JSONObject): JSONObject =
        request("POST", "/v1/devices/$deviceId/heartbeat", body, token)

    fun sendRemoteSignal(sessionId: String, childToken: String, type: String, payload: JSONObject): JSONObject =
        request("POST", "/v1/remote-sessions/$sessionId/signals", JSONObject().put("type", type).put("payload", payload), childToken)

    fun pollRemoteSignals(sessionId: String, childToken: String, afterId: Long): JSONObject =
        request("GET", "/v1/remote-sessions/$sessionId/signals?after=$afterId", null, childToken)

    fun pendingRemoteSession(deviceId: String, childToken: String): JSONObject = request("GET", "/v1/devices/$deviceId/remote-sessions/pending", null, childToken)

    fun decideRemoteSession(sessionId: String, childToken: String, decision: String): JSONObject = request("POST", "/v1/remote-sessions/$sessionId/decision", JSONObject().put("decision", decision), childToken)

    fun registerFcmToken(deviceId: String, childToken: String, fcmToken: String): JSONObject =
        request("POST", "/v1/devices/$deviceId/fcm-token", JSONObject().put("token", fcmToken), childToken)

    fun submitLocationResult(deviceId: String, childToken: String, requestId: String, location: android.location.Location): JSONObject =
        request("POST", "/v1/devices/$deviceId/commands/$requestId/result", JSONObject()
            .put("status", "completed")
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy.toDouble()), childToken)

    private fun request(method: String, path: String, body: JSONObject?, bearer: String?): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", API_KEY)
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (payload.isBlank()) JSONObject() else JSONObject(payload)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(json.optString("error", "تعذر الاتصال بالخادم"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
