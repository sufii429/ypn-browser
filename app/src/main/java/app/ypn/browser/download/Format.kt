package app.ypn.browser.download

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/** "412 MB", "1.2 GB" — whole numbers once the value reaches 100. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = minOf((log10(bytes.toDouble()) / log10(1024.0)).toInt(), units.size)
    val value = bytes / 1024.0.pow(exp)
    return if (value >= 100) {
        String.format(Locale.US, "%.0f %s", value, units[exp - 1])
    } else {
        String.format(Locale.US, "%.1f %s", value, units[exp - 1])
    }
}

/** "2.3 MB/s" for a bytes-per-second rate. */
fun formatSpeed(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

/** "18 Mbps" / "7.5 Mbps" — the home card's combined-speed line. */
fun formatMbps(bytesPerSec: Long): String {
    val mbps = bytesPerSec * 8.0 / 1_000_000.0
    return if (mbps >= 10) {
        String.format(Locale.US, "%.0f Mbps", mbps)
    } else {
        String.format(Locale.US, "%.1f Mbps", mbps)
    }
}
