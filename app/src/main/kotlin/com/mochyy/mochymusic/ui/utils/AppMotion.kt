// AppMotion.kt
//
// Settings > Appearance > Animation ("Smooth UI transitions and element blurs"). The switch was
// there but nothing read it (#10). The navigation transitions are plain lambdas, not composables,
// so they can't read a preference themselves: MainActivity mirrors the setting here, as snapshot
// state, and they read it when a transition starts.

package com.mochyy.mochymusic.ui.utils

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry

object AppMotion {
    /** false: pages swap without sliding or fading, and the lyrics' per-line blur is off */
    var enabled by mutableStateOf(true)
}

/** [transition], or no transition at all while animations are switched off */
fun AnimatedContentTransitionScope<NavBackStackEntry>.motionEnter(
    transition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?,
): EnterTransition? = if (AppMotion.enabled) transition() else EnterTransition.None

/** [transition], or no transition at all while animations are switched off */
fun AnimatedContentTransitionScope<NavBackStackEntry>.motionExit(
    transition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition?,
): ExitTransition? = if (AppMotion.enabled) transition() else ExitTransition.None

/** The NavHost's own defaults, which can't be null: [transition], or none while animations are off */
fun AnimatedContentTransitionScope<NavBackStackEntry>.defaultMotionEnter(
    transition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
): EnterTransition = if (AppMotion.enabled) transition() else EnterTransition.None

/** The NavHost's own defaults, which can't be null: [transition], or none while animations are off */
fun AnimatedContentTransitionScope<NavBackStackEntry>.defaultMotionExit(
    transition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
): ExitTransition = if (AppMotion.enabled) transition() else ExitTransition.None
