package com.retrocam.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrocam.ui.components.ButtonSize
import com.retrocam.ui.components.ButtonVariant
import com.retrocam.ui.components.ShadcnButton
import com.retrocam.ui.theme.AppType
import com.retrocam.ui.theme.ShadcnRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Grid of everything RetroCam has saved, photos and videos together. */
@Composable
fun GalleryScreen(saveDir: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    // The item open in the viewer, if any. An overlay over the grid rather
    // than a screen of its own, so closing it is one tap back to where you
    // were, scroll position included.
    var viewing by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(reloadTick) {
        items = withContext(Dispatchers.IO) { Gallery.loadItems(context, dir = saveDir) }
        loaded = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Every other screen clears the system bars. This one did not,
                    // so "ALBUM" and the back arrow were drawn straight through the
                    // clock and the signal icons.
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShadcnButton(
                    onClick = onBack,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leading = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    },
                )
                Text(
                    "ALBUM",
                    fontFamily = AppType.Sans,
                    fontWeight = AppType.Strong,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (loaded && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "NO SHOTS YET",
                        fontFamily = AppType.Sans,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            // More columns and a capped width in landscape. Three columns across a
            // landscape phone gives enormous thumbnails and leaves a long empty
            // margin either side; the extra columns are the whole point of turning
            // the phone, and the cap stops a tablet from producing poster-sized
            // squares.
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (isLandscape()) 5 else 3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 900.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                items(items, key = { it.id }) { item ->
                    GalleryCell(
                        item = item,
                        onClick = { viewing = item },
                        onShare = { Gallery.share(context, item) },
                    )
                }
            }
        }

        // Over the grid, not under it: a grid child would be measured with the
        // leftover height, which is how the Lab's save dialog once turned
        // invisible. The Box root is the only parent with a whole screen.
        viewing?.let { item ->
            ViewerOverlay(
                item = item,
                saveDir = saveDir,
                onClose = { viewing = null },
                onGone = { gone ->
                    items = items.filterNot { it.id == gone.id }
                    viewing = null
                },
                onSavedCopy = { reloadTick++ },
            )
        }
    }
}

/**
 * One photo or video, full screen, with the only actions a gallery needs:
 * share, delete, and - for photos - rotate, mirror and save-as-copy.
 *
 * Delete is confirmed first and goes through the system's consent screen on
 * Android 10+, where gallery files belong to MediaStore rather than the app.
 * Edits never touch the original: rotate and mirror work on the preview, and
 * only Save writes a new file next to it.
 */
