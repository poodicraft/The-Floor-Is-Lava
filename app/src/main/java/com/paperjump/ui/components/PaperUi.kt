package com.paperjump.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The shared furniture every screen outside the game canvas is built from.
 *
 * Keeping the header, the tiles and the paper backdrop in one place is what stops six
 * screens from each inventing their own spacing and drifting apart.
 */

/**
 * Ruled-paper background with a couple of doodles drifting across it.
 *
 * Drawn rather than shipped as an image: it costs nothing in the APK, it follows the
 * theme's colours in light and dark, and it scales to any screen without going fuzzy.
 */
@Composable
fun PaperBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift",
    )

    val ruleColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val inkColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val secondary = MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val spacing = size.minDimension / 14f
        var y = spacing
        while (y < size.height) {
            drawLine(ruleColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
            y += spacing
        }
        var x = spacing
        while (x < size.width) {
            drawLine(ruleColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1f)
            x += spacing
        }

        // A platform, a coin above it and a puddle of lava: the game's own vocabulary,
        // faint enough to stay behind the content.
        val bob = sin(phase * 6.28f) * spacing * 0.35f
        val platformWidth = size.width * 0.42f
        drawRoundRect(
            color = inkColor,
            topLeft = Offset(size.width * 0.5f, size.height * 0.72f + bob),
            size = Size(platformWidth, spacing * 0.5f),
            cornerRadius = CornerRadius(spacing * 0.25f),
        )
        drawCircle(
            color = secondary,
            radius = spacing * 0.55f,
            center = Offset(size.width * 0.72f, size.height * 0.62f - bob),
        )
        drawRoundRect(
            color = accent,
            topLeft = Offset(-spacing, size.height * 0.88f),
            size = Size(size.width * 0.45f, spacing * 1.4f),
            cornerRadius = CornerRadius(spacing * 0.4f),
        )
    }
}

/** Back arrow, title and optional trailing content. Used by every non-game screen. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                // Auto-mirrored on purpose: "back" points the other way in a right-to-left
                // layout, unlike the game's movement controls.
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** A big tappable tile: icon in a coloured disc, title, one line of explanation. */
@Composable
fun MenuTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.18f), contentColor = accent) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

/** Small caps label that introduces a group of settings or a list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A titled row with a switch on the end. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * A segmented picker.
 *
 * Hand-built from `Surface`s rather than using chips or `SegmentedButton` so it carries no
 * experimental-API opt-in, and so the selected segment can use the app's own accent.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A compact figure with a caption, used for level stats and personal bests. */
@Composable
fun StatChip(
    value: String,
    label: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Padding that keeps scrollable content clear of the system bars. */
val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
