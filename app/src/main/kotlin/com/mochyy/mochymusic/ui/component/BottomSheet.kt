// BottomSheet.kt

package com.mochyy.mochymusic.ui.component
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.compositionLocalOf

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.mochyy.mochymusic.LocalPlayerConnection
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.constants.NavigationBarAnimationSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.pow

private val PlayerSheetAnimationSpec = spring<Dp>(
    dampingRatio = 0.96f,
    stiffness = 80f
)

/**
 * How far past the touch slop a drag has to go, summed over the whole drag, before the sheet
 * opens or closes for it - on top of the old per-move bar, which still acts as soon as it did.
 */
private val SheetSwipeCommit = 8.dp

/**
 * How far a swipe down on the open sheet has to go before it closes, summed over the drag. Any
 * 2px move used to close it, which threw the player away on a swipe that was meant for something
 * else. A quick flick still closes it early ([SheetCloseFlick] past [SheetCloseFlickTravel]).
 */
private val SheetCloseCommit = 36.dp
private val SheetCloseFlick = 900.dp
private val SheetCloseFlickTravel = 12.dp

/**
 * How far the mini player has to be pulled down, summed over the drag, before it's dismissed
 * (which stops playback and clears the queue).
 */
private val MiniPlayerDismissCommit = 48.dp

private val PlayerSheetHorizontalAnimationSpec = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 90f
)

/** How far the mini player tilts and shrinks under a press. */
private const val PillMaxTilt = 6f
private const val PillPressedScale = 0.97f

/** A press only tilts the sheet this close to collapsed: the mini player itself. */
private const val PillPressMaxProgress = 0.02f

/** The progress the mini player's hairline outline and shadow are gone by. */
private const val PillEdgeFadeEnd = 0.08f

/** The collapsed sheet, the mini player: inset from each side, its height and its corners. */
private val PillInset = 24.dp
private val PillHeight = 64.dp
private val PillCornerRadius = 20.dp
private val PillShape = RoundedCornerShape(PillCornerRadius)

private val PillTiltSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 420f,
)

/** The mini player's press: how far in (0 to 1), and where it landed, -1 to 1 across the sheet. */
@Stable
private class PillPress {
    val amount = Animatable(0f)

    // Plain fields: set before each press animates, and read in the layer that animation redraws.
    var normalizedX = 0f
    var normalizedY = 0f
}

