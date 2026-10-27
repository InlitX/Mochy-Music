package com.mochyy.mochymusic.ui.player.ipod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.ui.component.damageBoundary
import com.mochyy.mochymusic.ui.player.styles.LocalStyleInteractive
import com.mochyy.mochymusic.ui.player.styles.whileStyleInteractive
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

// sizes on the ipod's screen, written in pixels of the 474 px wide mockup and scaled to the real screen
@Immutable
class IpodScale(val width: Dp, val height: Dp, private val fontScale: Float) {
    fun dp(mock: Float): Dp = width * (mock / MockScreenWidth)

    // text grows with the screen, not with the phone's font setting, or big fonts would spill off the little screen
    fun sp(mock: Float): TextUnit = (dp(mock).value / fontScale).sp

    val header: Dp get() = dp(MockHeader)
    val content: Dp get() = height - header

    // as many rows as fit the glass at about their mockup height, so a tall screen shows more of the list
    fun rows(art: Boolean): Int = (content / dp(if (art) MockArtRow else MockRow)).roundToInt().coerceAtLeast(3)

    fun rowHeight(art: Boolean): Dp = content / rows(art)

}

// a scale for a bare list with no header above it, like the back of an album in cover flow
fun ipodListScale(width: Dp, height: Dp, fontScale: Float) = IpodScale(width, height + width * (MockHeader / MockScreenWidth), fontScale)

const val MockScreenWidth = 474f
private const val MockHeader = 28f
private const val MockRow = 46.6f
private const val MockArtRow = 66f
private const val MockScrollbar = 11f

// the pages slide in from the right as you go deeper and back out as you press menu, cover flow rises from the bottom like a sheet
@Composable
fun IpodPages(nav: IpodNavigator, modifier: Modifier = Modifier, page: @Composable (entry: IpodEntry, slideX: () -> Float) -> Unit) {
    val background = LocalIpodLook.current.screen.background
    Box(modifier.clipToBounds()) {
        val shown = nav.top
        val leaving = nav.leaving
        // the deeper page is always on top: the one coming in, or the one going back out
        val deeper = if (nav.forward) shown else leaving
        val order = if (nav.forward) listOfNotNull(leaving, shown) else listOfNotNull(shown, leaving)
        val sheet = deeper?.page == IpodPage.CoverFlow
        for (entry in order) {
            key(entry.id) {
                val arriving = entry === shown
                val moving = leaving != null
                // how far across the page sits, as a share of the width: under a sheet nothing moves sideways
                val slideX: () -> Float = {
                    val t = nav.slide.value
                    when {
                        !moving || sheet -> 0f
                        nav.forward -> if (arriving) 1f - t else -t
                        else -> if (arriving) t - 1f else t
                    }
                }
                val rise = moving && sheet && entry === deeper
                // the main menu moves only its list, so its covers hold still and get covered over
                val holdsStill = entry.page == IpodPage.Main
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (!holdsStill) translationX = size.width * slideX()
                            if (rise) {
                                val t = nav.slide.value
                                translationY = size.height * if (nav.forward) 1f - t else t
                            }
                        }
                        .background(background),
                ) {
                    page(entry, slideX)
                }
            }
        }
    }
}

// a menu's lines, the highlighted one in that glossy blue, with a scrollbar once there's more than fits
@Composable
fun IpodMenuList(
    entry: IpodEntry,
    scale: IpodScale,
    marquee: Boolean,
    onTapRow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = entry.list
    val rows = list.rows
    val visible = scale.rows(list.art)
    val rowHeight = scale.rowHeight(list.art)
    SideEffect {
        if (entry.visibleRows != visible) {
            entry.visibleRows = visible
            entry.clamp()
        }
    }
    val first = entry.first
    val selected = entry.selected
    val scrolls = rows.size > visible
    val interactive = LocalStyleInteractive.current
    val tap by rememberUpdatedState(onTapRow)
    val rowSize by rememberUpdatedState(rowHeight)
    Box(
        modifier = modifier.pointerInput(entry, interactive) {
            whileStyleInteractive(interactive) {
                // a plain tap picks a line; nothing is eaten, so a drag still pulls the player down and a hold still opens the styles
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var still = true
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > slop) still = false
                            if (!change.pressed) break
                        }
                        still
                    }
                    if (lifted == true) {
                        val index = entry.first + (down.position.y / rowSize.toPx()).toInt()
                        if (index in entry.list.rows.indices) tap(index)
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(end = if (scrolls) scale.dp(MockScrollbar) else 0.dp)) {
            for (index in first until minOf(first + visible, rows.size)) {
                val row = rows[index]
                key(row.key) {
                    IpodMenuRow(row, list.art, index == selected, rowHeight, scale, marquee && index == selected)
                }
            }
        }
        if (scrolls) {
            IpodScrollbar(
                entry = entry,
                count = rows.size,
                visible = visible,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(scale.dp(MockScrollbar)),
            )
        }
    }
}

