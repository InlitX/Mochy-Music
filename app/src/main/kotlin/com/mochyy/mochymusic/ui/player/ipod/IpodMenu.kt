package com.mochyy.mochymusic.ui.player.ipod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mochyy.mochymusic.db.entities.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// one screen of the ipod's menu tree, named the way its header shows it
@Immutable
sealed class IpodPage(val title: String) {
    data object Main : IpodPage("Mochy")
    data object NowPlaying : IpodPage("Now Playing")
    data object Playlists : IpodPage("Playlists")
    data object Artists : IpodPage("Artists")
    data object Albums : IpodPage("Albums")
    data object Songs : IpodPage("Songs")
    data object LikedSongs : IpodPage("Liked Songs")
    class Playlist(val id: String, name: String) : IpodPage(name)
    class Artist(val id: String, name: String) : IpodPage(name)
    class Album(val id: String, name: String) : IpodPage(name)
}

// what picking a row does: open a page, play a song from the list, or shuffle everything
@Immutable
sealed interface IpodPick {
    class Open(val page: IpodPage) : IpodPick
    class Play(val index: Int) : IpodPick
    data object Shuffle : IpodPick
}

// one line in a menu, pick is null for the "nothing here" line
@Immutable
class IpodRow(val key: String, val title: String, val pick: IpodPick?) {
    val opensPage: Boolean get() = pick is IpodPick.Open
}

// a page's lines, plus the songs behind them when it's a song list
@Immutable
class IpodList(val rows: List<IpodRow>, val songs: List<Song> = emptyList()) {
    companion object {
        val Loading = IpodList(emptyList())
    }
}

// the main menu never changes, so it's built once
val IpodMainList = IpodList(
    listOf(
        IpodRow("now", "Now Playing", IpodPick.Open(IpodPage.NowPlaying)),
        IpodRow("playlists", "Playlists", IpodPick.Open(IpodPage.Playlists)),
        IpodRow("artists", "Artists", IpodPick.Open(IpodPage.Artists)),
        IpodRow("albums", "Albums", IpodPick.Open(IpodPage.Albums)),
        IpodRow("songs", "Songs", IpodPick.Open(IpodPage.Songs)),
        IpodRow("liked", "Liked Songs", IpodPick.Open(IpodPage.LikedSongs)),
        IpodRow("shuffle", "Shuffle Songs", IpodPick.Shuffle),
    )
)

// a page in the stack, keeping its own highlighted row so going back lands where you left it
@Stable
class IpodEntry(val page: IpodPage, val id: Long) {
    var list by mutableStateOf(if (page == IpodPage.Main) IpodMainList else IpodList.Loading)
    var selected by mutableIntStateOf(0)
    var first by mutableIntStateOf(0)

    // moves the highlight, keeping it inside the visible window, false when it was already at the end
    fun move(steps: Int, visibleRows: Int): Boolean {
        val count = list.rows.size
        if (count == 0) return false
        val target = (selected + steps).coerceIn(0, count - 1)
        if (target == selected) return false
        selected = target
        if (selected < first) first = selected
        if (selected >= first + visibleRows) first = selected - visibleRows + 1
        return true
    }

    // a list that shrank under the highlight pulls it back onto a real row
    fun clamp(visibleRows: Int) {
        val last = (list.rows.size - 1).coerceAtLeast(0)
        if (selected > last) selected = last
        first = first.coerceIn((selected - visibleRows + 1).coerceAtLeast(0), selected)
    }
}

// the stack of menu pages and the slide between them, like the real thing: the new page pushes the old one out
@Stable
class IpodNavigator(private val scope: CoroutineScope) {
    private var nextId = 0L
    val stack = mutableStateListOf(entry(IpodPage.Main), entry(IpodPage.NowPlaying))
    var leaving by mutableStateOf<IpodEntry?>(null)
        private set
    var forward by mutableStateOf(true)
        private set
    val slide = Animatable(1f)

    val top: IpodEntry get() = stack.last()

    fun push(page: IpodPage) {
        leaving = top
        stack.add(entry(page))
        forward = true
        play()
    }

    // false at the main menu, where menu has nowhere left to go
    fun back(): Boolean {
        if (stack.size <= 1) return false
        leaving = stack.removeAt(stack.lastIndex)
        forward = false
        play()
        return true
    }

    // a song just started, so its screen comes up wherever you picked it from
    fun showNowPlaying() {
        if (top.page != IpodPage.NowPlaying) push(IpodPage.NowPlaying)
    }

    private fun entry(page: IpodPage) = IpodEntry(page, nextId++)

    private fun play() {
        scope.launch {
            slide.snapTo(0f)
            slide.animateTo(1f, tween(SlideMs, easing = SlideEasing))
            leaving = null
        }
    }
}

// about as quick as the classic's own slide, quick to start and soft to land
private const val SlideMs = 240
private val SlideEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
