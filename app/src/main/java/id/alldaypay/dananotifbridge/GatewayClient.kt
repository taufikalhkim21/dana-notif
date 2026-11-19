package id.alldaypay.dananotifbridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the alldayPay gateway. Payload/header format identical to
 * what MacroDroid posts, plus an optional dedup hash:
 *
 *   POST /internal/notification
 *   x-notif-secret: <NOTIF_SECRET>
 *   Content-Type: application/json
 *   {"text": "<notification text>", "source": "phone", "hash": "<id>"}
 *
 * Gateway dedups by hash (falls back to sha1(text)), so never reformat or
 * truncate the text.
 */
object GatewayClient {

    const val TAG = "DanaNotifBridge"

    data class Result(val ok: Boolean, val httpCode: Int, val message: String, val json: JSONObject? = null) {
        /** true kalau gateway jawab matched != null (invoice jadi PAID) */
        val matched: Boolean get() = json?.opt("matched")?.let { it != JSONObject.NULL } == true
    }

    private fun post(urlBase: String, path: String, secret: String, body: JSONObject, timeout: Int = 15_000): Result {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$urlBase$path")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = timeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-notif-secret", secret)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            val json = try { if (resp.startsWith("{")) JSONObject(resp) else null } catch (_: Exception) { null }
            Result(code in 200..299, code, resp.take(300), json)
        } catch (e: Exception) {
            Log.w(TAG, "post failed: ${e.message}")
            Result(false, 0, e.message ?: "network error", null)
        } finally {
            conn?.disconnect()
        }
    }

    fun post(urlBase: String, secret: String, text: String, source: String = "phone", hash: String? = null): Result {
        val body = JSONObject().put("text", text).put("source", source)
        if (!hash.isNullOrBlank()) body.put("hash", hash)
        return post(urlBase, "/internal/notification", secret, body)
    }

    /** Watchdog heartbeat: POST /internal/heartbeat {"source": "phone"}.
     *  Falls back to GET /internal/ping on older gateways that only have it. */
    fun heartbeat(urlBase: String, secret: String): Result {
        val r = post(urlBase, "/internal/heartbeat", secret, JSONObject().put("source", "phone"), timeout = 8_000)
        if (r.ok) return r
        // older gateway: heartbeat route missing -> fallback GET ping
        if (r.httpCode == 404) return ping(urlBase, secret)
        return r
    }

    fun ping(urlBase: String, secret: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$urlBase/internal/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("x-notif-secret", secret)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Result(code in 200..299, code, resp.take(200))
        } catch (e: Exception) {
            Result(false, 0, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }
}