@Composable
private fun ViewerOverlay(
    item: MediaItem,
    saveDir: String,
    onClose: () -> Unit,
    onGone: (MediaItem) -> Unit,
    onSavedCopy: () -> Unit,
) {
    val context = LocalContext.current
    var photo by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var videoThumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    // The preview under edit. Null means the original is showing; non-null is
    // a transformed copy waiting for Save. The source stays untouched either
    // way, and only one transformed bitmap is ever alive.
    var edited by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var busy by remember(item.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Crop state. The rect lives in frame uv (y up), the same convention as
    // the Lab's stage masks, so a drag maps to bitmap pixels with one multiply
    // and letterboxing is handled by clamping, not by special cases.
    var cropping by remember(item.id) { mutableStateOf(false) }
    var cropUv by remember(item.id) { mutableStateOf<FloatArray?>(null) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    // Bitmaps are native memory the GC will not hurry for, so both go the
    // moment the viewer does rather than whenever finalisation feels like it.
    DisposableEffect(item.id) {
        onDispose {
            photo?.recycle()
            edited?.recycle()
        }
    }
    // A delete the system made us ask permission for is finished here: the
    // consent screen returns to this launcher, not to the tap site.
    val consentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                Feedback.info(context, "Deleted")
                onGone(item)
            } else {
                Feedback.info(context, "Not deleted")
            }
        }

    val density = LocalContext.current.resources.displayMetrics.density

    /**
     * Where the photo actually lands inside the frame, in box pixels.
     *
     * ContentScale.Fit letterboxes, so this rect - not the frame - is what a
     * drag maps onto. Computed from the live bitmap, so it stays right after
     * a rotate swaps width and height.
     */
    fun fittedRect(): android.graphics.RectF? {
        val src = edited ?: photo ?: return null
        if (frameSize.width <= 0 || frameSize.height <= 0) return null
        val pad = 8f * density
        val w = (frameSize.width - 2 * pad).coerceAtLeast(1f)
        val h = (frameSize.height - 2 * pad).coerceAtLeast(1f)
        val s = minOf(w / src.width, h / src.height)
        val dw = src.width * s
        val dh = src.height * s
        val left = pad + (w - dw) / 2f
        val top = pad + (h - dh) / 2f
        return android.graphics.RectF(left, top, left + dw, top + dh)
    }

    /** Cuts the dragged rect out of the working bitmap and saves it as a copy. */
    fun saveCrop() {
        val src = edited ?: photo ?: return
        val uv = cropUv
        if (uv == null) {
            Feedback.info(context, "Drag on the photo first")
            return
        }
        val x0 = (uv[0] * src.width).toInt().coerceIn(0, src.width - 1)
        val y0 = ((1f - uv[3]) * src.height).toInt().coerceIn(0, src.height - 1)
        val x1 = (uv[2] * src.width).toInt().coerceIn(1, src.width)
        val y1 = ((1f - uv[1]) * src.height).toInt().coerceIn(1, src.height)
        if (x1 - x0 < 8 || y1 - y0 < 8) {
            Feedback.info(context, "Drag a bigger area")
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            val cut = withContext(Dispatchers.IO) {
                runCatching { Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0) }.getOrNull()
            }
            val saved = if (cut != null) {
                withContext(Dispatchers.IO) { Gallery.saveCopy(context, saveDir, item, cut) }
            } else null
            cut?.recycle()
            busy = false
            if (saved != null) {
                Feedback.info(context, "Saved a copy")
                cropping = false
                cropUv = null
                onSavedCopy()
            } else {
                Feedback.info(context, "Couldn't save that copy")
            }
        }
    }

    LaunchedEffect(item.id) {
        if (item.isVideo) {
            videoThumb = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        item.uri, android.util.Size(1024, 1024), null,
                    )
                }.getOrNull()
            }
        } else {
            photo = withContext(Dispatchers.IO) { Gallery.loadBitmap(context, item) }
            if (photo == null) Feedback.info(context, "Couldn't open that photo")
        }
    }

    fun runDelete() {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Gallery.delete(context, item) }
            busy = false
            when (outcome) {
                Gallery.DeleteResult.Deleted -> {
                    Feedback.info(context, "Deleted")
                    onGone(item)
                }
                is Gallery.DeleteResult.NeedConsent -> {
                    runCatching {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(outcome.sender).build(),
                        )
                    }.onFailure {
                        Feedback.info(context, "Couldn't delete that")
                    }
                }
                Gallery.DeleteResult.Failed -> Feedback.info(context, "Couldn't delete that")
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShadcnButton(
                    onClick = {
                        edited?.recycle()
                        onClose()
                    },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leading = {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to album",
                            tint = Color.White,
                        )
                    },
                )
                Spacer(Modifier.weight(1f))
                ShadcnButton(
                    onClick = { Gallery.share(context, item) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leading = {
                        Icon(Icons.Filled.Share, "Share", tint = Color.White)
                    },
                )
                ShadcnButton(
                    onClick = { confirmDelete = true },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Icon,
                    leading = {
                        Icon(Icons.Filled.Delete, "Delete", tint = Color.White)
                    },
                )
            }

            // ---- the shot ----
            // ContentScale.Fit letterboxes, so box pixels are NOT bitmap pixels:
            // the fitted rect is derived below and every drag is clamped to it,
            // which means dragging over the black bars just pins to the edge.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { frameSize = it }
                    .pointerInput(item.id, cropping) {
                        if (item.isVideo || !cropping) return@pointerInput
                        var anchor: Offset? = null
                        fun toUv(p: Offset): Offset {
                            val r = fittedRect() ?: return Offset(0f, 0f)
                            return Offset(
                                ((p.x - r.left) / r.width()).coerceIn(0f, 1f),
                                (1f - (p.y - r.top) / r.height()).coerceIn(0f, 1f),
                            )
                        }
                        detectDragGestures(
                            onDragStart = { anchor = toUv(it); cropUv = null },
                            onDrag = { change, _ ->
                                change.consume()
                                val a = anchor ?: toUv(change.position)
                                val c = toUv(change.position)
                                anchor = a
                                cropUv = floatArrayOf(
                                    minOf(a.x, c.x), minOf(a.y, c.y),
                                    maxOf(a.x, c.x), maxOf(a.y, c.y),
                                )
                            },
                            onDragEnd = { anchor = null },
                            onDragCancel = { anchor = null },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val showing = edited ?: photo ?: videoThumb
                if (showing != null) {
                    Image(
                        bitmap = showing.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
                // The outline of the pending crop, in box pixels.
                val uv = cropUv
                val r = fittedRect()
                if (cropping && uv != null && r != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val tl = Offset(r.left + uv[0] * r.width(), r.top + (1f - uv[3]) * r.height())
                        val br = Offset(r.left + uv[2] * r.width(), r.top + (1f - uv[1]) * r.height())
                        drawRect(
                            color = Color.White,
                            topLeft = tl,
                            size = Size(
                                (br.x - tl.x).coerceAtLeast(1f),
                                (br.y - tl.y).coerceAtLeast(1f),
                            ),
                            style = Stroke(width = 2f),
                        )
                    }
                }
                if (item.isVideo) {
                    ShadcnButton(
                        text = "Play",
                        onClick = { Gallery.open(context, item) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Default,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            // ---- edits, photos only ----
            // Video frames are not bitmaps the app owns, so there is nothing
            // honest to rotate here; videos get share and delete only.
            if (cropping) {
                // Crop mode replaces the tool row: the only honest actions
                // with a pending rect are keeping it or dropping it. Rotating
                // underneath it would silently move the goalposts.
                Text(
                    "Drag on the photo, then save",
                    fontFamily = AppType.Sans,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShadcnButton(
                        text = "Cancel",
                        onClick = {
                            cropping = false
                            cropUv = null
                        },
                        variant = ButtonVariant.Outline,
                        modifier = Modifier.weight(1f),
                    )
                    ShadcnButton(
                        text = "Save crop",
                        onClick = ::saveCrop,
                        variant = ButtonVariant.Default,
                        enabled = cropUv != null && !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (!item.isVideo) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction(Icons.Filled.RotateRight, "Rotate") {
                        val src = edited ?: photo ?: return@ViewerAction
                        edited?.recycle()
                        edited = Gallery.transform(src, rotateCw = true)
                    }
                    ViewerAction(Icons.Filled.Flip, "Mirror") {
                        val src = edited ?: photo ?: return@ViewerAction
                        edited?.recycle()
                        edited = Gallery.transform(src, rotateCw = false)
                    }
                    ViewerAction(Icons.Filled.Crop, "Crop") {
                        if (photo != null) cropping = true
                    }
                    ViewerAction(
                        Icons.Filled.Save,
                        "Save copy",
                        enabled = edited != null && !busy,
                    ) {
                        val bmp = edited ?: return@ViewerAction
                        busy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                Gallery.saveCopy(context, saveDir, item, bmp)
                            }
                            busy = false
                            if (saved != null) {
                                Feedback.info(context, "Saved a copy")
                                onSavedCopy()
                            } else {
                                Feedback.info(context, "Couldn't save that copy")
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }

        if (confirmDelete) {
            DeleteConfirm(
                onCancel = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    runDelete()
                },
            )
        }
    }
}

/** One labelled tool in the viewer's edit bar. */
@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.35f),
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, fontFamily = AppType.Sans, fontSize = 10.sp, color = Color.White)
    }
}

/** "Really delete?" - a destructive tap gets a second tap, always. */
@Composable
private fun DeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(ShadcnRadius.Lg))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(20.dp),
        ) {
            Text(
                "DELETE THIS?",
                fontFamily = AppType.Sans,
                fontWeight = AppType.Strong,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "It is gone for good - there is no trash to fetch it back from.",
                fontFamily = AppType.Sans,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShadcnButton(
                    text = "Keep",
                    onClick = onCancel,
                    variant = ButtonVariant.Outline,
                    modifier = Modifier.weight(1f),
                )
                ShadcnButton(
                    text = "Delete",
                    onClick = onConfirm,
                    variant = ButtonVariant.Destructive,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun GalleryCell(item: MediaItem, onClick: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    item.uri, android.util.Size(256, 256), null,
                )
            }.getOrNull()
        }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ShadcnRadius.Lg))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        }
        ShadcnButton(
            onClick = onShare,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Icon,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(28.dp),
            leading = {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}
