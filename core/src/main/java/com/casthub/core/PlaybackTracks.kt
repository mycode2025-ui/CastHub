package com.casthub.core

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackTracks {
    /** Call from the player's application thread, which is Main in CastHub. */
    fun choices(player: Player?): List<MediaTrackChoice> {
        if (player == null) return emptyList()
        val result = mutableListOf(
            MediaTrackChoice("音轨：自动", C.TRACK_TYPE_AUDIO, -1, -1),
            MediaTrackChoice("字幕：关闭", C.TRACK_TYPE_TEXT, -1, -1),
        )
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            repeat(group.length) { index ->
                if (group.isTrackSupported(index)) {
                    val format = group.getTrackFormat(index)
                    val kind = if (group.type == C.TRACK_TYPE_AUDIO) "音轨" else "字幕"
                    val name = format.label ?: format.language ?: "${index + 1}"
                    val selected = if (group.isTrackSelected(index)) " ✓" else ""
                    result.add(MediaTrackChoice("$kind：$name$selected", group.type, groupIndex, index))
                }
            }
        }
        return result
    }

    fun select(player: Player?, choice: MediaTrackChoice): Boolean {
        if (player == null) return false
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(choice.type)
        if (choice.group < 0) {
            builder.setTrackTypeDisabled(choice.type, choice.type == C.TRACK_TYPE_TEXT)
        } else {
            val group = player.currentTracks.groups.getOrNull(choice.group) ?: return false
            if (group.type != choice.type || choice.index !in 0 until group.length ||
                !group.isTrackSupported(choice.index)) return false
            builder.setTrackTypeDisabled(choice.type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.index)))
        }
        player.trackSelectionParameters = builder.build()
        return true
    }
}
