package app.airbox.core

import app.airbox.core.companion.Ams
import app.airbox.core.companion.Ancs
import app.airbox.core.companion.AncsBridge
import app.airbox.core.companion.CompanionLink
import app.airbox.core.companion.LinkListener
import app.airbox.core.companion.NotificationPolicy
import app.airbox.core.net.DeviceIdentity
import app.airbox.core.net.IncomingRouter
import app.airbox.core.net.ReceiverServer
import app.airbox.core.net.TlsTransport
import app.airbox.core.protocol.BatteryStatus
import app.airbox.core.protocol.ClipboardPush
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.LinkFeature
import app.airbox.core.protocol.MediaCommand
import app.airbox.core.protocol.MediaCommandRequest
import app.airbox.core.protocol.Message
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.MirroredNotification
import app.airbox.core.protocol.NotificationActionInfo
import app.airbox.core.protocol.NotificationActionRequest
import app.airbox.core.protocol.NotificationKind
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.Platform
import app.airbox.core.security.InMemoryTrustStore
import app.airbox.core.security.PairingTokenIssuer
import app.airbox.core.security.TrustedDevice
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.DirectoryReceiveStorage
import app.airbox.core.transfer.ErrorCode
import app.airbox.core.transfer.OfferDecision
import app.airbox.core.transfer.PathOutgoingFile
import app.airbox.core.transfer.ReceivePrompt
import app.airbox.core.transfer.ReceiverSession
import app.airbox.core.transfer.SenderAuth
import app.airbox.core.transfer.SenderSession
import app.airbox.core.transfer.TransferState
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class AncsCodecTest {
    @Test fun parsesNotificationSource() {
        // Added, flags Important|PositiveAction|NegativeAction, IncomingCall, count 1, uid 0x01020304 (LE)
        val e = Ancs.parseSource(bytes(0, 0x1A, 1, 1, 0x04, 0x03, 0x02, 0x01))
        assertEquals(Ancs.EventId.ADDED, e.event)
        assertEquals(Ancs.Category.INCOMING_CALL, e.category)
        assertEquals(0x01020304L, e.uid)
        assertTrue(e.important && e.hasPositiveAction && e.hasNegativeAction)
        assertFalse(e.silent || e.preExisting)
        assertEquals(0xFFFFFFFFL, Ancs.parseSource(bytes(2, 0, 0, 0, 0xff, 0xff, 0xff, 0xff)).uid)
    }

    @Test fun encodesGetNotificationAttributes() {
        val cmd = Ancs.getNotificationAttributes(
            0x0A0B0C0DL, listOf(Ancs.NotificationAttribute.APP_IDENTIFIER, Ancs.NotificationAttribute.TITLE), maxLength = 0x0140,
        )
        assertContentEquals(bytes(0, 0x0D, 0x0C, 0x0B, 0x0A, 0, 1, 0x40, 0x01), cmd)
    }

    @Test fun encodesActionsAndAppAttributes() {
        assertContentEquals(bytes(2, 7, 0, 0, 0, 1), Ancs.performAction(7, Ancs.Action.NEGATIVE))
        assertContentEquals(byteArrayOf(1) + "com.apple.MobileSMS".toByteArray() + bytes(0, 0), Ancs.getAppAttributes("com.apple.MobileSMS"))
    }

    @Test fun reassemblesResponseSplitAcrossGattPackets() {
        val response = notificationResponse(7, mapOf(0 to "com.apple.MobileSMS", 1 to "Ana", 3 to "See you at 7?", 5 to "20261025T183000"))
        val a = Ancs.DataSourceAssembler().apply { expectNotificationAttributes(4) }
        val chunks = response.toList().chunked(20).map { it.toByteArray() }
        chunks.dropLast(1).forEach { assertNull(a.feed(it)) }
        val parsed = a.feed(chunks.last()) as Ancs.NotificationAttributes
        assertEquals(7, parsed.uid)
        assertEquals("Ana", parsed.title)
        assertEquals("See you at 7?", parsed.message)
        assertEquals(2026, parsed.date!!.year)
        assertEquals(18, parsed.date!!.hour)
    }

    companion object {
        fun notificationResponse(uid: Long, attrs: Map<Int, String>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0)
            for (i in 0 until 4) out.write(((uid shr (8 * i)) and 0xff).toInt())
            attrs.forEach { (id, v) ->
                val b = v.toByteArray()
                out.write(id); out.write(b.size and 0xff); out.write(b.size shr 8); out.write(b)
            }
            return out.toByteArray()
        }

        fun appResponse(appId: String, name: String): ByteArray {
            val n = name.toByteArray()
            return byteArrayOf(1) + appId.toByteArray() + bytes(0, 0, n.size and 0xff, n.size shr 8) + n
        }
    }
}

