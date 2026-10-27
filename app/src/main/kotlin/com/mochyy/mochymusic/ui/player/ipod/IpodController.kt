package com.mochyy.mochymusic.ui.player.ipod

import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.media3.common.Player
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.viewmodels.IpodLibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// everything the wheel and the screen can do, in one place
@Stable
internal class IpodController(
    val nav: IpodNavigator,
    val nowPlaying: IpodNowPlayingState,
    private val scope: CoroutineScope,
    private val connection: PlayerConnection?,
    private val library: IpodLibraryViewModel?,
    private val audio: AudioManager,
    private val haptics: IpodHaptics,
) : IpodWheelActions {
    private var seekJob: Job? = null

    private val onNowPlaying: Boolean get() = nav.top.page == IpodPage.NowPlaying

    override val accelerates: Boolean get() = !onNowPlaying

    override fun rotate(steps: Int): Boolean = when {
        !onNowPlaying -> nav.top.move(steps, VisibleRows)
        nowPlaying.bar == IpodBar.SCRUB -> scrub(steps)
        else -> volume(steps)
    }

    override fun menu() {
        if (onNowPlaying && nowPlaying.bar != IpodBar.PROGRESS) {
            nowPlaying.bar = IpodBar.PROGRESS
            return
        }
        nav.back()
    }

    override fun menuHeld() = nav.backToMain()

    override fun center() {
        if (!onNowPlaying) {
            pick(nav.top, nav.top.selected)
            return
        }
        // the middle button on now playing brings up the scrubber, and puts it away again
        nowPlaying.bar = if (nowPlaying.bar == IpodBar.SCRUB) IpodBar.PROGRESS else IpodBar.SCRUB
        nowPlaying.scrubMs = -1L
        touch()
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
        entry.clamp(VisibleRows)
        haptics.click()
        pick(entry, index)
    }

    private fun pick(entry: IpodEntry, index: Int) {
        when (val pick = entry.list.rows.getOrNull(index)?.pick) {
            is IpodPick.Open -> nav.push(pick.page)
            is IpodPick.Play -> {
                val songs = entry.list.songs
                val title = entry.page.title
                nav.showNowPlaying()
                scope.launch {
                    val items = withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } }
                    connection?.playQueue(ListQueue(title = title, items = items, startIndex = pick.index))
                }
            }
            IpodPick.Shuffle -> scope.launch {
                val items = library?.shuffledLibrary().orEmpty()
                if (items.isEmpty()) return@launch
                connection?.playQueue(ListQueue(title = "Shuffle Songs", items = items))
                nav.showNowPlaying()
            }
            null -> Unit
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
            player.seekTo(nowPlaying.scrubMs)
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
