package com.mochyy.mochymusic.viewmodels

import androidx.compose.runtime.Immutable
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

// one album as cover flow shows it
@Immutable
class IpodAlbum(val id: String, val title: String, val artist: String, val cover: String?)

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

    // every album with its cover, by name, for cover flow
    val coverFlow: Flow<List<IpodAlbum>> = database.albums(AlbumSortType.NAME, false)
        .map { albums ->
            albums.map { IpodAlbum(it.id, it.album.title, it.artists.joinToString { a -> a.name }, it.album.thumbnailUrl?.resize(CoverPx, CoverPx)) }
        }
        .flowOn(Dispatchers.Default)

    fun list(page: IpodPage): Flow<IpodList> = when (page) {
        IpodPage.Main -> flowOf(IpodMainList)
        IpodPage.NowPlaying, IpodPage.CoverFlow -> flowOf(IpodList.Loading)
        IpodPage.Playlists -> database.playlists(PlaylistSortType.NAME, false).map { playlists ->
            withArt(
                playlists.map {
                    IpodRow(
                        key = it.id,
                        title = it.playlist.name,
                        pick = IpodPick.Open(IpodPage.Playlist(it.id, it.playlist.name)),
                        subtitle = songCount(it.songCount),
                        art = it.thumbnails.firstOrNull()?.resize(RowPx, RowPx),
                    )
                },
                "No playlists yet",
            )
        }
        IpodPage.Artists -> database.artists(ArtistSortType.NAME, false).map { artists ->
            withArt(
                artists.map {
                    IpodRow(
                        key = it.id,
                        title = it.artist.name,
                        pick = IpodPick.Open(IpodPage.Artist(it.id, it.artist.name)),
                        subtitle = songCount(it.songCount),
                        art = it.artist.thumbnailUrl?.resize(RowPx, RowPx),
                        roundArt = true,
                    )
                },
                "No artists yet",
            )
        }
        IpodPage.Albums -> database.albums(AlbumSortType.NAME, false).map { albums ->
            withArt(
                albums.map {
                    IpodRow(
                        key = it.id,
                        title = it.album.title,
                        pick = IpodPick.Open(IpodPage.Album(it.id, it.album.title)),
                        subtitle = it.artists.joinToString { a -> a.name }.ifBlank { null },
                        art = it.album.thumbnailUrl?.resize(RowPx, RowPx),
                    )
                },
                "No albums yet",
            )
        }
        IpodPage.Songs -> database.songs(SongSortType.NAME, false).map { songs(it, art = true) }
        IpodPage.LikedSongs -> database.likedSongs(SongSortType.NAME, false).map { songs(it, art = true) }
        is IpodPage.Playlist -> database.playlistSongs(page.id).map { list -> songs(list.map { it.song }, art = true) }
        is IpodPage.Artist -> database.artistSongs(page.id, ArtistSongSortType.NAME, false).map { songs(it, art = true) }
        // an album's own tracks all share its cover, so they're a plain track list like on the classic
        is IpodPage.Album -> database.albumWithSongs(page.id).map { songs(it?.songs.orEmpty(), art = false) }
    }.flowOn(Dispatchers.Default)

    // the whole library in a random order, ready to hand to the player
    suspend fun shuffledLibrary(): List<MediaItem> = withContext(Dispatchers.Default) {
        database.songs(SongSortType.NAME, false).first().shuffled().map { it.toMediaItem() }
    }

    private fun withArt(rows: List<IpodRow>, emptyText: String) =
        if (rows.isEmpty()) IpodList(listOf(IpodRow("empty", emptyText, null))) else IpodList(rows, art = true)

    private fun songs(songs: List<Song>, art: Boolean): IpodList {
        if (songs.isEmpty()) return IpodList(listOf(IpodRow("empty", "No songs yet", null)))
        val rows = songs.mapIndexed { index, song ->
            IpodRow(
                key = "${song.id}#$index",
                title = song.song.title,
                pick = IpodPick.Play(index),
                subtitle = if (art) song.artists.joinToString { it.name }.ifBlank { null } else null,
                art = if (art) song.song.thumbnailUrl?.resize(RowPx, RowPx) else null,
            )
        }
        return IpodList(rows, songs, art = art)
    }

    private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

    private companion object {
        const val CoverPx = 400
        const val RowPx = 120
        const val MaxCovers = 24
    }
}
