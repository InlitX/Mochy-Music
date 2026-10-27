// GestureScreen.kt

package com.mochyy.mochymusic.ui.screens.gesture

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerAwareWindowInsets
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.HomeStyle
import com.mochyy.mochymusic.constants.HomeStyleKey
import com.mochyy.mochymusic.constants.PlayerStyle
import com.mochyy.mochymusic.constants.PlayerStyleKey
import com.mochyy.mochymusic.constants.PlaylistStyle
import com.mochyy.mochymusic.constants.PlaylistStyleKey
import com.mochyy.mochymusic.constants.SwipeLeftActionKey
import com.mochyy.mochymusic.constants.SwipeRightActionKey
import com.mochyy.mochymusic.constants.ThumbnailCornerRadiusKey
import com.mochyy.mochymusic.ui.component.DefaultSwipeLeft
import com.mochyy.mochymusic.ui.component.DefaultSwipeRight
import com.mochyy.mochymusic.ui.component.HomeCardCornerRadius
import com.mochyy.mochymusic.ui.component.LocalMenuState
import com.mochyy.mochymusic.ui.component.LocalPlayerBottomSheetState
import com.mochyy.mochymusic.ui.component.SheetIcon
import com.mochyy.mochymusic.ui.component.SheetOptionRow
import com.mochyy.mochymusic.ui.component.SongSwipeAction
import com.mochyy.mochymusic.ui.component.bleedHorizontally
import com.mochyy.mochymusic.ui.screens.audiolab.LabBackButton
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference

private val CardPadding = 20.dp

// past this the page stops widening, so a tablet reads like a wide phone
private val PageMaxWidth = 680.dp

private val PageTitleStyle = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 38.sp,
    letterSpacing = (-0.04).em,
)

@Composable
fun GestureScreen(navController: NavController) = CustomisationScreen(navController)

// settings > customisation: cover rounding, playlist/player/home style pickers, and song swipe gestures
@Composable
fun CustomisationScreen(navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val playerSheetState = LocalPlayerBottomSheetState.current

    val (coverRadiusPx, onCoverRadiusChange) = rememberPreference(ThumbnailCornerRadiusKey, defaultValue = 25f)
    val (playlistStyle, onPlaylistStyleChange) = rememberEnumPreference(PlaylistStyleKey, defaultValue = PlaylistStyle.FLOW)
    val playerStyle by rememberEnumPreference(PlayerStyleKey, defaultValue = PlayerStyle.DEFAULT)
    val (homeStyle, onHomeStyleChange) = rememberEnumPreference(HomeStyleKey, defaultValue = HomeStyle.MOCHYY)
    val (right, setRight) = rememberEnumPreference(SwipeRightActionKey, defaultValue = DefaultSwipeRight)
    val (left, setLeft) = rememberEnumPreference(SwipeLeftActionKey, defaultValue = DefaultSwipeLeft)

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    // a plain black page like the mock: back button, the title, then the cards
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PageMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = CustomisePageMargin)
                .padding(top = statusTop + 18.dp, bottom = bottomInset + 32.dp),
        ) {
            LabBackButton(onBack = { navController.navigateUp() })
            Spacer(Modifier.height(14.dp))
            Text(text = "Customize", style = PageTitleStyle, color = Color.White)
            Spacer(Modifier.height(16.dp))

            CoverRoundingSection(
                radiusPx = coverRadiusPx,
                onRadiusChange = { next ->
                    onCoverRadiusChange(next)
                    HomeCardCornerRadius = next.dp
                },
            )

            SectionRule()

            StylePagerSection(
                category = "Playlist styles",
                styles = PlaylistStyle.entries,
                selected = playlistStyle,
                nameOf = { it.displayName },
                onSelect = onPlaylistStyleChange,
                modifier = Modifier.bleedHorizontally(CustomisePageMargin),
            ) { PlaylistStylePreview(style = it, coverRadius = coverRadiusPx.dp) }

            SectionRule()

            PlayerStyleCard(
                selected = playerStyle,
                onOpenEditor = {
                    val hasMedia = playerConnection?.player?.currentMediaItem != null
                    if (hasMedia) {
                        playerSheetState?.expandSoft()
                        playerConnection?.requestPlayerStudio()
                    } else {
                        Toast.makeText(context, "Play a song first to open player customisation", Toast.LENGTH_SHORT).show()
                    }
                },
            )

            SectionRule()

            StylePagerSection(
                category = "Home styles",
                styles = HomeStyle.entries,
                selected = homeStyle,
                nameOf = { it.displayName },
                onSelect = onHomeStyleChange,
                modifier = Modifier.bleedHorizontally(CustomisePageMargin),
            ) { HomeStylePreview(style = it, coverRadius = coverRadiusPx.dp) }

            SectionRule()

            Text(
                text = "Gesture",
                style = TextStyle(
                    fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    letterSpacing = (-0.05).em,
                ),
                color = Color.White,
            )
            Spacer(Modifier.height(14.dp))

            SwipeCard(
                title = "Swipe right",
                subtitle = "Slide a song in any list to the right",
                toRight = true,
                action = right,
                onPick = setRight,
            )
            Spacer(Modifier.height(14.dp))
            SwipeCard(
                title = "Swipe left",
                subtitle = "Slide a song in any list to the left",
                toRight = false,
                action = left,
                onPick = setLeft,
            )
        }
    }
}

@Composable
private fun SectionRule() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier.padding(top = 18.dp, bottom = 20.dp),
    )
}

@Composable
private fun SwipeCard(
    title: String,
    subtitle: String,
    toRight: Boolean,
    action: SongSwipeAction,
    onPick: (SongSwipeAction) -> Unit,
) {
    val sheets = LocalMenuState.current
    LabCard(padding = CardPadding) {
        Text(
            title,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-0.02).em,
            color = LabColors.Primary,
        )
        Text(
            subtitle,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = LabColors.Secondary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(20.dp))
        // the demo runs edge to edge, like the mock: the row slides off the card's side
        SwipePreview(action, toRight, Modifier.bleedHorizontally(CardPadding))
        Spacer(Modifier.height(20.dp))
        ActionDropdown(action) {
            sheets.show {
                ActionPicker(selected = action) { picked ->
                    onPick(picked)
                    sheets.dismiss()
                }
            }
        }
    }
}

@Composable
private fun ActionDropdown(action: SongSwipeAction, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(LabColors.Row)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        ActionOptionBadge(action = action)
        Crossfade(targetState = action, animationSpec = tween(180), modifier = Modifier.weight(1f), label = "swipeAction") { shown ->
            Text(
                shown.title,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                letterSpacing = (-0.01).em,
                color = LabColors.Primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Icon(
            painter = painterResource(R.drawable.expand_more),
            contentDescription = null,
            tint = LabColors.Secondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ActionPicker(selected: SongSwipeAction, onPick: (SongSwipeAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        SongSwipeAction.entries.forEach { option ->
            SheetOptionRow(
                title = option.title,
                onClick = { onPick(option) },
                leading = { ActionOptionBadge(action = option) },
                trailing = {
                    if (option == selected) SheetIcon(R.drawable.check, size = 18.dp)
                },
            )
        }
    }
}

// colored circle badge so each gesture option shows its pill color in the selector and sheet
@Composable
private fun ActionOptionBadge(action: SongSwipeAction) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(action.color.copy(alpha = 0.22f)),
    ) {
        Icon(
            painter = painterResource(action.icon),
            contentDescription = null,
            tint = action.color,
            modifier = Modifier.size(16.dp),
        )
    }
}
