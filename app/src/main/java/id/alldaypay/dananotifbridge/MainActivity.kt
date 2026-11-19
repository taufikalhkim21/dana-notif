package id.alldaypay.dananotifbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var etUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etInterval: EditText
    private lateinit var cardStatus: android.view.View
    private lateinit var dotStatus: android.view.View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvBatteryState: TextView
    private lateinit var tvApps: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvStatSent: TextView
    private lateinit var tvStatMatch: TextView
    private lateinit var tvStatQueue: TextView
    private lateinit var cardLast: android.view.View
    private lateinit var tvLastAmount: TextView
    private lateinit var tvLastMatch: TextView
    private lateinit var tvLastTime: TextView
    private lateinit var swEnabled: Switch

    private val executor = Executors.newSingleThreadExecutor()
    private var lastPingResult: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swEnabled = findViewById(R.id.swEnabled)
        etUrl = findViewById(R.id.etUrl)
        etSecret = findViewById(R.id.etSecret)
        etInterval = findViewById(R.id.etInterval)
        cardStatus = findViewById(R.id.cardStatus)
        dotStatus = findViewById(R.id.dotStatus)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvPerm = findViewById(R.id.tvPerm)
        tvBatteryState = findViewById(R.id.tvBatteryState)
        tvApps = findViewById(R.id.tvApps)
        tvLog = findViewById(R.id.tvLog)
        tvStatSent = findViewById(R.id.tvStatSent)
        tvStatMatch = findViewById(R.id.tvStatMatch)
        tvStatQueue = findViewById(R.id.tvStatQueue)
        cardLast = findViewById(R.id.cardLast)
        tvLastAmount = findViewById(R.id.tvLastAmount)
        tvLastMatch = findViewById(R.id.tvLastMatch)
        tvLastTime = findViewById(R.id.tvLastTime)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testPing() }
        findViewById<Button>(R.id.btnPickApps).setOnClickListener { showAppPicker() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener { openListenerSettings() }
        findViewById<Button>(R.id.btnFakeNotif).setOnClickListener { sendTestNotif() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            LogStore.clear(); refreshLog()
        }

        // status card tap -> open listener settings when permission missing
        cardStatus.setOnClickListener {
            if (!listenerGranted()) openListenerSettings()
        }
    }

    private fun save() {
        val url = etUrl.text.toString().trim()
        val interval = etInterval.text.toString().trim().toIntOrNull()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Prefs.setGatewayUrl(this, url)
            Prefs.setSecret(this, etSecret.text.toString())
            Prefs.setEnabled(this, swEnabled.isChecked)
            if (interval != null) Prefs.setIntervalMinutes(this, interval)
            Toast.makeText(this, "✓ Tersimpan — heartbeat di-restart", Toast.LENGTH_SHORT).show()
            HeartbeatService.restart(this)
            refreshStatus()
        } else {
            Toast.makeText(this, "URL harus mulai http:// atau https://", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testPing() {
        val url = etUrl.text.toString().trim().trimEnd('/')
        val secret = etSecret.text.toString().trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "Isi URL yang valid dulu", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Ngetes koneksi…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val r = GatewayClient.ping(url, secret)
            val msg = if (r.ok) "OK — gateway reachable" else "HTTP ${r.httpCode}: ${r.message.take(150)}"
            runOnUiThread {
                lastPingResult = msg
                LogStore.append(ts() + "  PING $url/internal/ping -> $msg")
                refreshLog()
                refreshStatus()
            }
        }
    }

    /** Sends a clearly-marked test notification through the real pipeline. */
    private fun sendTestNotif() {
        val url = Prefs.gatewayUrl(this)
        val secret = etSecret.text.toString().trim()
        if (secret.isEmpty()) {
            Toast.makeText(this, "Isi secret dulu", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Ngetes pengiriman…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val text = "TES BRIDGE (bukan pembayaran asli) — Kamu menerima Rp 99.999 dari TEST"
            val r = GatewayClient.post(url, secret, text, source = "test")
            val msg = if (r.ok) "HTTP ${r.httpCode}: ${r.message.take(150)}" else "Gagal: ${r.message.take(150)}"
            runOnUiThread {
                LogStore.append(ts() + "  TES NOTIF -> $msg")
                refreshLog()
                refreshStatus()
                Toast.makeText(this, if (r.ok) "✓ Terkirim ke gateway" else "Gagal: HTTP ${r.httpCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- battery optimization ----

    private fun batteryIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        if (batteryIgnored()) {
            Toast.makeText(this, "Udah diizinkan dari awal", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Buka manual: Settings > Battery", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- app picker (MacroDroid style) ----

    private data class AppRow(val label: String, val pkg: String)

    private fun installedApps(): List<AppRow> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = mutableListOf<AppRow>()
        try {
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) continue
                val label = ri.loadLabel(pm)?.toString()?.ifBlank { pkg } ?: pkg
                out.add(AppRow(label, pkg))
            }
        } catch (_: Exception) { /* listing failed; keep whatever we have */ }
        // also include currently watched packages that aren't launchable (e.g. merchant apps)
        for (p in Prefs.watchedPackages(this)) {
            if (out.none { it.pkg == p }) out.add(AppRow(p.substringAfterLast('.'), p))
        }
        return out.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    private fun showAppPicker() {
        val apps = installedApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "Gak ada app yang bisa dilisting", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = Prefs.watchedPackages(this).toMutableSet()
        val checks = apps.map { selected.contains(it.pkg) }.toBooleanArray()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        apps.forEachIndexed { idx, app ->
            val cb = CheckBox(this).apply {
                text = "${app.label}\n${app.pkg}"
                isChecked = checks[idx]
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            container.addView(cb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // wrap in ScrollView so long app lists scroll inside the dialog
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("Pilih app sumber notif")
            .setView(scroll)
            .setPositiveButton("Simpan") { _, _ ->
                val chosen = apps.filterIndexed { i, _ ->
                    container.getChildAt(i) is CheckBox && (container.getChildAt(i) as CheckBox).isChecked
                }.map { it.pkg }.toSet()
                Prefs.setWatchedPackages(this, chosen)
                refreshApps()
                LogStore.append("${ts()}  Watch list: ${chosen.joinToString(", ").ifEmpty { "(kosong)" }}")
                refreshLog()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ts() = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date())

    private fun listenerGranted(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(":")
            ?.any { ComponentName.unflattenFromString(it)?.packageName == packageName } == true

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Gak bisa buka setting notif", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        swEnabled.isChecked = Prefs.enabled(this)
        etUrl.setText(Prefs.gatewayUrl(this))
        etSecret.setText(Prefs.secret(this))
        etInterval.setText(Prefs.intervalMinutes(this).toString())
        refreshStatus()
        refreshApps()
        refreshStats()
        refreshLast()
        refreshLog()
    }

    private fun refreshStats() {
        tvStatSent.text = Prefs.statSent(this).toString()
        tvStatMatch.text = Prefs.statMatch(this).toString()
        tvStatQueue.text = QueueStore.size(this).toString()
    }

    private fun refreshLast() {
        val amount = Prefs.lastAmount(this)
        if (amount.isEmpty()) {
            cardLast.visibility = android.view.View.GONE
            return
        }
        cardLast.visibility = android.view.View.VISIBLE
        tvLastAmount.text = amount
        val matched = Prefs.lastMatched(this)
        tvLastMatch.text = if (matched) "PAID" else "GAK COCOK"
        tvLastMatch.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (matched) "#15803D" else "#B45309")
        )
        val t = Prefs.lastTime(this)
        tvLastTime.text = if (t > 0)
            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        else ""
    }

    private fun refreshApps() {
        val pkgs = Prefs.watchedPackages(this)
        val shown = pkgs.joinToString(", ") { p ->
            try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(p, 0)
                ).toString()
            } catch (_: Exception) {
                p.substringAfterLast('.')
            }
        }
        tvApps.text = shown.ifEmpty { "(belum ada — notif gak akan dikirim)" }
    }

    private fun refreshStatus() {
        val granted = listenerGranted()
        val secretOk = Prefs.secret(this).isNotEmpty()
        val ping = lastPingResult
        val batteryOk = batteryIgnored()

        val (title, detail, color) = when {
            !granted -> Triple(
                "Izin belum aktif",
                "Ketuk di sini buat buka Notification Access",
                Color.parseColor("#D97706")
            )
            !secretOk -> Triple(
                "Secret belum diisi",
                "Isi NOTIF_SECRET lalu Simpan",
                Color.parseColor("#D97706")
            )
            ping != null && !ping.startsWith("OK") -> Triple(
                "Gateway gak reachable",
                ping.take(120),
                Color.parseColor("#B91C1C")
            )
            ping != null -> Triple(
                "Siap — gateway terhubung",
                ping.take(120),
                Color.parseColor("#0B3D2C")
            )
            DanaNotificationListener.listenerRunning -> Triple(
                "Bridge aktif",
                "Menyimak notifikasi pembayaran…",
                Color.parseColor("#0B3D2C")
            )
            else -> Triple(
                "Bridge belum jalan",
                "Beri izin dulu, lalu buka app pembayaran sekali",
                Color.parseColor("#D97706")
            )
        }
        tvStatusTitle.text = title
        tvStatusDetail.text = detail
        cardStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.argb(0x26, 0xFF, 0xFF, 0xFF)
        )
        dotStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(
                when {
                    color == Color.parseColor("#0B3D2C") -> "#4ADE80"   // green
                    color == Color.parseColor("#B91C1C") -> "#F87171"   // red
                    else -> "#FBBF24"                                    // amber
                }
            )
        )

        tvPerm.text = if (granted) "✓ aktif" else "belum aktif — wajib buat baca notif"
        tvBatteryState.text = if (batteryOk) "✓ diizinkan" else "belum — app bisa dimatiin hemat daya"
    }

    private fun refreshLog() {
        val lines = LogStore.snapshot()
        tvLog.text = if (lines.isEmpty())
            "Belum ada aktivitas.\nBuka app ini lagi setelah ada notif pembayaran masuk."
        else lines.joinToString("\n")
    }
}