class AncsBridgeTest {
    /** A fake iPhone answering Control Point writes on the Data Source in 20-byte packets. */
    private class FakeIphone {
        val notifications = mutableMapOf<Long, Map<Int, String>>()
        val writes = mutableListOf<ByteArray>()
        lateinit var bridge: AncsBridge
        fun onWrite(cmd: ByteArray) {
            writes += cmd
            val response = when (cmd[0].toInt()) {
                0 -> {
                    val uid = (cmd[1].toLong() and 0xff) or ((cmd[2].toLong() and 0xff) shl 8) or
                        ((cmd[3].toLong() and 0xff) shl 16) or ((cmd[4].toLong() and 0xff) shl 24)
                    val all = notifications.getValue(uid)
                    // Respond in the requested order, with every requested attribute (empty if absent).
                    val ids = mutableListOf<Int>()
                    var i = 5
                    while (i < cmd.size) { val id = cmd[i].toInt(); ids += id; i += if (id in 1..3) 3 else 1 }
                    AncsCodecTest.notificationResponse(uid, ids.associateWith { all[it] ?: "" })
                }
                1 -> AncsCodecTest.appResponse(String(cmd, 1, cmd.size - 3), "Messages")
                else -> return
            }
            response.toList().chunked(20).forEach { bridge.onData(it.toByteArray()) }
        }
    }

    @Test fun mirrorsIphoneMessageAndCallWithActions() {
        val phone = FakeIphone()
        val shown = mutableListOf<MirroredNotification>()
        val removed = mutableListOf<String>()
        phone.bridge = AncsBridge(phone::onWrite, { shown += it }, { removed += it }, ZoneOffset.UTC)
        phone.notifications[1] = mapOf(0 to "com.apple.MobileSMS", 1 to "Ana", 3 to "Running late", 5 to "20261025T120000")
        phone.notifications[2] = mapOf(0 to "com.apple.mobilephone", 1 to "Mum", 6 to "Answer", 7 to "Decline")

        phone.bridge.onSource(bytes(0, 0, 4, 1, 1, 0, 0, 0)) // social message
        phone.bridge.onSource(bytes(0, 0x18, 1, 1, 2, 0, 0, 0)) // incoming call with both actions
        phone.bridge.onSource(bytes(0, 0x04, 0, 1, 3, 0, 0, 0)) // pre-existing: ignored

        assertEquals(2, shown.size)
        val msg = shown[0]
        assertEquals("Messages", msg.appName)
        assertEquals("Ana", msg.title)
        assertEquals("Running late", msg.text)
        assertEquals(NotificationKind.MESSAGE, msg.kind)
        assertEquals(1_792_929_600_000L, msg.postedAtEpochMs)
        val call = shown[1]
        assertEquals(NotificationKind.CALL, call.kind)
        assertEquals(listOf("Answer", "Decline"), call.actions.map { it.label })

        assertTrue(phone.bridge.perform(call.key, AncsBridge.NEGATIVE))
        assertContentEquals(bytes(2, 2, 0, 0, 0, 1), phone.writes.last())

        phone.bridge.onSource(bytes(2, 0, 1, 0, 2, 0, 0, 0))
        assertEquals(listOf(call.key), removed)
        assertFalse(phone.bridge.perform(call.key, AncsBridge.POSITIVE), "removed notification can't be acted on")
    }
}

