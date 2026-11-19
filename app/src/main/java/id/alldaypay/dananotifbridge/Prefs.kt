package id.alldaypay.dananotifbridge

import android.content.Context

object Prefs {
    private const val FILE = "bridge_prefs"
    const val DEFAULT_URL = "http://192.168.1.17:3000"

    // Default watched apps: DANA + DANA Kasir (merchant/Bisnis — this is the
    // one that actually receives "Pembayaran Masuk ... diterima DANA Bisnis",
    // package id.dana.kasir) + Livin' by Mandiri + Livin' Merchant.
    // User can change this in-app (app picker, like MacroDroid).
    const val DEFAULT_PACKAGES =
        "id.dana,id.dana.kasir,com.bankmandiri.livinapp,com.bankmandiri.livinmerchant"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun gatewayUrl(ctx: Context): String = sp(ctx).getString("gateway_url", DEFAULT_URL)!!.trimEnd('/')
    fun setGatewayUrl(ctx: Context, v: String) {
        sp(ctx).edit().putString("gateway_url", v.trim().trimEnd('/')).apply()
    }

    fun secret(ctx: Context): String = sp(ctx).getString("notif_secret", "")!!
    fun setSecret(ctx: Context, v: String) = sp(ctx).edit().putString("notif_secret", v.trim()).apply()

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    // Watchdog heartbeat interval in minutes (default 3, gateway alerts at 40).
    fun intervalMinutes(ctx: Context): Int = sp(ctx).getInt("interval_minutes", 3)
    fun setIntervalMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("interval_minutes", v.coerceIn(1, 14)).apply()

    // Custom body template (MacroDroid style). Placeholders: {APP}, {TITLE},
    // {TEXT}, {AMOUNT}. Default mimics the exact format MacroDroid used —
    // proven parsed & matched by the gateway ("Pembayaran MasukRp1.175
    // diterima DANA Bisnis."). User can edit it in-app if a format differs.
    fun messageTemplate(ctx: Context): String =
        sp(ctx).getString("message_template", "Pembayaran Masuk{AMOUNT} diterima {APP}.")!!
    fun setMessageTemplate(ctx: Context, v: String) = sp(ctx).edit().putString("message_template", v).apply()

    // Packages whose notifications get forwarded (user-selected, MacroDroid style).
    fun watchedPackages(ctx: Context): Set<String> {
        val raw = sp(ctx).getString("watched_packages", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        val set = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return if (set.isEmpty()) DEFAULT_PACKAGES.split(",").toSet() else set
    }

    fun setWatchedPackages(ctx: Context, pkgs: Set<String>) {
        sp(ctx).edit().putString("watched_packages", pkgs.joinToString(",")).apply()
    }

    // ---- stats & last payment ----
    fun statSent(ctx: Context): Int = sp(ctx).getInt("stat_sent", 0)
    fun statMatch(ctx: Context): Int = sp(ctx).getInt("stat_match", 0)
    fun incStatSent(ctx: Context) = sp(ctx).edit().putInt("stat_sent", statSent(ctx) + 1).apply()
    fun incStatMatch(ctx: Context) = sp(ctx).edit().putInt("stat_match", statMatch(ctx) + 1).apply()

    fun setLastPayment(ctx: Context, amountText: String, matched: Boolean, ts: Long) {
        sp(ctx).edit()
            .putString("last_amount", amountText)
            .putBoolean("last_matched", matched)
            .putLong("last_time", ts)
            .apply()
    }
    fun lastAmount(ctx: Context): String = sp(ctx).getString("last_amount", "") ?: ""
    fun lastMatched(ctx: Context): Boolean = sp(ctx).getBoolean("last_matched", false)
    fun lastTime(ctx: Context): Long = sp(ctx).getLong("last_time", 0L)
}
