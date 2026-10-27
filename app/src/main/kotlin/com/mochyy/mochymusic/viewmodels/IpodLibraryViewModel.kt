package com.mochyy.mochymusic.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.mochyy.mochymusic.constants.AlbumSortType
import com.mochyy.mochymusic.constants.ArtistSongSortType
import com.mochyy.mochymusic.constants.ArtistSortType
import com.mochyy.mochymusic.constants.PlaylistSortType
import com.mochyy.mochymusic.constants.SongSortType
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.db.entities.Song
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.ui.player.ipod.IpodList
import com.mochyy.mochymusic.ui.player.ipod.IpodMainList
import com.mochyy.mochymusic.ui.player.ipod.IpodPage
import com.mochyy.mochymusic.ui.player.ipod.IpodPick
import com.mochyy.mochymusic.ui.player.ipod.IpodRow
import com.mochyy.mochymusic.ui.utils.resize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

// the library as the ipod player's menus see it, every list turned into rows off the main thread
@HiltViewModel
class IpodLibraryViewModel @Inject constructor(
    private val database: MusicDatabase,
) : ViewModel() {

    // album covers for the slideshow beside the main menu, newest first
    val covers: StateFlow<List<String>> = database.albums(AlbumSortType.CREATE_DATE, true)
        .map { albums -> albums.mapNotNull { it.album.thumbnailUrl?.resize(CoverPx, CoverPx) }.distinct().take(MaxCovers) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun list(page: IpodPage): Flow<IpodList> = when (page) {
        IpodPage.Main -> flowOf(IpodMainList)
        IpodPage.NowPlaying -> flowOf(IpodList.Loading)
        IpodPage.Playlists -> database.playlists(PlaylistSortType.NAME, false).map { playlists ->
            pages(playlists.map { IpodRow(it.id, it.playlist.name, IpodPick.Open(IpodPage.Playlist(it.id, it.playlist.name))) }, "No playlists yet")
        }
        IpodPage.Artists -> database.artists(ArtistSortType.NAME, false).map { artists ->
            pages(artists.map { IpodRow(it.id, it.artist.name, IpodPick.Open(IpodPage.Artist(it.id, it.artist.name))) }, "No artists yet")
        }
        IpodPage.Albums -> database.albums(AlbumSortType.NAME, false).map { albums ->
            pages(albums.map { IpodRow(it.id, it.album.title, IpodPick.Open(IpodPage.Album(it.id, it.album.title))) }, "No albums yet")
        }
        IpodPage.Songs -> database.songs(SongSortType.NAME, false).map(::songs)
        IpodPage.LikedSongs -> database.likedSongs(SongSortType.NAME, false).map(::songs)
        is IpodPage.Playlist -> database.playlistSongs(page.id).map { list -> songs(list.map { it.song }) }
        is IpodPage.Artist -> database.artistSongs(page.id, ArtistSongSortType.NAME, false).map(::songs)
        is IpodPage.Album -> database.albumWithSongs(page.id).map { songs(it?.songs.orEmpty()) }
    }.flowOn(Dispatchers.Default)

    // the whole library in a random order, ready to hand to the player
    suspend fun shuffledLibrary(): List<MediaItem> = withContext(Dispatchers.Default) {
        database.songs(SongSortType.NAME, false).first().shuffled().map { it.toMediaItem() }
    }

    private fun pages(rows: List<IpodRow>, emptyText: String) =
        IpodList(rows.ifEmpty { listOf(IpodRow("empty", emptyText, null)) })

    private fun songs(songs: List<Song>): IpodList {
        if (songs.isEmpty()) return pages(emptyList(), "No songs yet")
        val rows = songs.mapIndexed { index, song -> IpodRow("${song.id}#$index", song.song.title, IpodPick.Play(index)) }
        return IpodList(rows, songs)
    }

    private companion object {
        const val CoverPx = 400
        const val MaxCovers = 24
    }
}
