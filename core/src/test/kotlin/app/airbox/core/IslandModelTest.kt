package app.airbox.core

import app.airbox.core.live.IslandAction
import app.airbox.core.live.IslandEvent
import app.airbox.core.live.IslandItem
import app.airbox.core.live.IslandKind
import app.airbox.core.live.IslandModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandModelTest {
    private var now = 1_000_000L
    private val model = IslandModel("Pixel", clock = { now })

    private fun progress(done: Long, id: String = "t1") =
        IslandEvent.TransferProgress(id, incoming = false, fileName = "video.mov", fileCount = 1, done = done, total = 1000, bytesPerSecond = 100.0)

    @Test fun transferShowsPercentAndEta() {
        assertTrue(model.apply(progress(420)))
        val item = model.visible.single()
        assertEquals("42%", item.compact)
        assertEquals("To Pixel", item.title)
        assertEquals("video.mov · 5s left", item.subtitle)
        assertEquals(listOf(IslandAction.CANCEL), item.actions)
    }

    @Test fun progressUpdatesAreThrottled() {
        model.apply(progress(100))
        now += 100
        assertFalse(model.apply(progress(110)), "tiny change within a second is not pushed")
        now += 100
        assertTrue(model.apply(progress(200)), "a 5% jump is pushed right away")
        now += 1_000
        assertTrue(model.apply(progress(210)), "after a second, any change is pushed")
    }

    @Test fun callOutranksTransferAndOnlyTwoAreVisible() {
        model.apply(progress(100))
        model.apply(IslandEvent.NowPlaying("Song", "Artist", playing = true))
        model.apply(IslandEvent.CallStarted("c1", "Mum"))
        assertEquals(listOf(IslandKind.INCOMING_CALL, IslandKind.TRANSFER), model.visible.map { it.kind })
        assertEquals(3, model.all.size)
        model.apply(IslandEvent.CallEnded("c1"))
        assertEquals(listOf(IslandKind.TRANSFER, IslandKind.NOW_PLAYING), model.visible.map { it.kind })
    }

    @Test fun briefItemsExpireOnTheirOwn() {
        model.apply(IslandEvent.ClipboardSent("Meet at Gate 22"))
        assertEquals("Copied to Pixel", model.visible.single().title)
        now += 4_001
        assertTrue(model.apply(IslandEvent.Tick(now)))
        assertTrue(model.visible.isEmpty())
    }

    @Test fun finishedTransferLingersThenLeaves() {
        model.apply(progress(1000))
        model.apply(IslandEvent.TransferFinished("t1", incoming = true, ok = true, summary = "3 photos"))
        assertEquals("✓", model.visible.single().compact)
        now += 6_001
        model.apply(IslandEvent.Tick(now))
        assertTrue(model.visible.isEmpty())
    }

    @Test fun batteryNeverAppearsAloneButRidesAlong() {
        assertFalse(model.apply(IslandEvent.PeerBattery(64)))
        assertTrue(model.visible.isEmpty())
        model.apply(IslandEvent.RingStarted())
        assertEquals(64, model.visible.single().peerBatteryPercent)
        assertEquals(listOf(IslandAction.STOP_RING), model.visible.single().actions)
    }

    @Test fun nothingOutlivesEightHours() {
        model.apply(IslandEvent.NowPlaying("Long playlist", null, playing = true))
        now += 8 * 60 * 60_000L
        model.apply(IslandEvent.Tick(now))
        assertTrue(model.visible.isEmpty())
    }

    @Test fun encodedStateStaysUnderActivityKitLimit() {
        val huge = IslandItem("x", IslandKind.CLIPBOARD, "✓", "t".repeat(5_000), "s".repeat(5_000), peerName = "Pixel", startedAtEpochMs = 0)
        assertTrue(IslandModel.encode(huge).toByteArray().size <= IslandModel.MAX_STATE_BYTES)
        model.apply(IslandEvent.ClipboardReceived("x".repeat(500)))
        assertTrue(model.visible.single().subtitle.length <= 60)
    }

    @Test fun encodedItemHasEveryFieldSwiftNeeds() {
        model.apply(IslandEvent.RingStarted())
        val json = IslandModel.encode(model.visible.single())
        for (field in listOf("id", "kind", "compact", "title", "subtitle", "actions", "peerName", "startedAtEpochMs")) {
            assertTrue("\"$field\"" in json, "missing $field in $json")
        }
        assertTrue("\"kind\":\"FIND_DEVICE\"" in json && "\"actions\":[\"STOP_RING\"]" in json)
    }
}
