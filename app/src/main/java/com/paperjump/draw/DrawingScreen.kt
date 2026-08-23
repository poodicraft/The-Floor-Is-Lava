package com.paperjump.draw

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paperjump.ui.components.PaperButton
import com.paperjump.ui.components.ScreenHeader
import com.paperjump.ui.theme.SpringGreen
import kotlin.math.max

/**
 * The in-app sketchpad.
 *
 * Draw the level with a finger instead of on paper; the result is rasterised and handed to
 * exactly the same detector a photograph goes through, so the two routes cannot diverge.
 *
 * The sheet takes the aspect ratio of whatever space it is given and stores every point as
 * a fraction of it, which is what lets the drawing be rendered at 1200px for the detector
 * and at any screen size for the preview.
 */
@Composable
fun DrawingScreen(
    controller: DrawingController,
    onPlay: (DrawingState, Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Draw a level",
    subtitle: String = "Ground, lava, coins, a start and a flag",
    actionLabel: String = "Play this level",
    actionEnabled: Boolean = true,
    /** The element checklist only makes sense where the colours mean something. */
    showChecklist: Boolean = true,
    /** Room under the sheet for whatever the caller needs — a note field, a status line. */
    footer: @Composable () -> Unit = {},
) {
    var paperSize by remember { mutableStateOf(IntSize.Zero) }
    val document = controller.document

    // A blank sheet in portrait would be a tall, thin level; landscape suits a platformer,
    // but the sheet always matches the space it is drawn in so what you see is what you get.
    val configuration = LocalConfiguration.current
    val aspect = if (paperSize.height > 0) {
        paperSize.width.toFloat() / paperSize.height.toFloat()
    } else {
        max(0.4f, configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat())
    }

    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            trailing = {
                Row {
                    IconButton(onClick = controller::undo, enabled = document.canUndo) {
                        Icon(Icons.Rounded.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = controller::redo, enabled = document.canRedo) {
                        Icon(Icons.Rounded.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = controller::clear, enabled = !document.isEmpty) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear the sheet")
                    }
                }
            },
        )

        ToolBar(controller = controller, modifier = Modifier.fillMaxWidth())

        NibSizeSlider(controller = controller, modifier = Modifier.fillMaxWidth())

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(18.dp),
                color = PAPER_COLOR,
                shadowElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { paperSize = it }
                        .semantics { contentDescription = "Drawing sheet" }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val width = size.width.toFloat()
                                val height = size.height.toFloat()

                                toPaper(down.position.x, down.position.y, width, height).let {
                                    controller.begin(it.x, it.y)
                                }
                                down.consume()

                                var pressed = true
                                while (pressed) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    toPaper(change.position.x, change.position.y, width, height).let {
                                        controller.extend(it.x, it.y)
                                    }
                                    change.consume()
                                    pressed = change.pressed
                                }
                                controller.end()
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawSheetGrid()
                        controller.visibleStrokes().forEach { drawStroke(it) }
                        controller.previewStroke()?.let { drawStroke(it) }
                    }

                    if (document.isEmpty && controller.livePoints.isEmpty()) {
                        EmptySheetHint(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }

        if (showChecklist) {
            Checklist(
                document = document,
                onPick = { controller.tool = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        footer()

        PaperButton(
            text = actionLabel,
            icon = Icons.Rounded.PlayArrow,
            onClick = { onPlay(document, aspect) },
            enabled = actionEnabled && !document.isEmpty,
            accent = SpringGreen,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** The warm white of the sheet. Always light: it is paper, whatever the app's theme is. */
private val PAPER_COLOR = Color(0xFFFBF7EC)

@Composable
private fun ToolBar(controller: DrawingController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DrawTool.entries.forEach { tool ->
            ToolButton(
                tool = tool,
                selected = controller.tool == tool,
                onClick = { controller.tool = tool },
            )
        }
    }
}

@Composable
private fun ToolButton(tool: DrawTool, selected: Boolean, onClick: () -> Unit) {
    val swatch = Color(StrokeRasterizer.previewColor(tool))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.semantics { contentDescription = "${tool.label}: ${tool.hint}" },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = swatch,
                border = if (tool.isEraser) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                } else {
                    null
                },
                modifier = Modifier.size(26.dp),
            ) {}
            Text(
                text = tool.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun NibSizeSlider(controller: DrawingController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (controller.tool.isEraser) "Eraser size" else "Pen size",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = controller.sizeScale,
            onValueChange = { controller.sizeScale = it },
            valueRange = 0.5f..2.5f,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What the level still needs.
 *
 * The detector copes with anything missing — it will invent a floor and a spawn — but a
 * level with no flag can never be won, and finding that out after playing is annoying.
 */
@Composable
private fun Checklist(
    document: DrawingState,
    onPick: (DrawTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val used = document.toolsUsed
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(DrawTool.INK, DrawTool.SPAWN, DrawTool.GOAL).forEach { tool ->
            val present = tool in used
            Surface(
                onClick = { onPick(tool) },
                shape = RoundedCornerShape(50),
                color = if (present) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (present) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = if (present) "${tool.label} ✓" else "Add ${tool.label.lowercase()}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptySheetHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Draw a line to stand on",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF8A8172),
        )
        Text(
            text = "then a green start, a blue flag,\nand some coins in between",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFA79C89),
        )
    }
}

// ---- painting --------------------------------------------------------------------

/** Faint squares, so the sheet reads as paper and gives a sense of scale while drawing. */
private fun DrawScope.drawSheetGrid() {
    val spacing = size.width / 24f
    val line = Color(0x14000000)
    var x = spacing
    while (x < size.width) {
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += spacing
    }
    var y = spacing
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += spacing
    }
}

/** Draws a stroke exactly as [StrokeRasterizer] will, so the preview cannot lie. */
private fun DrawScope.drawStroke(stroke: Stroke) {
    val color = Color(StrokeRasterizer.colorOf(stroke.tool))
    val width = max(1f, stroke.width * size.width)

    if (stroke.isDot) {
        val point = stroke.points.first()
        drawCircle(
            color = color,
            radius = width / 2f,
            center = Offset(point.x * size.width, point.y * size.height),
        )
        return
    }

    val path = Path()
    val first = stroke.points.first()
    path.moveTo(first.x * size.width, first.y * size.height)
    for (index in 1 until stroke.points.size) {
        val point = stroke.points[index]
        path.lineTo(point.x * size.width, point.y * size.height)
    }
    drawPath(
        path = path,
        color = color,
        style = StrokeStyle(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
