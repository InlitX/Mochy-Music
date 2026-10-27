package com.mochyy.mochymusic.ui.player.ipod

import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.media3.common.Player
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.viewmodels.IpodLibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// everything the wheel and the screen can do, in one place
@Stable
internal class IpodController(
    val nav: IpodNavigator,
    val nowPlaying: IpodNowPlayingState,
    val coverFlow: IpodCoverFlowState,
    private val scope: CoroutineScope,
    private val connection: PlayerConnection?,
    private val library: IpodLibraryViewModel?,
    private val audio: AudioManager,
    private val haptics: IpodHaptics,
) : IpodWheelActions {
    private var seekJob: Job? = null

    // a page being loaded before it slides in, so a second press doesn't open it twice
    private var opening = false

    private val page: IpodPage get() = nav.top.page

    override val accelerates: Boolean get() = page != IpodPage.NowPlaying

    override fun rotate(steps: Int): Boolean = when {
        page == IpodPage.CoverFlow -> coverFlow.move(steps)
        page != IpodPage.NowPlaying -> nav.top.move(steps)
        nowPlaying.bar == IpodBar.SCRUB -> scrub(steps)
        else -> volume(steps)
    }

    override fun menu() {
        if (page == IpodPage.NowPlaying && nowPlaying.bar != IpodBar.PROGRESS) {
            nowPlaying.bar = IpodBar.PROGRESS
            return
        }
        if (page == IpodPage.CoverFlow && coverFlow.turnBack()) return
        nav.back()
    }

    override fun menuHeld() {
        coverFlow.turnBack()
        nav.backToMain()
    }

    override fun center() {
        when (page) {
            IpodPage.NowPlaying -> {
                // the middle button on now playing brings up the scrubber, and puts it away again
                nowPlaying.bar = if (nowPlaying.bar == IpodBar.SCRUB) IpodBar.PROGRESS else IpodBar.SCRUB
                touch()
            }
            IpodPage.CoverFlow -> if (coverFlow.flipped) playTrack(coverFlow.back.selected) else turnOver()
            else -> pick(nav.top, nav.top.selected)
        }
    }

    override fun next() {
        connection?.seekToNext()
    }

    override fun previous() {
        connection?.seekToPrevious()
    }

    override fun playPause() {
        val player = connection?.player ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
            player.playWhenReady = true
        } else {
            connection.togglePlayPause()
        }
    }

    override fun scan(forward: Boolean) {
        val player = connection?.player ?: return
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + if (forward) ScanStepMs else -ScanStepMs).coerceIn(0L, end))
    }

    // a tap on a line of the screen highlights it and picks it, the way pressing the middle button would
    fun tapRow(entry: IpodEntry, index: Int) {
        if (entry !== nav.top) return
        entry.selected = index
        entry.clamp()
        haptics.click()
        pick(entry, index)
    }

    // a tap beside the front cover slides that way, on it turns it over
    fun tapCoverFlow(direction: Int) {
        haptics.click()
        if (direction == 0) turnOver() else coverFlow.move(direction)
    }

    fun tapTrack(index: Int) {
        coverFlow.back.selected = index
        haptics.click()
        playTrack(index)
    }

    private fun pick(entry: IpodEntry, index: Int) {
        when (val pick = entry.list.rows.getOrNull(index)?.pick) {
            is IpodPick.Open -> open(pick.page)
            is IpodPick.Play -> play(entry.list.songs, pick.index, entry.page.title)
            IpodPick.Shuffle -> scope.launch {
                val items = library?.shuffledLibrary().orEmpty()
                if (items.isEmpty()) return@launch
                connection?.playQueue(ListQueue(title = "Shuffle Songs", items = items))
                nav.showNowPlaying()
            }
            null -> Unit
        }
    }

    // loads the page's list first, so it slides in already filled rather than filling up halfway across
    private fun open(target: IpodPage) {
        val source = library
        if (opening) return
        if (source == null || target == IpodPage.NowPlaying) {
            nav.push(target)
            return
        }
        opening = true
        scope.launch {
            try {
                if (target == IpodPage.CoverFlow) {
                    withTimeoutOrNull(PrepareMs) { source.coverFlow.first() }?.let(coverFlow::show)
                    nav.push(target)
                } else {
                    nav.push(target, withTimeoutOrNull(PrepareMs) { source.list(target).first() })
                }
            } finally {
                opening = false
            }
        }
    }

    private fun turnOver() {
        val album = coverFlow.current ?: return
        val source = library ?: return
        if (opening) return
        opening = true
        scope.launch {
            try {
                val tracks = withTimeoutOrNull(PrepareMs * 2) { source.list(IpodPage.Album(album.id, album.title)).first() }
                if (tracks != null) coverFlow.turnOver(tracks)
            } finally {
                opening = false
            }
        }
    }

    private fun playTrack(index: Int) {
        val album = coverFlow.current ?: return
        val songs = coverFlow.back.list.songs
        if (songs.isEmpty()) return
        play(songs, index, album.title)
    }

    private fun play(songs: List<Song>, index: Int, title: String) {
        nav.showNowPlaying()
        scope.launch {
            val items = withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } }
            connection?.playQueue(ListQueue(title = title, items = items, startIndex = index))
        }
    }

    private fun volume(steps: Int): Boolean {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (now + steps).coerceIn(0, max)
        nowPlaying.bar = IpodBar.VOLUME
        touch()
        if (target != now) {
            // some phones refuse in do not disturb, the bar then just shows where it really is
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
        val reached = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        nowPlaying.volume = reached.toFloat() / max
        return reached != now
    }

    private fun scrub(steps: Int): Boolean {
        val player = connection?.player ?: return false
        val duration = player.duration.takeIf { it > 0 } ?: return false
        val from = if (nowPlaying.scrubMs >= 0) nowPlaying.scrubMs else player.currentPosition
        val step = (duration / 100L).coerceIn(1_000L, 10_000L)
        val target = (from + steps * step).coerceIn(0L, duration)
        touch()
        if (target == from) return false
        nowPlaying.scrubMs = target
        // the player only seeks once the wheel rests, so a stream isn't asked to buffer at every notch
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(ScrubSettleMs)
            player.seekTo(target)
            nowPlaying.scrubMs = -1L
        }
        return true
    }

    private fun touch() {
        nowPlaying.touchedAt = SystemClock.uptimeMillis()
    }
}

private const val ScrubSettleMs = 300L
private const val ScanStepMs = 4_000L
// how long a page waits for its list before sliding in anyway
private const val PrepareMs = 250L
