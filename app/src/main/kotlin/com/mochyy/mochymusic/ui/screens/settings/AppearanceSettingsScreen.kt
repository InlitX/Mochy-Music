package com.mochyy.mochymusic.ui.screens.settings

import android.os.Build
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.BlurStrengthPxKey
import com.mochyy.mochymusic.constants.CanvasThumbnailAnimationKey
import com.mochyy.mochymusic.constants.CanvasWifiOnlyKey
import com.mochyy.mochymusic.constants.DisableBlurKey
import com.mochyy.mochymusic.constants.EnableElementBlurAnimatorKey
import com.mochyy.mochymusic.constants.EnableProgressiveBlurKey
import com.mochyy.mochymusic.constants.LyricsHighBloomKey
import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.constants.LyricsMotionStyleKey
import com.mochyy.mochymusic.constants.LyricsWaveAnimationKey
import com.mochyy.mochymusic.constants.PlayVideoBackgroundKey
import com.mochyy.mochymusic.constants.ShowFormatBadgeKey
import com.mochyy.mochymusic.constants.ShowFormatSampleRateKey
import com.mochyy.mochymusic.constants.YtVideoBackgroundLyricsSyncKey
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.DefaultPlayerSeekBarSlider
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.SubSettingsSearchBar
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsScreen(navController: NavController) {
    val playerConnection = com.mochyy.mochymusic.LocalPlayerConnection.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }

    val (playVideoBackground, onPlayVideoBackgroundChange) = rememberPreference(
        PlayVideoBackgroundKey,
        defaultValue = false
    )
    val (lyricsSync, onLyricsSyncChange) = rememberPreference(
        YtVideoBackgroundLyricsSyncKey,
        defaultValue = true
    )
    val (canvasEnabled, onCanvasEnabledChange) = rememberPreference(
        CanvasThumbnailAnimationKey,
        defaultValue = true
    )
    val (canvasWifiOnly, onCanvasWifiOnlyChange) = rememberPreference(
        CanvasWifiOnlyKey,
        defaultValue = true
    )
    val (disableBlur, onDisableBlurChange) = rememberPreference(
        DisableBlurKey,
        defaultValue = false
    )
    var blurQuality by com.mochyy.mochymusic.utils.rememberEnumPreference(
        com.mochyy.mochymusic.constants.BlurQualityKey,
        com.mochyy.mochymusic.constants.BlurQuality.HIGH,
    )
    val (progressiveBlur, onProgressiveBlurChange) = rememberPreference(
        EnableProgressiveBlurKey,
        defaultValue = true
    )
    val (elementBlurAnimator, onElementBlurAnimatorChange) = rememberPreference(
        EnableElementBlurAnimatorKey,
        defaultValue = true
    )
    val (blurStrengthPx, onBlurStrengthPxChange) = rememberPreference(
        BlurStrengthPxKey,
        defaultValue = 25f
    )
    val (lyricsWaveAnimation, onLyricsWaveAnimationChange) = rememberPreference(
        LyricsWaveAnimationKey,
        defaultValue = true
    )
    val (lyricsHighBloom, onLyricsHighBloomChange) = rememberPreference(
        LyricsHighBloomKey,
        defaultValue = true
    )
    val (formatBadge, onFormatBadgeChange) = rememberPreference(
        ShowFormatBadgeKey,
        defaultValue = true
    )
    val (formatSampleRate, onFormatSampleRateChange) = rememberPreference(
        ShowFormatSampleRateKey,
        defaultValue = false
    )
    val (lyricsMotionStyle, onLyricsMotionStyleChange) = rememberEnumPreference(
        LyricsMotionStyleKey,
        defaultValue = LyricsMotionStyle.MOTION,
    )

    SubSettingsScaffold(
        title = stringResource(R.string.appearance),
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        Text(
            text = stringResource(R.string.appearance_player),
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
        )

        val playerPageItems = listOf(
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_customize_player)) },
                highlightKey = "Player customization",
                descriptionText = stringResource(R.string.appearance_customize_player_desc),
                icon = painterResource(R.drawable.album),
                iconShape = CircleShape,
                // the player is edited right where it plays: it opens and shrinks into its selector
                onClick = {
                    if (playerConnection?.player?.currentMediaItem != null) {
                        playerConnection.requestPlayerStudio()
                    } else {
                        android.widget.Toast.makeText(context, context.getString(R.string.appearance_customize_player_toast), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_customize_lyrics)) },
                highlightKey = "Lyrics letter animation",
                descriptionText = stringResource(R.string.appearance_customize_lyrics_desc),
                icon = painterResource(R.drawable.lyrics),
                iconShape = CircleShape,
                onClick = { onLyricsWaveAnimationChange(!lyricsWaveAnimation) },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (playerPageItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = playerPageItems,
            )
            Spacer(Modifier.height(14.dp))
        }

        val playerToggleItems = listOf(
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_lyrics_animation)) },
                highlightKey = "Lyrics animation style",
                descriptionText = stringResource(if (lyricsMotionStyle == LyricsMotionStyle.MOTION) R.string.appearance_lyrics_motion_desc else R.string.appearance_lyrics_legacy_desc),
                icon = painterResource(R.drawable.lyrics),
                iconShape = CircleShape,
                onClick = {
                    onLyricsMotionStyleChange(
                        if (lyricsMotionStyle == LyricsMotionStyle.MOTION) LyricsMotionStyle.LEGACY else LyricsMotionStyle.MOTION
                    )
                },
                trailingContent = {
                    LyricsStyleToggle(selected = lyricsMotionStyle, onSelect = onLyricsMotionStyleChange)
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_shader)) },
                highlightKey = "Lyrics letter animation",
                // style 1's rise is drawn, not shaded, so it keeps working with glow off
                descriptionText = stringResource(R.string.appearance_shader_desc),
                icon = painterResource(R.drawable.biotech),
                iconShape = CircleShape,
                onClick = { onLyricsWaveAnimationChange(!lyricsWaveAnimation) },
                trailingContent = {
                    AppSwitch(
                        checked = lyricsWaveAnimation,
                        onCheckedChange = onLyricsWaveAnimationChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_bloom)) },
                highlightKey = "High quality bloom",
                descriptionText = stringResource(R.string.appearance_bloom_desc),
                icon = painterResource(R.drawable.sparks),
                iconShape = CircleShape,
                isVisible = lyricsWaveAnimation,
                isSubOption = true,
                onClick = { onLyricsHighBloomChange(!lyricsHighBloom) },
                trailingContent = {
                    AppSwitch(
                        checked = lyricsHighBloom,
                        onCheckedChange = onLyricsHighBloomChange,
                        enabled = lyricsWaveAnimation,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_format_badge)) },
                highlightKey = "Audio format badge",
                descriptionText = stringResource(R.string.appearance_format_badge_desc),
                icon = painterResource(R.drawable.graphic_eq),
                iconShape = CircleShape,
                onClick = { onFormatBadgeChange(!formatBadge) },
                trailingContent = {
                    AppSwitch(
                        checked = formatBadge,
                        onCheckedChange = onFormatBadgeChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_sample_rate)) },
                highlightKey = "Show sample rate",
                descriptionText = stringResource(R.string.appearance_sample_rate_desc),
                icon = painterResource(R.drawable.speed),
                iconShape = CircleShape,
                isVisible = formatBadge,
                isSubOption = true,
                onClick = { onFormatSampleRateChange(!formatSampleRate) },
                trailingContent = {
                    AppSwitch(
                        checked = formatSampleRate,
                        onCheckedChange = onFormatSampleRateChange,
                        enabled = formatBadge,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_youtube_background)) },
                highlightKey = "Yt video background",
                descriptionText = stringResource(R.string.appearance_youtube_background_desc),
                icon = painterResource(R.drawable.slow_motion_video),
                iconShape = CircleShape,
                onClick = { onPlayVideoBackgroundChange(!playVideoBackground) },
                trailingContent = {
                    AppSwitch(
                        checked = playVideoBackground,
                        onCheckedChange = onPlayVideoBackgroundChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_timestamp_matching)) },
                descriptionText = stringResource(R.string.appearance_timestamp_matching_desc),
                icon = painterResource(R.drawable.lyrics),
                iconShape = CircleShape,
                isVisible = playVideoBackground,
                isSubOption = true,
                onClick = { onLyricsSyncChange(!lyricsSync) },
                trailingContent = {
                    AppSwitch(
                        checked = lyricsSync,
                        onCheckedChange = onLyricsSyncChange,
                        enabled = playVideoBackground,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_canvas)) },
                highlightKey = "Animated canvas",
                descriptionText = stringResource(R.string.appearance_canvas_desc),
                icon = painterResource(R.drawable.sparks),
                iconShape = CircleShape,
                onClick = { onCanvasEnabledChange(!canvasEnabled) },
                trailingContent = {
                    AppSwitch(
                        checked = canvasEnabled,
                        onCheckedChange = onCanvasEnabledChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_canvas_wifi)) },
                descriptionText = stringResource(R.string.appearance_canvas_wifi_desc),
                icon = painterResource(R.drawable.wifi_proxy),
                iconShape = CircleShape,
                isVisible = canvasEnabled,
                isSubOption = true,
                onClick = { onCanvasWifiOnlyChange(!canvasWifiOnly) },
                trailingContent = {
                    AppSwitch(
                        checked = canvasWifiOnly,
                        onCheckedChange = onCanvasWifiOnlyChange,
                        enabled = canvasEnabled,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (playerToggleItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = playerToggleItems,
            )
            Spacer(Modifier.height(18.dp))
        }

        Text(
            text = stringResource(R.string.appearance_global),
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
        )

        val globalToggleItems = listOf(
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_blur)) },
                highlightKey = "Blur",
                descriptionText = stringResource(R.string.appearance_blur_desc),
                icon = painterResource(R.drawable.contrast),
                iconShape = CircleShape,
                onClick = { onDisableBlurChange(!disableBlur) },
                trailingContent = {
                    AppSwitch(
                        checked = !disableBlur,
                        onCheckedChange = { enabled -> onDisableBlurChange(!enabled) },
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_blur_quality)) },
                highlightKey = "Blur quality",
                descriptionText = if (blurQuality == com.mochyy.mochymusic.constants.BlurQuality.LOW) {
                    stringResource(R.string.appearance_blur_low_desc)
                } else {
                    stringResource(R.string.appearance_blur_high_desc)
                },
                icon = painterResource(R.drawable.tune),
                iconShape = CircleShape,
                isVisible = !disableBlur,
                isSubOption = true,
                onClick = {
                    blurQuality = if (blurQuality == com.mochyy.mochymusic.constants.BlurQuality.LOW) {
                        com.mochyy.mochymusic.constants.BlurQuality.HIGH
                    } else {
                        com.mochyy.mochymusic.constants.BlurQuality.LOW
                    }
                },
                trailingContent = {
                    Text(
                        text = stringResource(if (blurQuality == com.mochyy.mochymusic.constants.BlurQuality.LOW) R.string.appearance_low else R.string.appearance_high),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.70f),
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_progressive_blur)) },
                descriptionText = stringResource(R.string.appearance_progressive_blur_desc),
                icon = painterResource(R.drawable.gradient),
                iconShape = CircleShape,
                isVisible = !disableBlur,
                isSubOption = true,
                onClick = { onProgressiveBlurChange(!progressiveBlur) },
                trailingContent = {
                    AppSwitch(
                        checked = progressiveBlur,
                        onCheckedChange = onProgressiveBlurChange,
                        enabled = !disableBlur,
                    )
                },
            ),
            SettingsItem(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.appearance_blur_strength))
                        Text(
                            text = "${blurStrengthPx.roundToInt()}px",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.70f),
                        )
                    }
                },
                description = {
                    DefaultPlayerSeekBarSlider(
                        value = blurStrengthPx,
                        onValueChange = { onBlurStrengthPxChange(it) },
                        valueRange = 10f..50f,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                },
                icon = painterResource(R.drawable.tune),
                iconShape = CircleShape,
                isVisible = !disableBlur,
                isSubOption = true,
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.appearance_animation)) },
                highlightKey = "Animation",
                descriptionText = stringResource(R.string.appearance_animation_desc),
                icon = painterResource(R.drawable.speed),
                iconShape = CircleShape,
                onClick = { onElementBlurAnimatorChange(!elementBlurAnimator) },
                trailingContent = {
                    AppSwitch(
                        checked = elementBlurAnimator,
                        onCheckedChange = onElementBlurAnimatorChange,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (globalToggleItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = globalToggleItems,
            )
        }

        Spacer(Modifier.height(140.dp))
    }
}

/** two-option pill for the lyrics animation style: "1" is the new motion, "2" the legacy wave */
@Composable
private fun LyricsStyleToggle(
    selected: LyricsMotionStyle,
    onSelect: (LyricsMotionStyle) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LyricsMotionStyle.entries.forEach { style ->
            val isSelected = style == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) Color.White else Color.Transparent,
                label = "lyricsStyleChip",
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
                label = "lyricsStyleChipText",
            )
            Text(
                text = stringResource(if (style == LyricsMotionStyle.MOTION) R.string.appearance_style_1 else R.string.appearance_style_2),
                color = content,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(style) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
