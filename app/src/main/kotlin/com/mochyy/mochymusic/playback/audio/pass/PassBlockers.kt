// PassBlockers.kt

package com.mochyy.mochymusic.playback.audio.pass

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.mochyy.mochymusic.constants.AudioNormalizationKey
import com.mochyy.mochymusic.constants.CrossfadeEnabledKey
import com.mochyy.mochymusic.constants.CrossmixMode
import com.mochyy.mochymusic.constants.CrossmixModeKey
import com.mochyy.mochymusic.constants.MusicHapticsEnabledKey
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.constants.TransportEffect
import com.mochyy.mochymusic.constants.TransportEffectKey
import com.mochyy.mochymusic.constants.playable
import com.mochyy.mochymusic.eq.AudioLabStore
import com.mochyy.mochymusic.eq.EqSettings
import com.mochyy.mochymusic.eq.LoudnessSettings
import com.mochyy.mochymusic.eq.PlaybackSettings
import com.mochyy.mochymusic.eq.ReverbSettings
import com.mochyy.mochymusic.eq.SpatialSettings
import com.mochyy.mochymusic.eq.VolumeSettings
import com.mochyy.mochymusic.extensions.toEnum

/** each of these changes the sound on mochy's side, and an output that takes the song untouched can't, so songs play the normal way while one is on */
enum class PassBlocker { EQUALIZER, AUDIO_FX, VOLUME, NORMALIZATION, SPEED, TRANSITIONS, SILENCE_OR_HAPTICS }

/** the pieces of audio lab the blockers are read from, gathered so one call can decide */
data class LabSound(
    val eq: EqSettings,
    val reverb: ReverbSettings,
    val loudness: LoudnessSettings,
    val spatial: SpatialSettings,
    val playback: PlaybackSettings,
    val volume: VolumeSettings,
)

fun passBlockersOf(prefs: Preferences, lab: LabSound, playerVolume: Float): Set<PassBlocker> = buildSet {
    if (lab.eq.enabled) add(PassBlocker.EQUALIZER)
    if (lab.reverb.enabled || lab.loudness.enabled || lab.spatial.enabled || lab.spatial.mono) add(PassBlocker.AUDIO_FX)
    if (playerVolume < 0.999f || (lab.volume.enabled && lab.volume.limit < 0.999f)) add(PassBlocker.VOLUME)
    if (prefs[AudioNormalizationKey] ?: true) add(PassBlocker.NORMALIZATION)
    if (!lab.playback.isNormal) add(PassBlocker.SPEED)
    if (transitionMode(prefs) != CrossmixMode.OFF || prefs[TransportEffectKey].toEnum(TransportEffect.OFF) != TransportEffect.OFF) {
        add(PassBlocker.TRANSITIONS)
    }
    if ((prefs[SkipSilenceKey] ?: false) || (prefs[MusicHapticsEnabledKey] ?: false)) add(PassBlocker.SILENCE_OR_HAPTICS)
}

/** the transition that really plays, read the way the player reads it: no saved mode means auto crossfade */
private fun transitionMode(prefs: Preferences): CrossmixMode {
    val legacy = prefs[CrossfadeEnabledKey] ?: false
    return (prefs[CrossmixModeKey]?.let { runCatching { CrossmixMode.valueOf(it) }.getOrNull() }
        ?: if (legacy) CrossmixMode.MANUAL_CROSSFADE else CrossmixMode.AUTO_CROSSFADE).playable()
}

/** turns off the preference side of every blocker; audio lab's own settings and the volume go through their owners */
fun MutablePreferences.clearPassBlockers() {
    this[AudioNormalizationKey] = false
    this[CrossmixModeKey] = CrossmixMode.OFF.name
    this[CrossfadeEnabledKey] = false
    this[TransportEffectKey] = TransportEffect.OFF.name
    this[SkipSilenceKey] = false
    this[MusicHapticsEnabledKey] = false
}

/** the audio lab side of the same */
fun AudioLabStore.clearPassBlockers() {
    updateEq { it.copy(enabled = false) }
    updateReverb { it.copy(enabled = false) }
    updateLoudness { it.copy(enabled = false) }
    updateSpatial { it.copy(enabled = false, mono = false) }
    updatePlayback { PlaybackSettings() }
    updateVolume { it.copy(limit = 1f) }
}
