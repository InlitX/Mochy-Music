package com.mochyy.mochymusic.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mochyy.mochymusic.ui.component.BottomSheetState

/** Hide only the status bar while the player is open; restore it when leaving or disabling the option. */
@Composable
internal fun HidePlayerStatusBar(state: BottomSheetState) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    // The sheet object changes when the window resizes for the keyboard. Follow the replacement
    // instead of disposing/restarting the bar effect, so typing in the player's own sheets works.
    val currentSheet by rememberUpdatedState(state)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(window, windowFocused) {
        val controller = WindowCompat.getInsetsController(window, window.decorView.rootView)
        try {
            snapshotFlow { currentSheet.isExpandedOrExpanding }.collect { open ->
                if (open) controller.hide(WindowInsetsCompat.Type.statusBars())
                else controller.show(WindowInsetsCompat.Type.statusBars())
            }
        } finally {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
