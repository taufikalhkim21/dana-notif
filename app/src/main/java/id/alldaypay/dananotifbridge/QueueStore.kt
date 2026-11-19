package id.alldaypay.dananotifbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent offline queue for payment notifications. If the gateway is
 * unreachable, failed notifications are stored here and re-sent when the
 * network comes back (drained by the listener reconnect + heartbeat tick).
 * Payment data must never be dropped.
 */
object QueueStore {
    private const val KEY = "pending_queue"
    private const val MAX = 100

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bridge_queue", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(ctx: Context, text: String, hash: String) {
        val arr = JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")
        // avoid exact duplicates already queued
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("hash") == hash) return
        }
        val item = JSONObject().put("text", text).put("hash", hash).put("ts", System.currentTimeMillis())
        arr.put(item)
        while (arr.length() > MAX) arr.remove(0)
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Returns and clears all queued items. */
    @Synchronized
    fun drain(ctx: Context): List<Pair<String, String>> {
        val arr = JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(o.optString("text") to o.optString("hash"))
        }
        if (arr.length() > 0) sp(ctx).edit().putString(KEY, "[]").apply()
        return out
    }

    @Synchronized
    fun size(ctx: Context): Int =
        (JSONArray(sp(ctx).getString(KEY, "[]") ?: "[]")).length()

    /** Puts items back at the front if a drain-retry failed (oldest first). */
    @Synchronized
    fun requeue(ctx: Context, items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val arr = JSONArray()
        for ((text, hash) in items) arr.put(JSONObject().put("text", text).put("hash", hash).put("ts", 0))
        sp(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
