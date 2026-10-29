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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.DisableVideoAutoplayKey
import com.mochyy.mochymusic.constants.SYSTEM_DEFAULT
import com.mochyy.mochymusic.i18n.AppLanguage
import com.mochyy.mochymusic.i18n.activityOrNull
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GeneralSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.activityOrNull() }
    val language = remember(context) { AppLanguage.selection(context) }
    val languageName = if (language == SYSTEM_DEFAULT) stringResource(R.string.system_default)
        else appLanguageNativeName(language)
    val languageTitle = stringResource(R.string.app_language)
    val languageFailure = stringResource(R.string.app_language_update_failed)
    val scope = rememberCoroutineScope()

    if (showLanguageDialog) {
        AppLanguageDialog(
            selected = language,
            onDismiss = { showLanguageDialog = false },
            onSelect = { tag ->
                showLanguageDialog = false
                if (tag != language && activity != null) {
                    scope.launch {
                        try {
                            AppLanguage.select(activity, tag)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, languageFailure, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        )
    }

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
        title = stringResource(R.string.general),
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        Text(
            text = stringResource(R.string.general_app_settings),
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier.padding(bottom = 10.dp, start = 4.dp)
        )

        val searchableTitles = mapOf(
            "App language" to languageTitle,
            "Disable App Updates notice" to stringResource(R.string.settings_updates_notice),
            "Offline mode" to stringResource(R.string.settings_offline_mode),
            "Local song auto metadata" to stringResource(R.string.settings_local_auto_metadata),
            "Disable auto-playing YouTube video" to stringResource(R.string.settings_disable_video_autoplay),
            "Translations from YouTube" to stringResource(R.string.settings_youtube_translations),
            "Sync lyrics to music videos" to stringResource(R.string.settings_sync_lyrics_video),
            "Don't skip on failure" to stringResource(R.string.settings_dont_skip_failure),
        )
        val toggleItems = listOf(
            SettingsItem(
                title = { Text(languageTitle) },
                highlightKey = "App language",
                descriptionText = languageName,
                icon = painterResource(R.drawable.translate),
                iconShape = CircleShape,
                enabled = activity != null,
                onClick = { showLanguageDialog = true },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.settings_updates_notice)) },
                highlightKey = "Disable App Updates notice",
                descriptionText = stringResource(R.string.settings_updates_notice_desc),
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
                title = { Text(stringResource(R.string.settings_offline_mode)) },
                highlightKey = "Offline mode",
                descriptionText = stringResource(R.string.settings_offline_mode_desc),
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
                title = { Text(stringResource(R.string.settings_local_auto_metadata)) },
                highlightKey = "Local song auto metadata",
                descriptionText = stringResource(R.string.settings_local_auto_metadata_desc),
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
                title = { Text(stringResource(R.string.settings_disable_video_autoplay)) },
                highlightKey = "Disable auto-playing YouTube video",
                descriptionText = stringResource(R.string.settings_disable_video_autoplay_desc),
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
                title = { Text(stringResource(R.string.settings_youtube_translations)) },
                highlightKey = "Translations from YouTube",
                descriptionText = stringResource(R.string.settings_youtube_translations_desc),
                detail = stringResource(R.string.settings_youtube_translations_detail),
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
                title = { Text(stringResource(R.string.settings_sync_lyrics_video)) },
                highlightKey = "Sync lyrics to music videos",
                descriptionText = stringResource(R.string.settings_sync_lyrics_video_desc),
                detail = stringResource(R.string.settings_sync_lyrics_video_detail),
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
                title = { Text(stringResource(R.string.settings_dont_skip_failure)) },
                highlightKey = "Don't skip on failure",
                descriptionText = stringResource(R.string.settings_dont_skip_failure_desc),
                detail = stringResource(R.string.settings_dont_skip_failure_detail),
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
                it.highlightKey?.let { key -> searchableTitles[key] }?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true ||
                it.detail?.contains(query, ignoreCase = true) == true
        }

        if (toggleItems.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = toggleItems,
            )
        }

        if (!dontSkipOnFailure && (query.isBlank() || "skip timeout failure".contains(query, ignoreCase = true) ||
                stringResource(R.string.settings_skip_timeout).contains(query, ignoreCase = true))) {
            Spacer(Modifier.height(14.dp))
            LabCard(padding = 14.dp) {
                LabSlider(
                    label = stringResource(R.string.settings_skip_timeout),
                    valueText = stringResource(R.string.settings_seconds_short, failureTimeout),
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
