package com.mochyy.mochymusic.ui.player.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.constants.DefaultPlayerCoverStyle
import com.mochyy.mochymusic.constants.PlayerBackgroundStyle
import com.mochyy.mochymusic.constants.PlayerBackgroundStyleKey
import com.mochyy.mochymusic.constants.PlayerBottomBarStyle
import com.mochyy.mochymusic.constants.PlayerBottomBarStyleKey
import com.mochyy.mochymusic.constants.PlayerCoverStyle
import com.mochyy.mochymusic.constants.PlayerCoverStyleKey
import com.mochyy.mochymusic.ui.player.PlayerProgressSlider
import com.mochyy.mochymusic.ui.player.PlayerTransportRow
import com.mochyy.mochymusic.ui.player.PlayerVolumeBar
import com.mochyy.mochymusic.ui.player.SongInfoRow
import com.mochyy.mochymusic.ui.player.customize.DiscCoverStack
import com.mochyy.mochymusic.ui.player.customize.PlayerBackgroundPreview
import com.mochyy.mochymusic.ui.player.customize.coverArtBox
import com.mochyy.mochymusic.ui.player.customize.isDisc
import com.mochyy.mochymusic.ui.player.models.TrackInfo
import com.mochyy.mochymusic.ui.utils.resize
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.ui.utils.stableStatusBars

/** swallows every touch, so a preview shows its content without playing it */
internal fun Modifier.inert(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** the cover as [style] draws it, filling its box: the same art the player shows */
@Composable
internal fun CoverArt(
    style: PlayerCoverStyle,
    trackInfo: TrackInfo,
    isPlaying: Boolean,
    queueIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (style.isDisc) {
        DiscCoverStack(
            style = style,
            artworkUrl = trackInfo.thumbnailUrl,
            mediaId = trackInfo.mediaId,
            isPlaying = isPlaying,
            spinActive = true,
            editMode = true,
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    val url = trackInfo.thumbnailUrl ?: return
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url.resize(1200, 1200))
            .crossfade(200)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .then(if (style == PlayerCoverStyle.SQUARED) Modifier.clip(RoundedCornerShape(22.dp)) else Modifier),
    )
}

/**
 * The flagship player put together from its real parts - its background, the cover in the cover
 * style it's set to, the title row, the seek bar, the transport and the card - for its selector
 * card when another style is the live one.
 */
@Composable
internal fun FlagshipPreview(
    modifier: Modifier = Modifier,
    /** the Turntable: the flagship around a record player, its arm for the seek bar */
    turntable: Boolean = false,
) {
    val connection = LocalPlayerConnection.current ?: return
    val track by connection.uiState.trackInfo.collectAsState()
    val transport by connection.uiState.transportState.collectAsState()
    val queue by connection.uiState.queueState.collectAsState()
    val flagshipCover by rememberEnumPreference(PlayerCoverStyleKey, DefaultPlayerCoverStyle)
    val coverStyle = if (turntable) PlayerCoverStyle.TURNTABLE else flagshipCover
    val backgroundStyle by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.COVER_GRADIENT)
    val barStyle by rememberEnumPreference(PlayerBottomBarStyleKey, PlayerBottomBarStyle.STACK)
    val statusTop = WindowInsets.stableStatusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier = modifier.fillMaxSize().inert()) {
        PlayerBackgroundPreview(
            style = backgroundStyle,
            thumbnailUrl = track.thumbnailUrl,
            width = maxWidth,
            height = maxHeight,
            animate = false,
            warp = false,
            modifier = Modifier.fillMaxSize(),
        )
        val box = coverArtBox(coverStyle, maxWidth, maxHeight, statusTop)
        Box(
            modifier = Modifier
                .offset(box.x, box.y)
                .size(box.width, box.height),
        ) {
            CoverArt(coverStyle, track, transport.isPlaying, queue.currentIndex)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = navBottom + 108.dp),
        ) {
            SongInfoRow()
            Spacer(Modifier.height(10.dp))
            // the Turntable's arm is its seek bar: the bar's room is kept, empty
            if (turntable) Box(Modifier.graphicsLayer { alpha = 0f }) { PlayerProgressSlider(active = false) } else PlayerProgressSlider()
            Spacer(Modifier.height(22.dp))
            PlayerTransportRow()
        }
        if (barStyle == PlayerBottomBarStyle.VOLUME) {
            PlayerVolumeBar(
                isActive = { false },
                onAction = {},
                preview = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 26.dp, end = 26.dp, bottom = navBottom + 14.dp),
            )
        } else {
            // the bottom card, as its glass plate
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 26.dp, end = 26.dp, bottom = navBottom + 14.dp)
                    .fillMaxWidth()
                    .height(78.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
            )
        }
    }
}
