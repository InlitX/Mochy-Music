package com.mochyy.mochymusic.ui.player.ipod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerConnection
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

// the grey bar along the top of the screen: what you're looking at, play state and battery
@Composable
fun IpodHeader(title: String, scale: IpodScale, modifier: Modifier = Modifier) {
    val playing = rememberIsPlaying()
    val battery = rememberBatteryLevel()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(scale.header)
            .drawBehind {
                drawRect(HeaderBrush)
                drawRect(HeaderLine, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            },
    ) {
        Text(
            text = title,
            color = Color.Black,
            fontSize = scale.sp(18f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = scale.dp(9f), end = scale.dp(84f)),
        )
        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = scale.dp(10f))
                .size(scale.dp(60f), scale.dp(16f)),
        ) {
            val unit = size.height / 16f
            drawPlayState(playing, unit)
            drawBattery(battery.value, unit)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayState(playing: Boolean, unit: Float) {
    val h = 14f * unit
    val top = (size.height - h) / 2f
    if (playing) {
        val path = Path().apply {
            moveTo(0f, top)
            lineTo(13f * unit, top + h / 2f)
            lineTo(0f, top + h)
            close()
        }
        drawPath(path, PlayBrush)
    } else {
        drawRect(PlayBrush, topLeft = Offset(0f, top), size = Size(4.5f * unit, h))
        drawRect(PlayBrush, topLeft = Offset(7.5f * unit, top), size = Size(4.5f * unit, h))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBattery(level: Float, unit: Float) {
    val w = 36f * unit
    val h = 15f * unit
    val left = size.width - w
    val top = (size.height - h) / 2f
    val nub = 2.5f * unit
    val bodyW = w - nub
    drawRoundRect(BatteryShell, topLeft = Offset(left, top), size = Size(bodyW, h), cornerRadius = CornerRadius(2f * unit))
    drawRect(BatteryShell, topLeft = Offset(left + bodyW, top + h * 0.3f), size = Size(nub, h * 0.4f))
    val inset = 1.5f * unit
    val innerW = bodyW - inset * 2f
    drawRect(Color.White, topLeft = Offset(left + inset, top + inset), size = Size(innerW, h - inset * 2f))
    drawRect(
        brush = if (level < 0.2f) BatteryLow else BatteryFull,
        topLeft = Offset(left + inset, top + inset),
        size = Size(innerW * level.coerceIn(0.06f, 1f), h - inset * 2f),
    )
}

@Composable
private fun rememberIsPlaying(): Boolean {
    val connection = LocalPlayerConnection.current ?: return false
    val transport by connection.uiState.transportState.collectAsState()
    return transport.isPlaying
}

// the phone's real battery, so the little icon in the corner isn't just for show
@Composable
private fun rememberBatteryLevel(): State<Float> {
    val context = LocalContext.current
    val level = remember { mutableFloatStateOf(1f) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.batteryFraction()?.let { level.floatValue = it }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.batteryFraction()?.let { level.floatValue = it }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level
}

private fun Intent.batteryFraction(): Float? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level.toFloat() / scale
}

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
private val HeaderBrush = Brush.verticalGradient(listOf(Color(0xFFFDFDFD), Color(0xFFE2E4E6), Color(0xFFA9AEB3)))
private val HeaderLine = Color(0xFF8C8C8C)
private val PlayBrush = Brush.verticalGradient(listOf(Color(0xFF9AD6FF), Color(0xFF2A8BDD)))
private val BatteryShell = Color(0xFF6E6E6E)
private val BatteryFull = Brush.verticalGradient(listOf(Color(0xFFD9F5B6), Color(0xFF9BD563), Color(0xFF6DAE34)))
private val BatteryLow = Brush.verticalGradient(listOf(Color(0xFFFFC2B8), Color(0xFFF0624D), Color(0xFFC9311F)))
private val QuietInk = Color(0xFF8A8A8A)
private val TrackBrush = Brush.horizontalGradient(listOf(Color(0xFFDADADA), Color(0xFFF6F6F6), Color(0xFFDADADA)))
private val TrackEdge = Color(0xFFB4B4B4)
private val ThumbBrush = Brush.horizontalGradient(listOf(Color(0xFF6F7B88), Color(0xFFA9B4BF), Color(0xFF5B6673)))
private val CoverEmpty = Color(0xFFDDDDDD)
