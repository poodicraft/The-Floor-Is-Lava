package com.paperjump.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paperjump.ui.theme.InkBlack
import kotlin.math.sin

/**
 * The shared furniture every screen outside the game canvas is built from.
 *
 * Keeping the header, the tiles and the buttons in one place is what stops eight screens
 * from each inventing their own spacing and drifting apart — and it means the app's whole
 * feel can be changed here, once.
 *
 * Everything tappable is a *sticker*: a coloured slab sits under it, and pressing pushes
 * the face down onto that slab. It is the one gesture that makes a flat screen feel like
 * something you can actually push, and it costs a single animated dimension.
 */

/** How far a pressed control travels. Small enough to feel crisp, big enough to see. */
private val PRESS_LIFT = 5.dp

/** Ninety milliseconds: fast enough that the button is already down when you look at it. */
private const val PRESS_MS = 90

private val ButtonShape = RoundedCornerShape(18.dp)
private val TileShape = RoundedCornerShape(24.dp)

/**
 * A tappable surface with a slab beneath it that it presses down onto.
 *
 * Everything clickable in the app funnels through here so the whole app presses the same
 * way — a tile, a button and a card differ only in shape, colour and padding.
 */
@Composable
private fun StickerSurface(
    onClick: () -> Unit,
    enabled: Boolean,
    shape: Shape,
    color: Color,
    contentColor: Color,
    slabColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    lift: Dp = PRESS_LIFT,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateDpAsState(
        targetValue = if (pressed && enabled) lift else 0.dp,
        animationSpec = tween(durationMillis = PRESS_MS, easing = LinearEasing),
        label = "press",
    )
    // Padding throws on a negative value, so never let the animation leave the range.
    val drop = travel.coerceIn(0.dp, lift)

    // propagateMinConstraints so a caller's fillMaxWidth reaches the face, not just the box.
    Box(modifier = modifier, propagateMinConstraints = true) {
        Spacer(
            Modifier
                .matchParentSize()
                .padding(top = lift)
                .background(color = if (enabled) slabColor else Color.Transparent, shape = shape),
        )
        Surface(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier.padding(top = drop, bottom = lift - drop),
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    }
}

/**
 * Black or white, whichever can actually be read on top of [color].
 *
 * The accents range from a dark blue to a bright gold, so a fixed "white on accent" rule
 * would leave half the app's labels unreadable.
 */
fun contentColorOn(color: Color): Color =
    if (color.luminance() > 0.45f) InkBlack else Color.White

/** The slab under a solid button: the same colour, pushed towards black. */
private fun slabUnder(color: Color): Color = lerp(color, Color.Black, 0.34f)

@Composable
private fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    color: Color,
    contentColor: Color,
    slabColor: Color,
    border: BorderStroke?,
    lift: Dp,
    verticalPadding: Dp,
) {
    StickerSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        color = color,
        contentColor = contentColor,
        slabColor = slabColor,
        border = border,
        lift = lift,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The loud one: the single thing this screen wants you to do. */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val muted = MaterialTheme.colorScheme.onSurface
    ChunkyButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        color = if (enabled) accent else muted.copy(alpha = 0.12f),
        contentColor = if (enabled) contentColorOn(accent) else muted.copy(alpha = 0.38f),
        slabColor = slabUnder(accent),
        border = null,
        lift = PRESS_LIFT,
        verticalPadding = 15.dp,
    )
}

/** The second choice on a screen: outlined, still pressable, still coloured. */
@Composable
fun PaperOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val muted = MaterialTheme.colorScheme.onSurface
    ChunkyButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surface,
        contentColor = if (enabled) accent else muted.copy(alpha = 0.38f),
        slabColor = accent.copy(alpha = 0.30f),
        border = BorderStroke(2.dp, accent.copy(alpha = if (enabled) 0.6f else 0.2f)),
        lift = PRESS_LIFT,
        verticalPadding = 14.dp,
    )
}

