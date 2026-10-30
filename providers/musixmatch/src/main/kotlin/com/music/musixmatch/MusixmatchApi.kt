package com.music.musixmatch

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import kotlin.math.abs

// musixmatch's android app api: plain http with a free token, no bot check, and its search knows every song page's address
internal object MusixmatchApi {
    private const val Base = "https://apic.musixmatch.com/ws/1.1/"
    private const val AppId = "android-player-v1.0"
    private const val Prefs = "musixmatch"
    private const val TokenKey = "user_token"
    private const val Tag = "Musixmatch"
    // youtube's upload and musixmatch's recording of one song rarely differ by more than a couple of seconds
    private const val LengthSlackSeconds = 3

    sealed interface Search {
        data class Hit(val track: JsonObject) : Search
        data object Missing : Search
        /** no connection, or musixmatch wants a captcha for every token we can get */
        data object Failed : Search
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var token: String? = null

    fun search(client: OkHttpClient, context: Context?, title: String, artist: String, durationSeconds: Int?): Search {
        val byName = tracks(client, context, "q_track" to title, "q_artist" to artist) ?: return Search.Failed
        pick(byName, title, artist, durationSeconds)?.let { return Search.Hit(it) }
        // a title written another way ("フィナーレ。" for "Finale.") only turns up in the loose search, and only its length gives it away
        val loose = tracks(client, context, "q" to "$artist $title") ?: return Search.Failed
        return pick(loose, title, artist, durationSeconds)?.let { Search.Hit(it) } ?: Search.Missing
    }

    // what the app api says about a song: everything but the meaning, which only its page has
    fun songOf(track: JsonObject, title: String, artist: String): MusixmatchSong {
        val genres = listOf("primary_genres", "secondary_genres").flatMap { key ->
            (track.obj(key)?.get("music_genre_list") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.obj("music_genre")?.string("music_genre_name") }
        }
        return MusixmatchSong(
            title = track.string("track_name") ?: title,
            artist = track.string("artist_name") ?: artist,
            album = track.string("album_name"),
            releaseDate = track.string("first_release_date")?.take(10),
            genres = genres.distinct(),
            coverUrl = listOf("album_coverart_800x800", "album_coverart_500x500", "album_coverart_350x350")
                .firstNotNullOfOrNull { key -> track.string(key)?.takeIf { it.startsWith("http") && "nocover" !in it } },
            spotifyId = track.string("track_spotify_id")
                ?: (track["commontrack_spotify_ids"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull },
            appleMusicId = (track["commontrack_itunes_ids"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull },
            isExplicit = track.int("explicit") == 1,
            pageUrl = track.string("track_share_url")?.substringBefore('?')?.let(::decodeUrl)
                ?: track.string("commontrack_vanity_id")?.let { "https://www.musixmatch.com/lyrics/$it" },
            lengthSeconds = track.int("track_length")?.takeIf { it > 0 },
        )
    }

    // musixmatch keeps empty copies of popular songs under a numbered artist ("NewJeans-5/DITTO"): the copy with the song's analysis wins
    private fun pick(tracks: List<JsonObject>, title: String, artist: String, durationSeconds: Int?): JsonObject? {
        val wantedArtist = normalize(artist)
        val wantedTitle = normalize(title)
        return tracks
            .filter { sameWords(normalize(it.string("artist_name").orEmpty()), wantedArtist) }
            .mapNotNull { track ->
                val name = normalize(track.string("track_name").orEmpty())
                val sameLength = durationSeconds != null &&
                    track.int("track_length")?.let { abs(it - durationSeconds) <= LengthSlackSeconds } == true
                val titleScore = when {
                    name == wantedTitle -> 3
                    sameWords(name, wantedTitle) -> 2
                    sameLength -> 1
                    else -> return@mapNotNull null
                }
                val analysed = if (track.int("has_lyrics_analysis") == 1) 2 else 0
                track to titleScore * 4 + analysed + (if (sameLength) 1 else 0)
            }
            // ties keep musixmatch's own order, which is by rating
            .maxByOrNull { it.second }
            ?.first
    }

    /** the search's tracks, best rated first; null when musixmatch couldn't be asked */
    private fun tracks(client: OkHttpClient, context: Context?, vararg query: Pair<String, String>): List<JsonObject>? {
        val body = call(client, context, "track.search", *query, "page_size" to "10", "s_track_rating" to "desc") ?: return null
        return (body["track_list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.obj("track") }
    }

    // a token musixmatch has started asking a captcha for is swapped for a fresh one, once
    private fun call(client: OkHttpClient, context: Context?, method: String, vararg query: Pair<String, String>): JsonObject? {
        repeat(2) { attempt ->
            val userToken = token(client, context, fresh = attempt > 0) ?: return null
            val message = get(client, method, *query, "usertoken" to userToken) ?: return null
            val status = message.obj("header")?.int("status_code")
            if (status == 200) return message.obj("body") ?: JsonObject(emptyMap())
            Timber.tag(Tag).d("$method answered $status")
            if (status != 401) return null
        }
        return null
    }

    // kept on disk: asking for a new one costs most of a second on every app start
    private fun token(client: OkHttpClient, context: Context?, fresh: Boolean): String? {
        val prefs = context?.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        if (!fresh) {
            token?.let { return it }
            prefs?.getString(TokenKey, null)?.let { saved -> token = saved; return saved }
        }
        // a token of only zeros is musixmatch saying "captcha first"
        val newToken = get(client, "token.get")?.obj("body")?.string("user_token")?.takeIf { it.any { c -> c != '0' } }
        // a refused refresh keeps the old token: musixmatch's captcha wears off by itself after a while
        if (newToken != null) {
            token = newToken
            prefs?.edit()?.putString(TokenKey, newToken)?.apply()
        }
        return newToken
    }

    /** the reply's message (header and body), or null when there was no answer at all */
    private fun get(client: OkHttpClient, method: String, vararg query: Pair<String, String>): JsonObject? {
        val url = (Base + method).toHttpUrl().newBuilder()
            .addQueryParameter("app_id", AppId)
            .addQueryParameter("format", "json")
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val text = runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else null }
        }.onFailure { Timber.tag(Tag).d(it, "$method failed") }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.obj("message")
    }
}
