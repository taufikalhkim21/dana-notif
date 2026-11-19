package id.alldaypay.dananotifbridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the alldayPay gateway. Payload/header format must stay
 * identical to what MacroDroid posts today:
 *
 *   POST /internal/notification
 *   x-notif-secret: <NOTIF_SECRET>
 *   Content-Type: application/json
 *   {"text": "<notification text>", "source": "phone"}
 *
 * Gateway dedups by sha1(text), so never reformat or truncate the text.
 */
object GatewayClient {

    const val TAG = "DanaNotifBridge"

    data class Result(val ok: Boolean, val httpCode: Int, val message: String)

    fun post(urlBase: String, secret: String, text: String, source: String = "phone"): Result {
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().put("text", text).put("source", source).toString()
            val url = URL("$urlBase/internal/notification")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-notif-secret", secret)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            Result(code in 200..299, code, resp.take(300))
        } catch (e: Exception) {
            Log.w(TAG, "post failed: ${e.message}")
            Result(false, 0, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }
}
