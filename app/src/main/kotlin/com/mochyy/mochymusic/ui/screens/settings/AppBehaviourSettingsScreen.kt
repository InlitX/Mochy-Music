package com.mochyy.mochymusic.ui.screens.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.CanvasThumbnailAnimationKey
import com.mochyy.mochymusic.constants.CanvasWifiOnlyKey
import com.mochyy.mochymusic.constants.DisableVideoAutoplayKey
import com.mochyy.mochymusic.constants.DontSkipOnFailureKey
import com.mochyy.mochymusic.constants.HiFiResamplerKey
import com.mochyy.mochymusic.constants.HiResOutputKey
import com.mochyy.mochymusic.constants.HideSettingsDescriptionsKey
import com.mochyy.mochymusic.constants.HideUpdateNoticeKey
import com.mochyy.mochymusic.constants.LocalSongAutoMetadataKey
import com.mochyy.mochymusic.constants.LyricsHighBloomKey
import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.constants.LyricsMotionStyleKey
import com.mochyy.mochymusic.constants.OfflineModeKey
import com.mochyy.mochymusic.constants.PersistentQueueKey
import com.mochyy.mochymusic.constants.PlayVideoBackgroundKey
import com.mochyy.mochymusic.constants.PreferUsbDacKey
import com.mochyy.mochymusic.constants.SYSTEM_DEFAULT
import com.mochyy.mochymusic.constants.ShowFormatBadgeKey
import com.mochyy.mochymusic.constants.ShowFormatSampleRateKey
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.constants.StopPlaybackOnTaskRemovedKey
import com.mochyy.mochymusic.constants.StreamFailureTimeoutKey
import com.mochyy.mochymusic.constants.SyncLyricsToVideoKey
import com.mochyy.mochymusic.constants.UsbPassthroughKey
import com.mochyy.mochymusic.constants.YouTubeCaptionTranslationKey
import com.mochyy.mochymusic.constants.YtVideoBackgroundLyricsSyncKey
import com.mochyy.mochymusic.core.updater.saveUpdateNotificationsSetting
import com.mochyy.mochymusic.i18n.AppLanguage
import com.mochyy.mochymusic.i18n.activityOrNull
import com.mochyy.mochymusic.playback.audio.AudioOutputs
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.DefaultPlayerSeekBarSlider
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsHighlight
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.SubSettingsSearchBar
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// settings > app behaviour: how the app and the player act, what used to be general, playback and most of appearance
@Composable
fun AppBehaviourSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }

    SubSettingsScaffold(
        title = "App behaviour",
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        Section("Generally app settings", generalItems().matching(query))
        Section("Local settings", localItems().matching(query))
        Section("Player behaviour", playerBehaviourItems().matching(query))
        Section("On the player", playerLookItems().matching(query))
        Section("Output", outputItems().matching(query))
        Section("Other", otherItems().matching(query))
        Section("Related", relatedItems(navController).matching(query))
        Spacer(Modifier.height(140.dp))
    }
}

// a section's title and its grouped rows, left out whole when the search hides every row
@Composable
private fun Section(title: String, items: List<SettingsItem>) {
    if (items.isEmpty()) return
    Text(
        text = title,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        color = Color.White,
        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp),
    )
    SettingsGroup(style = SettingsGroupStyle.Grouped, items = items)
    Spacer(Modifier.height(18.dp))
}

private fun List<SettingsItem>.matching(query: String): List<SettingsItem> {
    if (query.isBlank()) return this
    return filter {
        it.highlightKey?.contains(query, ignoreCase = true) == true ||
            it.descriptionText?.contains(query, ignoreCase = true) == true ||
            it.detail?.contains(query, ignoreCase = true) == true
    }
}

@Composable
private fun generalItems(): List<SettingsItem> {
    val context = LocalContext.current
    val (noticeHidden, onNoticeHidden) = rememberPreference(HideUpdateNoticeKey, defaultValue = false)
    val (offlineMode, onOfflineModeChange) = rememberPreference(OfflineModeKey, defaultValue = false)
    val (stopOnTaskRemoved, onStopOnTaskRemoved) = rememberPreference(StopPlaybackOnTaskRemovedKey, defaultValue = false)
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(PersistentQueueKey, defaultValue = true)
    // the launch check's notification reads its own switch, so it follows this one too
    val setNoticeHidden = { hidden: Boolean ->
        onNoticeHidden(hidden)
        saveUpdateNotificationsSetting(context, !hidden)
    }
    return listOf(
        languageItem(),
        switchItem(
            title = "Disable App Updates notice",
            description = "No popup will appear except if you open the updater",
            icon = painterResource(R.drawable.update),
            checked = noticeHidden,
            onCheckedChange = setNoticeHidden,
        ),
        switchItem(
            title = "Offline mode",
            description = "Play downloaded music only",
            icon = painterResource(R.drawable.download),
            checked = offlineMode,
            onCheckedChange = onOfflineModeChange,
        ),
        switchItem(
            title = "Keep playing even when closed",
            description = "Keep playing after the app is cleared",
            detail = "Continue playback in background when app is cleared from recents",
            icon = painterResource(R.drawable.clear_all),
            checked = !stopOnTaskRemoved,
            onCheckedChange = { onStopOnTaskRemoved(!it) },
        ),
        switchItem(
            title = "Save Player's last state",
            description = "Off clears played tracks on close",
            detail = "Turning this off removes the songs you already played when you close the app",
            icon = painterResource(R.drawable.cached),
            checked = persistentQueue,
            onCheckedChange = onPersistentQueueChange,
        ),
    )
}