class AmsCodecTest {
    @Test fun encodesAndParses() {
        assertContentEquals(bytes(2), Ams.command(Ams.Command.TOGGLE_PLAY_PAUSE))
        assertContentEquals(bytes(2, 0, 2), Ams.subscribe(Ams.Entity.TRACK, Ams.TrackAttr.ARTIST, Ams.TrackAttr.TITLE))
        val u = Ams.parseEntityUpdate(bytes(2, 2, 1) + "Bohemian Rhapsody".toByteArray())
        assertEquals(Ams.Entity.TRACK, u.entity)
        assertTrue(u.truncated)
        assertEquals("Bohemian Rhapsody", u.value)
        val info = Ams.parsePlaybackInfo("1,1.0,42.5")
        assertEquals(Ams.PlaybackState.PLAYING, info.state)
        assertEquals(42.5, info.elapsedSeconds)
        assertEquals(setOf(Ams.Command.PLAY, Ams.Command.NEXT_TRACK), Ams.parseSupportedCommands(bytes(0, 3, 99)))
    }
}

class NotificationPolicyTest {
    private val base = MirroredNotification("k", "com.whatsapp", "WhatsApp", "Ana", "hi", 0,
        actions = listOf(NotificationActionInfo("reply", "Reply", acceptsText = true)))

    @Test fun nothingIsMirroredUnlessTheUserOptsIn() {
        assertNull(NotificationPolicy().apply(base))
        assertNotNull(NotificationPolicy(allowedApps = setOf("com.whatsapp")).apply(base))
    }

    @Test fun hiddenContentDropsTextAndReply() {
        val n = NotificationPolicy(allowedApps = setOf("com.whatsapp"), hideContentApps = setOf("com.whatsapp")).apply(base)!!
        assertEquals("New notification", n.text)
        assertTrue(n.actions.isEmpty())
    }

    @Test fun masksOneTimeCodesButNotOrdinaryNumbers() {
        val p = NotificationPolicy(allowedApps = setOf("bank"))
        val otp = p.apply(base.copy(appId = "bank", title = "Bank", text = "Your verification code is 482913"))!!
        assertEquals("Your verification code is ••••••", otp.text)
        val normal = p.apply(base.copy(appId = "bank", title = "Bank", text = "Payment of 2500 received"))!!
        assertEquals("Payment of 2500 received", normal.text)
    }
}

/** Android and iPhone, already paired, holding a live link over real TLS. */
class CompanionLinkTest {
    private val iphoneId = DeviceIdentity.generate()
    private val androidId = DeviceIdentity.generate()
    private val iphoneTrust = InMemoryTrustStore().apply { trust(TrustedDevice(androidId.fingerprint, "Pixel", 0)) }
    private val iphoneInbox = LinkedBlockingQueue<Message>()
    private val androidInbox = LinkedBlockingQueue<Message>()
    private val disconnects = LinkedBlockingQueue<String>()
    private val iphoneLinks = LinkedBlockingQueue<CompanionLink>()
    private val tmp = Files.createTempDirectory("airbox-link")

    private fun hello(name: String, p: Platform) = Hello(PROTOCOL_VERSION, java.util.UUID.randomUUID().toString(), name, p)

    private fun listener(inbox: LinkedBlockingQueue<Message>, who: String) = object : LinkListener {
        override fun onMessage(link: CompanionLink, message: Message) { inbox += message }
        override fun onDisconnected(link: CompanionLink, error: ErrorCode?) { disconnects += who }
    }

    // iPhone can't read Android-style media sessions of other apps, so it doesn't offer MEDIA_CONTROL here.
    private val iphoneFeatures = setOf(LinkFeature.NOTIFICATIONS, LinkFeature.CLIPBOARD, LinkFeature.BATTERY, LinkFeature.FIND_DEVICE)
    private val transfers = LinkedBlockingQueue<TransferState>()

    private val router = IncomingRouter(
        hello("iPhone", Platform.IOS), iphoneTrust, iphoneFeatures, listener(iphoneInbox, "iphone"),
        onLink = { iphoneLinks += it },
        runTransfer = { channel, peer ->
            val prompt = object : ReceivePrompt {
                override fun confirmSasCode(peer: Hello, code: String) = true
                override fun decideOffer(peer: Hello, offer: app.airbox.core.protocol.Offer, peerTrusted: Boolean) = OfferDecision(true)
            }
            transfers += ReceiverSession(channel, hello("iPhone", Platform.IOS), DirectoryReceiveStorage(tmp), iphoneTrust,
                PairingTokenIssuer(), prompt, peerHello = peer).run().state
        },
    )
    private val server = ReceiverServer(TlsTransport(iphoneId), 0, handler = router::handle, bindAddress = "127.0.0.1").start()
    private val androidTls = TlsTransport(androidId)

