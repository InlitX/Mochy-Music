// SongVideoMap.kt

package com.mochyy.mochymusic.lyrics

import kotlin.math.abs

// where a song's moments sit in its music video: stretches of steady shift, split where the video adds an intro or a skit, or cuts a part
class SongVideoMap(val runs: List<Run>) {

    // from one song time to another, the video runs deltaMs ahead of the song (behind, when negative)
    class Run(val fromSongMs: Long, val toSongMs: Long, val deltaMs: Long)

    // a shift under this is only timing noise, not worth moving anything for
    val isShifted: Boolean get() = runs.any { abs(it.deltaMs) >= MinShiftMs }

    // how far the video is from the song at a song time; between two stretches the nearer one decides
    fun deltaAt(songMs: Long): Long {
        val after = runs.indexOfFirst { it.toSongMs >= songMs }
        if (after == -1) return runs.last().deltaMs
        val run = runs[after]
        if (songMs >= run.fromSongMs || after == 0) return run.deltaMs
        val before = runs[after - 1]
        return if (songMs - before.toSongMs < run.fromSongMs - songMs) before.deltaMs else run.deltaMs
    }

    fun videoMsAt(songMs: Long): Long = songMs + deltaAt(songMs)

    override fun toString(): String = runs.joinToString { "${it.fromSongMs}..${it.toSongMs}:${it.deltaMs}" }

    private companion object {
        const val MinShiftMs = 250L
    }
}
