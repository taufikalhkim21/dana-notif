package id.alldaypay.dananotifbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads DANA notifications and forwards the text to the gateway using the
 * exact same POST format MacroDroid used, so the gateway needs zero changes.
 *
 * Posting runs on a single worker thread with simple retry (3 attempts,
 * 2s backoff) for network failures. Successful posts appear in the app log.
 */
class DanaNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        const val DANA_PACKAGE = "id.dana"
        const val CHANNEL_ID = "bridge_status"
        const val FOREGROUND_ID = 1
        const val EVENT_CHANNEL_ID = "bridge_events"
        const val EVENT_ID = 2

        @Volatile var listenerRunning = false
            private set
        @Volatile var lastEventAt = 0L
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()
        listenerRunning = true
        // watchdog heartbeat (pengganti macro MacroDroid ping)
        HeartbeatService.start(this)
    }

    override fun onDestroy() {
        listenerRunning = false
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Log.i(GatewayClient.TAG, "listener connected")
        addLog("Listener connected (siap baca notif DANA)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != DANA_PACKAGE) return
        if (!Prefs.enabled(this)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val body = (if (!bigText.isNullOrBlank() && bigText.length > text.length) bigText else text).trim()
        if (body.isBlank()) return

        val full = if (title.isBlank()) body else "$title\n$body"
        lastEventAt = System.currentTimeMillis()
        val amount = AmountParser.parse(full)
        addLog("DANA: $title — ${amount?.let { AmountParser.formatRupiah(it) } ?: "nominal gak ketemu"}")
        Log.i(GatewayClient.TAG, "DANA notif: $full")

        val url = Prefs.gatewayUrl(this)
        val secret = Prefs.secret(this)
        executor.execute { sendWithRetry(url, secret, full) }
    }

    private fun sendWithRetry(url: String, secret: String, text: String, maxAttempts: Int = 3) {
        for (attempt in 1..maxAttempts) {
            val result = GatewayClient.post(url, secret, text)
            if (result.ok) {
                addLog("Terkirim (HTTP ${result.httpCode}): ${result.message}")
                notifyEvent("Terkirim", "POST $url/internal/notification OK")
                return
            }
            addLog("Gagal (attempt $attempt/$maxAttempts): HTTP ${result.httpCode} ${result.message}")
            if (attempt < maxAttempts) {
                try { Thread.sleep(2000L * attempt) } catch (_: InterruptedException) { return }
            }
        }
        notifyEvent("Gagal kirim ke gateway", "Cek URL/secret di app, atau pastikan HP & server satu jaringan")
    }

    // ---- foreground status notification ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL_ID, "Pengiriman notif", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DANA Notif Bridge aktif")
            .setContentText("Menyimak notifikasi DANA")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, n)
    }

    private fun notifyEvent(title: String, text: String) {
        val n = Notification.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(EVENT_ID, n)
    }

    // ---- in-app log (shared buffer, read by MainActivity) ----

    private fun addLog(line: String) {
        val ts = timeFmt.format(Date())
        LogStore.append("$ts  $line")
    }
}

/** Simple ring buffer so MainActivity can display recent events. */
object LogStore {
    private val lines = ArrayDeque<String>()
    private const val MAX = 100

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        if (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
