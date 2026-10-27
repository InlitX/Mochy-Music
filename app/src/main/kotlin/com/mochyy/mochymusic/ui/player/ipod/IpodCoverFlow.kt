package com.mochyy.mochymusic.ui.player.ipod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.ui.player.styles.LocalStyleInteractive
import com.mochyy.mochymusic.ui.player.styles.whileStyleInteractive
import com.mochyy.mochymusic.viewmodels.IpodAlbum
import com.mochyy.mochymusic.viewmodels.IpodLibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// where cover flow is: which album is in front, the glide between them, and the album turned round to its tracks
@Stable
class IpodCoverFlowState(private val scope: CoroutineScope) {
    var albums by mutableStateOf<List<IpodAlbum>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    val position = Animatable(0f)
    val flip = Animatable(0f)
    var flipped by mutableStateOf(false)
        private set

    // the tracks on the back of the album, using the menus' own highlight and scrolling
    val back = IpodEntry(IpodPage.CoverFlow, -1)

    val current: IpodAlbum? get() = albums.getOrNull(index)

    fun show(list: List<IpodAlbum>) {
        albums = list
        val clamped = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (clamped != index) {
            index = clamped
            scope.launch { position.snapTo(clamped.toFloat()) }
        }
    }

    // a notch slides one album along, and a spring carries the covers there however fast the notches come
    fun move(steps: Int): Boolean {
        if (flipped) return back.move(steps)
        val target = (index + steps).coerceIn(0, (albums.size - 1).coerceAtLeast(0))
        if (target == index) return false
        index = target
        scope.launch { position.animateTo(target.toFloat(), GlideSpring) }
        return true
    }

    fun turnOver(tracks: IpodList) {
        back.list = tracks
        back.selected = 0
        back.first = 0
        flipped = true
        scope.launch { flip.animateTo(1f, FlipSpring) }
    }

    // false when the album was already facing front, so menu can leave cover flow instead
    fun turnBack(): Boolean {
        if (!flipped) return false
        flipped = false
        scope.launch { flip.animateTo(0f, FlipSpring) }
        return true
    }
}