/** Where the pill sheet sits, and its tilt under a press. The sheet and its shadow share this. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.placeSheet(state: BottomSheetState, pillPress: PillPress) {
    translationY = (state.expandedBound - state.value).toPx()
    translationX = state.horizontalOffset

    val t = pillPress.amount.value
    if (t > 0f) {
        val s = 1f - (1f - PillPressedScale) * t
        scaleX = s
        scaleY = s
        rotationX = -pillPress.normalizedY * PillMaxTilt * t
        rotationY = pillPress.normalizedX * PillMaxTilt * t
        cameraDistance = size.width * 3f
        // about the pill's centre
        transformOrigin = TransformOrigin(
            0.5f,
            if (size.height > 0f) (PillHeight / 2).toPx() / size.height else 0f,
        )
    }
}

@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    /** Added to [modifier] only while the sheet sits fully open (pill transition only). */
    expandedModifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    isExpandable: Boolean = true,
    isPillTransition: Boolean = false,
    pureBlack: Boolean = false,
    /** Offered vertical swipes on the fully expanded sheet first (pill transition only). */
    expandedSwipe: ExpandedSwipe? = null,
    /**
     * Takes sideways swipes on the collapsed sheet (the mini player) instead of the sheet sliding
     * them itself (pill transition only).
     */
    collapsedSwipe: CollapsedSwipe? = null,
    /**
     * Told when a finger comes down anywhere on the sheet (true) and when the last one lifts
     * (false), whatever on it takes the touch (pill transition only).
     */
    onTouchActivity: ((touching: Boolean) -> Unit)? = null,
    sharedContent: @Composable (BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val currentSwipe by androidx.compose.runtime.rememberUpdatedState(expandedSwipe)
    val currentSideSwipe by androidx.compose.runtime.rememberUpdatedState(collapsedSwipe)
    val currentOnTouchActivity by androidx.compose.runtime.rememberUpdatedState(onTouchActivity)

    if (!isPillTransition) {
        Box(
            modifier = modifier
                .graphicsLayer {

                    alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
                }
                .fillMaxSize(),
            content = background
        )
    }
        if (!isPillTransition) {
            val sheetCornerShape = remember { RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) }
            // Latches once the sheet has been opened, so later drags reuse the composed content.
            var contentEverShown by remember { mutableStateOf(false) }

            Box(
                modifier = modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val y = (state.expandedBound - state.value).toPx().coerceAtLeast(0f)
                        translationY = y
                        // Pre-built shapes: this block runs every frame of the drag, and
                        // allocating a RoundedCornerShape per frame is pure churn.
                        shape = if (!state.isExpanded) sheetCornerShape else RectangleShape
                        clip = true
                    }
                    .pointerInput(state, isExpandable) {
                        if (!isExpandable) return@pointerInput
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                velocityTracker.addPointerInputChange(change)
                                if (dragAmount < -3f && state.isCollapsed) {
                                    state.expandSoft()
                                    change.consume()
                                } else if (dragAmount > 3f && state.isExpanded) {
                                    state.collapseSoft()
                                    change.consume()
                                } else if (dragAmount > 3f && state.isCollapsed && onDismiss != null) {
                                    state.dismiss()
                                    onDismiss.invoke()
                                    change.consume()
                                }
                            },
                            onDragCancel = {
                                velocityTracker.resetTracking()
                            },
                            onDragEnd = {
                                velocityTracker.resetTracking()
                            }
                        )
                    }
            ) {
                if (!state.isCollapsed && !state.isDismissed) {
                    BackHandler(onBack = state::collapseSoft)
                }

                // Composing the sheet content is expensive, and gating it on `!isCollapsed`
                // meant the whole player subtree was built the instant a drag started - the
                // stutter in the first few pixels of a swipe up. Once it has been shown, keep
                // it composed; alpha already hides it while collapsed.
                if (!state.isCollapsed && !contentEverShown) {
                    SideEffect { contentEverShown = true }
                }
                if (contentEverShown || !state.isCollapsed) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = ((state.progress - 0.15f) * 4).coerceIn(0f, 1f)
                            },
                        content = content
                    )
                }

                if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = 1f - (state.progress * 4).coerceAtMost(1f)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (isExpandable) state.expandSoft() },
                            )
                            .fillMaxWidth()
                            .height(state.collapsedBound),
                        content = collapsedContent,
                    )
                }

                if (sharedContent != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        content = sharedContent
                    )
                }
            }
        } else {

            val playerConnection = LocalPlayerConnection.current

            val coroutineScope = rememberCoroutineScope()
            val animationSpec = remember {
                PlayerSheetHorizontalAnimationSpec
            }
            var dragStartTime by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            var totalHorizontalDrag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

            val sheetCoordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }

            val sheetClipShape = remember {
                object : androidx.compose.ui.graphics.Shape {
                    var hp = 0f
                    var visibleHeight = 0f
                    var cr = 0f

                    override fun createOutline(
                        size: androidx.compose.ui.geometry.Size,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        density: androidx.compose.ui.unit.Density
                    ): androidx.compose.ui.graphics.Outline {
                        return androidx.compose.ui.graphics.Outline.Rounded(
                            androidx.compose.ui.geometry.RoundRect(
                                left = hp,
                                top = 0f,
                                right = size.width - hp,
                                bottom = visibleHeight,
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr)
                            )
                        )
                    }
                }
            }

            // Pressing the mini player tilts it toward the finger. In the sheet's own layer, so the
            // shape it's clipped to tilts with what's in it, and kept through the open: the tilt
            // eases back out over the open's first frames. It was a modifier on the player inside,
            // taken off at 2% open, so a tap's tilt snapped flat a frame or two into every open.
            val pillPress = remember { PillPress() }

            Box(
                modifier = modifier
                    .then(if (state.isExpanded) expandedModifier else Modifier)
                    .fillMaxSize()
                    .nestedScroll(remember(state) { state.preUpPostDownNestedScrollConnection })
            ) {

                // The mini player's faint shadow. The sheet's clip would cut it off, so it's a
                // layer of its own placed and tilted exactly like the sheet, and it fades with the
                // hairline. It stays the pill's size: the opening sheet grows over it.
                PillShadow(state, pillPress)

                Box(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(state.expandedBound)
                        .graphicsLayer {
                            val p = state.progress.coerceIn(0f, 1f)
                            sheetClipShape.cr = androidx.compose.ui.unit.lerp(PillCornerRadius, 0.dp, p).toPx()
                            sheetClipShape.hp = androidx.compose.ui.unit.lerp(PillInset, 0.dp, p).toPx()
                            sheetClipShape.visibleHeight = androidx.compose.ui.unit.lerp(PillHeight, state.expandedBound, p).toPx()

                            shape = sheetClipShape
                            clip = true
                            placeSheet(state, pillPress)
                        }
                        .drawWithContent {
                            drawContent()
                            val p = state.progress.coerceIn(0f, 1f)
                            // The pill's hairline fades out as it opens; it used to drop in one
                            // frame at 5% open.
                            val edge = 1f - p / PillEdgeFadeEnd
                            if (edge > 0f) {
                                val hp = androidx.compose.ui.unit.lerp(PillInset, 0.dp, p).toPx()
                                drawChromeOutline(
                                    topLeft = androidx.compose.ui.geometry.Offset(hp, 0f),
                                    size = androidx.compose.ui.geometry.Size(
                                        size.width - hp * 2,
                                        androidx.compose.ui.unit.lerp(PillHeight, state.expandedBound, p).toPx(),
                                    ),
                                    cornerRadius = androidx.compose.ui.unit.lerp(PillCornerRadius, 0.dp, p).toPx(),
                                    alpha = edge,
                                )
                            }
                        }
                        .pointerInput(state, isExpandable) {
                            if (!isExpandable) return@pointerInput
                            // A tap on the collapsed sheet opens it. Open, a touch is left alone:
                            // taking it here would leave nothing for the player's background hold
                            // to tell apart from a button's.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (!state.isCollapsed) return@awaitEachGesture
                                down.consume()
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                up.consume()
                                if (state.isCollapsed) coroutineScope.launch { state.expandSoft() }
                            }
                        }
                        // Where the sheet's own coordinates are, so a drag's start can be handed
                        // to the swipe handler in root coordinates. Stored, not state: it is only
                        // read when a drag starts.
                        .onPlaced { sheetCoordinates[0] = it }
                        // Any touch on the sheet, for whoever wants to know it's in use. Watched
                        // like the press tilt below, so it takes nothing from anyone.
                        .then(
                            if (onTouchActivity == null) Modifier else Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    var touching = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val now = event.changes.any { it.pressed }
                                        if (now != touching) {
                                            touching = now
                                            currentOnTouchActivity?.invoke(now)
                                        }
                                    }
                                }
                            }
                        )
                        // The press tilt: watched in the Initial pass and never consumed, so the
                        // tap and the drags below still get every touch.
                        .pointerInput(state, isExpandable) {
                            // A press cut off by this restarting must not leave the sheet tilted.
                            if (pillPress.amount.targetValue != 0f) {
                                coroutineScope.launch { pillPress.amount.animateTo(0f, PillTiltSpring) }
                            }
                            if (!isExpandable) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitPointerEvent(PointerEventPass.Initial)
                                        .changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                                    if (state.progress >= PillPressMaxProgress) continue
                                    if (size.width > 0 && size.height > 0) {
                                        pillPress.normalizedX = ((down.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
                                        pillPress.normalizedY = ((down.position.y / size.height) * 2f - 1f).coerceIn(-1f, 1f)
                                    }
                                    coroutineScope.launch { pillPress.amount.animateTo(1f, PillTiltSpring) }
                                    // Let go when the finger lifts, or as soon as the sheet leaves
                                    // the pill under a drag.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.changes.none { it.pressed } || state.progress >= PillPressMaxProgress) break
                                    }
                                    coroutineScope.launch { pillPress.amount.animateTo(0f, PillTiltSpring) }
                                }
                            }
                        }
                        .pointerInput(state, isExpandable) {
                            if (!isExpandable) return@pointerInput
                            val velocityTracker = VelocityTracker()
                            // Set once a swipe on the expanded sheet has been handed over; from then
                            // on the whole drag, both directions, goes to that handler.
                            var swipe: ExpandedSwipe? = null
                            // Where the drag began, in root coordinates.
                            var swipeStart = Offset.Zero
                            // The whole drag so far. Each move alone used to have to pass 2px for
                            // the sheet to act, and a screen that reports touches more often (120Hz
                            // and up, as on newer Samsungs) splits a steady swipe into moves too
                            // small to ever pass it: the player wouldn't swipe closed.
                            var dragSum = Offset.Zero
                            val commitPx = SheetSwipeCommit.toPx()
                            val closePx = SheetCloseCommit.toPx()
                            val flickPx = SheetCloseFlick.toPx()
                            val flickTravelPx = SheetCloseFlickTravel.toPx()
                            val dismissPx = MiniPlayerDismissCommit.toPx()
                            // Whether this drag began on the open player (or one all but settled
                            // open: the open spring's tail runs on for a second after it looks up).
                            var startedOpen = false
                            // The sheet was sent on its way by this drag: once is enough.
                            var acted = false
                            // A sideways swipe on the mini player handed to [collapsedSwipe].
                            var sideSwipe: CollapsedSwipe? = null

                            detectDragGestures(
                                onDragStart = { start ->
                                    dragStartTime = System.currentTimeMillis()
                                    totalHorizontalDrag = 0f
                                    velocityTracker.resetTracking()
                                    swipe = null
                                    sideSwipe = null
                                    dragSum = Offset.Zero
                                    acted = false
                                    // by how open it looks: a sheet showing open whose anchor says
                                    // otherwise (a cut-short animation) still swipes closed (#109)
                                    startedOpen = state.isExpanded || state.progress > 0.9f
                                    val coordinates = sheetCoordinates[0]
                                    swipeStart = if (coordinates != null && coordinates.isAttached) {
                                        coordinates.localToRoot(start)
                                    } else {
                                        start
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    velocityTracker.addPointerInputChange(change)
                                    dragSum += dragAmount
                                    val vertical = abs(dragSum.y) > abs(dragSum.x)
                                    val pulledUp = vertical && dragSum.y < -commitPx
                                    val pulledDown = vertical && dragSum.y > closePx

                                    val claimed = swipe ?: currentSwipe?.takeIf { handler ->
                                        state.isExpanded && handler.onStart(swipeStart, dragAmount)
                                    }?.also { swipe = it }

                                    if (claimed != null) {
                                        claimed.onDrag(dragAmount.y)
                                        change.consume()
                                    } else if (sideSwipe != null) {
                                        totalHorizontalDrag += dragAmount.x
                                        sideSwipe?.onDrag(totalHorizontalDrag, size.width.toFloat())
                                        change.consume()
                                    } else if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y) && state.isCollapsed) {
                                        totalHorizontalDrag += dragAmount.x
                                        val handler = currentSideSwipe
                                        if (handler != null) {
                                            sideSwipe = handler
                                            handler.onStart()
                                            handler.onDrag(totalHorizontalDrag, size.width.toFloat())
                                            change.consume()
                                            return@detectDragGestures
                                        }
                                        val resistance = 1f - (kotlin.math.abs(state.horizontalOffset) / (size.width / 2f)).coerceIn(0f, 0.8f)
                                        state.horizontalOffset += dragAmount.x * resistance
                                    } else {
                                        if ((dragAmount.y < -2f || pulledUp) && state.isCollapsed) {
                                            state.expandSoft()
                                            change.consume()
                                        } else if (
                                            !acted && startedOpen && pulledDown &&
                                            !state.isCollapsed &&
                                            currentSwipe?.canCollapseFrom(swipeStart) != false
                                        ) {
                                            acted = true
                                            state.collapseSoft()
                                            change.consume()
                                        } else if (
                                            !acted && state.isCollapsed && onDismiss != null &&
                                            dragSum.y > dismissPx && abs(dragSum.y) > abs(dragSum.x) * 2f
                                        ) {
                                            // Dismissing stops playback and clears the queue, so it
                                            // takes a deliberate pull down, summed over the drag and
                                            // clearly vertical. It used to be any single 2px move
                                            // down: the first move of a slightly slanted sideways
                                            // swipe (meant to skip) threw the whole queue away (#47).
                                            acted = true
                                            state.dismiss()
                                            onDismiss.invoke()
                                            change.consume()
                                        }
                                    }
                                },
                                onDragCancel = {
                                    velocityTracker.resetTracking()
                                    swipe?.onEnd(0f)
                                    swipe = null
                                    sideSwipe?.onEnd(totalHorizontalDrag, 0f, size.width.toFloat(), cancelled = true)
                                    sideSwipe = null
                                    coroutineScope.launch {
                                        state.animateHorizontalOffsetTo(0f)
                                    }
                                },
                                onDragEnd = {
                                    swipe?.let { handler ->
                                        handler.onEnd(velocityTracker.calculateVelocity().y)
                                        velocityTracker.resetTracking()
                                        swipe = null
                                        return@detectDragGestures
                                    }
                                    sideSwipe?.let { handler ->
                                        handler.onEnd(totalHorizontalDrag, velocityTracker.calculateVelocity().x, size.width.toFloat(), cancelled = false)
                                        velocityTracker.resetTracking()
                                        sideSwipe = null
                                        return@detectDragGestures
                                    }
                                    val velocityY = velocityTracker.calculateVelocity().y
                                    velocityTracker.resetTracking()

                                    // a quick flick down that ended before it got far enough
                                    if (
                                        !acted && startedOpen && !state.isCollapsed &&
                                        abs(dragSum.y) > abs(dragSum.x) && dragSum.y > flickTravelPx &&
                                        velocityY > flickPx &&
                                        currentSwipe?.canCollapseFrom(swipeStart) != false
                                    ) {
                                        acted = true
                                        state.collapseSoft()
                                        return@detectDragGestures
                                    }

                                    if (state.isCollapsed || state.progress < 0.2f) {
                                        val dragDuration = System.currentTimeMillis() - dragStartTime
                                        val horizontalVelocity = if (dragDuration > 0) totalHorizontalDrag / dragDuration else 0f
                                        val currentOffset = state.horizontalOffset

                                        val shouldChangeSong = (kotlin.math.abs(currentOffset) > 50f && kotlin.math.abs(horizontalVelocity) > 2f) ||
                                                (kotlin.math.abs(currentOffset) > size.width / 3f)

                                        if (shouldChangeSong && playerConnection != null) {
                                            if (currentOffset > 0) playerConnection.player.seekToPreviousMediaItem()
                                            else playerConnection.player.seekToNext()
                                        }
                                    }

                                    coroutineScope.launch { state.animateHorizontalOffsetTo(0f) }
                                }
                            )
                        }
                ) {

                    val showBackground by remember { derivedStateOf { !state.isDismissed } }
                    val showControls by remember { derivedStateOf { !state.isDismissed } }

                    if (showBackground) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {

                                    val p = state.progress.coerceIn(0f, 1f)
                                    scaleX = 1.10f - (0.10f * p)
                                    scaleY = 1.10f - (0.10f * p)
                                }
                        ) {
                            background()
                        }
                    }

                    if (sharedContent != null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            content = sharedContent
                        )
                    }

                    if (showControls) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {

                                    alpha = ((state.progress.coerceIn(0f, 1f) - 0.25f) / 0.60f).coerceIn(0f, 1f)
                                }
                        ) {
                            content()
                        }
                    }
                }

                if (!isPillTransition && !state.isExpanded && (onDismiss == null || !state.isDismissed)) {
                    Box(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(state.collapsedBound)
                            .graphicsLayer {
                                val p = state.progress.coerceIn(0f, 1f)
                                alpha = (1f - (p / 0.3f)).coerceIn(0f, 1f)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { if (isExpandable) state.expandSoft() },
                            ),
                        contentAlignment = androidx.compose.ui.Alignment.TopStart
                    ) {
                        collapsedContent()
                    }
                }

                SheetBackHandler(state)
            }
        }
    }

