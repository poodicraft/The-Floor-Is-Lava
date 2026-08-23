package com.paperjump.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.paperjump.ui.theme.CoinGold
import com.paperjump.ui.theme.CreatureViolet
import com.paperjump.ui.theme.InkSoft
import com.paperjump.ui.theme.LavaRed
import com.paperjump.ui.theme.SkyBlue
import com.paperjump.ui.theme.SpringGreen

/** What each colour on the paper turns into. Shown on the capture and tuning screens. */
private val LEGEND = listOf(
    LegendEntry("Black lines", "Platforms", InkSoft),
    LegendEntry("Green dot", "Start", SpringGreen),
    LegendEntry("Red", "Lava", LavaRed),
    LegendEntry("Yellow", "Coins", CoinGold),
    LegendEntry("Blue", "Goal", SkyBlue),
    LegendEntry("Purple", "Creatures", CreatureViolet),
)

private data class LegendEntry(val ink: String, val meaning: String, val color: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SketchLegend(modifier: Modifier = Modifier, compact: Boolean = false) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LEGEND.forEach { entry ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(entry.color, CircleShape),
                    )
                    Text(
                        text = if (compact) entry.meaning else "${entry.ink} → ${entry.meaning}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
