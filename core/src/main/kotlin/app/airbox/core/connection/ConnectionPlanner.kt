package app.airbox.core.connection

/**
 * Transports in preference order. Discovery-only transports (BLE, NFC, QR) never carry
 * file data: they hand over enough information to open one of the data transports.
 */
enum class Transport(val carriesData: Boolean) {
    /** Both devices on the same Wi-Fi / Ethernet LAN, reachable via mDNS (DNS-SD) or a known address. */
    LOCAL_NETWORK(true),
    /** Wi-Fi Aware (NAN): Android 8+ with hardware support; iOS 26+ WiFiAware framework, iPhone 12+. */
    WIFI_AWARE(true),
    /** Android-only Wi-Fi Direct; never available to an iPhone. */
    WIFI_DIRECT(true),
    /** One device hosts a hotspot (user-approved) and the other joins it. */
    HOTSPOT(true),
    /** BLE advertisement used to find a peer and exchange a connection token, then upgrade. */
    BLE_DISCOVERY(false),
    /** NFC tap that passes a pairing link. Android only for third-party apps writing/reading NDEF. */
    NFC_TAP(false),
    /** QR code / deep link carrying address, fingerprint and one-time token. */
    QR_LINK(false),
}

/** What the OS tells us about this device right now. Built by the platform layer. */
data class LocalRadios(
    val wifiEnabled: Boolean,
    /** Opaque identifier of the joined network (e.g. hashed BSSID/SSID), or null if not joined. */
    val wifiNetworkId: String?,
    val bluetoothEnabled: Boolean,
    val nfcAvailable: Boolean,
    val localNetworkPermission: Boolean,
    val wifiAwareAvailable: Boolean,
    val wifiDirectAvailable: Boolean,
    val canHostHotspot: Boolean,
    val hasCamera: Boolean,
)

/** What we know about the peer, from a discovery record or a scanned QR code. */
data class PeerCapabilities(
    val wifiNetworkId: String?,
    val wifiAware: Boolean,
    val wifiDirect: Boolean,
    val reachableAddress: Boolean,
)

data class ConnectionPlan(
    /** Data transports to try, best first. Empty means no transfer is currently possible. */
    val dataTransports: List<Transport>,
    /** Ways to find/introduce the peer that work right now. */
    val discovery: List<Transport>,
    /** Shown to the user. Honest, and names the single smallest action when blocked. */
    val status: String,
    val blockedReason: BlockedReason? = null,
)

enum class BlockedReason { ALL_RADIOS_OFF, LOCAL_NETWORK_PERMISSION_DENIED, NO_COMMON_TRANSPORT }

/**
 * Pure transport selection. No radio is ever switched on here; when nothing usable is left
 * we say what the user must do instead of pretending.
 */
object ConnectionPlanner {

    fun plan(local: LocalRadios, peer: PeerCapabilities?): ConnectionPlan {
        if (!local.wifiEnabled && !local.bluetoothEnabled) {
            return ConnectionPlan(
                emptyList(), emptyList(),
                "Wi-Fi and Bluetooth are both off, so nearby sharing can't work. Turn on Wi-Fi to continue.",
                BlockedReason.ALL_RADIOS_OFF,
            )
        }

        val data = mutableListOf<Transport>()
        val lanUsable = local.wifiEnabled && local.wifiNetworkId != null && local.localNetworkPermission
        if (lanUsable && (peer == null || peer.reachableAddress || peer.wifiNetworkId == local.wifiNetworkId)) {
            data += Transport.LOCAL_NETWORK
        }
        if (local.wifiEnabled && local.wifiAwareAvailable && (peer == null || peer.wifiAware)) data += Transport.WIFI_AWARE
        if (local.wifiEnabled && local.wifiDirectAvailable && (peer == null || peer.wifiDirect)) data += Transport.WIFI_DIRECT
        if (local.canHostHotspot) data += Transport.HOTSPOT

        val discovery = buildList {
            if (lanUsable) add(Transport.LOCAL_NETWORK)
            if (local.wifiEnabled && local.wifiAwareAvailable) add(Transport.WIFI_AWARE)
            if (local.bluetoothEnabled) add(Transport.BLE_DISCOVERY)
            if (local.nfcAvailable) add(Transport.NFC_TAP)
            if (local.hasCamera || lanUsable) add(Transport.QR_LINK)
        }

        if (data.isEmpty()) {
            val (message, reason) = when {
                local.wifiEnabled && local.wifiNetworkId != null && !local.localNetworkPermission ->
                    "Airbox needs Local Network access to reach nearby devices. Allow it in Settings." to
                        BlockedReason.LOCAL_NETWORK_PERMISSION_DENIED
                !local.wifiEnabled ->
                    "Bluetooth can find nearby devices but is too slow to carry files. Turn on Wi-Fi to send." to
                        BlockedReason.NO_COMMON_TRANSPORT
                else ->
                    "The devices can't reach each other directly. Join the same Wi-Fi network, or turn on a hotspot on one device." to
                        BlockedReason.NO_COMMON_TRANSPORT
            }
            return ConnectionPlan(emptyList(), discovery, message, reason)
        }

        val status = when (data.first()) {
            Transport.LOCAL_NETWORK -> if (local.bluetoothEnabled) "Ready — using Wi-Fi." else "Ready — using Wi-Fi (Bluetooth is off, not needed)."
            Transport.WIFI_AWARE -> "Ready — direct Wi-Fi connection, no network needed."
            Transport.WIFI_DIRECT -> "Ready — Wi-Fi Direct."
            Transport.HOTSPOT -> "No shared network. You can share through a hotspot on this device."
            else -> error("non-data transport selected")
        }
        return ConnectionPlan(data, discovery, status)
    }
}
