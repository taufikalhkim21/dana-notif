package id.alldaypay.dananotifbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted history of every incoming payment the bridge forwarded.
 * Stored as a JSON array in prefs (newest last). Kept small — max 50 entries.
 */
object PaymentStore {
    private const val KEY = "payment_history"
    private const val MAX = 50
    private const val FILE_NAME = "bridge_prefs"

    data class Entry(
        val amount: String,     // "Rp 1.245"
        val matched: Boolean,   // invoice PAID at the gateway
        val source: String,     // app label, e.g. "Livin' Merchant"
        val time: Long          // epoch millis
    )

    fun append(ctx: Context, amount: String, matched: Boolean, source: String) {
        val arr = JSONArray(raw(ctx))
        val o = JSONObject()
            .put("amount", amount)
            .put("matched", matched)
            .put("source", source)
            .put("time", System.currentTimeMillis())
        arr.put(o)
        val trimmed = if (arr.length() > MAX) {
            val keep = JSONArray()
            for (i in arr.length() - MAX until arr.length()) keep.put(arr.get(i))
            keep
        } else arr
        sp(ctx).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun all(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw(ctx))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(
                    o.optString("amount", "-"),
                    o.optBoolean("matched", false),
                    o.optString("source", ""),
                    o.optLong("time", 0L)
                ))
            }
        } catch (_: Exception) { /* corrupt history -> start clean */ }
        return out.reversed() // newest first
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove(KEY).apply()

    private fun raw(ctx: Context): String = sp(ctx).getString(KEY, "[]") ?: "[]"
    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
