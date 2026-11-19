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

/**
 * Watchdog heartbeat: replaces the MacroDroid ping macro.
 *
 * POST {URL}/internal/heartbeat {"source": "phone"} with header
 * x-notif-secret every N minutes. Falls back to GET /internal/ping on older
 * gateways. The gateway's Telegram watchdog goes quiet while heartbeats keep
 * arriving. Every tick also retries queued offline notifications.
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
            val result = GatewayClient.heartbeat(url, secret)
            Log.i(GatewayClient.TAG, "heartbeat ${result.httpCode}: ${result.message}")
            if (!result.ok) {
                LogStore.append(ts() + "  Heartbeat gagal: HTTP ${result.httpCode} ${result.message.take(120)}")
            } else if (QueueStore.size(this) > 0) {
                // gateway reachable again -> flush queued payment notifs
                DanaNotificationListener.drainQueue(this)
            }
        }
        scheduleNext(minutes * 60_000L)
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
