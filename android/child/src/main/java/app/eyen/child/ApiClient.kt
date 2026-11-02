package app.eyen.child

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ApiClient {
    // Replace with the deployed HTTPS API origin before building.
    private const val BASE_URL = "https://wreilawyquhhbvkkgmfy.supabase.co/functions/v1/eyen-api"
    private const val API_KEY = "sb_publishable_KMbb4z0cX2jBw4ep3Rwm2w_l4M5jKft"

    fun claim(code: String, deviceName: String): JSONObject {
        require(BASE_URL.startsWith("https://") && !BASE_URL.contains("YOUR_API_HOST")) {
            "اضبط عنوان خادم عين HTTPS أولاً"
        }
        val connection = (URL("$BASE_URL/v1/pairing-sessions/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", API_KEY)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use {
                it.write(JSONObject().put("code", code).put("deviceName", deviceName).toString().toByteArray(Charsets.UTF_8))
            }
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (payload.isBlank()) JSONObject() else JSONObject(payload)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(json.optString("error", "تعذر إتمام الربط"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
