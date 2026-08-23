package com.paperjump.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperjump.data.SavedLevelMeta
import com.paperjump.ui.components.PaperButton
import com.paperjump.ui.components.PaperCard
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.theme.LavaOrange
import java.text.DateFormat
import java.util.Date

/** The saved level library: open, rename or delete anything made before. */
@Composable
fun LevelLibraryScreen(
    levels: List<SavedLevelMeta>,
    loadThumbnail: suspend (String) -> Bitmap?,
    onOpen: (SavedLevelMeta) -> Unit,
    onRename: (SavedLevelMeta, String) -> Unit,
    onDelete: (SavedLevelMeta) -> Unit,
    onDraw: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<SavedLevelMeta?>(null) }
    var deleting by remember { mutableStateOf<SavedLevelMeta?>(null) }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "My levels",
            subtitle = if (levels.isEmpty()) null else "${levels.size} saved",
            onBack = onBack,
        )

        if (levels.isEmpty()) {
            EmptyLibrary(onDraw = onDraw, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(levels, key = { it.id }) { meta ->
                    LevelRow(
                        meta = meta,
                        loadThumbnail = loadThumbnail,
                        onOpen = { onOpen(meta) },
                        onRename = { renaming = meta },
                        onDelete = { deleting = meta },
                    )
                }
            }
        }
    }

    renaming?.let { meta ->
        RenameDialog(
            meta = meta,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRename(meta, name)
                renaming = null
            },
        )
    }

    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this level?") },
            text = { Text("\"${meta.name}\" will be removed from the library. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(meta)
                        deleting = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun LevelRow(
    meta: SavedLevelMeta,
    loadThumbnail: suspend (String) -> Bitmap?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    // Decoded off the main thread, once per row, and only at thumbnail size.
    var thumbnail by remember(meta.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(meta.id) { thumbnail = loadThumbnail(meta.id) }

    PaperCard(onClick = onOpen, accent = LavaOrange) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 86.dp, height = 62.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                thumbnail?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${meta.source.label} · ${formatDate(meta.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onRename) {
                Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Rename ${meta.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete ${meta.name}",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    meta: SavedLevelMeta,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(meta.id) { mutableStateOf(meta.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename level") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyLibrary(onDraw: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No levels saved yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Save a level after playing it and it will show up here, ready to play again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        PaperButton(
            text = "Draw one now",
            icon = Icons.Rounded.Brush,
            onClick = onDraw,
            accent = LavaOrange,
        )
    }
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
