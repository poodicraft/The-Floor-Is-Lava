package com.poodicraft.bookquest.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.Badges
import com.poodicraft.bookquest.data.Book
import com.poodicraft.bookquest.data.Profile
import com.poodicraft.bookquest.ui.components.RingProgress
import com.poodicraft.bookquest.ui.components.SectionHeader
import com.poodicraft.bookquest.ui.components.StatTile
import com.poodicraft.bookquest.ui.theme.Brand

@Composable
fun StatsScreen(books: List<Book>, profile: Profile) {
    val cards = books.sumOf { it.cards.size }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.stats_title),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(Brand.Violet, Brand.Sky)))
                    .padding(22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingProgress(
                        progress = profile.levelProgress,
                        modifier = Modifier.size(100.dp),
                        strokeWidth = 10.dp,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        ringColors = listOf(Brand.Sun, Color.White),
                        center = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = profile.level.toString(),
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    )
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.level, profile.level),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = stringResource(R.string.xp_amount, profile.xp),
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.xp_to_next,
                                profile.xpToNextLevel,
                                profile.level + 1
                            ),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    emoji = "⏱️",
                    value = profile.totalMinutes.toString(),
                    label = stringResource(R.string.minutes_read_total),
                    colors = listOf(Brand.Sky, Brand.Mint),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    emoji = "🔥",
                    value = profile.bestStreak.toString(),
                    label = stringResource(R.string.streak_days, profile.bestStreak),
                    colors = listOf(Brand.Coral, Brand.Sun),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    emoji = "📚",
                    value = books.size.toString(),
                    label = stringResource(R.string.books_in_library),
                    colors = listOf(Brand.Violet, Brand.Bubblegum),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    emoji = "🏁",
                    value = books.count { it.finished }.toString(),
                    label = stringResource(R.string.books_finished),
                    colors = listOf(Brand.Mint, Brand.Sky),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    emoji = "🃏",
                    value = cards.toString(),
                    label = stringResource(R.string.cards_created),
                    colors = listOf(Brand.Sun, Brand.Coral),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            SectionHeader(
                title = stringResource(R.string.badges),
                trailing = {
                    Text(
                        text = stringResource(
                            R.string.badges_unlocked,
                            profile.badges.size,
                            Badges.ALL.size
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        items(Badges.ALL.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { badge ->
                    val unlocked = profile.badges.contains(badge.id)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .alpha(if (unlocked) 1f else 0.5f),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (unlocked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = if (unlocked) badge.emoji else "🔒", fontSize = 26.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(badge.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(badge.descRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
