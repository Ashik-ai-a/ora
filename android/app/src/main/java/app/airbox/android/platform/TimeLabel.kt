package app.airbox.android.platform

import android.text.format.DateFormat
import java.util.Calendar
import java.util.TimeZone

/** Short, human times for lists: "Today 14:32", "Yesterday 09:10", "Mon 18:05", "12 Oct, 09:10". */
object TimeLabel {
    /** Follows the phone's 12/24-hour setting; set when the UI starts. */
    @Volatile var is24h: Boolean = true

    fun of(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val zone = TimeZone.getDefault()
        val then = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        val today = Calendar.getInstance(zone).apply { timeInMillis = now }
        val time = DateFormat.format(if (is24h) "HH:mm" else "h:mm a", then).toString()
        val dayDiff = ((startOfDay(today) - startOfDay(then)) / 86_400_000L).toInt()
        return when {
            dayDiff == 0 -> "Today $time"
            dayDiff == 1 -> "Yesterday $time"
            dayDiff in 2..6 -> DateFormat.format("EEE", then).toString() + " $time"
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> DateFormat.format("d MMM", then).toString() + ", $time"
            else -> DateFormat.format("d MMM yyyy", then).toString() + ", $time"
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
