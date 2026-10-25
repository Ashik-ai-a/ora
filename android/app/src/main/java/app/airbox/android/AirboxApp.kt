package app.airbox.android

import android.app.Application
import app.airbox.android.platform.Notifications

class AirboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
