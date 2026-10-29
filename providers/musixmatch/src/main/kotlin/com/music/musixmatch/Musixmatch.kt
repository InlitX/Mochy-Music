package com.music.musixmatch

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// musixmatch's song facts from its public site: the same calls and pages its own website makes, no account or key
object Musixmatch {
    private const val Site = "https://www.musixmatch.com"
    private const val ApiBase = "$Site/ws/1.1/"
    private const val AppId = "community-app-v1.0"
    // the site's signing key as of its last bundle; a stale one gets a 401 and is read again from the site
    private const val DefaultSigningKey = "e96ddc8a77ab4d745906ff46323346df"
    private const val FallbackUserAgent =
        "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val Tag = "Musixmatch"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var signingKey = DefaultSigningKey

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // a song asked for twice in one run (sheet closed and opened again) isn't fetched twice; null means not found
    private val songs = ConcurrentHashMap<String, Result<MusixmatchSong?>>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences("musixmatch", Context.MODE_PRIVATE)
        MusixmatchGate.restore(prefs.getString("user_agent", null))
        prefs.getString("signing_key", null)?.let { signingKey = it }
    }

    // the best match for this song and everything its page says, or null when musixmatch doesn't have it
    suspend fun song(title: String, artist: String, durationSeconds: Int? = null): MusixmatchSong? = withContext(Dispatchers.IO) {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        songs[key]?.getOrNull()?.let { return@withContext it }
        val result = runCatching { fetchSong(title, artist, durationSeconds) }
            .onFailure { Timber.tag(Tag).w(it, "couldn't read $title by $artist") }
        // only a real answer is kept: a blocked or offline try is asked again next time
        if (result.isSuccess) songs[key] = result
        result.getOrNull()
    }

    private suspend fun fetchSong(title: String, artist: String, durationSeconds: Int?): MusixmatchSong? {
        val found = search(cleanTitle(title), artist, durationSeconds) ?: return null
        val vanity = found.string("commontrack_vanity_id") ?: return fromSearch(found)
        val page = runCatching { page("/lyrics/$vanity") }
            .onFailure { Timber.tag(Tag).w(it, "page for $vanity didn't load") }
            .getOrNull()
        return merge(found, page, "$Site/lyrics/$vanity")
    }

    // ---- the api ----------------------------------------------------------------------------

    private suspend fun search(title: String, artist: String, durationSeconds: Int?): JsonObject? {
        val body = api(
            "track.search",
            "q_track" to title,
            "q_artist" to artist,
            "page_size" to "10",
            "page" to "1",
            "s_track_rating" to "desc",
        ) ?: return null
        val tracks = (body["track_list"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("track") }
        return tracks
            .map { it to score(it, title, artist, durationSeconds) }
            .filter { it.second >= 60 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(track: JsonObject, title: String, artist: String, durationSeconds: Int?): Int {
        val name = normalize(track.string("track_name").orEmpty())
        val wanted = normalize(title)
        var score = when {
            name == wanted -> 80
            name.contains(wanted) || wanted.contains(name) -> 40
            else -> 0
        }
        val artistName = normalize(track.string("artist_name").orEmpty())
        val wantedArtist = normalize(artist.substringBefore(",").substringBefore("&"))
        if (wantedArtist.isNotEmpty() && (artistName.contains(wantedArtist) || wantedArtist.contains(artistName))) score += 40
        val length = track.int("track_length")
        if (durationSeconds != null && durationSeconds > 0 && length != null && length > 0) {
            val off = abs(length - durationSeconds)
            score += when {
                off <= 2 -> 30
                off <= 6 -> 15
                off <= 12 -> 0
                else -> -40
            }
        }
        return score
    }

    // one signed call: a stale key is read again from the site, a bot check is passed in the webview, each once
    private suspend fun api(method: String, vararg params: Pair<String, String>): JsonObject? {
        repeat(2) { attempt ->
            val query = (listOf("app_id" to AppId, "format" to "json") + params)
                .joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            val response = get(sign("$ApiBase$method?$query"), "application/json")
            // a stale key gets a plain http 401 with no body worth reading
            if (response == Unauthorized) {
                if (attempt == 0 && refreshSigningKey()) return@repeat else return null
            }
            val message = response?.let(::parse)?.obj("message") ?: return null
            val status = message.obj("header")?.int("status_code")
            when (status) {
                200 -> return message.obj("body")
                401 -> if (attempt == 0 && refreshSigningKey()) return@repeat else return null
                else -> return null
            }
        }
        return null
    }

    private fun sign(url: String): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey.toByteArray(), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal("$url$day".toByteArray()), Base64.NO_WRAP)
        return "$url&signature=${encode(signature)}&signature_protocol=sha256"
    }

    // the key ships in the site's app bundle as reversed base64
    private suspend fun refreshSigningKey(): Boolean {
        val home = get("$Site/search?query=lyrics", "text/html") ?: return false
        val bundle = AppBundle.find(home)?.groupValues?.get(1) ?: return false
        val script = get(if (bundle.startsWith("http")) bundle else "$Site$bundle", "*/*") ?: return false
        val key = KeyPatterns.asSequence()
            .flatMap { it.findAll(script) }
            .mapNotNull { match ->
                runCatching { String(Base64.decode(match.groupValues[1].reversed(), Base64.DEFAULT)) }.getOrNull()
            }
            .firstOrNull { it.matches(Regex("[0-9a-f]{32}")) }
            ?: return false
        if (key == signingKey) return false
        signingKey = key
        appContext?.getSharedPreferences("musixmatch", Context.MODE_PRIVATE)?.edit()?.putString("signing_key", key)?.apply()
        return true
    }

    // ---- the song page ----------------------------------------------------------------------

    private suspend fun page(path: String): JsonObject? {
        val html = get("$Site$path", "text/html,application/xhtml+xml") ?: return null
        val data = NextData.find(html)?.groupValues?.get(1) ?: return null
        return parse(data)?.obj("props")?.obj("pageProps")?.obj("data")
            ?.obj("trackInfo")?.obj("data")?.obj("track")
    }

    private fun merge(found: JsonObject, page: JsonObject?, pageUrl: String): MusixmatchSong {
        val base = fromSearch(found)
        if (page == null) return base.copy(pageUrl = pageUrl)
        val lens = page.obj("lens")
        val artists = (page["artists"] as? JsonArray).orEmpty().mapNotNull { element ->
            val artist = element as? JsonObject ?: return@mapNotNull null
            val name = artist.string("name") ?: return@mapNotNull null
            MusixmatchArtist(name = name, imageUrl = artist.string("image")?.takeIf { it.startsWith("http") })
        }
        val pageGenres = (page["artists"] as? JsonArray).orEmpty()
            .flatMap { (it as? JsonObject)?.get("genres") as? JsonArray ?: JsonArray(emptyList()) }
            .mapNotNull { (it as? JsonObject)?.string("name") }
        return base.copy(
            title = page.string("name") ?: base.title,
            artist = page.string("artistName") ?: base.artist,
            album = page.string("albumName") ?: base.album,
            releaseDate = page.string("releaseDate")?.take(10) ?: base.releaseDate,
            genres = (base.genres + pageGenres).distinct(),
            meaning = lens?.obj("meaning")?.string("explanation")?.trim()?.takeIf { it.isNotEmpty() },
            moods = (lens?.obj("moods")?.get("main_moods") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            themes = (lens?.obj("themes")?.get("main_themes") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("theme") },
            artists = artists.ifEmpty { base.artists },
            coverUrl = page.string("coverImageHD") ?: page.string("coverImage") ?: base.coverUrl,
            spotifyId = page.string("spotifyId")
                ?: (page["commontrackSpotifyIds"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: base.spotifyId,
            appleMusicId = (page["commontrackItunesIds"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: base.appleMusicId,
            isExplicit = page.bool("isExplicit") ?: base.isExplicit,
            pageUrl = pageUrl,
        )
    }

    private fun fromSearch(track: JsonObject): MusixmatchSong = MusixmatchSong(
        title = track.string("track_name").orEmpty(),
        artist = track.string("artist_name").orEmpty(),
        album = track.string("album_name"),
        releaseDate = track.string("first_release_date")?.take(10)?.takeIf { !it.startsWith("0") },
        genres = (track.obj("primary_genres")?.get("music_genre_list") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("music_genre")?.string("music_genre_name") },
        coverUrl = track.string("album_coverart_500x500")?.takeIf { it.startsWith("http") }
            ?: track.string("album_coverart_350x350")?.takeIf { it.startsWith("http") },
        spotifyId = track.string("track_spotify_id"),
        isExplicit = track.int("explicit") == 1,
        pageUrl = track.string("track_share_url")?.substringBefore("?"),
        lengthSeconds = track.int("track_length"),
    )

    // ---- http -------------------------------------------------------------------------------

    // a challenged answer sends the webview through the bot check once, then the same request goes again
    private suspend fun get(url: String, accept: String): String? {
        repeat(2) { attempt ->
            val cookiesBefore = MusixmatchGate.cookies()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MusixmatchGate.userAgent ?: FallbackUserAgent)
                .header("Accept", accept)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "$Site/")
                .apply { if (accept.contains("json")) header("Origin", Site) }
                .apply { cookiesBefore?.let { header("Cookie", it) } }
                .build()
            val (code, body, challenged) = client.newCall(request).execute().use { response ->
                val challenged = response.header("x-amzn-waf-action") != null || response.code == 202 || response.code == 405
                Triple(response.code, if (response.isSuccessful && !challenged) response.body.string() else null, challenged)
            }
            if (body != null) return body
            if (code == 401) return Unauthorized
            if (!challenged || attempt > 0) {
                Timber.tag(Tag).d("$code for ${url.substringBefore('?')}")
                return null
            }
            val context = appContext ?: return null
            if (!MusixmatchGate.clear(context, "$Site/search?query=lyrics", cookiesBefore)) return null
            MusixmatchGate.userAgent?.let { agent ->
                context.getSharedPreferences("musixmatch", Context.MODE_PRIVATE).edit().putString("user_agent", agent).apply()
            }
        }
        return null
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // "(Remastered 2011)" and "- Live" keep a song from matching its plain self
    private fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*[(\[][^)\]]*(remaster|live|version|edit|mix|feat|ft\.|from|official|audio|video)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|live|version|edit|mix).*$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifEmpty { title }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n == 1 } }

    private const val Unauthorized = "\u0000unauthorized"

    private val NextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val AppBundle = Regex("""src="([^"]*/_next/static/chunks/pages/_app-[^"]+\.js)"""")
    private val KeyPatterns = listOf(
        Regex(""""([A-Za-z0-9+/=]{16,})"\.split\(""\)\.reverse\(\)\.join\(""\)"""),
        Regex("""from\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\.split"""),
    )
}
