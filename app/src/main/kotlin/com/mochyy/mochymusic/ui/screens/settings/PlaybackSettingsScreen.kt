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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.mochyy.mochymusic.LocalPlayerConnection
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
    val outputs = LocalPlayerConnection.current?.service?.audioOutputs
    val devices by remember(outputs) { outputs?.devices ?: MutableStateFlow(emptyList()) }.collectAsState()
    val dac = devices.firstOrNull { it.kind == AudioOutputs.Kind.USB }
    val dacName = dac?.name?.ifBlank { "USB DAC" }

    SubSettingsScaffold(
        title = "Playback",
        onBack = { navController.navigateUp() },
        searchBar = { SubSettingsSearchBar(query = query, onQueryChange = { query = it }) },
    ) {
        val items = listOf(
            SettingsItem(
                title = { Text("Keep playing even when closed") },
                highlightKey = "Keep playing even when closed",
                descriptionText = "Keep playing after the app is cleared",
                detail = "Continue playback in background when app is cleared from recents",
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
                title = { Text("Save Player's last state") },
                highlightKey = "Save Player's last state",
                descriptionText = "Off clears played tracks on close",
                detail = "disabling this will removing previously played tracks when closing the app",
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
                title = { Text("Skip Silence") },
                highlightKey = "Skip Silence",
                descriptionText = "Jump over the quiet gaps in songs",
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
                title = { Text("Prefer USB DAC") },
                highlightKey = "Prefer USB DAC",
                descriptionText = dacName?.let { "$it is plugged in" } ?: "Plays through a USB DAC when one is plugged in",
                detail = "Sends the music to a plugged-in USB DAC even while Bluetooth or other headphones are connected. Unplug it and the music goes back to where it was.",
                icon = rememberVectorPainter(Icons.Rounded.Usb),
                iconShape = CircleShape,
                onClick = { onPreferUsbDacChange(!preferUsbDac) },
                trailingContent = { AppSwitch(checked = preferUsbDac, onCheckedChange = onPreferUsbDacChange) },
            ),
            // the dac's own mixer settings only exist on android 14 and up, so older phones don't see the row at all
            SettingsItem(
                title = { Text("USB DAC passthrough") },
                highlightKey = "USB DAC passthrough",
                descriptionText = "Each song at its own rate, bit-perfect when nothing changes the sound",
                detail = "Asks the DAC to run at the song's own sample rate instead of Android's 48 kHz, so nothing is resampled. When the equalizer and effects are off and the volume is at 100%, the song goes out bit-perfect. While it's on, your phone's volume buttons may set the DAC's own volume.",
                icon = rememberVectorPainter(Icons.Rounded.GraphicEq),
                iconShape = CircleShape,
                onClick = { onUsbPassthroughChange(!usbPassthrough) },
                trailingContent = { AppSwitch(checked = usbPassthrough, onCheckedChange = onUsbPassthroughChange) },
            ).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            SettingsItem(
                title = { Text("Hi-res output") },
                highlightKey = "Hi-res output",
                descriptionText = "Keeps 24-bit songs at full detail as 32-bit float",
                detail = "Sends hi-res songs to the speaker as 32-bit float instead of cutting them down to 16-bit. It only kicks in while the equalizer, Audio FX, Crossmix, layered fades, haptics, skip silence and speed changes are all off, because those work in 16-bit. Turn any of them on and the song carries on in 16-bit with every effect.",
                icon = rememberVectorPainter(Icons.Rounded.HighQuality),
                iconShape = CircleShape,
                onClick = { onHiResOutputChange(!hiResOutput) },
                trailingContent = { AppSwitch(checked = hiResOutput, onCheckedChange = onHiResOutputChange) },
            ),
        ).filter {
            query.isBlank() || it.highlightKey?.contains(query, ignoreCase = true) == true ||
                it.descriptionText?.contains(query, ignoreCase = true) == true
        }

        if (outputItems.isNotEmpty()) {
            SettingsGroup(
                title = "Output",
                style = SettingsGroupStyle.Grouped,
                items = outputItems,
            )
        }

        Spacer(Modifier.height(140.dp))
    }
}
