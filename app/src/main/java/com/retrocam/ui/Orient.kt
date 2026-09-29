package com.retrocam.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * True when the window is wider than it is tall.
 *
 * Read from the *configuration* rather than from `BoxWithConstraints`, on
 * purpose. Constraints are whatever space a particular composable is left with,
 * so a screen that measures inside its own content sees the remainder after its
 * header and toolbars. On a short landscape window that remainder can be taller
 * than it is wide, and a `maxWidth > maxHeight` branch then picks the portrait
 * layout on a device that is plainly sideways.
 *
 * The configuration describes the window itself, which is what "is this device
 * in landscape" actually means.
 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
