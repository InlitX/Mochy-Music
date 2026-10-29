package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.constants.HiFiResamplerKey
import com.mochyy.mochymusic.constants.HiResOutputKey
import com.mochyy.mochymusic.constants.PersistentQueueKey
import com.mochyy.mochymusic.constants.PreferUsbDacKey
import com.mochyy.mochymusic.constants.UsbPassthroughKey
import com.mochyy.mochymusic.playback.audio.AudioOutputs
import kotlinx.coroutines.flow.MutableStateFlow
import com.mochyy.mochymusic.constants.SkipSilenceKey
import com.mochyy.mochymusic.constants.StopPlaybackOnTaskRemovedKey
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.SettingsGroup
import com.mochyy.mochymusic.ui.component.SettingsGroupStyle
import com.mochyy.mochymusic.ui.component.SettingsItem
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.component.SubSettingsSearchBar
import com.mochyy.mochymusic.utils.rememberPreference

@Composable
fun PlaybackSettingsScreen(navController: NavController) {
    var query by remember { mutableStateOf("") }

    val (stopPlaybackOnTaskRemoved, onStopPlaybackOnTaskRemovedChange) = rememberPreference(
        StopPlaybackOnTaskRemovedKey,
        defaultValue = false
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )

    val keepPlaying = !stopPlaybackOnTaskRemoved

    val (preferUsbDac, onPreferUsbDacChange) = rememberPreference(PreferUsbDacKey, defaultValue = false)
    val (usbPassthrough, onUsbPassthroughChange) = rememberPreference(UsbPassthroughKey, defaultValue = false)
    val (hiResOutput, onHiResOutputChange) = rememberPreference(HiResOutputKey, defaultValue = false)
    val (hiFiResampler, onHiFiResamplerChange) = rememberPreference(HiFiResamplerKey, defaultValue = false)
    val outputs = LocalPlayerConnection.current?.service?.audioOutputs
    val devices by remember(outputs) { outputs?.devices ?: MutableStateFlow(emptyList()) }.collectAsState()
    val dac = devices.firstOrNull { it.kind == AudioOutputs.Kind.USB }
    val dacName = dac?.name?.ifBlank { "USB DAC" }

    SubSettingsScaffold(
        title = stringResource(R.string.settings_playback_category),
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        val items = listOf(
            SettingsItem(
                title = { Text(stringResource(R.string.playback_keep_playing)) },
                highlightKey = "Keep playing even when closed",
                descriptionText = stringResource(R.string.playback_keep_playing_desc),
                detail = stringResource(R.string.playback_keep_playing_detail),
                icon = painterResource(R.drawable.clear_all),
                iconShape = CircleShape,
                onClick = { onStopPlaybackOnTaskRemovedChange(!stopPlaybackOnTaskRemoved) },
                trailingContent = {
                    AppSwitch(
                        checked = keepPlaying,
                        onCheckedChange = { onStopPlaybackOnTaskRemovedChange(!it) },
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.playback_save_state)) },
                highlightKey = "Save Player's last state",
                descriptionText = stringResource(R.string.playback_save_state_desc),
                detail = stringResource(R.string.playback_save_state_detail),
                icon = painterResource(R.drawable.cached),
                iconShape = CircleShape,
                onClick = { onPersistentQueueChange(!persistentQueue) },
                trailingContent = {
                    AppSwitch(
                        checked = persistentQueue,
                        onCheckedChange = onPersistentQueueChange,
                    )
                },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.playback_skip_silence)) },
                highlightKey = "Skip Silence",
                descriptionText = stringResource(R.string.playback_skip_silence_desc),
                icon = painterResource(R.drawable.skip_next),
                iconShape = CircleShape,
                onClick = { onSkipSilenceChange(!skipSilence) },
                trailingContent = {
                    AppSwitch(
                        checked = skipSilence,
                        onCheckedChange = onSkipSilenceChange,
                    )
                },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (items.isNotEmpty()) {
            SettingsGroup(
                style = SettingsGroupStyle.Grouped,
                items = items,
            )
        }

        val outputItems = listOfNotNull(
            SettingsItem(
                title = { Text(stringResource(R.string.playback_usb_prefer)) },
                highlightKey = "Prefer USB DAC",
                descriptionText = if (dacName != null) stringResource(R.string.playback_usb_connected, dacName) else stringResource(R.string.playback_usb_prefer_desc),
                detail = stringResource(R.string.playback_usb_prefer_detail),
                icon = rememberVectorPainter(Icons.Rounded.Usb),
                iconShape = CircleShape,
                onClick = { onPreferUsbDacChange(!preferUsbDac) },
                trailingContent = { AppSwitch(checked = preferUsbDac, onCheckedChange = onPreferUsbDacChange) },
            ),
            // the dac's own mixer settings only exist on android 14 and up, so older phones don't see the row at all
            SettingsItem(
                title = { Text(stringResource(R.string.playback_usb_passthrough)) },
                highlightKey = "USB DAC passthrough",
                descriptionText = stringResource(R.string.playback_usb_passthrough_desc),
                detail = stringResource(R.string.playback_usb_passthrough_detail),
                icon = rememberVectorPainter(Icons.Rounded.GraphicEq),
                iconShape = CircleShape,
                onClick = { onUsbPassthroughChange(!usbPassthrough) },
                trailingContent = { AppSwitch(checked = usbPassthrough, onCheckedChange = onUsbPassthroughChange) },
            ).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            SettingsItem(
                title = { Text(stringResource(R.string.playback_hires)) },
                highlightKey = "Hi-res output",
                descriptionText = stringResource(R.string.playback_hires_desc),
                detail = stringResource(R.string.playback_hires_detail),
                icon = rememberVectorPainter(Icons.Rounded.HighQuality),
                iconShape = CircleShape,
                onClick = { onHiResOutputChange(!hiResOutput) },
                trailingContent = { AppSwitch(checked = hiResOutput, onCheckedChange = onHiResOutputChange) },
            ),
            SettingsItem(
                title = { Text(stringResource(R.string.playback_hifi)) },
                highlightKey = "Hi-fi resampler",
                descriptionText = stringResource(R.string.playback_hifi_desc),
                detail = stringResource(R.string.playback_hifi_detail),
                icon = rememberVectorPainter(Icons.Rounded.Waves),
                iconShape = CircleShape,
                onClick = { onHiFiResamplerChange(!hiFiResampler) },
                trailingContent = { AppSwitch(checked = hiFiResampler, onCheckedChange = onHiFiResamplerChange) },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (outputItems.isNotEmpty()) {
            SettingsGroup(
                title = stringResource(R.string.playback_output),
                style = SettingsGroupStyle.Grouped,
                items = outputItems,
            )
        }

        Spacer(Modifier.height(140.dp))
    }
}
