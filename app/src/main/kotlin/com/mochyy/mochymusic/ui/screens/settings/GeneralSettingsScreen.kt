package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.DisableVideoAutoplayKey
import com.mochyy.mochymusic.constants.DontSkipOnFailureKey
import com.mochyy.mochymusic.constants.LocalSongAutoMetadataKey
import com.mochyy.mochymusic.constants.OfflineModeKey
import com.mochyy.mochymusic.constants.StreamFailureTimeoutKey
import com.mochyy.mochymusic.constants.SyncLyricsToVideoKey
import com.mochyy.mochymusic.constants.YouTubeCaptionTranslationKey
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabSlider
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.SubSettingsSearchBar
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberPreference

@Composable
fun GeneralSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }

    val (offlineMode, onOfflineModeChange) = rememberPreference(
        OfflineModeKey,
        defaultValue = false
    )
    val (localSongAutoMetadata, onLocalSongAutoMetadataChange) = rememberPreference(
        LocalSongAutoMetadataKey,
        defaultValue = true
    )
    val (disableVideoAutoplay, onDisableVideoAutoplayChange) = rememberPreference(
        DisableVideoAutoplayKey,
        defaultValue = false
    )

    val (youtubeTranslations, onYoutubeTranslationsChange) = rememberPreference(
        YouTubeCaptionTranslationKey,
        defaultValue = true
    )
    val (syncLyricsToVideo, onSyncLyricsToVideoChange) = rememberPreference(
        SyncLyricsToVideoKey,
        defaultValue = true
    )

    val (dontSkipOnFailure, onDontSkipOnFailureChange) = rememberPreference(
        DontSkipOnFailureKey,
        defaultValue = false
    )

    val (failureTimeout, onFailureTimeoutChange) = rememberPreference(
        StreamFailureTimeoutKey,
        defaultValue = 6
    )

    SubSettingsScaffold(
        title = "General",
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        Text(
            text = "Generally app settings",
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
        )

        val toggleItems = listOf(
            SettingsItem(
                title = { Text("Disable App Updates notice") },
                highlightKey = "Disable App Updates notice",
                descriptionText = "No popup will appear except if u open the updater",
                icon = painterResource(R.drawable.commit),
                iconShape = CircleShape,
                onClick = { },
                trailingContent = {
                    AppSwitch(
                        checked = false,
                        onCheckedChange = { },
                        enabled = false,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Offline mode") },
                highlightKey = "Offline mode",
                descriptionText = "play downloaded music only",
                icon = painterResource(R.drawable.download),
                iconShape = CircleShape,
                onClick = { onOfflineModeChange(!offlineMode) },
                trailingContent = {
                    AppSwitch(
                        checked = offlineMode,
                        onCheckedChange = onOfflineModeChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Local song auto metadata") },
                highlightKey = "Local song auto metadata",
                descriptionText = "Automatically match local tracks with online metadata",
                icon = painterResource(R.drawable.cached),
                iconShape = CircleShape,
                onClick = { onLocalSongAutoMetadataChange(!localSongAutoMetadata) },
                trailingContent = {
                    AppSwitch(
                        checked = localSongAutoMetadata,
                        onCheckedChange = onLocalSongAutoMetadataChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Disable auto-playing YouTube video") },
                highlightKey = "Disable auto-playing YouTube video",
                descriptionText = "Music videos and live shows play with their cover instead of the video",
                icon = painterResource(R.drawable.slow_motion_video),
                iconShape = CircleShape,
                onClick = { onDisableVideoAutoplayChange(!disableVideoAutoplay) },
                trailingContent = {
                    AppSwitch(
                        checked = disableVideoAutoplay,
                        onCheckedChange = onDisableVideoAutoplayChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Translations from YouTube") },
                highlightKey = "Translations from YouTube",
                descriptionText = "Use a video's own translated captions when you translate lyrics",
                detail = "When the song's video has captions someone made in your language, they're used instead of a machine translation",
                icon = painterResource(R.drawable.translate),
                iconShape = CircleShape,
                onClick = { onYoutubeTranslationsChange(!youtubeTranslations) },
                trailingContent = {
                    AppSwitch(
                        checked = youtubeTranslations,
                        onCheckedChange = onYoutubeTranslationsChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Sync lyrics to music videos") },
                highlightKey = "Sync lyrics to music videos",
                descriptionText = "Line lyrics up with the video's intro, skits and cuts",
                detail = "Lyrics are usually timed to the album version. When a music video plays, the app listens to it next to the album version and moves the lyrics to match",
                icon = painterResource(R.drawable.lyrics),
                iconShape = CircleShape,
                onClick = { onSyncLyricsToVideoChange(!syncLyricsToVideo) },
                trailingContent = {
                    AppSwitch(
                        checked = syncLyricsToVideo,
                        onCheckedChange = onSyncLyricsToVideoChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text("Don't skip on failure") },
                highlightKey = "Don't skip on failure",
                descriptionText = "Stay on a failed stream instead of skipping",
                detail = "When a stream fails, stay on it instead of skipping to the next track after a few seconds",
                icon = painterResource(R.drawable.skip_next),
                iconShape = CircleShape,
                onClick = { onDontSkipOnFailureChange(!dontSkipOnFailure) },
                trailingContent = {
                    AppSwitch(
                        checked = dontSkipOnFailure,
                        onCheckedChange = onDontSkipOnFailureChange,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (toggleItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = toggleItems,
            )
        }

        if (!dontSkipOnFailure && (query.isBlank() || "skip timeout failure".contains(query, ignoreCase = true))) {
            Spacer(Modifier.height(14.dp))
            LabCard(padding = 14.dp) {
                LabSlider(
                    label = "Skip timeout",
                    valueText = "${failureTimeout}s",
                    value = failureTimeout.toFloat(),
                    onValueChange = { onFailureTimeoutChange(it.toInt()) },
                    valueRange = 5f..20f,
                    steps = 14,
                )
            }
        }

        Spacer(Modifier.height(140.dp))
    }
}
