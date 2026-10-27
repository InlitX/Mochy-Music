package com.mochyy.mochymusic.ui.player.ipod

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.Player
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.extensions.toMediaItem
import com.mochyy.mochymusic.playback.PlayerConnection
import com.mochyy.mochymusic.playback.queues.ListQueue
import com.mochyy.mochymusic.ui.component.BottomSheetState
import com.mochyy.mochymusic.ui.component.LocalPlayerBottomSheetState
import com.mochyy.mochymusic.ui.player.findActivity
import com.mochyy.mochymusic.ui.player.styles.LocalStyleInteractive
import com.mochyy.mochymusic.ui.player.styles.rememberStyleShowing
import com.mochyy.mochymusic.ui.player.styles.whileStyleInteractive
import com.mochyy.mochymusic.ui.theme.ForceDarkTheme
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.ui.utils.stableSystemBars
import com.mochyy.mochymusic.viewmodels.IpodLibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// the ipod classic as a player: fills a phone edge to edge, sits whole on black on a big screen
@Composable
fun IpodPlayerStyle(modifier: Modifier = Modifier, preview: Boolean = false) {
    val density = LocalDensity.current
    val bars = WindowInsets.stableSystemBars
    val topInset = bars.getTop(density)
    val bottomInset = bars.getBottom(density)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .then(if (preview) SwallowTouches else Modifier),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // a tablet, or a phone on its side, has no room for a full-height ipod, so it gets the whole device instead
        val wholeDevice = minOf(maxWidth, maxHeight) >= 600.dp || maxWidth > maxHeight * 0.62f
        val geometry = remember(w, h, topInset, bottomInset, wholeDevice) {
            if (wholeDevice) deviceGeometry(w, h, topInset.toFloat(), bottomInset.toFloat())
            else phoneGeometry(w, h, topInset.toFloat(), bottomInset.toFloat())
        }
        if (!wholeDevice && !preview) DarkStatusIcons()
        Box(Modifier.fillMaxSize().drawWithCache { onDrawBehind { drawBody(geometry, wholeDevice) } })
        androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = InterFontFamily)) {
            IpodFace(geometry, preview, density)
        }
    }
}

@Composable
private fun IpodFace(geometry: IpodGeometry, preview: Boolean, density: Density) {
    val connection = LocalPlayerConnection.current
    val sheet = LocalPlayerBottomSheetState.current
    val library: IpodLibraryViewModel? = if (preview) null else hiltViewModel()
    val scope = rememberCoroutineScope()
    val nav = remember(scope) { IpodNavigator(scope) }
    val nowPlaying = remember { IpodNowPlayingState() }
    val haptics = rememberIpodHaptics()
    val context = LocalContext.current
    val controller = remember(nav, nowPlaying, scope, connection, library, haptics, context) {
        IpodController(nav, nowPlaying, scope, connection, library, context.getSystemService(Context.AUDIO_SERVICE) as AudioManager, haptics)
    }
    val showing = rememberStyleShowing()
    val open by remember(sheet) { derivedStateOf { sheet == null || sheet.isExpanded } }
    val marquee = open && !preview

    BarTimeout(nowPlaying)
    Chevron(geometry, sheet, preview, density)

    val screen = geometry.screen
    val bezel = screen.width * BezelShare
    val innerW = with(density) { (screen.width - bezel * 2f).toDp() }
    val innerH = with(density) { (screen.height - bezel * 2f).toDp() }
    val scale = remember(innerW, innerH, density.fontScale) { IpodScale(innerW, innerH, density.fontScale) }
    Box(
        modifier = Modifier
            .place(screen, density)
            .drawBehind { drawRect(Bezel) },
    ) {
        Column(
            modifier = Modifier
                .padding(with(density) { bezel.toDp() })
                .fillMaxSize()
                .background(Color.White)
                .clipToBounds(),
        ) {
            TitleBar(nav, scale)
            IpodPages(nav, Modifier.fillMaxWidth().weight(1f)) { entry ->
                IpodPageContent(entry, controller, library, scale, marquee, showing)
            }
        }
        // the thin dark edge down the right of the glass, as on the mockup
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = with(density) { bezel.toDp() }, horizontal = with(density) { bezel.toDp() })
                .fillMaxHeight()
                .width(scale.dp(3.5f))
                .background(GlassEdge),
        )
    }

    IpodClickWheel(
        actions = controller,
        haptics = haptics,
        enabled = !preview && connection != null,
        modifier = Modifier.place(geometry.wheel, density),
    )
}

