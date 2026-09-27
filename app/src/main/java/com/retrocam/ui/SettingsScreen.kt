package com.retrocam.ui

import com.retrocam.ui.theme.RetroType

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Full-screen settings (reference layout): General / Filters / Storage / About. */
@Composable
fun SettingsScreen(viewModel: CameraViewModel, thumbnails: Map<String, Bitmap>) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    var dialog by remember { mutableStateOf<String?>(null) }

    // Real folder picker. The chosen tree gives us a document id like
    // "primary:Pictures/RetroCam", which maps straight onto MediaStore's
    // RELATIVE_PATH so saves still land in the gallery.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel::setSaveDirFromTree)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.setSettingsOpen(false) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "RetroCam",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    "Settings",
                    fontFamily = RetroType.Mono,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }

        SectionLabel("General")
        SettingsCard {
            SettingRow(
                icon = Icons.Filled.Crop,
                title = "Aspect Ratio",
                subtitle = ASPECT_LABELS[state.viewAspect.coerceIn(0, ASPECT_LABELS.lastIndex)],
                onClick = { dialog = "aspect" },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Filled.GridOn,
                title = "Grid",
                subtitle = if (state.gridOn) "On" else "Off",
                onClick = { dialog = "grid" },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Filled.Timer,
                title = "Timer",
                subtitle = TIMER_LABELS[TIMER_VALUES.indexOf(state.timerSeconds).coerceAtLeast(0)],
                onClick = { dialog = "timer" },
            )
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Favorite, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Photo Card", fontFamily = RetroType.Mono, color = Color.White)
                    Text(
                        "Polaroid frame baked into saved photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(checked = state.photoCard, onCheckedChange = viewModel::setPhotoCard)
            }
        }

        SectionLabel("Filters")
        SettingsCard {
            FilterStrip(
                specs = state.specs,
                selectedId = state.filter.id,
                favorites = state.favorites,
                thumbnails = thumbnails,
                onSelect = viewModel::selectFilter,
                onToggleFavorite = viewModel::toggleFavorite,
                reducedMotion = reduced,
            )
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Visibility, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Preview", fontFamily = RetroType.Mono, color = Color.White)
                    Text(
                        "Show filter preview before capture",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(checked = state.filterPreview, onCheckedChange = viewModel::setFilterPreview)
            }
        }

        SectionLabel("Storage")
        SettingsCard {
            SettingRow(
                icon = Icons.Filled.Folder,
                title = "Save Location",
                subtitle = state.saveDir,
                onClick = { folderPicker.launch(null) },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Filled.Description,
                title = "File Format",
                subtitle = if (state.fileFormat == "PNG") "PNG (Lossless)" else "JPEG (High Quality)",
                onClick = { dialog = "format" },
            )
        }

        SectionLabel("About")
        SettingsCard {
            SettingRow(
                icon = Icons.Filled.Info,
                title = "App Version",
                subtitle = "0.5.0 (debug)",
                onClick = { dialog = "version" },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Filled.Info,
                title = "Diagnostics",
                subtitle = "Share render state for debugging",
                onClick = { viewModel.exportDiagnostics() },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Filled.Favorite,
                title = "Rate Us",
                subtitle = "Help us grow",
                onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )
        }

        SettingsCard(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.resetAll() }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Reset Settings",
                        fontFamily = RetroType.Mono,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Restore default settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }

    when (dialog) {
        "aspect" -> OptionsDialog(
            title = "Aspect Ratio",
            options = ASPECT_LABELS,
            selected = ASPECT_LABELS[state.viewAspect.coerceIn(0, ASPECT_LABELS.lastIndex)],
            onPick = { viewModel.setViewAspect(ASPECT_LABELS.indexOf(it)) },
            onDismiss = { dialog = null },
        )
        "grid" -> OptionsDialog(
            title = "Grid",
            options = listOf("Off", "On"),
            selected = if (state.gridOn) "On" else "Off",
            onPick = { viewModel.setGrid(it == "On") },
            onDismiss = { dialog = null },
        )
        "timer" -> OptionsDialog(
            title = "Timer",
            options = TIMER_LABELS,
            selected = TIMER_LABELS[TIMER_VALUES.indexOf(state.timerSeconds).coerceAtLeast(0)],
            onPick = { viewModel.setTimerSeconds(TIMER_VALUES[TIMER_LABELS.indexOf(it)]) },
            onDismiss = { dialog = null },
        )
        "format" -> OptionsDialog(
            title = "File Format",
            options = listOf("JPEG", "PNG"),
            selected = state.fileFormat,
            onPick = { viewModel.setFileFormat(it) },
            onDismiss = { dialog = null },
        )
        "version" -> OptionsDialog(
            title = "RetroCam 0.5.0",
            options = listOf("Debug build — 39 filters"),
            selected = "Debug build — 39 filters",
            onPick = {},
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = RetroType.Mono,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = RetroType.Mono, color = Color.White)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun RowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
    )
}
