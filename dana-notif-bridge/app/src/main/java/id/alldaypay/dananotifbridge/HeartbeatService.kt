package id.alldaypay.dananotifbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Watchdog heartbeat: replaces the MacroDroid ping macro.
 *
 * GET {URL}/internal/ping with header x-notif-secret every N minutes
 * (route checked in gateway src/index.js — it is a GET, not a POST).
 * The gateway's Telegram watchdog goes quiet while pings keep arriving.
 */
class HeartbeatService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    companion object {
        const val CHANNEL_ID = "bridge_heartbeat"
        const val NOTIF_ID = 3

        fun start(ctx: android.content.Context) {
            ctx.startForegroundService(Intent(ctx, HeartbeatService::class.java))
        }

        fun restart(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, HeartbeatService::class.java))
            start(ctx)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watchdog heartbeat", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTIF_ID, buildNotification())
        thread = HandlerThread("heartbeat").apply { start() }
        handler = Handler(thread.looper)
        scheduleNext(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-read interval in case settings changed; keep running.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleNext(delayMs: Long) {
        handler.postDelayed({ tick() }, delayMs)
    }

    private fun tick() {
        val url = Prefs.gatewayUrl(this)
        val secret = Prefs.secret(this)
        val minutes = Prefs.intervalMinutes(this)

        if (Prefs.enabled(this) && secret.isNotEmpty()) {
            val result = ping(url, secret)
            Log.i(GatewayClient.TAG, "heartbeat ${result.httpCode}: ${result.message}")
            if (!result.ok) {
                LogStore.append(ts() + "  Heartbeat gagal: HTTP ${result.httpCode} ${result.message}")
            }
        }
        scheduleNext(minutes * 60_000L)
    }

    private fun ping(urlBase: String, secret: String): GatewayClient.Result {
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
            GatewayClient.Result(code in 200..299, code, resp.take(200))
        } catch (e: Exception) {
            GatewayClient.Result(false, 0, e.message ?: "network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun ts() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date())

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Watchdog heartbeat aktif")
            .setContentText("Ping gateway tiap ${Prefs.intervalMinutes(this)} menit")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