// the volume and scrub bars give way to the song's time once the wheel's been left alone
@Composable
private fun BarTimeout(nowPlaying: IpodNowPlayingState) {
    LaunchedEffect(nowPlaying.bar, nowPlaying.touchedAt) {
        if (nowPlaying.bar == IpodBar.PROGRESS) return@LaunchedEffect
        delay(if (nowPlaying.bar == IpodBar.VOLUME) VolumeShownMs else ScrubShownMs)
        nowPlaying.bar = IpodBar.PROGRESS
    }
}

// read here so a page change only redraws the header, not the whole face
@Composable
private fun TitleBar(nav: IpodNavigator, scale: IpodScale) = IpodHeader(nav.top.page.title, scale)

// one page of the menu tree: its list, the main menu with its slideshow, or now playing
@Composable
private fun IpodPageContent(
    entry: IpodEntry,
    controller: IpodController,
    library: IpodLibraryViewModel?,
    scale: IpodScale,
    marquee: Boolean,
    showing: () -> Boolean,
) {
    when (entry.page) {
        IpodPage.NowPlaying -> IpodNowPlaying(controller.nowPlaying, scale, marquee)
        IpodPage.Main -> Row(Modifier.fillMaxSize()) {
            IpodMenuList(entry, scale, marquee, { controller.tapRow(entry, it) }, Modifier.weight(1f).fillMaxHeight())
            val covers by (library?.covers ?: NoCovers).collectAsState()
            val fallback = rememberArtwork()
            IpodCoverCycle(
                covers = covers.ifEmpty { listOfNotNull(fallback) },
                running = showing,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        else -> {
            // the list stays as it was while the player is closed, and catches up when it opens again
            LaunchedEffect(entry, library) {
                val source = library ?: return@LaunchedEffect
                snapshotFlow { showing() }.distinctUntilChanged().collectLatest { shown ->
                    if (!shown) return@collectLatest
                    source.list(entry.page).collect {
                        entry.list = it
                        entry.clamp(VisibleRows)
                    }
                }
            }
            IpodMenuList(entry, scale, marquee, { controller.tapRow(entry, it) }, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun rememberArtwork(): String? {
    val connection = LocalPlayerConnection.current ?: return null
    val track by connection.uiState.trackInfo.collectAsState()
    return track.thumbnailUrl
}

// the chevron at the top of the body closes the player, the same as the bar on every other style
@Composable
private fun Chevron(geometry: IpodGeometry, sheet: BottomSheetState?, preview: Boolean, density: Density) {
    val interactive = LocalStyleInteractive.current
    val target = with(density) { Rect(Offset(geometry.chevron.x - 32.dp.toPx(), geometry.chevron.y - 24.dp.toPx()), androidx.compose.ui.geometry.Size(64.dp.toPx(), 48.dp.toPx())) }
    Box(
        modifier = Modifier
            .place(target, density)
            .then(
                if (preview || sheet == null) Modifier else Modifier.pointerInput(sheet, interactive) {
                    whileStyleInteractive(interactive) { detectTapGestures { sheet.collapseSoft() } }
                }
            )
            .drawWithCache {
                val half = geometry.unit * 0.0367f
                val depth = geometry.unit * 0.026f
                val path = Path().apply {
                    moveTo(size.width / 2f - half, size.height / 2f - depth / 2f)
                    lineTo(size.width / 2f, size.height / 2f + depth / 2f)
                    lineTo(size.width / 2f + half, size.height / 2f - depth / 2f)
                }
                val stroke = Stroke(width = geometry.unit * 0.0075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, ChevronInk, style = stroke) }
            },
    )
}

// dark status bar icons over the light body, put back to white the moment the player heads down or the style changes
@Composable
private fun DarkStatusIcons() {
    val view = LocalView.current
    val sheet = LocalPlayerBottomSheetState.current
    LaunchedEffect(view, sheet) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        try {
            snapshotFlow { sheet == null || (sheet.isExpandedOrExpanding && sheet.progress > 0.5f) }
                .distinctUntilChanged()
                .collect { light -> controller.isAppearanceLightStatusBars = light || !ForceDarkTheme }
        } finally {
            controller.isAppearanceLightStatusBars = !ForceDarkTheme
        }
    }
}

// everything the wheel and the screen can do, in one place
@Stable
private class IpodController(
    val nav: IpodNavigator,
    val nowPlaying: IpodNowPlayingState,
    private val scope: CoroutineScope,
    private val connection: PlayerConnection?,
    private val library: IpodLibraryViewModel?,
    private val audio: AudioManager,
    private val haptics: IpodHaptics,
) : IpodWheelActions {
    private var seekJob: Job? = null

    private val onNowPlaying: Boolean get() = nav.top.page == IpodPage.NowPlaying

    override val accelerates: Boolean get() = !onNowPlaying

    override fun rotate(steps: Int): Boolean = when {
        !onNowPlaying -> nav.top.move(steps, VisibleRows)
        nowPlaying.bar == IpodBar.SCRUB -> scrub(steps)
        else -> volume(steps)
    }

    override fun menu() {
        if (onNowPlaying && nowPlaying.bar != IpodBar.PROGRESS) {
            nowPlaying.bar = IpodBar.PROGRESS
            return
        }
        nav.back()
    }

    override fun menuHeld() = nav.backToMain()

    override fun center() {
        if (!onNowPlaying) {
            pick(nav.top, nav.top.selected)
            return
        }
        // the middle button on now playing brings up the scrubber, and puts it away again
        nowPlaying.bar = if (nowPlaying.bar == IpodBar.SCRUB) IpodBar.PROGRESS else IpodBar.SCRUB
        nowPlaying.scrubMs = -1L
        touch()
    }

    override fun next() {
        connection?.seekToNext()
    }

    override fun previous() {
        connection?.seekToPrevious()
    }

    override fun playPause() {
        val player = connection?.player ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0)
            player.playWhenReady = true
        } else {
            connection.togglePlayPause()
        }
    }

    override fun scan(forward: Boolean) {
        val player = connection?.player ?: return
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + if (forward) ScanStepMs else -ScanStepMs).coerceIn(0L, end))
    }

    // a tap on a line of the screen highlights it and picks it, the way pressing the middle button would
    fun tapRow(entry: IpodEntry, index: Int) {
        if (entry !== nav.top) return
        entry.selected = index
        entry.clamp(VisibleRows)
        haptics.click()
        pick(entry, index)
    }

    private fun pick(entry: IpodEntry, index: Int) {
        when (val pick = entry.list.rows.getOrNull(index)?.pick) {
            is IpodPick.Open -> nav.push(pick.page)
            is IpodPick.Play -> {
                val songs = entry.list.songs
                val title = entry.page.title
                nav.showNowPlaying()
                scope.launch {
                    val items = withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } }
                    connection?.playQueue(ListQueue(title = title, items = items, startIndex = pick.index))
                }
            }
            IpodPick.Shuffle -> scope.launch {
                val items = library?.shuffledLibrary().orEmpty()
                if (items.isEmpty()) return@launch
                connection?.playQueue(ListQueue(title = "Shuffle Songs", items = items))
                nav.showNowPlaying()
            }
            null -> Unit
        }
    }

    private fun volume(steps: Int): Boolean {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (now + steps).coerceIn(0, max)
        nowPlaying.bar = IpodBar.VOLUME
        touch()
        if (target != now) {
            // some phones refuse in do not disturb, the bar then just shows where it really is
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
        val reached = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        nowPlaying.volume = reached.toFloat() / max
        return reached != now
    }

    private fun scrub(steps: Int): Boolean {
        val player = connection?.player ?: return false
        val duration = player.duration.takeIf { it > 0 } ?: return false
        val from = if (nowPlaying.scrubMs >= 0) nowPlaying.scrubMs else player.currentPosition
        val step = (duration / 100L).coerceIn(1_000L, 10_000L)
        val target = (from + steps * step).coerceIn(0L, duration)
        touch()
        if (target == from) return false
        nowPlaying.scrubMs = target
        // the player only seeks once the wheel rests, so a stream isn't asked to buffer at every notch
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(ScrubSettleMs)
            player.seekTo(nowPlaying.scrubMs)
            nowPlaying.scrubMs = -1L
        }
        return true
    }

    private fun touch() {
        nowPlaying.touchedAt = SystemClock.uptimeMillis()
    }
}

