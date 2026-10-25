package app.airbox.core.companion

import java.util.UUID

/**
 * Apple Media Service (AMS) client codec: lets the Android app show what's playing on the
 * iPhone and control it (play/pause/skip/volume), the same way car stereos and watches do.
 */
object Ams {
    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

    enum class Command {
        PLAY, PAUSE, TOGGLE_PLAY_PAUSE, NEXT_TRACK, PREVIOUS_TRACK, VOLUME_UP, VOLUME_DOWN,
        ADVANCE_REPEAT_MODE, ADVANCE_SHUFFLE_MODE, SKIP_FORWARD, SKIP_BACKWARD, LIKE_TRACK,
        DISLIKE_TRACK, BOOKMARK_TRACK,
    }

    enum class Entity { PLAYER, QUEUE, TRACK }

    object PlayerAttr { const val NAME = 0; const val PLAYBACK_INFO = 1; const val VOLUME = 2 }
    object TrackAttr { const val ARTIST = 0; const val ALBUM = 1; const val TITLE = 2; const val DURATION = 3 }

    /** Write to Remote Command. */
    fun command(command: Command): ByteArray = byteArrayOf(command.ordinal.toByte())

    /** The Remote Command characteristic notifies the list of currently supported commands. */
    fun parseSupportedCommands(bytes: ByteArray): Set<Command> =
        bytes.toList().mapNotNull { Command.entries.getOrNull(it.toInt() and 0xff) }.toSet()

    /** Write to Entity Update to subscribe to attributes of one entity. */
    fun subscribe(entity: Entity, vararg attributeIds: Int): ByteArray =
        byteArrayOf(entity.ordinal.toByte(), *attributeIds.map { it.toByte() }.toByteArray())

    data class EntityUpdate(val entity: Entity, val attributeId: Int, val truncated: Boolean, val value: String)

    fun parseEntityUpdate(bytes: ByteArray): EntityUpdate {
        require(bytes.size >= 3) { "entity update too short" }
        val entity = Entity.entries.getOrNull(bytes[0].toInt() and 0xff) ?: throw IllegalArgumentException("unknown entity")
        return EntityUpdate(
            entity = entity,
            attributeId = bytes[1].toInt() and 0xff,
            truncated = (bytes[2].toInt() and 0x1) != 0,
            value = bytes.copyOfRange(3, bytes.size).decodeToString(),
        )
    }

    enum class PlaybackState { PAUSED, PLAYING, REWINDING, FAST_FORWARDING }

    data class PlaybackInfo(val state: PlaybackState?, val rate: Double?, val elapsedSeconds: Double?)

    /** Player PlaybackInfo value: "state,rate,elapsed" e.g. "1,1.0,42.5". */
    fun parsePlaybackInfo(value: String): PlaybackInfo {
        val parts = value.split(',')
        return PlaybackInfo(
            state = parts.getOrNull(0)?.toIntOrNull()?.let { PlaybackState.entries.getOrNull(it) },
            rate = parts.getOrNull(1)?.toDoubleOrNull(),
            elapsedSeconds = parts.getOrNull(2)?.toDoubleOrNull(),
        )
    }
}
