// a module added from a link: a service that answers search, stream and lyrics over plain http, no code of its own runs in mochy

package com.mochyy.mochymusic.modules.web

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.LookModule
import com.mochyy.mochymusic.module.LyricsModule
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleFeature
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.NO_ACCOUNT_LABEL
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal class WebModule(val base: String, manifest: WebModuleManifest) : SourceModule, LyricsModule, LookModule {

    private val siteName = runCatching { URI(base).host }.getOrNull().orEmpty()

    override val info = ModuleInfo(
        id = WebModuleManifest.idFor(base, manifest),
        name = manifest.name.ifBlank { siteName },
        version = manifest.version.ifBlank { "1" },
        description = manifest.description.ifBlank { "Added from $siteName" },
        author = manifest.author,
    )

    override val adds: Set<ModuleFeature> = buildSet {
        if (manifest.playsSongs) add(ModuleFeature.AudioSource)
        if (manifest.hasLyrics) add(ModuleFeature.Lyrics)
        if (manifest.look.isNotEmpty()) add(ModuleFeature.Look)
    }

    override val look: Map<String, String> = manifest.look.mapNotNull { (key, value) -> value.contentOrNull?.let { key to it } }.toMap()

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val matches = ConcurrentHashMap<String, CatalogTrack>()
    private val misses = ConcurrentHashMap<String, Long>()

    // a search answer may already carry each song's stream, then there's no second request
    private val directStreams = ConcurrentHashMap<String, String>()

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override fun accountState(): AccountState {
        val reason = host.storage.get("unreachable") ?: return AccountState.SignedIn(NO_ACCOUNT_LABEL)
        val markedAt = host.storage.get("unreachable_at")?.toLongOrNull() ?: 0L
        return if (System.currentTimeMillis() - markedAt < UnreachableRetryMs) AccountState.Expired(reason) else AccountState.SignedIn(NO_ACCOUNT_LABEL)
    }

    override suspend fun verifyAccount(): AccountState {
        val response = runCatching { host.http(HttpRequest("$base/manifest.json", headers = headers)) }.getOrNull()
        return if (response?.ok == true) {
            host.storage.set("unreachable", null)
            AccountState.SignedIn("$NO_ACCOUNT_LABEL · $siteName answers")
        } else {
            val reason = "$siteName didn't answer (${response?.code ?: "offline"})"
            markUnreachable(reason)
            AccountState.Expired(reason)
        }
    }

    override fun signOut() {
        matches.clear()
        misses.clear()
    }

    // ---- songs ----------------------------------------------------------------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult = diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        if (ModuleFeature.AudioSource !in adds) return ResolveResult.Unavailable("${info.name} doesn't play songs")
        val key = "${track.title}|${track.artists.joinToString(",")}"
        val missedAt = misses[key]
        if (missedAt != null && System.currentTimeMillis() - missedAt < MissMemoryMs) {
            report(DiagnosticStep("Find the song", false, "Not on ${info.name}"))
            return ResolveResult.NotFound("Not on ${info.name}")
        }

        report(DiagnosticStep("Find the song", null, "Searching ${info.name}…"))
        val found = matches[key] ?: run {
            val query = "${track.artists.firstOrNull().orEmpty()} ${TrackMatcher.core(track.title)}".trim()
            val response = runCatching { host.http(HttpRequest("$base/search?q=${enc(query)}", headers = headers)) }.getOrElse {
                report(DiagnosticStep("Find the song", false, "$siteName didn't answer"))
                markUnreachable("$siteName didn't answer")
                return ResolveResult.Unavailable("$siteName didn't answer")
            }
            if (!response.ok) {
                report(DiagnosticStep("Find the song", false, "$siteName answered ${response.code}"))
                return ResolveResult.Unavailable("$siteName answered ${response.code}")
            }
            host.storage.set("unreachable", null)
            val best = TrackMatcher.ranked(track, tracksIn(response.text)).firstOrNull()?.track
            if (best == null) {
                misses[key] = System.currentTimeMillis()
                report(DiagnosticStep("Find the song", false, "No matching recording of “${track.title}”"))
                return ResolveResult.NotFound("No matching recording on ${info.name}")
            }
            matches[key] = best
            best
        }
        report(DiagnosticStep("Find the song", true, "${found.title} · ${found.artist}"))

        report(DiagnosticStep("Stream", null, "Asking for the stream…"))
        val stream = streamFor(found)
        if (stream == null) {
            report(DiagnosticStep("Stream", false, "$siteName gave no stream Mochy can play"))
            return ResolveResult.Unavailable("$siteName gave no stream Mochy can play")
        }
        report(DiagnosticStep("Stream", true, stream.qualityLabel))
        return ResolveResult.Found(
            stream.copy(
                matched = MatchedTrack(
                    id = found.id,
                    title = found.title,
                    artist = found.artist,
                    album = found.album,
                    durationMs = found.durationMs,
                    confidence = TrackMatcher.score(track, found),
                ),
            ),
        )
    }

    private fun tracksIn(text: String): List<CatalogTrack> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val tracks = root["tracks"] as? JsonArray ?: return emptyList()
        return tracks.mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            val id = t.string("id") ?: return@mapNotNull null
            // seconds, though a few services send milliseconds
            val duration = (t["duration"] as? JsonPrimitive)?.doubleOrNull?.let { if (it > 10_000) it.toLong() else (it * 1000).toLong() }
            CatalogTrack(
                id = id,
                title = t.string("title") ?: return@mapNotNull null,
                artist = t.string("artist").orEmpty(),
                album = t.string("album"),
                durationMs = duration,
                isrc = t.string("isrc"),
            ).also { track -> t.string("streamURL")?.let { directStreams[track.id] = it } }
        }
    }

    private fun streamFor(track: CatalogTrack): SourceStream? {
        directStreams[track.id]?.let { url -> return streamOf(url, JsonObject(emptyMap())) }
        val response = runCatching { host.http(HttpRequest("$base/stream/${enc(track.id)}", headers = headers)) }.getOrNull()
        if (response == null || !response.ok) return null
        val answer = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull() ?: return null
        // a locked stream would need keys mochy doesn't have
        if ((answer["encrypted"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull !in setOf(null, "", "false", "none")) } == true) return null
        val url = answer.string("url")?.takeIf { it.startsWith("http") } ?: return null
        return streamOf(url, answer)
    }

    private fun streamOf(url: String, answer: JsonObject): SourceStream {
        val stated = listOfNotNull(answer.string("manifest"), answer.string("mediaType"), answer.string("format")).joinToString(" ").lowercase()
        val format = when {
            "hls" in stated || "m3u8" in stated || ".m3u8" in url -> StreamFormat.Hls
            "dash" in stated || "mpd" in stated || ".mpd" in url -> StreamFormat.Dash
            else -> StreamFormat.Progressive
        }
        val codec = codecName(answer.string("codec") ?: answer.string("fileCodec") ?: answer.string("format") ?: url.substringBefore('?').substringAfterLast('.', ""))
        val rate = (answer["sampleRate"] as? JsonPrimitive)?.doubleOrNull?.let { if (it < 1000) (it * 1000).toInt() else it.toInt() }
        val bits = (answer["bitDepth"] as? JsonPrimitive)?.doubleOrNull?.toInt()
        val quality = listOfNotNull(answer.string("quality"), answer.string("audioQuality")).firstOrNull { it.isNotBlank() }
        val label = quality ?: listOfNotNull(codec, bits?.let { "$it-bit" }, rate?.let { "${it / 1000.0} kHz".replace(".0 ", " ") }).joinToString(" · ").ifEmpty { "Stream" }
        return SourceStream(
            url = url,
            format = format,
            sourceLabel = info.name,
            qualityLabel = label,
            codec = codec,
            sampleRateHz = rate,
            matched = MatchedTrack("", "", "", null, null, 0f),
        )
    }

    private fun codecName(raw: String): String? = when (raw.lowercase().trim()) {
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        "aac", "mp4a", "m4a", "mp4" -> "AAC"
        "opus", "webm" -> "Opus"
        "mp3", "mpeg" -> "MP3"
        "eac3", "ec-3", "ac3" -> "Dolby"
        else -> null
    }

    // ---- lyrics ---------------------------------------------------------------------------------

    override suspend fun lyrics(track: TrackQuery): String? {
        if (ModuleFeature.Lyrics !in adds) return null
        val params = buildList {
            add("title=${enc(track.title)}")
            add("artist=${enc(track.artists.joinToString(", "))}")
            track.album?.let { add("album=${enc(it)}") }
            track.durationMs?.let { add("duration=${it / 1000}") }
        }.joinToString("&")
        val response = runCatching { host.http(HttpRequest("$base/lyrics?$params", headers = headers)) }.getOrNull()
        if (response == null || !response.ok) return null
        val answer = runCatching { json.parseToJsonElement(response.text) as? JsonObject }.getOrNull()
            ?: return response.text.takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
        // timed lyrics first, plain ones when that's all there is
        return listOf("syncedLyrics", "lrc", "lyrics", "plainLyrics").firstNotNullOfOrNull { answer.string(it)?.takeIf(String::isNotBlank) }
    }

    private fun markUnreachable(reason: String) {
        host.storage.set("unreachable", reason)
        host.storage.set("unreachable_at", System.currentTimeMillis().toString())
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private companion object {
        const val MissMemoryMs = 30L * 60 * 1000
        const val UnreachableRetryMs = 10L * 60 * 1000
        val headers = mapOf("Accept" to "application/json", "User-Agent" to "Mochy")
    }
}
