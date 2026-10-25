package app.airbox.core

import app.airbox.core.connection.BlockedReason
import app.airbox.core.connection.ConnectionPlanner
import app.airbox.core.connection.LocalRadios
import app.airbox.core.connection.PeerCapabilities
import app.airbox.core.connection.Transport
import app.airbox.core.protocol.FileEntry
import app.airbox.core.protocol.FrameReader
import app.airbox.core.protocol.FrameType
import app.airbox.core.protocol.FrameWriter
import app.airbox.core.protocol.Message
import app.airbox.core.protocol.MessageCodec
import app.airbox.core.protocol.Offer
import app.airbox.core.security.PairingLink
import app.airbox.core.security.PairingTokenIssuer
import app.airbox.core.security.SasPairing
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import app.airbox.core.transfer.FileNames
import app.airbox.core.transfer.TransferState
import app.airbox.core.transfer.TransferStateMachine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileNamesTest {
    @Test fun stripsTraversalAndDirectories() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileNames.sanitize("C:\\Windows\\evil.txt"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("hidden", FileNames.sanitize(".hidden"))
    }

    @Test fun removesControlAndBidiCharacters() {
        // "invoice\u202Efdp.exe" displays as "invoiceexe.pdf" in many UIs.
        assertEquals("invoicefdp.exe", FileNames.sanitize("invoice\u202Efdp.exe"))
        assertEquals("a_b", FileNames.sanitize("a\u0000b"))
        assertEquals("a_b_c", FileNames.sanitize("a:b*c"))
    }

    @Test fun guardsReservedNames() {
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"))
    }

    @Test fun truncatesKeepingExtension() {
        val name = FileNames.sanitize("😀".repeat(200) + ".jpeg")
        assertTrue(name.toByteArray().size <= 200)
        assertTrue(name.endsWith(".jpeg"))
        assertFalse(name.removeSuffix(".jpeg").last().isHighSurrogate())
    }

    @Test fun uniquifiesWithoutOverwriting() {
        val existing = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", FileNames.uniquify("photo.jpg") { it in existing })
        assertEquals("notes", FileNames.uniquify("notes") { false })
    }
}

class StateMachineTest {
    @Test fun completedRequiresVerification() {
        val sm = TransferStateMachine(TransferState.TRANSFERRING)
        assertFailsWith<IllegalStateException> { sm.moveTo(TransferState.COMPLETED) }
        sm.moveTo(TransferState.VERIFYING)
        sm.moveTo(TransferState.COMPLETED)
        assertTrue(sm.state.isTerminal)
        assertFailsWith<IllegalStateException> { sm.moveTo(TransferState.CONNECTING) }
    }

    @Test fun pausedTransferCanReconnect() {
        val sm = TransferStateMachine(TransferState.TRANSFERRING)
        sm.moveTo(TransferState.PAUSED)
        sm.moveTo(TransferState.CONNECTING)
        assertEquals(TransferState.CONNECTING, sm.state)
    }
}

class FramingTest {
    @Test fun roundTripsControlMessages() {
        val offer = Offer("abcdefgh12", listOf(FileEntry(0, "a.txt", 3)))
        val out = ByteArrayOutputStream()
        FrameWriter(out).apply { write(FrameType.CONTROL, MessageCodec.encode(offer)); flush() }
        val frame = FrameReader(ByteArrayInputStream(out.toByteArray())).read()
        assertEquals(offer, MessageCodec.decode(frame.payload))
    }

    @Test fun rejectsOversizedFrameBeforeAllocating() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeByte(FrameType.CONTROL.wire); writeInt(Int.MAX_VALUE) }
        val e = assertFailsWith<AirboxException> { FrameReader(ByteArrayInputStream(out.toByteArray())).read() }
        assertEquals(ErrorCode.FRAME_TOO_LARGE, e.code)
    }

    @Test fun rejectsUnknownFrameTypeAndMalformedJson() {
        assertEquals(ErrorCode.PROTOCOL_VIOLATION,
            assertFailsWith<AirboxException> { FrameReader(ByteArrayInputStream(byteArrayOf(9, 0, 0, 0, 0))).read() }.code)
        assertEquals(ErrorCode.PROTOCOL_VIOLATION,
            assertFailsWith<AirboxException> { MessageCodec.decode("{\"type\":\"nope\"}".toByteArray()) }.code)
    }

    @Test fun ignoresUnknownFieldsForForwardCompatibility() {
        val json = """{"type":"file_start","index":2,"offset":10,"futureField":true}"""
        val decoded: Message = MessageCodec.decode(json.toByteArray())
        assertEquals(app.airbox.core.protocol.FileStart(2, 10), decoded)
    }
}

