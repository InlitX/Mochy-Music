package com.mochyy.mochymusic.ui.player.ipod

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import com.mochyy.mochymusic.ui.player.styles.LocalStyleInteractive
import com.mochyy.mochymusic.ui.player.styles.whileStyleInteractive
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import kotlin.math.PI
import kotlin.math.atan2

// what the wheel asks of whatever screen is showing
@Stable
interface IpodWheelActions {
    // one notch each way, true when something actually moved so the notch is felt
    fun rotate(steps: Int): Boolean

    // menus speed up the longer you keep spinning, volume and scrubbing stay one notch at a time
    val accelerates: Boolean

    fun menu()
    fun menuHeld()
    fun center()
    fun next()
    fun previous()
    fun playPause()

    // called over and over while a side button is held, like scanning on the real thing
    fun scan(forward: Boolean)
}

// the click wheel: spin the ring to scroll, press menu, the side dots, the bottom dot or the middle button
@Composable
fun IpodClickWheel(
    actions: IpodWheelActions,
    haptics: IpodHaptics,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(actions)
    val interactive = LocalStyleInteractive.current
    var centerDown by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    Box(
        modifier = modifier
            .pointerInput(enabled, interactive, haptics) {
                if (!enabled) return@pointerInput
                val spinner = Spinner()
                whileStyleInteractive(interactive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val middle = Offset(size.width / 2f, size.height / 2f)
                        val outer = minOf(size.width, size.height) / 2f
                        val from = down.position - middle
                        // the corners of the square aren't the wheel, they're left for the player's own swipe
                        if (from.getDistance() > outer) return@awaitEachGesture
                        down.consume()

                        if (from.getDistance() < outer * CenterRatio) {
                            centerDown = true
                            haptics.press()
                            val lifted = awaitLift(down.id)
                            centerDown = false
                            if (lifted) {
                                haptics.release()
                                current.center()
                            }
                            return@awaitEachGesture
                        }

                        val zone = ringZone(from)
                        val slop = viewConfiguration.touchSlop
                        val notch: (Int, Long) -> Unit = { direction, at ->
                            val steps = spinner.steps(direction, at, current.accelerates)
                            if (current.rotate(steps)) haptics.tick()
                        }
                        val start = withTimeoutOrNull(HoldMs) { awaitRingStart(down.id, down.position, slop) }
                        when {
                            start == RingStart.Tap -> tap(zone, haptics, current)
                            start == RingStart.Spin -> spin(down.id, middle, outer, down.position, notch)
                            start == RingStart.Cancel -> Unit
                            zone == RingZone.NEXT || zone == RingZone.PREVIOUS -> {
                                haptics.press()
                                scanWhileHeld(down.id) {
                                    current.scan(forward = zone == RingZone.NEXT)
                                    haptics.tick()
                                }
                                haptics.release()
                            }
                            zone == RingZone.MENU -> {
                                haptics.press()
                                current.menuHeld()
                                awaitLift(down.id)
                            }
                            // a slow press on play is still a press, and can still turn into a spin
                            else -> when (awaitRingStart(down.id, down.position, slop)) {
                                RingStart.Tap -> tap(zone, haptics, current)
                                RingStart.Spin -> spin(down.id, middle, outer, down.position, notch)
                                RingStart.Cancel -> Unit
                            }
                        }
                    }
                }
            }
            .drawWithCache {
                val radius = size.minDimension / 2f
                val middle = Offset(size.width / 2f, size.height / 2f)
                val label = measurer.measure(
                    "MENU",
                    TextStyle(
                        color = WheelLabel,
                        fontSize = (radius * 0.085f).toSp(),
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily,
                        letterSpacing = 0.02.em,
                    ),
                )
                val labelAt = Offset(middle.x - label.size.width / 2f, middle.y - radius * 0.83f - label.size.height / 2f)
                val dot = radius * 0.053f
                val hairline = Stroke(width = 1f)
                onDrawBehind {
                    drawCircle(WheelFace, radius, middle)
                    drawCircle(WheelEdge, radius - 0.5f, middle, style = hairline)
                    drawText(label, topLeft = labelAt)
                    drawCircle(WheelDot, dot, Offset(middle.x - radius * 0.853f, middle.y))
                    drawCircle(WheelDot, dot, Offset(middle.x + radius * 0.853f, middle.y))
                    drawCircle(WheelDot, dot, Offset(middle.x, middle.y + radius * 0.88f))
                    drawCircle(if (centerDown) ButtonPressed else IpodBody, radius * CenterRatio, middle)
                }
            },
    )
}

