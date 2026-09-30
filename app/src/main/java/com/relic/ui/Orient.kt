package com.relic.ui

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

/**
 * The live preview's aspect ratio for the current orientation.
 *
 * [ASPECT_RATIOS] is a list of portrait framings - 4:3, 16:9, 1:1, 2:3, 3:2 -
 * which is how the choice is labelled and how a photo is framed. In landscape,
 * though, CameraX hands the surface a buffer it has already turned to match the
 * display, so the content is wide while the ratio still says tall. Sizing a
 * portrait-shaped box around wide content makes the renderer's cover-crop keep
 * only a narrow vertical slice of the frame, which looks exactly like the
 * preview having gone sideways.
 *
 * The reciprocal is the same framing expressed in the turned frame - 4:3
 * becomes 3:4 - and 1:1 maps to itself, so one rule covers the whole list. It
 * is also what the capture should use, since the saved photo takes the shape of
 * the frame that was composed on screen.
 *
 * Shared by the camera and the Lab so the two cannot drift apart again.
 */
@Composable
@ReadOnlyComposable
fun previewAspect(viewAspect: Int): Float {
    val portrait = ASPECT_RATIOS[viewAspect.coerceIn(0, ASPECT_RATIOS.lastIndex)]
    return if (isLandscape()) 1f / portrait else portrait
}
