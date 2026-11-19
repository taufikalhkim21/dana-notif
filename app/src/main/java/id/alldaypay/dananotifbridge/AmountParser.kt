package id.alldaypay.dananotifbridge

/**
 * Parses the transfer amount out of a DANA notification text, e.g.
 * "Kamu menerima Rp 25.000 dari ..." -> 25000.
 * Handles "Rp 25.000", "Rp25.000", "Rp 1.116", lowercase "rp", and plain
 * digits without separators.
 */
object AmountParser {
    // Match "Rp" (optional space) followed by digits/commas/dots; take the
    // longest plausible group so "Rp25.000" doesn't split weirdly.
    private val RX = Regex("""rp[\s\u00A0]*([0-9][0-9.,\u00A0\s]{0,11}[0-9])""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Long? {
        val m = RX.find(text) ?: return null
        val raw = m.groupValues[1]
            .replace("\u00A0", "")
            .replace(" ", "")
        // Indonesian format: dot = thousands separator, comma may be decimals.
        val cleaned = if (raw.contains('.') && raw.contains(',')) {
            raw.replace(",", "").replace(".", "")
        } else if (raw.contains('.')) {
            raw.replace(".", "")
        } else {
            raw.replace(",", "")
        }
        return cleaned.toLongOrNull()
    }

    fun formatRupiah(amount: Long): String {
        val s = amount.toString().reversed().chunked(3).joinToString(".").reversed()
        return "Rp $s"
    }
}