// where everything sits, in pixels: the body, the chevron, the screen's outer edge and the wheel
@Immutable
private class IpodGeometry(
    val body: Rect,
    val corner: Float,
    val unit: Float,
    val chevron: Offset,
    val screen: Rect,
    val wheel: Rect,
)

// a phone: the body is the whole screen and the wheel takes whatever height is left under the glass
private fun phoneGeometry(w: Float, h: Float, top: Float, bottom: Float): IpodGeometry {
    val chevron = Offset(w / 2f, top + w * 0.064f)
    val screenTop = chevron.y + w * 0.091f
    val screen = Rect(w * ScreenLeft, screenTop, w * (ScreenLeft + ScreenWidth), screenTop + w * ScreenWidth * ScreenAspect)
    val floor = h - bottom
    val diameter = minOf(w * WheelShare, (floor - screen.bottom) * 0.84f)
    val cy = ((screen.bottom + floor) / 2f + w * 0.02f).coerceAtMost(floor - diameter / 2f - w * 0.04f)
    return IpodGeometry(
        body = Rect(0f, 0f, w, h),
        corner = 0f,
        unit = w,
        chevron = chevron,
        screen = screen,
        wheel = Rect(Offset(w / 2f, cy), diameter / 2f),
    )
}

// a big screen: the whole ipod at the mockup's proportions, as large as fits, on black
private fun deviceGeometry(w: Float, h: Float, top: Float, bottom: Float): IpodGeometry {
    val room = h - top - bottom
    val bodyW = minOf(w * 0.88f, room * 0.94f / BodyAspect)
    val bodyH = bodyW * BodyAspect
    val body = Rect(Offset((w - bodyW) / 2f, top + (room - bodyH) / 2f), androidx.compose.ui.geometry.Size(bodyW, bodyH))
    val screenTop = body.top + bodyW * 0.1976f
    val screenLeft = body.left + bodyW * ScreenLeft
    return IpodGeometry(
        body = body,
        corner = bodyW * 0.1224f,
        unit = bodyW,
        chevron = Offset(body.center.x, body.top + bodyW * 0.1066f),
        screen = Rect(screenLeft, screenTop, screenLeft + bodyW * ScreenWidth, screenTop + bodyW * ScreenWidth * ScreenAspect),
        wheel = Rect(Offset(body.center.x, body.top + bodyW * 1.5752f), bodyW * WheelShare / 2f),
    )
}