    @AfterTest fun cleanup() { server.close(); tmp.toFile().deleteRecursively() }

    private fun connect(identity: TlsTransport = androidTls, features: Set<LinkFeature> = LinkFeature.entries.toSet(), ping: Long = 15_000) =
        CompanionLink.connect(
            MessageChannel(identity.connectPinned("127.0.0.1", server.port, iphoneId.fingerprint)),
            hello("Pixel", Platform.ANDROID), features, listener(androidInbox, "android"), ping,
        )

    @Test fun notificationsRepliesAndClipboardFlowBothWays() {
        val android = connect()
        val iphone = assertNotNull(iphoneLinks.poll(5, TimeUnit.SECONDS))
        assertEquals(iphoneFeatures, android.features)
        assertEquals(iphoneFeatures, iphone.features)

        // Android -> iPhone: a WhatsApp message with an inline reply action.
        val n = MirroredNotification("wa:1", "com.whatsapp", "WhatsApp", "Ana", "Dinner?", 1L, NotificationKind.MESSAGE,
            listOf(NotificationActionInfo("reply", "Reply", acceptsText = true)))
        android.send(n)
        assertEquals(n, iphoneInbox.poll(5, TimeUnit.SECONDS))

        // iPhone user types a reply in the notification; Android receives it to fire RemoteInput.
        iphone.send(NotificationActionRequest("wa:1", "reply", "Yes, 8pm"))
        assertEquals(NotificationActionRequest("wa:1", "reply", "Yes, 8pm"), androidInbox.poll(5, TimeUnit.SECONDS))

        iphone.send(ClipboardPush("https://example.com/recipe"))
        assertEquals(ClipboardPush("https://example.com/recipe"), androidInbox.poll(5, TimeUnit.SECONDS))
        android.send(BatteryStatus(64, charging = false))
        assertEquals(BatteryStatus(64, false), iphoneInbox.poll(5, TimeUnit.SECONDS))

        android.close()
        assertEquals("iphone", disconnects.poll(5, TimeUnit.SECONDS).let { if (it == "android") disconnects.poll(5, TimeUnit.SECONDS) else it })
    }

    @Test fun unsupportedFeatureIsNotFaked() {
        val android = connect()
        assertFailsWith<IllegalStateException> { android.send(MediaCommandRequest(MediaCommand.NEXT)) }
        android.close()
    }

    @Test fun strangerCannotOpenALink() {
        val stranger = TlsTransport(DeviceIdentity.generate())
        val e = assertFailsWith<AirboxException> { connect(stranger) }
        assertEquals(ErrorCode.PEER_NOT_TRUSTED, e.code)
        assertNull(iphoneLinks.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun fileTransferWorksWhileLinkIsOpen() {
        val link = connect()
        assertNotNull(iphoneLinks.poll(5, TimeUnit.SECONDS))
        val src = tmp.resolve("src.bin").also { Files.write(it, ByteArray(300_000) { i -> i.toByte() }) }
        val stream = androidTls.connectPinned("127.0.0.1", server.port, iphoneId.fingerprint)
        val out = SenderSession(MessageChannel(stream), hello("Pixel", Platform.ANDROID), listOf(PathOutgoingFile(src)),
            SenderAuth.TrustedOnly, receiverVerified = true).run()
        assertEquals(TransferState.COMPLETED, out.state)
        assertEquals(TransferState.COMPLETED, transfers.poll(5, TimeUnit.SECONDS))
        assertTrue(link.isOpen)
        link.close()
    }

    @Test fun keepalivesHoldAnIdleLinkOpen() {
        val link = connect(ping = 200)
        assertNotNull(iphoneLinks.poll(5, TimeUnit.SECONDS))
        // The iPhone pings every 15 s, Android every 200 ms. Android must wait on the iPhone's
        // cadence, and the iPhone must not time out an Android that pings often.
        Thread.sleep(1_500)
        assertTrue(link.isOpen)
        assertNull(disconnects.poll())
        link.close()
    }
}
