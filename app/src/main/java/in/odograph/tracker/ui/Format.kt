package `in`.odograph.tracker.ui

fun mpsToKmh(mps: Float): Float = mps * 3.6f

fun formatHhMm(seconds: Long): String {
    val m = seconds / 60
    return "%02d:%02d".format(m / 60, m % 60)
}

fun formatKm(metres: Double): String = "%.1f".format(metres / 1000.0)
