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
    // what the site's own pages call themselves since its 1.42 release
    private const val AppId = "mxm-com-v1.0"
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

    /** how one lookup ended: only [Found] and [Missing] are real answers worth keeping */
    private sealed interface Lookup {
        data class Found(val song: MusixmatchSong) : Lookup
        /** musixmatch answered, and doesn't have the song */
        data object Missing : Lookup
        /** couldn't ask: no connection, or the bot check held every road */
        data object Failed : Lookup
    }

    // a song asked for twice in one run (sheet closed and opened again) isn't looked up twice
    private val songs = ConcurrentHashMap<String, Lookup>()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences("musixmatch", Context.MODE_PRIVATE)
        MusixmatchBrowser.restore(prefs.getString("user_agent", null))
        prefs.getString("signing_key", null)?.let { signingKey = it }
    }

    // the best match for this song and everything its page says, or null when musixmatch doesn't have it (or couldn't be asked)
    suspend fun song(title: String, artist: String, durationSeconds: Int? = null): MusixmatchSong? = withContext(Dispatchers.IO) {
        val key = "${title.lowercase()}|${artist.lowercase()}"
        songs[key]?.let { return@withContext (it as? Lookup.Found)?.song }
        val lookup = runCatching { lookUp(title, artist, durationSeconds) }
            .onFailure { Timber.tag(Tag).w(it, "couldn't read $title by $artist") }
            .getOrDefault(Lookup.Failed)
        // a blocked or offline try is asked again the next time the sheet opens
        if (lookup !is Lookup.Failed) songs[key] = lookup
        Timber.tag(Tag).d("$title by $artist: ${lookup::class.simpleName}")
        (lookup as? Lookup.Found)?.song
    }

    private suspend fun lookUp(title: String, artist: String, durationSeconds: Int?): Lookup {
        val wanted = cleanTitle(title)
        // the api is quick when plain http gets through; the site's own search page when the bot check holds it
        val (path, base) = when (val api = search(wanted, artist, durationSeconds)) {
            is ApiSearch.Hit -> {
                val vanity = api.track.string("commontrack_vanity_id") ?: return Lookup.Found(fromSearch(api.track))
                "/lyrics/$vanity" to fromSearch(api.track)
            }
            // an empty api answer isn't trusted on its own: the site's search page gets the last word
            ApiSearch.Missing, ApiSearch.Blocked -> when (val hit = browserSearch(wanted, artist)) {
                null -> return Lookup.Failed
                "" -> return Lookup.Missing
                else -> hit to MusixmatchSong(title = title, artist = artist)
            }
        }
        val page = runCatching { page(path) }
            .onFailure { Timber.tag(Tag).w(it, "page $path didn't load") }
            .getOrNull()
            ?: return if (base.album != null) Lookup.Found(base.copy(pageUrl = "$Site$path")) else Lookup.Failed
        return Lookup.Found(merge(base, page, "$Site$path"))
    }

    // ---- the site's own search, through the hidden browser -------------------------------------

    /** the best result's page path, "" when the site found nothing like it, null when it couldn't be searched */
    private suspend fun browserSearch(title: String, artist: String): String? {
        val context = appContext ?: return null
        val leadArtist = artist.substringBefore(",").substringBefore("&").trim()
        val hits = MusixmatchBrowser.search(context, "$leadArtist $title") { found ->
            found.any { hitScore(it, title, leadArtist) >= GoodMatch }
        } ?: return null
        return hits
            .map { it to hitScore(it, title, leadArtist) }
            .filter { it.second >= GoodMatch }
            .maxByOrNull { it.second }
            ?.first?.path
            ?: ""
    }

    // a result's link names the artist and song ("/lyrics/eill-3/Finale"), the number only tells artists of one name apart
    private fun hitScore(hit: MusixmatchBrowser.Hit, title: String, artist: String): Int {
        val parts = hit.path.removePrefix("/lyrics/").split('/')
        if (parts.size < 2) return 0
        val linkArtist = normalize(decode(parts[0]).replace(Regex("-\\d+$"), "").replace('-', ' '))
        val linkTitle = normalize(decode(parts[1]).replace('-', ' '))
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        var score = when {
            linkTitle == wantedTitle -> 80
            linkTitle.isNotEmpty() && (linkTitle.contains(wantedTitle) || wantedTitle.contains(linkTitle)) -> 40
            normalize(hit.text).contains(wantedTitle) -> 30
            else -> 0
        }
        if (wantedArtist.isNotEmpty() && (linkArtist == wantedArtist || linkArtist.contains(wantedArtist) || normalize(hit.text).contains(wantedArtist))) score += 40
        return score
    }

    private fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    // ---- the api ----------------------------------------------------------------------------

    private sealed interface ApiSearch {
        data class Hit(val track: JsonObject) : ApiSearch
        data object Missing : ApiSearch
        data object Blocked : ApiSearch
    }

    private suspend fun search(title: String, artist: String, durationSeconds: Int?): ApiSearch {
        val body = api(
            "track.search",
            "q_track" to title,
            "q_artist" to artist,
            "page_size" to "10",
            "page" to "1",
            "s_track_rating" to "desc",
        ) ?: return ApiSearch.Blocked
        val tracks = (body["track_list"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("track") }
        return tracks
            .map { it to score(it, title, artist, durationSeconds) }
            .filter { it.second >= GoodMatch }
            .maxByOrNull { it.second }
            ?.first?.let { ApiSearch.Hit(it) }
            ?: ApiSearch.Missing
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

    // one signed call; a stale key is read again from the site, once
    private suspend fun api(method: String, vararg params: Pair<String, String>): JsonObject? {
        repeat(2) { attempt ->
            val query = (listOf("app_id" to AppId, "format" to "json") + params)
                .joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            // plain http only: the api is held at the bot check unless the pass cookie gets through, and then the search page takes over
            val reply = runCatching { directGet(sign("$ApiBase$method?$query"), "application/json") }.getOrNull() ?: return null
            if (reply.code in Blocked) return null
            // a stale key gets a plain http 401 with no body worth reading
            if (reply.code == 401) {
                if (attempt == 0 && refreshSigningKey()) return@repeat else return null
            }
            val message = reply.body?.let(::parse)?.obj("message") ?: return null
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
        val home = get("$Site/search?query=lyrics", "text/html")?.body ?: return false
        val bundle = AppBundle.find(home)?.groupValues?.get(1) ?: return false
        val script = get(if (bundle.startsWith("http")) bundle else "$Site$bundle", "*/*")?.body ?: return false
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
        val html = get("$Site$path", "text/html,application/xhtml+xml")?.body ?: return null
        val data = NextData.find(html)?.groupValues?.get(1) ?: return null
        return parse(data)?.obj("props")?.obj("pageProps")?.obj("data")
            ?.obj("trackInfo")?.obj("data")?.obj("track")
    }

    private fun merge(base: MusixmatchSong, page: JsonObject, pageUrl: String): MusixmatchSong {
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

    /** one answer from the site: its status, and its body when it's a success */
    private class Reply(val code: Int, val body: String?)

    // plain http first; when the bot check turns it away, the very same request goes through the hidden browser
    private suspend fun get(url: String, accept: String): Reply? {
        val direct = runCatching { directGet(url, accept) }
            .onFailure { Timber.tag(Tag).d("${it.message} for ${url.substringBefore('?')}") }
            .getOrNull()
            // no connection at all: the browser wouldn't get through either, and would only make the sheet wait
            ?: return null
        if (direct.code !in Blocked || !url.startsWith(Site)) return direct
        val context = appContext ?: return direct
        val (code, body) = MusixmatchBrowser.fetch(context, url, accept) ?: return null
        MusixmatchBrowser.userAgent?.let { agent ->
            context.getSharedPreferences("musixmatch", Context.MODE_PRIVATE).edit().putString("user_agent", agent).apply()
        }
        return Reply(code, body.takeIf { code in 200..299 })
    }

    private fun directGet(url: String, accept: String): Reply {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MusixmatchBrowser.userAgent ?: FallbackUserAgent)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$Site/")
            .apply { if (accept.contains("json")) header("Origin", Site) }
            .apply { MusixmatchBrowser.cookies()?.let { header("Cookie", it) } }
            .build()
        return client.newCall(request).execute().use { response ->
            // the check's page can come back as a 200 too; its header gives it away
            val code = if (response.header("x-amzn-waf-action") != null && response.isSuccessful) 202 else response.code
            Reply(code, if (code in 200..299) response.body.string() else null)
        }
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // "(remastered 2011)" and "- live" keep a song from matching its plain self
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

    // a title match (80 or 40) plus the artist (40): anything less is a different song
    private const val GoodMatch = 60

    // what the bot check answers with: a challenge, a refusal, a captcha, or too many requests
    private val Blocked = setOf(202, 403, 405, 429)

    private val NextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val AppBundle = Regex("""src="([^"]*/_next/static/chunks/pages/_app-[^"]+\.js)"""")
    private val KeyPatterns = listOf(
        Regex(""""([A-Za-z0-9+/=]{16,})"\.split\(""\)\.reverse\(\)\.join\(""\)"""),
        Regex("""from\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\.split"""),
    )
}
