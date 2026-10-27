//
// The contract between Mochy and a source module (Apple Music first). A module finds the track
// Mochy wants on its service and hands back a stream the player opens; Mochy does all the
// playing, so the equalizer, Audio FX, Crossmix and the visuals work on any source.
//
// Modules are built into Mochy today. The contract is kept free of Android, Media3 and HTTP
// libraries so that a module can also ship as its own APK, found and loaded at runtime (see
// ModuleRegistry in the app). Change any shape here and bump MODULE_API_VERSION: a module built
// against another version is refused rather than crashing.

package com.mochyy.mochymusic.module

const val MODULE_API_VERSION = 2

data class ModuleInfo(
    /** stable, lowercase: "applemusic" */
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val apiVersion: Int = MODULE_API_VERSION,
    /** where the module's own code lives, for the player's "star on github"; null when it isn't public */
    val sourceUrl: String? = null,
)

/** What Mochy gives a module. */
interface ModuleHost {
    /** small key-value storage private to this module: tokens, choices */
    val storage: ModuleStorage

    /** HTTP through Mochy's own client, so its proxy, DNS and IP-version settings apply. Blocking. */
    fun http(request: HttpRequest): HttpResponse

    fun log(message: String)
}

interface ModuleStorage {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
)

class HttpResponse(val code: Int, val body: ByteArray) {
    val ok: Boolean get() = code in 200..299
    val text: String get() = body.decodeToString()
}

/** The track Mochy wants to play, as Mochy knows it. */
data class TrackQuery(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
)

/**
 * How a module's account is signed into: Mochy opens [startUrl] in its sign-in browser and
 * passes every request header the service's own web page sends to [SourceModule.onSignInHeader].
 */
data class SignInSpec(
    val startUrl: String,
    /** the sites the sign-in may pass through */
    val origins: List<String>,
    /** the site whose requests carry the session (headers are read only there) */
    val sessionOrigins: Set<String>,
    val desktopSite: Boolean,
    val title: String,
    val subtitle: String,
)

sealed interface AccountState {
    data object SignedOut : AccountState
    data class SignedIn(val label: String) : AccountState
    data class Expired(val reason: String) : AccountState
}

/** One of a choice's options, best or most usual first. */
data class StreamOption(val id: String, val label: String, val description: String)

/**
 * A setting the module offers, shown as a row of chips: the stream quality, the catalog region.
 * The first option is the default; Mochy keeps what was picked and passes it to every call.
 */
data class ModuleChoice(val id: String, val label: String, val options: List<StreamOption>)

enum class StreamFormat { Progressive, Hls, Dash }

data class MatchedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    /** 0..1, how sure the module is it's the same recording */
    val confidence: Float,
)

/** Something the player can open. */
data class SourceStream(
    val url: String,
    val format: StreamFormat,
    val headers: Map<String, String> = emptyMap(),
    val drm: StreamDrm? = null,
    val sourceLabel: String,
    val qualityLabel: String,
    val matched: MatchedTrack,
    /** the audio's codec as listeners know it ("AAC", "HE-AAC", "FLAC"), for the player's format badge */
    val codec: String? = null,
    /**
     * the audio's sample rate, when the module knows it (Monochrome reads it from the FLAC's
     * header): the app passes over a stream the phone has no decoder for at that rate
     */
    val sampleRateHz: Int? = null,
)

sealed interface StreamDrm {
    /**
     * Widevine: the device's own CDM decrypts, inside the platform, as on the service's web
     * player. [license] trades a CDM challenge for a license; it's called on the player's DRM
     * thread and may block. [initData] are the stream's PSSH boxes, for checking a license can be
     * had before anything plays.
     */
    class Widevine(val license: LicenseServer, val initData: List<ByteArray>) : StreamDrm
}

fun interface LicenseServer {
    fun acquire(challenge: ByteArray): ByteArray
}

sealed interface ResolveResult {
    data class Found(val stream: SourceStream) : ResolveResult
    /** the service doesn't have this song */
    data class NotFound(val reason: String) : ResolveResult
    /** the service couldn't be asked: signed out, offline, region */
    data class Unavailable(val reason: String) : ResolveResult
}

/** One line of a module's self-test. [ok] null while the step runs. */
data class DiagnosticStep(val name: String, val ok: Boolean?, val detail: String)

interface SourceModule {
    val info: ModuleInfo

    /** called once, before anything else */
    fun attach(host: ModuleHost)

    val signIn: SignInSpec?

    fun accountState(): AccountState

    /** a header the sign-in page sent; true once there's everything needed to sign in */
    fun onSignInHeader(name: String, value: String, url: String): Boolean

    /** asks the service whether the saved account still works */
    suspend fun verifyAccount(): AccountState

    fun signOut()

    val choices: List<ModuleChoice>

    /** [picks]: choice id to option id, for the choices the user changed */
    suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult

    /** the module's own checks, step by step, for the test button; the host adds the CDM part */
    suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult
}