// cover flow: the albums standing in a row on black, the one in front facing you, the rest turned away with their reflections under them
@Composable
fun IpodCoverFlow(
    state: IpodCoverFlowState,
    library: IpodLibraryViewModel?,
    scale: IpodScale,
    showing: () -> Boolean,
    onTapSide: (Int) -> Unit,
    onTapTrack: (Int) -> Unit,
) {
    // new albums turn up while it's open, the rest of the time it keeps what it had
    LaunchedEffect(state, library) {
        val source = library ?: return@LaunchedEffect
        snapshotFlow { showing() }.distinctUntilChanged().collectLatest { shown ->
            if (shown) source.coverFlow.collect { state.show(it) }
        }
    }
    val density = LocalDensity.current
    val side = minOf(scale.width * 0.42f, scale.content * 0.56f)
    val sidePx = with(density) { side.toPx() }
    val albums = state.albums
    val interactive = LocalStyleInteractive.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(interactive) {
                whileStyleInteractive(interactive) {
                    // a tap on either side slides that way, a tap on the front cover turns it over
                    detectTapGestures { at ->
                        if (state.flipped) return@detectTapGestures
                        val third = size.width / 3f
                        onTapSide(if (at.x < third) -1 else if (at.x > third * 2f) 1 else 0)
                    }
                }
            },
    ) {
        if (albums.isEmpty()) {
            Text(
                text = "No albums yet",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = scale.sp(18f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        val center by remember(state) { derivedStateOf { state.position.value.roundToInt() } }
        val window = (center - Window..center + Window).filter { it in albums.indices }
        val painters = ArrayList<Pair<Int, Painter>>(window.size)
        for (i in window) {
            key(albums[i].id) { painters += i to rememberCoverPainter(albums[i].cover, sidePx.roundToInt()) }
        }
        val matrix = remember { android.graphics.Matrix() }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val cx = size.width / 2f
            val cy = size.height * CoverCenter
            val pos = state.position.value
            val flip = state.flip.value
            // furthest first, so the nearer covers land on top
            painters.sortedByDescending { abs(it.first - pos) }.forEach { (i, painter) ->
                val d = i - pos
                val front = i == state.index
                if (front && flip >= 0.5f) return@forEach
                val corners = coverCorners(d, if (front) flip * 180f else 0f, sidePx, cx, cy)
                val alpha = if (front) 1f else 1f - flip
                if (alpha <= 0.01f) return@forEach
                drawCoverQuad(painter, sidePx, corners, CoverBlank, Color.Black, Reflection * alpha, matrix)
            }
        }
        AlbumBack(state, scale, sidePx, onTapTrack)
        AlbumCaption(state, scale)
    }
}

// the album's name and artist under the row, fading out while it's turned over
@Composable
private fun AlbumCaption(state: IpodCoverFlowState, scale: IpodScale) {
    val album = state.current ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = scale.content - scale.dp(56f))
            .padding(horizontal = scale.dp(16f))
            .graphicsLayer { alpha = 1f - state.flip.value },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(album.title, color = Color.White, fontSize = scale.sp(17f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (album.artist.isNotBlank()) {
            Text(album.artist, color = Color.White.copy(alpha = 0.6f), fontSize = scale.sp(15f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

// the back of the album: its tracks, turning in as the cover turns away
@Composable
private fun AlbumBack(state: IpodCoverFlowState, scale: IpodScale, coverPx: Float, onTapTrack: (Int) -> Unit) {
    val density = LocalDensity.current
    val width = scale.width * 0.74f
    val height = minOf(scale.content * 0.78f, width * 0.95f)
    val backScale = remember(width, height, density.fontScale) { IpodScale(width, height, density.fontScale) }
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    val left = (scale.width - width) / 2f
    val top = scale.content * 0.05f
    val look = LocalIpodLook.current.screen
    val showBack by remember(state) { derivedStateOf { state.flipped || state.flip.value > 0f } }
    Box(
        modifier = Modifier
            .offset(left, top)
            .size(width, height)
            .drawWithCache {
                val matrix = android.graphics.Matrix()
                val src = floatArrayOf(0f, 0f, widthPx, 0f, widthPx, heightPx, 0f, heightPx)
                onDrawWithContent {
                    val flip = state.flip.value
                    if (flip < 0.5f) return@onDrawWithContent
                    // the back finishes the turn the cover started, from edge on to facing you
                    val angle = flip * 180f - 180f
                    val dst = panelCorners(angle, widthPx, heightPx, coverPx)
                    matrix.setPolyToPoly(src, 0, dst, 0, 4)
                    val canvas = drawContext.canvas.nativeCanvas
                    canvas.save()
                    canvas.concat(matrix)
                    drawContent()
                    canvas.restore()
                }
            }
            .background(look.background),
    ) {
        if (showBack) {
            Column(Modifier.fillMaxSize()) {
                IpodHeader(state.current?.title.orEmpty(), backScale)
                IpodMenuList(state.back, backScale, marquee = false, onTapRow = onTapTrack, modifier = Modifier.fillMaxWidth().height(backScale.content))
            }
        }
    }
}

// the four corners of a cover d places from the front, turned a further extra degrees for the flip, seen through a little perspective
private fun coverCorners(d: Float, extra: Float, s: Float, cx: Float, cy: Float): FloatArray {
    val t = abs(d).coerceAtMost(1f)
    val sign = if (d < 0f) -1f else 1f
    val x3d = if (abs(d) <= 1f) sign * t * FirstStep * s else sign * (FirstStep + (abs(d) - 1f) * Step) * s
    // the side covers swing to face the middle quickly, then just slide along
    val swing = smooth((t * 1.4f).coerceAtMost(1f))
    val angle = Math.toRadians((sign * TurnDegrees * swing + extra).toDouble())
    val depthBack = PushBack * s * swing
    return project(x3d, depthBack, angle, s / 2f, s / 2f, cx, cy, s)
}

// the album back, as wide and tall as it is, turned by angle degrees around its middle
private fun panelCorners(angle: Float, w: Float, h: Float, coverPx: Float): FloatArray =
    project(w / 2f, 0f, Math.toRadians(angle.toDouble()), w / 2f, h / 2f, 0f, h / 2f, coverPx)

// a flat rectangle half w by half h, centred x3d across and pushed back by depth, turned around its upright middle; positive angles bring its right edge toward you
private fun project(x3d: Float, depth: Float, angle: Double, halfW: Float, halfH: Float, cx: Float, cy: Float, s: Float): FloatArray {
    val camera = Camera * s
    val c = cos(angle).toFloat()
    val sn = sin(angle).toFloat()
    val out = FloatArray(8)
    val xs = floatArrayOf(-halfW, halfW, halfW, -halfW)
    val ys = floatArrayOf(-halfH, -halfH, halfH, halfH)
    for (k in 0 until 4) {
        val z = camera + depth - xs[k] * sn
        val f = camera / z.coerceAtLeast(camera * 0.2f)
        out[k * 2] = cx + (x3d + xs[k] * c) * f
        out[k * 2 + 1] = cy + ys[k] * f
    }
    return out
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private const val Window = 7
// where the row of covers sits, as a share of the page's height
private const val CoverCenter = 0.4f
private const val FirstStep = 0.7f
private const val Step = 0.34f
private const val TurnDegrees = 70f
private const val PushBack = 0.45f
private const val Camera = 3.2f
private const val Reflection = 0.36f
private val CoverBlank = Color(0xFF2A2A2A)
private val GlideSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 280f)
private val FlipSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 220f)
