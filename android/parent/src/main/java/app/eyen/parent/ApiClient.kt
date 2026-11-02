package app.eyen.parent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ApiClient {
    // Replace with the deployed HTTPS API origin before building.
    private const val BASE_URL = "https://wreilawyquhhbvkkgmfy.supabase.co/functions/v1/eyen-api"

    fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body, null)
    fun get(path: String, bearer: String): JSONObject = request("GET", path, null, bearer)

    private fun request(method: String, path: String, body: JSONObject?, bearer: String?): JSONObject {
        require(BASE_URL.startsWith("https://") && !BASE_URL.contains("YOUR_API_HOST")) {
            "اضبط عنوان خادم عين HTTPS أولاً"
        }
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
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