private enum class RingZone { MENU, NEXT, PLAY, PREVIOUS }

private enum class RingStart { Tap, Spin, Cancel }

// which quarter of the ring a touch is in, measured clockwise from the top
private fun ringZone(from: Offset): RingZone {
    val degrees = ((Math.toDegrees(atan2(from.y, from.x).toDouble()) + 90.0 + 360.0) % 360.0)
    return when {
        degrees < 45.0 || degrees >= 315.0 -> RingZone.MENU
        degrees < 135.0 -> RingZone.NEXT
        degrees < 225.0 -> RingZone.PLAY
        else -> RingZone.PREVIOUS
    }
}

private fun tap(zone: RingZone, haptics: IpodHaptics, actions: IpodWheelActions) {
    haptics.click()
    when (zone) {
        RingZone.MENU -> actions.menu()
        RingZone.NEXT -> actions.next()
        RingZone.PLAY -> actions.playPause()
        RingZone.PREVIOUS -> actions.previous()
    }
}

// waits to see whether a touch on the ring is a tap or the start of a spin, eating every event on the way
private suspend fun AwaitPointerEventScope.awaitRingStart(id: PointerId, from: Offset, slop: Float): RingStart {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return RingStart.Cancel
        event.changes.forEach { it.consume() }
        if (!change.pressed) return RingStart.Tap
        if ((change.position - from).getDistance() > slop) return RingStart.Spin
    }
}

// true when the finger came up, false when the touch was taken away
private suspend fun AwaitPointerEventScope.awaitLift(id: PointerId): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        event.changes.forEach { it.consume() }
        if (!change.pressed) return true
    }
}

private suspend fun AwaitPointerEventScope.scanWhileHeld(id: PointerId, onStep: () -> Unit) {
    while (true) {
        onStep()
        if (withTimeoutOrNull(ScanRepeatMs) { awaitLift(id) } != null) return
    }
}

// follows the finger round the ring, one notch every so many degrees, either way round
private suspend fun AwaitPointerEventScope.spin(
    id: PointerId,
    middle: Offset,
    outer: Float,
    start: Offset,
    onNotch: (direction: Int, at: Long) -> Unit,
) {
    // starting from where the finger came down, so the travel inside the slop still counts
    var last: Float? = (start - middle).let { atan2(it.y, it.x) }
    var travelled = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return
        event.changes.forEach { it.consume() }
        if (!change.pressed) return
        val p = change.position - middle
        // right over the middle the angle jumps around wildly, so it's ignored until the finger comes back out
        if (p.getDistance() < outer * DeadZone) {
            last = null
            continue
        }
        val angle = atan2(p.y, p.x)
        val before = last
        last = angle
        if (before == null) continue
        travelled += wrapAngle(angle - before)
        while (travelled >= Notch) {
            travelled -= Notch
            onNotch(1, change.uptimeMillis)
        }
        while (travelled <= -Notch) {
            travelled += Notch
            onNotch(-1, change.uptimeMillis)
        }
    }
}

private fun wrapAngle(radians: Float): Float {
    var a = radians
    while (a > PI) a -= (2 * PI).toFloat()
    while (a < -PI) a += (2 * PI).toFloat()
    return a
}

// keep spinning the same way and the list starts flying, like the classic does after a second or so
private class Spinner {
    private var lastAt = 0L
    private var lastDirection = 0
    private var streakStart = 0L

    fun steps(direction: Int, at: Long, accelerates: Boolean): Int {
        if (direction != lastDirection || at - lastAt > StreakGapMs) streakStart = at
        lastAt = at
        lastDirection = direction
        if (!accelerates) return direction
        val seconds = ((at - streakStart) / 1000L).toInt().coerceIn(0, 4)
        return direction * (1 shl seconds)
    }
}

// the middle button's share of the wheel, as on the mockup
const val CenterRatio = 0.467f
private const val DeadZone = 0.22f
// 24 notches a turn, a little finer than retro's 20 so a phone-sized wheel doesn't feel sticky
private const val Notch = (2 * PI / 24).toFloat()
private const val HoldMs = 450L
private const val ScanRepeatMs = 180L
private const val StreakGapMs = 160L

private val WheelFace = Color.White
private val WheelEdge = Color(0x14000000)
private val WheelLabel = Color(0xFFBDBDBD)
private val WheelDot = Color(0xFFD8D8D8)
private val ButtonPressed = Color(0xFFD9D9D9)