class PairingTest {
    @Test fun sasCodesMatchOnlyWithSameFingerprints() {
        val nI = SasPairing.newNonce()
        val nR = SasPairing.newNonce()
        val a = SasPairing.code("fpA", "fpB", nI, nR)
        assertEquals(a, SasPairing.code("fpA", "fpB", nI, nR))
        assertEquals(6, a.length)
        // A MITM presents its own certificate to each side; the codes diverge.
        val mitmCodes = (0 until 20).map { SasPairing.code("fpA", "fpM", nI, nR) }
        assertNotEquals(a, mitmCodes.first())
    }

    @Test fun commitmentMustMatchReveal() {
        val n = SasPairing.newNonce()
        SasPairing.verifyCommitment(SasPairing.commitment(n), n)
        assertFailsWith<AirboxException> { SasPairing.verifyCommitment(SasPairing.commitment(n), SasPairing.newNonce()) }
    }

    @Test fun tokensAreSingleUseAndExpire() {
        var now = 0L
        val issuer = PairingTokenIssuer(ttlMillis = 1_000) { now }
        val t = issuer.issue()
        assertTrue(issuer.redeem(t))
        assertFalse(issuer.redeem(t))
        val t2 = issuer.issue()
        now = 1_001
        assertFalse(issuer.redeem(t2))
    }

    @Test fun locksOutAfterRepeatedFailures() {
        val issuer = PairingTokenIssuer()
        val good = issuer.issue()
        repeat(PairingTokenIssuer.MAX_FAILURES) { assertFalse(issuer.redeem("guess$it")) }
        assertFalse(issuer.redeem(good))
    }

    @Test fun pairingLinkRoundTrip() {
        val link = PairingLink("192.168.1.20", 47123, "a".repeat(64), "tok_en-1", "Ana's Pixel")
        assertEquals(link, PairingLink.parse(link.toUri()))
        assertFailsWith<IllegalArgumentException> { PairingLink.parse("https://evil.example/pair?v=1") }
        assertFailsWith<IllegalArgumentException> { PairingLink.parse(link.toUri().replace("fp=aaaa", "fp=zzzz")) }
    }
}

class ConnectionPlannerTest {
    private val base = LocalRadios(
        wifiEnabled = true, wifiNetworkId = "net1", bluetoothEnabled = true, nfcAvailable = false,
        localNetworkPermission = true, wifiAwareAvailable = false, wifiDirectAvailable = false,
        canHostHotspot = false, hasCamera = true,
    )
    private val peerSameNet = PeerCapabilities("net1", wifiAware = false, wifiDirect = false, reachableAddress = true)

    @Test fun bluetoothOffStillUsesWifi() {
        val plan = ConnectionPlanner.plan(base.copy(bluetoothEnabled = false), peerSameNet)
        assertEquals(Transport.LOCAL_NETWORK, plan.dataTransports.first())
        assertTrue("Bluetooth is off" in plan.status)
        assertFalse(Transport.BLE_DISCOVERY in plan.discovery)
    }

    @Test fun allRadiosOffIsBlockedWithMinimumAction() {
        val plan = ConnectionPlanner.plan(base.copy(wifiEnabled = false, bluetoothEnabled = false, wifiNetworkId = null), null)
        assertTrue(plan.dataTransports.isEmpty())
        assertEquals(BlockedReason.ALL_RADIOS_OFF, plan.blockedReason)
        assertTrue("Turn on Wi-Fi" in plan.status)
    }

    @Test fun bluetoothAloneNeverClaimsDataTransfer() {
        val plan = ConnectionPlanner.plan(base.copy(wifiEnabled = false, wifiNetworkId = null), null)
        assertTrue(plan.dataTransports.isEmpty())
        assertTrue(Transport.BLE_DISCOVERY in plan.discovery)
    }

    @Test fun localNetworkPermissionDenied() {
        val plan = ConnectionPlanner.plan(base.copy(localNetworkPermission = false), peerSameNet)
        assertEquals(BlockedReason.LOCAL_NETWORK_PERMISSION_DENIED, plan.blockedReason)
    }

    @Test fun differentNetworksFallBackToWifiAware() {
        val radios = base.copy(wifiAwareAvailable = true)
        val peer = PeerCapabilities("net2", wifiAware = true, wifiDirect = false, reachableAddress = false)
        assertEquals(listOf(Transport.WIFI_AWARE), ConnectionPlanner.plan(radios, peer).dataTransports)
    }

    @Test fun noCommonTransportOffersHotspotGuidance() {
        val peer = PeerCapabilities("net2", wifiAware = false, wifiDirect = false, reachableAddress = false)
        val plan = ConnectionPlanner.plan(base, peer)
        assertEquals(BlockedReason.NO_COMMON_TRANSPORT, plan.blockedReason)
        assertTrue("hotspot" in plan.status)
    }
}
