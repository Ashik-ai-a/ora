package app.airbox.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.airbox.core.web.WifiQr
import java.net.Inet4Address
import java.net.NetworkInterface

/** Finding the address the iPhone should use, and the no-router fallback (a hotspot). */
object Net {
    /** IPv4 address on Wi-Fi (or on our own hotspot), or null when there is none. */
    fun localAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null && Build.VERSION.SDK_INT >= 23) {
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                cm.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.let { return it.hostAddress }
            }
        }
        // Hotspot interfaces (ap0, swlan0, wlan1...) aren't the "active network"; scan them.
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }

    /**
     * Starts a hotspot only Airbox uses (Android 8+, no internet sharing, no carrier
     * tethering limits). The system picks name and password; we show them as a Wi-Fi QR
     * code the iPhone camera can join. Needs NEARBY_WIFI_DEVICES (13+) or location (8–12).
     */
    class Hotspot(private val context: Context) {
        private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

        fun start(onStarted: (HotspotInfo) -> Unit, onFailed: (String) -> Unit) {
            if (Build.VERSION.SDK_INT < 26) {
                onFailed("This phone is too old for a private hotspot. Use your normal hotspot instead.")
                return
            }
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return onFailed("No Wi-Fi on this phone.")
            try {
                wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        val (ssid, pass) = credentials(res) ?: return onFailed("The hotspot started without a name.")
                        onStarted(HotspotInfo(ssid, pass, WifiQr.encode(ssid, pass)))
                    }

                    override fun onFailed(reason: Int) {
                        onFailed(
                            when (reason) {
                                ERROR_TETHERING_DISALLOWED -> "Hotspots are turned off by your carrier or device policy."
                                ERROR_INCOMPATIBLE_MODE -> "Turn off your normal hotspot first."
                                else -> "The hotspot couldn't start (code $reason)."
                            },
                        )
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                onFailed("Allow Nearby devices (or Location on older Android) so Airbox can start a hotspot.")
            } catch (e: IllegalStateException) {
                onFailed("A hotspot is already running.")
            }
        }

        fun stop() {
            reservation?.close()
            reservation = null
        }

        private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
            if (Build.VERSION.SDK_INT >= 30) {
                val config = res.softApConfiguration
                val ssid = if (Build.VERSION.SDK_INT >= 33) config.wifiSsid?.toString()?.trim('"') else @Suppress("DEPRECATION") config.ssid
                return ssid?.let { it to config.passphrase.orEmpty() }
            }
            @Suppress("DEPRECATION")
            val config = res.wifiConfiguration ?: return null
            @Suppress("DEPRECATION")
            return config.SSID.trim('"') to config.preSharedKey.orEmpty().trim('"')
        }
    }
}
