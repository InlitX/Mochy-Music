// OffscreenFrames.kt

package com.mochyy.mochymusic.ui.player.share.song.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Picture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

private const val Tag = "SongShare"

// how many turns the main thread gets for the composition to ask for its next frame
private const val SettleYields = 6

// how finely a frame is checked for anything drawn at all: about every 10 picture units
private const val CheckColumns = 36
private const val CheckRows = 64

// a drawn frame: premultiplied, top row first; the bitmap can be wider than the picture when the gpu pads its rows
internal class FrameImage(val bitmap: Bitmap, val width: Int, val height: Int)

// draws a composable off screen, one frame at a time, on a clock moved by hand: every animation in it lands exactly on the frame's time, however long the frame took to draw
internal class OffscreenFrames(
    private val host: ViewGroup,
    val width: Int,
    val height: Int,
    content: @Composable () -> Unit,
) {
    private val clock = BroadcastFrameClock()
    private val main = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(Dispatchers.Main.immediate + clock + SupervisorJob())
    private val recomposer = Recomposer(scope.coroutineContext)
    private var frameNanos = 1_000_000_000L

    // the gpu draws blur and shaders like the screen does; some phones hand back an empty frame from it, and those draw in software instead
    private var onGpu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    private var software: Bitmap? = null
    private var lastGpuFrame: Bitmap? = null

    private val view = ComposeView(host.context).apply {
        setParentCompositionContext(recomposer)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent(content)
    }

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        host.addView(view, ViewGroup.LayoutParams(width, height))
        layOut()
    }

    // moves the clock on by frameMs and lets the composition, its animations and its effects all catch up
    suspend fun advance(frameMs: Double) {
        frameNanos += (frameMs * 1_000_000.0).toLong()
        Snapshot.sendApplyNotifications()
        var turns = 0
        while (!clock.hasAwaiters && turns < SettleYields) {
            nextTurn()
            turns++
        }
        if (clock.hasAwaiters) clock.sendFrame(frameNanos)
        Snapshot.sendApplyNotifications()
        nextTurn()
    }

    // a few still frames for the picture to settle into place, then a check that something was really drawn; a gpu frame that comes back empty switches to software
    suspend fun warmUp(frames: Int) {
        repeat(frames) {
            advance(0.0)
            capture()
        }
        if (onGpu && capture().isBlank()) {
            Log.w(Tag, "The gpu frame came back empty, drawing the share in software instead")
            onGpu = false
        }
        check(!capture().isBlank()) { "The share's picture came out empty" }
    }

    // the composable as it looks right now
    suspend fun capture(): FrameImage {
        layOut()
        if (onGpu && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val frame = runCatching { captureOnGpu() }
                .onFailure { Log.w(Tag, "Drawing the share on the gpu failed, using software", it) }
                .getOrNull()
            if (frame != null) return frame
            onGpu = false
        }
        return captureInSoftware()
    }

    fun release() {
        lastGpuFrame?.recycle()
        lastGpuFrame = null
        view.disposeComposition()
        host.removeView(view)
        recomposer.cancel()
        scope.cancel()
    }

    private fun layOut() {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    // one trip round the main thread's queue, so anything the frame posted there has run before the picture is taken
    private suspend fun nextTurn() {
        yield()
        suspendCancellableCoroutine { continuation -> main.post { continuation.resume(Unit) } }
    }

    // the same way compose takes a picture of a layer: the view drawn into a picture that the gpu turns into a bitmap, then copied out for the encoder
    @RequiresApi(Build.VERSION_CODES.P)
    private fun captureOnGpu(): FrameImage {
        val drawn = Bitmap.createBitmap(ViewPicture(view, width, height))
        val copy = try {
            drawn.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            drawn.recycle()
        }
        // the frame before has been handed to the encoder by now; its memory goes straight back
        lastGpuFrame?.recycle()
        lastGpuFrame = copy
        return FrameImage(copy, width, height)
    }

    private fun captureInSoftware(): FrameImage {
        val bitmap = software ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { software = it }
        bitmap.eraseColor(Color.TRANSPARENT)
        view.draw(Canvas(bitmap))
        return FrameImage(bitmap, width, height)
    }
}

// a picture that draws the view each time it's drawn, so the gpu renders it fresh with blur and shaders included
@RequiresApi(Build.VERSION_CODES.P)
private class ViewPicture(private val view: View, private val pictureWidth: Int, private val pictureHeight: Int) : Picture() {
    override fun beginRecording(width: Int, height: Int): Canvas = Canvas()

    override fun endRecording() = Unit

    override fun getWidth(): Int = pictureWidth

    override fun getHeight(): Int = pictureHeight

    override fun requiresHardwareAcceleration(): Boolean = true

    override fun draw(canvas: Canvas) {
        view.draw(canvas)
    }
}

// nothing drawn anywhere on a fine grid across the frame: the card, the words or the background always land on some of it
private fun FrameImage.isBlank(): Boolean {
    for (row in 0 until CheckRows) {
        val y = (row * height + height / 2) / CheckRows
        for (column in 0 until CheckColumns) {
            val x = (column * width + width / 2) / CheckColumns
            if (Color.alpha(bitmap.getPixel(x, y)) != 0) return false
        }
    }
    return true
}