@Composable
private fun IpodMenuRow(row: IpodRow, art: Boolean, selected: Boolean, height: Dp, scale: IpodScale, marquee: Boolean) {
    val colors = LocalIpodLook.current.screen
    val ink = when {
        selected -> Color.White
        row.pick == null -> colors.quietInk
        else -> colors.ink
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .then(if (selected) Modifier.background(SelectionBrush) else Modifier)
            .padding(start = scale.dp(if (art) 6f else 10f), end = scale.dp(10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art && row.pick != null) {
            val side = height - scale.dp(10f)
            AsyncImage(
                model = row.art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(side)
                    .clip(if (row.roundArt) CircleShape else RoundedCornerShape(scale.dp(3f)))
                    .background(colors.artBlank),
            )
            Spacer(Modifier.width(scale.dp(10f)))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = ink,
                fontSize = scale.sp(if (art) 18f else 19f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                // the highlighted line scrolls its name when it's too long, in a layer of its own so only it redraws
                modifier = if (marquee) {
                    Modifier
                        .graphicsLayer()
                        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 1500, velocity = 28.dp)
                } else Modifier,
            )
            if (art && row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    color = if (selected) Color.White.copy(alpha = 0.85f) else colors.softInk,
                    fontSize = scale.sp(15f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.opensPage) {
            Canvas(
                Modifier
                    .padding(start = scale.dp(8f))
                    .size(scale.dp(9f), scale.dp(15f)),
            ) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                }
                drawPath(path, ink, style = Stroke(width = size.width * 0.36f, cap = StrokeCap.Square))
            }
        }
    }
}

@Composable
private fun IpodScrollbar(entry: IpodEntry, count: Int, visible: Int, modifier: Modifier) {
    val colors = LocalIpodLook.current.screen
    Canvas(modifier) {
        drawRect(colors.scrollTrack)
        drawRect(colors.trackLine, style = Stroke(1f))
        val thumb = (size.height * visible / count).coerceAtLeast(size.width * 1.6f)
        val maxFirst = (count - visible).coerceAtLeast(1)
        val top = (size.height - thumb) * entry.first / maxFirst
        drawRect(ThumbBrush, topLeft = Offset(1f, top), size = Size(size.width - 2f, thumb))
    }
}

// the classic's slideshow beside the main menu: covers drifting slowly and fading into each other
@Composable
fun IpodCoverCycle(covers: List<String>, running: () -> Boolean, modifier: Modifier = Modifier) {
    val blank = LocalIpodLook.current.screen.artBlank
    if (covers.isEmpty()) {
        Box(modifier.background(blank))
        return
    }
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // only ticks while it can be seen, so a closed player costs nothing
        snapshotFlow { running() }.collectLatest { on ->
            if (!on) return@collectLatest
            var last = -1L
            while (true) {
                withFrameNanos { now ->
                    if (last >= 0L) clock.floatValue += (now - last) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    val slot by remember { derivedStateOf { (clock.floatValue / CoverSeconds).toInt() } }
    Box(modifier.damageBoundary().clipToBounds().background(blank)) {
        for (n in slot..slot + 1) {
            key(n) {
                AsyncImage(
                    model = covers[n % covers.size],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // -1..0 while it fades in over the one before, 0..1 while it's the one on show
                            val t = clock.floatValue / CoverSeconds - n
                            alpha = ((t + CoverFade) / CoverFade).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            val drift = ((t + 1f) / 2f).coerceIn(0f, 1f)
                            val zoom = 1.12f + 0.08f * drift
                            scaleX = zoom
                            scaleY = zoom
                            val side = if (n % 2 == 0) 1f else -1f
                            translationX = size.width * 0.05f * side * (1f - 2f * drift)
                        },
                )
            }
        }
    }
}

private const val CoverSeconds = 6f
// share of a cover's time spent fading in over the last one
private const val CoverFade = 0.18f

// ipod.js's highlight, a touch brighter at the top than the mockup's
internal val SelectionBrush = Brush.verticalGradient(listOf(Color(0xFF3CB8FF), Color(0xFF347AB5)))
private val ThumbBrush = Brush.horizontalGradient(listOf(Color(0xFF6F7B88), Color(0xFFA9B4BF), Color(0xFF5B6673)))
