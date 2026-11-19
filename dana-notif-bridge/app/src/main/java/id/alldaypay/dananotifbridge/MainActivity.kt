package id.alldaypay.dananotifbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var etUrl: EditText
    private lateinit var etSecret: EditText
    private lateinit var etInterval: EditText
    private lateinit var cardStatus: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvLog: TextView
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
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        tvPerm = findViewById(R.id.tvPerm)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testPing() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            LogStore.clear(); refreshLog()
        }
        tvPerm.setOnClickListener { openListenerSettings() }

        // status card tap -> open listener settings too
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
            val r = try {
                val conn = java.net.URL("$url/internal/ping").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("x-notif-secret", secret)
                val code = conn.responseCode
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: conn.inputStream.bufferedReader().use { it.readText() }
                if (code in 200..299) "OK — gateway reachable" else "HTTP $code: ${body.take(150)}"
            } catch (e: Exception) {
                "Gagal: ${e.message}"
            }
            runOnUiThread {
                lastPingResult = r
                LogStore.append(ts() + "  PING $url/internal/ping -> $r")
                refreshLog()
                refreshStatus()
            }
        }
    }

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
        refreshLog()
    }

    private fun refreshStatus() {
        val granted = listenerGranted()
        val secretOk = Prefs.secret(this).isNotEmpty()
        val ping = lastPingResult

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
                "Menyimak notifikasi DANA…",
                Color.parseColor("#0B3D2C")
            )
            else -> Triple(
                "Bridge belum jalan",
                "Beri izin dulu, lalu buka DANA sekali",
                Color.parseColor("#D97706")
            )
        }
        tvStatusTitle.text = title
        tvStatusDetail.text = detail
        cardStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun refreshLog() {
        val lines = LogStore.snapshot()
        tvLog.text = if (lines.isEmpty())
            "Belum ada aktivitas.\nBuka app ini lagi setelah ada transfer DANA masuk."
        else lines.joinToString("\n")
    }
}