// its own function, so the shadow showing or hiding at 8% open recomposes only this
@Composable
private fun BoxScope.PillShadow(state: BottomSheetState, pillPress: PillPress) {
    val showPillShadow by remember(state) {
        derivedStateOf { !state.isDismissed && state.progress < PillEdgeFadeEnd }
    }
    if (showPillShadow) {
        Box(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .height(state.expandedBound)
                .graphicsLayer {
                    placeSheet(state, pillPress)
                    alpha = (1f - state.progress / PillEdgeFadeEnd).coerceIn(0f, 1f)
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                }
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = PillInset)
                    .fillMaxWidth()
                    .height(PillHeight)
                    .dropShadow(PillShape, ChromeShadow)
            )
        }
    }
}

// its own function, so the sheet leaving collapsed or dismissed doesn't recompose the whole sheet
@Composable
private fun SheetBackHandler(state: BottomSheetState) {
    if (!state.isCollapsed && !state.isDismissed) {
        BackHandler(onBack = state::collapseSoft)
    }
}

val LocalPlayerBottomSheetState = compositionLocalOf<BottomSheetState?> { null }

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
    /** How far a list pulled past its top drags the sheet closed, px (see the nested scroll). */
    private val collapsePullPx: Float = 24f,
    /**
     * Where the sheet was last sent ([expandedAnchor], [collapsedAnchor], [dismissedAnchor]). Kept
     * by [rememberBottomSheetState] across the new states it makes when the bounds change, so it
     * holds through a resize, while the value catches up with the new bounds.
     */
    private val anchor: () -> Int = { -1 },
) : DraggableState by draggableState {
    val dismissedBound: Dp
        get() = animatable.lowerBound!!

    val expandedBound: Dp
        get() = animatable.upperBound!!

    val value by animatable.asState()

    val isDismissed by derivedStateOf {
        value == animatable.lowerBound!!
    }

    val isCollapsed by derivedStateOf {
        value == collapsedBound
    }

    val isExpanded by derivedStateOf {
        value == animatable.upperBound
    }

    /**
     * Open, or on its way there. [isExpanded] only holds once the open spring has settled to the
     * exact pixel - a good second after the player looks open - and it drops whenever the window's
     * height changes under it, until the sheet has caught up. What the open player's own state
     * hangs on (its menus, whether a swipe down closes it) goes by this, so neither is lost then.
     */
    val isExpandedOrExpanding by derivedStateOf { anchor() == expandedAnchor }

    val progress by derivedStateOf {
        1f - (animatable.upperBound!! - animatable.value) / (animatable.upperBound!! - collapsedBound)
    }

    var horizontalOffset by androidx.compose.runtime.mutableFloatStateOf(0f)
        internal set

    suspend fun animateHorizontalOffsetTo(targetValue: Float) {
        androidx.compose.animation.core.animate(
            initialValue = horizontalOffset,
            targetValue = targetValue,
            animationSpec = PlayerSheetHorizontalAnimationSpec
        ) { value, _ ->
            horizontalOffset = value
        }
    }

    fun collapse(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(collapsedAnchor)
        coroutineScope.launch {
            animatable.animateTo(collapsedBound, animationSpec)
        }
    }

    fun expand(animationSpec: AnimationSpec<Dp>) {
        onAnchorChanged(expandedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.upperBound!!, animationSpec)
        }
    }

    private fun collapse() {
        collapse(PlayerSheetAnimationSpec)
    }

    private fun expand() {
        expand(PlayerSheetAnimationSpec)
    }

    fun collapseSoft() {
        collapse(PlayerSheetAnimationSpec)
    }

    fun expandSoft() {
        expand(PlayerSheetAnimationSpec)
    }

    fun dismiss() {
        onAnchorChanged(dismissedAnchor)
        coroutineScope.launch {
            animatable.animateTo(animatable.lowerBound!!)
        }
    }

    suspend fun dismissAndWait() {
        onAnchorChanged(dismissedAnchor)
        animatable.animateTo(animatable.lowerBound!!)
    }

    fun snapTo(value: Dp) {
        coroutineScope.launch {
            animatable.snapTo(value)
        }
    }

    fun performFling(velocity: Float, onDismiss: (() -> Unit)?) {
        if (velocity > 250) {
            expand()
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse()
            }
        } else {
            val l0 = dismissedBound
            val l1 = dismissedBound + (collapsedBound - dismissedBound) / 2
            val l2 = expandedBound - (expandedBound - collapsedBound) / 6
            val l3 = expandedBound

            when (value) {
                in l0..l1 -> {
                    if (onDismiss != null) {
                        dismiss()
                        onDismiss.invoke()
                    } else {
                        collapse()
                    }
                }

                in l1..l2 -> collapse()
                in l2..l3 -> expand()
                else -> Unit
            }
        }
    }

    /**
     * Whether a list scrolled past its top drags the sheet closed. The player turns this off while
     * its queue is open, so pulling down at the top of the queue only stretches the list.
     */
    var collapseOnNestedOverscroll: Boolean = true

    val preUpPostDownNestedScrollConnection
        get() = object : NestedScrollConnection {
            var isTopReached = false
            // Pulled past the list's top so far, summed: a fast screen hands the pull over in
            // moves too small to pass the per-move bar one at a time (see the sheet's own drag).
            var pulled = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                    pulled = 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!isTopReached) {
                    isTopReached = consumed.y == 0f && available.y > 0
                }
                if (isTopReached && source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                }

                return if (
                    collapseOnNestedOverscroll && isTopReached &&
                    source == NestedScrollSource.UserInput &&
                    (available.y > 3f || (available.y > 0f && pulled > collapsePullPx))
                ) {
                    collapseSoft()
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return if (collapseOnNestedOverscroll && isTopReached && available.y > 100f) {
                    collapseSoft()
                    available
                } else {
                    Velocity.Zero
                }
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                isTopReached = false
                pulled = 0f
                return Velocity.Zero
            }
        }
}

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable {
        mutableIntStateOf(initialAnchor)
    }
    val animatable = remember {
        Animatable(0.dp, Dp.VectorConverter)
    }

    val state = remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue = when (previousAnchor) {
            expandedAnchor -> expandedBound
            collapsedAnchor -> collapsedBound
            dismissedAnchor -> dismissedBound
            else -> error("Unknown BottomSheet anchor")
        }

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), expandedBound)
        coroutineScope.launch {
            animatable.animateTo(initialValue, NavigationBarAnimationSpec)
        }

        BottomSheetState(
            draggableState = DraggableState { delta ->

                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    animatable.snapTo(animatable.value - with(density) { delta.toDp() })
                }
            },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
            collapsePullPx = with(density) { SheetSwipeCommit.toPx() },
            anchor = { previousAnchor },
        )
    }

    // The sheet always ends where it was last sent. collapse()/expand() set the anchor first and
    // animate after, in a coroutine of their own, and the window resizing under the open player
    // (the navigation bar hiding, a rotation) swaps in a new state mid-way. Should that animation
    // be cut short, the anchor and the drawn sheet disagree: the player looks open but reads as
    // closing, so the handle, the swipe down and the open player's own state all refuse it - the
    // "stuck in the player" report (#109). Whenever nothing is animating the sheet and it rests
    // somewhere other than its anchor's bound, it is sent the rest of the way.
    LaunchedEffect(state) {
        snapshotFlow {
            val target = when (previousAnchor) {
                expandedAnchor -> state.expandedBound
                collapsedAnchor -> state.collapsedBound
                else -> state.dismissedBound
            }
            if (animatable.isRunning || animatable.value == target) null else target
        }.collectLatest { target ->
            if (target == null) return@collectLatest
            // a beat first: an animation just launched (a bounds change, a swipe's settle) starts
            // running within a frame, and this then stands down
            delay(SheetSettleGraceMs)
            // launched on its own, so the next emission (the animation starting) can't cancel it
            coroutineScope.launch { animatable.animateTo(target, PlayerSheetAnimationSpec) }
        }
    }
    return state
}

