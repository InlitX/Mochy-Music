package com.mochyy.mochymusic.ui.player.ipod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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

// sizes on the ipod's screen, written in pixels of the 474 px wide mockup and scaled to the real screen
@Immutable
class IpodScale(val width: Dp, val height: Dp, private val fontScale: Float) {
    fun dp(mock: Float): Dp = width * (mock / MockScreenWidth)

    // text grows with the screen, not with the phone's font setting, or big fonts would spill off the little screen
    fun sp(mock: Float): TextUnit = (dp(mock).value / fontScale).sp

    val header: Dp get() = dp(MockHeader)
    val row: Dp get() = (height - header) / VisibleRows
}

const val VisibleRows = 7
const val MockScreenWidth = 474f
const val MockScreenHeight = 355f
private const val MockHeader = 28f
private const val MockScrollbar = 11f

// the pages slide in from the right as you go deeper and back out as you press menu, under a header that stays put
@Composable
fun IpodPages(nav: IpodNavigator, modifier: Modifier = Modifier, page: @Composable (IpodEntry) -> Unit) {
    Box(modifier.clipToBounds()) {
        val shown = nav.top
        for (entry in listOfNotNull(nav.leaving, shown)) {
            key(entry.id) {
                val arriving = entry === shown
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = nav.slide.value
                            val away = if (nav.forward) 1f else -1f
                            translationX = size.width * away * if (arriving) 1f - t else -t
                        }
                        .background(Color.White),
                ) {
                    page(entry)
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
    val rows = entry.list.rows
    val first = entry.first
    val selected = entry.selected
    val scrolls = rows.size > VisibleRows
    val interactive = LocalStyleInteractive.current
    val tap by rememberUpdatedState(onTapRow)
    Box(
        modifier = modifier.pointerInput(entry, interactive, scale) {
            val rowPx = scale.row.toPx()
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
                        val index = entry.first + (down.position.y / rowPx).toInt()
                        if (index in entry.list.rows.indices) tap(index)
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(end = if (scrolls) scale.dp(MockScrollbar) else 0.dp)) {
            for (index in first until minOf(first + VisibleRows, rows.size)) {
                val row = rows[index]
                key(row.key) {
                    IpodMenuRow(row, selected = index == selected, scale = scale, marquee = marquee && index == selected)
                }
            }
        }
        if (scrolls) {
            IpodScrollbar(
                entry = entry,
                count = rows.size,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(scale.dp(MockScrollbar)),
            )
        }
    }
}

@Composable
private fun IpodMenuRow(row: IpodRow, selected: Boolean, scale: IpodScale, marquee: Boolean) {
    val ink = when {
        selected -> Color.White
        row.pick == null -> QuietInk
        else -> Color.Black
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(scale.row)
            .then(if (selected) Modifier.background(SelectionBrush) else Modifier)
            .padding(start = scale.dp(10f), end = scale.dp(10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.title,
            color = ink,
            fontSize = scale.sp(19f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                // the highlighted line scrolls its name when it's too long, like the classic
                .then(if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 1500, velocity = 28.dp) else Modifier),
        )
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
private fun IpodScrollbar(entry: IpodEntry, count: Int, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(TrackBrush)
        drawRect(TrackEdge, style = Stroke(1f))
        val thumb = (size.height * VisibleRows / count).coerceAtLeast(size.width * 1.6f)
        val maxFirst = (count - VisibleRows).coerceAtLeast(1)
        val top = (size.height - thumb) * entry.first / maxFirst
        drawRect(ThumbBrush, topLeft = Offset(1f, top), size = Size(size.width - 2f, thumb))
    }
}

// the classic's slideshow beside the main menu: covers drifting slowly and fading into each other
@Composable
fun IpodCoverCycle(covers: List<String>, running: () -> Boolean, modifier: Modifier = Modifier) {
    if (covers.isEmpty()) {
        Box(modifier.background(CoverEmpty))
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
    Box(modifier.damageBoundary().clipToBounds().background(CoverEmpty)) {
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

internal val SelectionBrush = Brush.verticalGradient(listOf(Color(0xFF3BB3EF), Color(0xFF1F7BC4)))
private val QuietInk = Color(0xFF8A8A8A)
private val TrackBrush = Brush.horizontalGradient(listOf(Color(0xFFDADADA), Color(0xFFF6F6F6), Color(0xFFDADADA)))
private val TrackEdge = Color(0xFFB4B4B4)
private val ThumbBrush = Brush.horizontalGradient(listOf(Color(0xFF6F7B88), Color(0xFFA9B4BF), Color(0xFF5B6673)))
private val CoverEmpty = Color(0xFFDDDDDD)
