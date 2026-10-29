// YouTubeVideoLookup.kt

package com.mochyy.mochymusic.ui.player

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.mochyy.mochymusic.utils.YTPlayerUtils

data class OfficialMusicVideo(
    val videoId: String,
    val streamUrl: String,
)

object YouTubeVideoUrlCache {
    private const val maxSize = 64
    private val map = LinkedHashMap<String, OfficialMusicVideo?>(maxSize, 0.75f, true)

    @Synchronized
    fun contains(mediaId: String): Boolean = map.containsKey(mediaId)

    @Synchronized
    fun get(mediaId: String): OfficialMusicVideo? = map[mediaId]

    @Synchronized
    fun put(mediaId: String, video: OfficialMusicVideo?) {
        if (mediaId.isBlank()) return
        map[mediaId] = video
        while (map.size > maxSize) {
            val it = map.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                break
            }
        }
    }
}

suspend fun findOfficialMusicVideo(title: String, artist: String): OfficialMusicVideo? {
    val videoId = findOfficialMusicVideoId(title, artist) ?: return null
    val streamUrl = YTPlayerUtils.resolveVideoStreamUrl(videoId).getOrNull() ?: return null
    return OfficialMusicVideo(videoId = videoId, streamUrl = streamUrl)
}

// just the official video's id, for when its captions are wanted and not its picture; misses are kept too, so a song is searched once
suspend fun findOfficialMusicVideoId(title: String, artist: String): String? {
    if (title.isBlank() || artist.isBlank()) return null
    val key = "$title|$artist"
    if (OfficialVideoIdCache.contains(key)) return OfficialVideoIdCache.get(key)

    val results = YouTube.search(query = "$title $artist", filter = YouTube.SearchFilter.FILTER_VIDEO)
        .getOrElse { return null }.items

    val normalizedTitle = normalizeCanvasSongTitle(title)
    val normalizedArtist = normalizeCanvasArtistName(artist)

    val match = results
        .filterIsInstance<SongItem>()
        .filter { it.musicVideoType == MUSIC_VIDEO_TYPE_OMV }
        .firstOrNull { candidate ->
            val candidateTitle = normalizeCanvasSongTitle(candidate.title)
            val candidateArtist = normalizeCanvasArtistName(candidate.artists.joinToString { it.name })
            (candidateTitle.contains(normalizedTitle, ignoreCase = true) || normalizedTitle.contains(candidateTitle, ignoreCase = true)) &&
                (candidateArtist.contains(normalizedArtist, ignoreCase = true) || normalizedArtist.contains(candidateArtist, ignoreCase = true))
        }
    OfficialVideoIdCache.put(key, match?.id)
    return match?.id
}

private object OfficialVideoIdCache {
    private const val maxSize = 64
    private val map = LinkedHashMap<String, String?>(maxSize, 0.75f, true)

    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(key: String, videoId: String?) {
        map[key] = videoId
        if (map.size > maxSize) map.remove(map.keys.first())
    }
}