// the light grey shell, darker along its edges and toward the bottom like the mockup's
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBody(geometry: IpodGeometry, wholeDevice: Boolean) {
    if (wholeDevice) drawRect(Color.Black)
    val body = geometry.body
    val corner = CornerRadius(geometry.corner)
    drawRoundRect(IpodBody, body.topLeft, body.size, corner)
    drawRoundRect(
        Brush.verticalGradient(0f to Color(0x0D000000), 0.03f to Color.Transparent, 0.86f to Color.Transparent, 1f to Color(0x3A000000), startY = body.top, endY = body.bottom),
        body.topLeft, body.size, corner,
    )
    drawRoundRect(
        Brush.horizontalGradient(0f to Color(0x1C000000), 0.045f to Color.Transparent, 0.955f to Color.Transparent, 1f to Color(0x1C000000), startX = body.left, endX = body.right),
        body.topLeft, body.size, corner,
    )
}

private fun Modifier.place(rect: Rect, density: Density): Modifier = with(density) {
    offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .size(rect.width.toDp(), rect.height.toDp())
}

// a style card in the picker shows the ipod, it doesn't work it
private val SwallowTouches = Modifier.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

private val NoCovers = kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>())

// the mockup's body is 572 px wide, its screen 486 x 367 starting 43 px in, its wheel 375 px across
private const val ScreenLeft = 43f / 572f
private const val ScreenWidth = 486f / 572f
private const val ScreenAspect = 367f / 486f
private const val WheelShare = 375f / 572f
private const val BodyAspect = 1248f / 572f
private const val BezelShare = 6f / 486f

private const val VolumeShownMs = 1_600L
private const val ScrubShownMs = 4_000L
private const val ScrubSettleMs = 300L
private const val ScanStepMs = 4_000L

internal val IpodBody = Color(0xFFE6E6E6)
private val Bezel = Color(0xFF8A8A8A)
private val GlassEdge = Color(0xE6111111)
private val ChevronInk = Color(0xFF555555)
