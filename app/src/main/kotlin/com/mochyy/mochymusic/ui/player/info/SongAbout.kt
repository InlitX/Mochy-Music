// SongAbout.kt

package com.mochyy.mochymusic.ui.player.info

import android.content.Context
import com.mochyy.mochymusic.db.MusicDatabase
import com.mochyy.mochymusic.importer.account.AccountService
import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.ui.utils.resize
import com.music.innertube.YouTube
import com.music.musixmatch.Musixmatch
import com.music.musixmatch.MusixmatchSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.ConcurrentHashMap

/** everything the about sheet shows for one song; what's still loading or unknown stays null */
data class SongAbout(
    val genre: String? = null,
    val released: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val lengthSeconds: Int? = null,
    val musixmatchDone: Boolean = false,
    val musixmatch: MusixmatchSong? = null,
    val youtubeDone: Boolean = false,
    val youtubeDescription: String? = null,
    val artists: List<AboutArtist> = emptyList(),
    val links: List<StoreLink> = emptyList(),
)

data class AboutArtist(val id: String?, val name: String, val imageUrl: String?)

data class StoreLink(val service: AccountService, val url: String)

/** gathers [SongAbout] off the main thread: the library first, then youtube and musixmatch side by side */
object SongAboutLoader {
    // kept for the run, so closing and opening the sheet again is instant
    private val cache = ConcurrentHashMap<String, SongAbout>()

    fun load(song: MediaMetadata, database: MusicDatabase, context: Context): Flow<SongAbout> = channelFlow {
        // here rather than at app start: it reads its saved key from disk, which belongs off the main thread
        Musixmatch.init(context)
        val cached = cache[song.id]
        if (cached != null && cached.musixmatchDone && cached.youtubeDone) {
            send(cached)
            return@channelFlow
        }
        val state = MutableStateFlow(cached ?: fromLibrary(song, database))
        send(state.value)
        fun publish(change: (SongAbout) -> SongAbout) {
            state.update(change)
            cache[song.id] = state.value
            trySend(state.value)
        }

        val artistName = song.artists.firstOrNull()?.name.orEmpty()
        launch {
            val description = if (song.id.startsWith("LOCAL_")) null
            else YouTube.getMediaInfo(song.id).getOrNull()?.description?.trim()?.takeIf { it.isNotEmpty() }
            publish { it.copy(youtubeDone = true, youtubeDescription = description) }
        }
        launch {
            val found = if (artistName.isBlank()) null else Musixmatch.song(song.title, artistName, song.duration.takeIf { d -> d > 0 })
            publish { about -> about.withMusixmatch(found, song) }
        }
        launch {
            val photos = artistPhotos(song, database)
            publish { about -> about.copy(artists = about.artists.map { a -> a.copy(imageUrl = photos[a.name] ?: a.imageUrl) }) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fromLibrary(song: MediaMetadata, database: MusicDatabase): SongAbout {
        val stored = database.song(song.id).first()
        val year = stored?.song?.year ?: stored?.album?.year
        return SongAbout(
            released = year?.toString(),
            album = song.album?.title ?: stored?.song?.albumName,
            artist = song.artists.joinToString(", ") { it.name }.ifBlank { null },
            lengthSeconds = song.duration.takeIf { it > 0 },
            artists = song.artists.map { AboutArtist(it.id, it.name, null) },
            links = linksFor(song, null),
        )
    }

    private fun SongAbout.withMusixmatch(found: MusixmatchSong?, song: MediaMetadata): SongAbout {
        if (found == null) return copy(musixmatchDone = true)
        val byName = found.artists.associateBy { it.name.lowercase() }
        return copy(
            musixmatchDone = true,
            musixmatch = found,
            genre = found.genres.take(2).joinToString(", ").ifBlank { null } ?: genre,
            released = found.releaseDate?.let(::readableDate) ?: released,
            album = album ?: found.album,
            artists = artists.map { a -> if (a.imageUrl == null) a.copy(imageUrl = byName[a.name.lowercase()]?.imageUrl) else a },
            links = linksFor(song, found),
        )
    }

    // the library's photo when the artist is in it, otherwise their youtube music page's, in parallel
    private suspend fun artistPhotos(song: MediaMetadata, database: MusicDatabase): Map<String, String> = coroutineScope {
        song.artists.filter { !it.id.isNullOrBlank() }.map { artist ->
            async {
                val id = artist.id!!
                val url = runCatching {
                    database.artist(id).first()?.artist?.thumbnailUrl ?: YouTube.artist(id).getOrNull()?.artist?.thumbnail
                }.getOrNull()?.resize(240, 240)
                url?.let { artist.name to it }
            }
        }.mapNotNull { it.await() }.toMap()
    }

    // a store's own song page when musixmatch knows its id, its search otherwise
    private fun linksFor(song: MediaMetadata, found: MusixmatchSong?): List<StoreLink> {
        val query = URLEncoder.encode("${song.title} ${song.artists.firstOrNull()?.name.orEmpty()}".trim(), "UTF-8")
        return listOfNotNull(
            StoreLink(
                AccountService.SPOTIFY,
                found?.spotifyId?.let { "https://open.spotify.com/track/$it" } ?: "https://open.spotify.com/search/$query",
            ),
            StoreLink(AccountService.YOUTUBE_MUSIC, "https://music.youtube.com/watch?v=${song.id}").takeIf { !song.id.startsWith("LOCAL_") },
            StoreLink(
                AccountService.APPLE_MUSIC,
                found?.appleMusicId?.let { "https://music.apple.com/song/$it" } ?: "https://music.apple.com/search?term=$query",
            ),
            StoreLink(AccountService.TIDAL, "https://listen.tidal.com/search?q=$query"),
        )
    }

    private fun readableDate(iso: String): String? = runCatching {
        LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull() ?: iso.take(4).takeIf { it.all(Char::isDigit) }
}
