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
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads payment-app notifications (DANA, Livin' Merchant, ... — user picks
 * which apps to watch, MacroDroid style) and forwards the text to the gateway
 * using the same POST format MacroDroid used, so the gateway needs zero
 * changes.
 *
 * Filter layers (configurable gateway parses the amount server-side):
 *   1. package must be in the user-selected watch list
 *   2. keyword success check ("diterima", "berhasil", "QRIS", ...)
 *   3. must contain a "Rp <amount>" nominal
 * Notifications that match layer 1 but neither 2 nor 3 (promo/login alerts)
 * are skipped and logged. Doubtful ones that have an amount still go out —
 * the gateway decides.
 *
 * Posting runs on a single worker thread with retry (3 attempts, backoff).
 * Failures are persisted in QueueStore and retried when the network returns.
 */
class DanaNotificationListener : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        const val CHANNEL_ID = "bridge_status"
        const val FOREGROUND_ID = 1
        const val EVENT_CHANNEL_ID = "bridge_events"
        const val EVENT_ID = 2

        // Success/payment keywords, case-insensitive. Anything with one of
        // these (or a parseable amount) passes layer 2.
        val KEYWORDS = listOf(
            "diterima", "berhasil", "menerima", "kredit", "qris",
            "pembayaran", "masuk", "paid"
        )

        @Volatile var listenerRunning = false
            private set
        @Volatile var lastEventAt = 0L
            private set

        /** Resends everything that failed while offline. Context-free so the
         *  heartbeat service can call it too. */
        fun drainQueue(ctx: android.content.Context) {
            val url = Prefs.gatewayUrl(ctx)
            val secret = Prefs.secret(ctx)
            if (secret.isEmpty()) return
            val pending = QueueStore.drain(ctx)
            if (pending.isEmpty()) return
            Thread {
                LogStore.append(ts() + "  Drain queue: ${pending.size} notif tertunda")
                val failed = mutableListOf<Pair<String, String>>()
                for ((text, hash) in pending) {
                    val r = GatewayClient.post(url, secret, text, hash = hash)
                    if (r.ok) {
                        LogStore.append("${ts()}  Queue terkirim (HTTP ${r.httpCode})")
                    } else {
                        failed.add(text to hash)
                        LogStore.append("${ts()}  Queue gagal: HTTP ${r.httpCode}")
                        break // gateway still unreachable; retry next round
                    }
                }
                QueueStore.requeue(ctx, failed)
            }.start()
        }

        private fun ts() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
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
        addLog("Listener connected (menyimak: ${Prefs.watchedPackages(this).joinToString(", ")})")
        drainQueue(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Diagnostic: log EVERY notification (compact) so a missed payment can
        // be traced in-app — e.g. the watch list missing the real package.
        val extrasAll = sbn.notification?.extras
        if (extrasAll != null) {
            val t = extrasAll.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val x = extrasAll.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            addLog("NOTIF ${sbn.packageName.substringAfterLast('.')}: ${t.take(30)} — ${x.take(70)}")
        }

        if (!Prefs.enabled(this)) return

        // Layer 1: package filter (user-selected list)
        val watched = Prefs.watchedPackages(this)
        if (sbn.packageName !in watched) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        // Some apps (DANA Kasir among them) deliver the body only via
        // MessagingStyle (EXTRA_MESSAGES) or BIG_TEXT; gather every carrier.
        val parts = buildList {
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { m ->
                if (m is android.os.Bundle) {
                    add(m.getCharSequence(Notification.MessagingStyle.Message.TEXT_KEY)?.toString())
                }
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.forEach { add(it?.toString()) }
        }
        val body = parts.filter { !it.isNullOrBlank() }.joinToString("\n").trim()
        if (body.isBlank()) return

        val full = if (title.isBlank()) body else "$title\n$body"
        lastEventAt = System.currentTimeMillis()

        val amount = AmountParser.parse(full)
        val lower = full.lowercase(Locale.getDefault())
        val keywordHit = KEYWORDS.any { lower.contains(it) }

        // Layers 2+3: skip promo/login/update noise; log it so new formats
        // can be added later. If the amount exists we send regardless — the
        // gateway makes the final call.
        if (amount == null && !keywordHit) {
            addLog("SKIP ${sbn.packageName}: $title — bukan notif pembayaran")
            Log.i(GatewayClient.TAG, "skipped (no keyword/amount): $full")
            return
        }

        // MacroDroid-style custom body: user-defined template with
        // {APP} {TITLE} {TEXT} {AMOUNT}. Empty/broken template falls back to
        // the raw notification text so payments are never lost.
        val tpl = Prefs.messageTemplate(this)
        val payload = if (tpl.isBlank()) full else
            tpl.replace("{APP}", appLabel(sbn.packageName))
                .replace("{TITLE}", title)
                .replace("{TEXT}", body)
                .replace("{AMOUNT}", amount?.let { AmountParser.formatRupiah(it) } ?: "")
                .trim().ifEmpty { full }

        // Dedup id: package + title + text + minute. Same notif reposted by
        // the OS within the same minute collapses server-side.
        val minute = full.length + (System.currentTimeMillis() / 60_000L)
        val hash = (sbn.packageName + "|" + title + "|" + full + "|" + minute).toUtfSha1Hex()

        addLog(
            "${appLabel(sbn.packageName)}: $title — " +
                (amount?.let { AmountParser.formatRupiah(it) } ?: "nominal gak ketemu")
        )
        Log.i(GatewayClient.TAG, "forwarding from ${sbn.packageName}: $full")

        val url = Prefs.gatewayUrl(this)
        val secret = Prefs.secret(this)
        executor.execute { sendWithRetry(url, secret, payload, hash) }
    }

    private fun sendWithRetry(url: String, secret: String, text: String, hash: String, maxAttempts: Int = 3) {
        for (attempt in 1..maxAttempts) {
            val result = GatewayClient.post(url, secret, text, hash = hash)
            if (result.ok) {
                Prefs.incStatSent(this)
                if (result.matched) Prefs.incStatMatch(this)
                val amountStr = AmountParser.parse(text)?.let { AmountParser.formatRupiah(it) }
                if (amountStr != null) {
                    Prefs.setLastPayment(this, amountStr, result.matched, System.currentTimeMillis())
                }
                addLog(
                    if (result.matched) "Terkirim & MATCH — invoice PAID (${result.message.take(100)})"
                    else "Terkirim (HTTP ${result.httpCode}): ${result.message.take(100)}"
                )
                notifyEvent(
                    if (result.matched) "Pembayaran cocok" else "Notif terkirim",
                    if (result.matched) "Invoice PAID di gateway" else "POST /internal/notification OK"
                )
                return
            }
            addLog("Gagal (attempt $attempt/$maxAttempts): HTTP ${result.httpCode} ${result.message.take(120)}")
            if (attempt < maxAttempts) {
                try { Thread.sleep(2000L * attempt) } catch (_: InterruptedException) { return }
            }
        }
        // network fail: persist for later retry, never drop payment data
        QueueStore.enqueue(this, text, hash)
        addLog("Masuk queue offline (${QueueStore.size(this)} menunggu)")
        notifyEvent("Gagal kirim ke gateway", "Disimpan di queue, dikirim ulang pas online")
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.').ifEmpty { pkg }
    }

    private fun String.toUtfSha1Hex(): String =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
            .setContentTitle("Notif Bridge aktif")
            .setContentText("Menyimak notifikasi pembayaran")
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