/** The quiet one: a way out, not an invitation. No slab, so it never competes. */
@Composable
fun PaperQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    ChunkyButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = if (enabled) accent else accent.copy(alpha = 0.38f),
        slabColor = Color.Transparent,
        border = null,
        lift = 0.dp,
        verticalPadding = 12.dp,
    )
}

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
    val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)

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

        // A platform, a coin above it, a flag and a puddle of lava: the game's own
        // vocabulary, faint enough to stay behind the content.
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
        drawCircle(
            color = secondary,
            radius = spacing * 0.35f,
            center = Offset(size.width * 0.16f, size.height * 0.24f + bob),
        )
        drawRoundRect(
            color = tertiary,
            topLeft = Offset(size.width * 0.78f, size.height * 0.16f - bob * 0.6f),
            size = Size(spacing * 1.6f, spacing * 1.1f),
            cornerRadius = CornerRadius(spacing * 0.2f),
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
            // A round target rather than a bare glyph: it looks like something to press.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                IconButton(onClick = onBack) {
                    // Auto-mirrored on purpose: "back" points the other way in a
                    // right-to-left layout, unlike the game's movement controls.
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
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

/**
 * The one thing a screen most wants you to press: a big filled slab of colour.
 *
 * Used for "Draw a level" on the home screen, where the whole point of the app is one tap
 * away and should look like it.
 */
@Composable
fun HeroTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onAccent = contentColorOn(accent)
    StickerSurface(
        onClick = onClick,
        enabled = true,
        shape = RoundedCornerShape(28.dp),
        color = accent,
        contentColor = onAccent,
        slabColor = slabUnder(accent),
        lift = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, onAccent.copy(alpha = 0.12f)),
                ),
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = onAccent.copy(alpha = 0.18f),
                    contentColor = onAccent,
                ) {
                    Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onAccent.copy(alpha = 0.85f),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
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
    StickerSurface(
        onClick = onClick,
        enabled = enabled,
        shape = TileShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        slabColor = accent.copy(alpha = 0.38f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.18f), contentColor = accent) {
                Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
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
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = accent.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * A pressable card with a free content slot.
 *
 * The mode picker and the level library both want a tile that presses like everything else
 * but holds their own layout inside.
 */
@Composable
fun PaperCard(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    StickerSurface(
        onClick = onClick,
        enabled = true,
        shape = TileShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        slabColor = accent.copy(alpha = 0.38f),
        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

/** Small caps label that introduces a group of settings or a list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A hand-drawn dash before the words, so a section reads as a heading at a glance.
        Spacer(
            Modifier
                .width(16.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
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
    val accent = MaterialTheme.colorScheme.primary
    StickerSurface(
        onClick = { onCheckedChange(!checked) },
        enabled = true,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // The slab lights up when the setting is on, so a row's state reads from its edge.
        slabColor = if (checked) accent.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outline
            .copy(alpha = 0.25f),
        border = BorderStroke(
            1.5.dp,
            if (checked) accent.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
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
 * experimental-API opt-in, and so the selected segment can use the app's own accent and
 * slide into place instead of blinking.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                val fill by animateColorAsState(
                    targetValue = if (isSelected) accent else Color.Transparent,
                    animationSpec = tween(durationMillis = 160),
                    label = "segment",
                )
                val ink by animateColorAsState(
                    targetValue = if (isSelected) {
                        contentColorOn(accent)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 160),
                    label = "segmentInk",
                )
                Surface(
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp),
                    color = fill,
                    contentColor = ink,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
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
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
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

/**
 * A little tilted label, the way you would stick a note on a page.
 *
 * Used for the badges that are meant to catch the eye — "new best", "4 games" — where a
 * straight rectangle would just look like more chrome.
 */
@Composable
fun StickerBadge(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    tilt: Float = -4f,
) {
    Surface(
        modifier = modifier.rotate(tilt),
        shape = RoundedCornerShape(10.dp),
        color = accent,
        contentColor = contentColorOn(accent),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Padding that keeps scrollable content clear of the system bars. */
val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