// the app's own language, apart from the phone's; android 13+ keeps it in the system's per-app setting too
@Composable
private fun languageItem(): SettingsItem {
    var showDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.activityOrNull() }
    val language = remember(context) { AppLanguage.selection(context) }
    val languageName = if (language == SYSTEM_DEFAULT) stringResource(R.string.system_default) else appLanguageNativeName(language)
    val failure = stringResource(R.string.app_language_update_failed)
    val scope = rememberCoroutineScope()
    if (showDialog) {
        AppLanguageDialog(
            selected = language,
            onDismiss = { showDialog = false },
            onSelect = { tag ->
                showDialog = false
                if (tag != language && activity != null) {
                    scope.launch {
                        try {
                            AppLanguage.select(activity, tag)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        )
    }
    return SettingsItem(
        title = { Text(stringResource(R.string.app_language)) },
        highlightKey = "App language",
        descriptionText = languageName,
        icon = painterResource(R.drawable.translate),
        iconShape = CircleShape,
        enabled = activity != null,
        onClick = { showDialog = true },
    )
}

@Composable
private fun localItems(): List<SettingsItem> {
    val (autoMetadata, onAutoMetadataChange) = rememberPreference(LocalSongAutoMetadataKey, defaultValue = true)
    return listOf(
        switchItem(
            title = "Local song auto metadata",
            description = "Automatically match local tracks with online metadata",
            icon = painterResource(R.drawable.cached),
            checked = autoMetadata,
            onCheckedChange = onAutoMetadataChange,
        ),
    )
}

@Composable
private fun playerBehaviourItems(): List<SettingsItem> {
    val (disableVideoAutoplay, onDisableVideoAutoplayChange) = rememberPreference(DisableVideoAutoplayKey, defaultValue = false)
    val (youtubeTranslations, onYoutubeTranslationsChange) = rememberPreference(YouTubeCaptionTranslationKey, defaultValue = true)
    val (syncLyricsToVideo, onSyncLyricsToVideoChange) = rememberPreference(SyncLyricsToVideoKey, defaultValue = true)
    val (dontSkipOnFailure, onDontSkipOnFailureChange) = rememberPreference(DontSkipOnFailureKey, defaultValue = false)
    val (failureTimeout, onFailureTimeoutChange) = rememberPreference(StreamFailureTimeoutKey, defaultValue = 6)
    val (skipSilence, onSkipSilenceChange) = rememberPreference(SkipSilenceKey, defaultValue = false)
    return listOf(
        switchItem(
            title = "Disable auto-playing YouTube video",
            description = "Music videos and live shows play with their cover instead of the video",
            icon = painterResource(R.drawable.slow_motion_video),
            checked = disableVideoAutoplay,
            onCheckedChange = onDisableVideoAutoplayChange,
        ),
        switchItem(
            title = "Translations from YouTube",
            description = "Use a video's own translated captions when you translate lyrics",
            detail = "When the song's video has captions someone made in your language, they're used instead of a machine translation",
            icon = painterResource(R.drawable.translate),
            checked = youtubeTranslations,
            onCheckedChange = onYoutubeTranslationsChange,
        ),
        switchItem(
            title = "Sync lyrics to music videos",
            description = "Line lyrics up with the video's intro, skits and cuts",
            detail = "Lyrics are usually timed to the album version. When a music video plays, the app listens to it next to the album version and moves the lyrics to match",
            icon = painterResource(R.drawable.lyrics),
            checked = syncLyricsToVideo,
            onCheckedChange = onSyncLyricsToVideoChange,
        ),
        switchItem(
            title = "Don't skip on failure",
            description = "Stay on a failed stream instead of skipping",
            detail = "When a stream fails, stay on it instead of skipping to the next track after a few seconds",
            icon = painterResource(R.drawable.skip_next),
            checked = dontSkipOnFailure,
            onCheckedChange = onDontSkipOnFailureChange,
        ),
        SettingsItem(
            title = { ValueTitle("Skip timeout", "${failureTimeout}s") },
            highlightKey = "Skip timeout failure",
            description = {
                DefaultPlayerSeekBarSlider(
                    value = failureTimeout.toFloat(),
                    onValueChange = { onFailureTimeoutChange(it.toInt()) },
                    valueRange = 5f..20f,
                    steps = 14,
                    modifier = Modifier.padding(top = 8.dp),
                )
            },
            icon = painterResource(R.drawable.speed),
            iconShape = CircleShape,
            isVisible = !dontSkipOnFailure,
            isSubOption = true,
        ),
        switchItem(
            title = "Skip Silence",
            description = "Jump over the quiet gaps in songs",
            icon = painterResource(R.drawable.skip_next),
            checked = skipSilence,
            onCheckedChange = onSkipSilenceChange,
        ),
    )
}

@Composable
private fun playerLookItems(): List<SettingsItem> {
    val (formatBadge, onFormatBadgeChange) = rememberPreference(ShowFormatBadgeKey, defaultValue = true)
    val (formatSampleRate, onFormatSampleRateChange) = rememberPreference(ShowFormatSampleRateKey, defaultValue = false)
    val (playVideoBackground, onPlayVideoBackgroundChange) = rememberPreference(PlayVideoBackgroundKey, defaultValue = false)
    val (lyricsSync, onLyricsSyncChange) = rememberPreference(YtVideoBackgroundLyricsSyncKey, defaultValue = true)
    val (canvasEnabled, onCanvasEnabledChange) = rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true)
    val (canvasWifiOnly, onCanvasWifiOnlyChange) = rememberPreference(CanvasWifiOnlyKey, defaultValue = true)
    val (highBloom, onHighBloomChange) = rememberPreference(LyricsHighBloomKey, defaultValue = true)
    val (lyricsStyle) = rememberEnumPreference(LyricsMotionStyleKey, defaultValue = LyricsMotionStyle.MOTION)
    return listOf(
        switchItem(
            title = "Audio format badge",
            description = "What the song is playing as, beside its title",
            icon = painterResource(R.drawable.graphic_eq),
            checked = formatBadge,
            onCheckedChange = onFormatBadgeChange,
        ),
        switchItem(
            title = "Show sample rate",
            description = "The stream's sample rate after its format",
            icon = painterResource(R.drawable.speed),
            checked = formatSampleRate,
            onCheckedChange = onFormatSampleRateChange,
            subOptionOf = formatBadge,
        ),
        switchItem(
            title = "Always enable Youtube background",
            description = "Stream video background on player when available",
            icon = painterResource(R.drawable.slow_motion_video),
            checked = playVideoBackground,
            onCheckedChange = onPlayVideoBackgroundChange,
        ),
        switchItem(
            title = "Timestamp matching",
            description = "Based on subtitle",
            icon = painterResource(R.drawable.lyrics),
            checked = lyricsSync,
            onCheckedChange = onLyricsSyncChange,
            subOptionOf = playVideoBackground,
        ),
        switchItem(
            title = "Enable animated canvas",
            description = "Looping visuals for supported tracks",
            icon = painterResource(R.drawable.sparks),
            checked = canvasEnabled,
            onCheckedChange = onCanvasEnabledChange,
        ),
        switchItem(
            title = "Canvas on Wi-Fi only",
            description = "Skip mobile data",
            icon = painterResource(R.drawable.wifi_proxy),
            checked = canvasWifiOnly,
            onCheckedChange = onCanvasWifiOnlyChange,
            subOptionOf = canvasEnabled,
        ),
        // only the shaded lyric styles have a bloom to make softer
        switchItem(
            title = "High quality bloom",
            description = "Extra soft bloom pass on the lyrics glow",
            icon = painterResource(R.drawable.sparks),
            checked = highBloom,
            onCheckedChange = onHighBloomChange,
            visible = lyricsStyle.shaded,
        ),
    )
}

@Composable
private fun outputItems(): List<SettingsItem> {
    val (preferUsbDac, onPreferUsbDacChange) = rememberPreference(PreferUsbDacKey, defaultValue = false)
    val (usbPassthrough, onUsbPassthroughChange) = rememberPreference(UsbPassthroughKey, defaultValue = false)
    val (hiResOutput, onHiResOutputChange) = rememberPreference(HiResOutputKey, defaultValue = false)
    val (hiFiResampler, onHiFiResamplerChange) = rememberPreference(HiFiResamplerKey, defaultValue = false)
    val outputs = LocalPlayerConnection.current?.service?.audioOutputs
    val devices by remember(outputs) { outputs?.devices ?: MutableStateFlow(emptyList()) }.collectAsState()
    val dacName = devices.firstOrNull { it.kind == AudioOutputs.Kind.USB }?.name?.ifBlank { "USB DAC" }
    return listOfNotNull(
        switchItem(
            title = "Prefer USB DAC",
            description = dacName?.let { "$it is plugged in" } ?: "Plays through a USB DAC when one is plugged in",
            detail = "Sends the music to a plugged-in USB DAC even while Bluetooth or other headphones are connected. Unplug it and the music goes back to where it was.",
            icon = rememberVectorPainter(Icons.Rounded.Usb),
            checked = preferUsbDac,
            onCheckedChange = onPreferUsbDacChange,
        ),
        // the dac's own mixer settings only exist on android 14 and up, so older phones don't see the row at all
        switchItem(
            title = "USB DAC passthrough",
            description = "Each song at its own rate, bit-perfect when nothing changes the sound",
            detail = "Asks the DAC to run at the song's own sample rate instead of Android's 48 kHz, so nothing is resampled. When the equalizer and effects are off and the volume is at 100%, the song goes out bit-perfect. While it's on, your phone's volume buttons may set the DAC's own volume.",
            icon = rememberVectorPainter(Icons.Rounded.GraphicEq),
            checked = usbPassthrough,
            onCheckedChange = onUsbPassthroughChange,
        ).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
        switchItem(
            title = "Hi-res output",
            description = "Keeps 24-bit songs at full detail as 32-bit float",
            detail = "Sends hi-res songs to the speaker as 32-bit float instead of cutting them down to 16-bit. It only kicks in while the equalizer, Audio FX, Crossmix, layered fades, haptics, skip silence and speed changes are all off, because those work in 16-bit. Turn any of them on and the song carries on in 16-bit with every effect.",
            icon = rememberVectorPainter(Icons.Rounded.HighQuality),
            checked = hiResOutput,
            onCheckedChange = onHiResOutputChange,
        ),
        switchItem(
            title = "Hi-fi resampler",
            description = "Mochy's own filter turns songs to your speaker's rate",
            detail = "Most songs are 44.1 kHz and your phone plays at 48 kHz, so something has to convert them. With this on, Mochy does it with its own high-quality filter (flat to 20 kHz, 120 dB of rejection, in 32-bit float) instead of leaving it to the phone. It works on the speaker and the headphone jack. Bluetooth and USB are left alone: Bluetooth's rate can't be read reliably, and USB has passthrough. It uses a little more battery.",
            icon = rememberVectorPainter(Icons.Rounded.Waves),
            checked = hiFiResampler,
            onCheckedChange = onHiFiResamplerChange,
        ),
    )
}

@Composable
private fun otherItems(): List<SettingsItem> {
    val (hideDescriptions, onHideDescriptions) = rememberPreference(HideSettingsDescriptionsKey, defaultValue = false)
    return listOf(
        switchItem(
            title = "Disable description on settings",
            description = "Only the titles show, hold a setting to read what it does",
            icon = painterResource(R.drawable.info),
            checked = hideDescriptions,
            onCheckedChange = onHideDescriptions,
        ),
    )
}

// settings that live on another page but people look for here
@Composable
private fun relatedItems(navController: NavController): List<SettingsItem> = listOf(
    SettingsItem(
        title = { Text("Hide Top Bar") },
        highlightKey = "Hide Top Bar status bar player",
        descriptionText = "The phone's status bar over the player, in Customize",
        icon = painterResource(R.drawable.tune),
        iconShape = CircleShape,
        onClick = {
            SettingsHighlight.pending = TopBarHighlightKey
            navController.navigate("customisation_settings")
        },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        },
    ),
)

// what customize answers to when a search or the related row asks for its top bar card
const val TopBarHighlightKey = "Top Bar on player"

// a row title with its value on the right, for the rows with a slider under them
@Composable
private fun ValueTitle(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title)
        Text(
            text = value,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.70f),
        )
    }
}

// one switch row; subOptionOf tucks it under its parent and only shows it while the parent is on
private fun switchItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.painter.Painter,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
    subOptionOf: Boolean? = null,
    visible: Boolean = true,
) = SettingsItem(
    title = { Text(title) },
    highlightKey = title,
    descriptionText = description,
    detail = detail,
    icon = icon,
    iconShape = CircleShape,
    isVisible = visible && subOptionOf != false,
    isSubOption = subOptionOf != null,
    onClick = { onCheckedChange(!checked) },
    trailingContent = {
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = subOptionOf != false)
    },
)
