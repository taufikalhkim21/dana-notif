package id.alldaypay.dananotifbridge

import android.content.Context

object Prefs {
    private const val FILE = "bridge_prefs"
    const val DEFAULT_URL = "http://192.168.1.17:3000"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun gatewayUrl(ctx: Context): String = sp(ctx).getString("gateway_url", DEFAULT_URL)!!.trimEnd('/')
    fun setGatewayUrl(ctx: Context, v: String) {
        sp(ctx).edit().putString("gateway_url", v.trim().trimEnd('/')).apply()
    }

    fun secret(ctx: Context): String = sp(ctx).getString("notif_secret", "")!!
    fun setSecret(ctx: Context, v: String) = sp(ctx).edit().putString("notif_secret", v.trim()).apply()

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    // Watchdog heartbeat interval in minutes (default 3, gateway alerts at 15).
    fun intervalMinutes(ctx: Context): Int = sp(ctx).getInt("interval_minutes", 3)
    fun setIntervalMinutes(ctx: Context, v: Int) = sp(ctx).edit().putInt("interval_minutes", v.coerceIn(1, 14)).apply()
}