/** How long the sheet may rest away from its anchor, with nothing animating it, before it's sent home. */
private const val SheetSettleGraceMs = 250L

/**
 * A sideways swipe on the collapsed sheet, the mini player. The player hands it to its row of
 * songs, so the mini player's cover and title slide under the finger like the open player's do.
 */
interface CollapsedSwipe {
    fun onStart()

    /** The finger is [travelledPx] from where it went down, on a sheet [widthPx] wide. */
    fun onDrag(travelledPx: Float, widthPx: Float)

    /** It lifted moving at [velocityPx] px/s, or was taken away ([cancelled]). */
    fun onEnd(travelledPx: Float, velocityPx: Float, widthPx: Float, cancelled: Boolean)
}

/**
 * Offered every vertical swipe on the fully expanded sheet before the sheet itself acts on it. The
 * player uses it to pull a page up out of its bottom card, to send a page back down, and to keep
 * swipes over its controls from closing it. Children that handle their own drags (the card's flip,
 * a list, the seek bar, the cover's skip swipe) consume them first, so only the free parts of the
 * sheet get here.
 */
interface ExpandedSwipe {
    /**
     * A swipe has begun at [start] (root coordinates) and moved by [drag] (px) so far. Called on
     * each move until it returns true; once it does, the whole drag goes here.
     */
    fun onStart(start: Offset, drag: Offset): Boolean

    /** [dy] in px, negative going up. */
    fun onDrag(dy: Float)

    /** [velocityY] in px/s, negative going up; 0 when the gesture was cancelled. */
    fun onEnd(velocityY: Float)

    /**
     * False where a swipe down must not close the sheet even though [onStart] didn't take it -
     * a diagonal drag over controls that have swipes of their own, say.
     */
    fun canCollapseFrom(start: Offset): Boolean = true
}
