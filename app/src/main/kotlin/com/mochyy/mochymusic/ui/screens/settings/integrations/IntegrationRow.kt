package com.mochyy.mochymusic.ui.screens.settings.integrations

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardColor = Color(0xFF232323)
private val CardHeight = 167.dp
private val BannerHeight = 110.dp

/** grey line under a settings page's big title, as in the integrations and discord mocks */
@Composable
internal fun IntegrationPageSubtitle(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 18.dp),
    )
}

/** one service on the integrations page: its banner fading into the card, with the app icon and name over the fade */
@Composable
internal fun IntegrationBannerCard(
    name: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    banner: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CardHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BannerHeight)
                .clipToBounds()
                // the banner melts into the card instead of ending in a hard edge
                .drawWithCache {
                    val fade = Brush.verticalGradient(
                        0.74f to Color.Transparent,
                        1f to CardColor,
                    )
                    onDrawWithContent {
                        drawContent()
                        drawRect(fade)
                    }
                },
            content = banner,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 22.dp),
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Text(
                text = name,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 22.dp),
            )
        }
    }
}

/** a banner that's one of the user's pictures, filling the width */
@Composable
internal fun BoxScope.PictureBanner(@DrawableRes picture: Int) {
    Image(
        painter = painterResource(picture),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // a touch closer than a plain crop, as the mock frames the Discord banner
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = 1.1f
                scaleY = 1.1f
            },
    )
}

/** a plain colour banner, with the service's wordmark on it when there is one */
@Composable
internal fun BoxScope.ColorBanner(color: Color, @DrawableRes wordmark: Int? = null) {
    Box(
        Modifier
            .fillMaxSize()
            .background(color),
    )
    wordmark?.let {
        Image(
            painter = painterResource(it),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 21.dp)
                .height(57.dp),
        )
    }
}
