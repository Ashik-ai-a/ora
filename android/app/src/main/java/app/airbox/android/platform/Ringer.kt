package app.airbox.android.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

/** "Find my phone" from the iPhone: rings on the alarm stream so it's heard in silent mode. */
object Ringer {
    private var ringtone: Ringtone? = null

    fun start(context: Context) {
        stop()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null
    }

    val ringing: Boolean get() = ringtone?.isPlaying == true
}
