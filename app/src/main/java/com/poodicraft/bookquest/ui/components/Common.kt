package com.poodicraft.bookquest.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poodicraft.bookquest.data.Book
import kotlin.math.min
import kotlin.random.Random

/** Soft, colourful page background used behind every screen. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background,
                        colors.background,
                        colors.primaryContainer.copy(alpha = 0.55f)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.BottomStart)
                .background(
                    Brush.radialGradient(
                        listOf(colors.secondary.copy(alpha = 0.18f), Color.Transparent)
                    )
                )
        )
        content()
    }
}

/** Circular progress ring with a slot in the middle. */
@Composable
fun RingProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 10.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    ringColors: List<Color> = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
    center: @Composable () -> Unit = {}
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val safeProgress = progress.coerceIn(0f, 1f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = min(size.width, size.height) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (safeProgress > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(ringColors + ringColors.first()),
                    startAngle = -90f,
                    sweepAngle = 360f * safeProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        center()
    }
}

/** Generated book cover: no artwork files needed, every book still looks different. */
@Composable
fun BookCoverArt(
    book: Book,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    titleSize: Int = 15
) {
    val subject = book.subject
    val start = Color(subject.colorStart)
    val end = Color(subject.colorEnd)
    val tilt = ((book.coverSeed % 5) - 2) * 6f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(start, end)))
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.28f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .width(9.dp)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .background(Color.Black.copy(alpha = 0.18f))
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = subject.emoji,
                fontSize = (titleSize + 5).sp
            )
            Text(
                text = book.title,
                color = Color.White,
                fontSize = titleSize.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = (titleSize + 5).sp
            )
        }
        if (book.finished) {
            Text(
                text = "🏅",
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            )
        }
        // A faint highlight sweep keeps the flat gradient from looking dull.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .align(Alignment.TopCenter)
                .background(Color.White.copy(alpha = 0.10f + (tilt.coerceAtLeast(0f) / 100f)))
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (trailing != null) trailing()
    }
}

@Composable
fun StatTile(
    emoji: String,
    value: String,
    label: String,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(text = emoji, fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = emoji, fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

private class Particle(
    val startX: Float,
    val driftX: Float,
    val speedY: Float,
    val color: Color,
    val radius: Float,
    val round: Boolean
)

/** Small celebration shower. Increase [trigger] to fire it again. */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return
    val palette = listOf(
        Color(0xFFFFB020), Color(0xFFFF5C7A), Color(0xFF37B6FF),
        Color(0xFF17C99A), Color(0xFFE07BFF), Color(0xFF6C4CF1)
    )
    val particles = remember(trigger) {
        val random = Random(trigger * 7919)
        List(46) {
            Particle(
                startX = random.nextFloat(),
                driftX = (random.nextFloat() - 0.5f) * 0.6f,
                speedY = 0.7f + random.nextFloat() * 0.9f,
                color = palette[random.nextInt(palette.size)],
                radius = 5f + random.nextFloat() * 8f,
                round = random.nextBoolean()
            )
        }
    }
    val animation = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        animation.snapTo(0f)
        animation.animateTo(1f, animationSpec = tween(durationMillis = 2200))
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val time = animation.value
        if (time >= 1f) return@Canvas
        val fade = (1f - time).coerceIn(0f, 1f)
        particles.forEach { particle ->
            val x = (particle.startX + particle.driftX * time) * size.width
            val y = (-0.1f + particle.speedY * time + 0.9f * time * time) * size.height
            val color = particle.color.copy(alpha = fade)
            if (particle.round) {
                drawCircle(color = color, radius = particle.radius, center = Offset(x, y))
            } else {
                drawRect(
                    color = color,
                    topLeft = Offset(x - particle.radius, y - particle.radius),
                    size = Size(particle.radius * 2f, particle.radius * 1.3f)
                )
            }
        }
    }
}
