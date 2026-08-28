package com.poodicraft.bookquest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.Book
import com.poodicraft.bookquest.data.Profile
import com.poodicraft.bookquest.ui.components.BookCoverArt
import com.poodicraft.bookquest.ui.components.EmptyState
import com.poodicraft.bookquest.ui.components.RingProgress
import com.poodicraft.bookquest.ui.components.SectionHeader
import com.poodicraft.bookquest.ui.components.StatTile
import com.poodicraft.bookquest.ui.theme.Brand
import java.util.Calendar

@Composable
fun HomeScreen(
    books: List<Book>,
    profile: Profile,
    onOpenBook: (String) -> Unit,
    onRead: (String) -> Unit,
    onImport: () -> Unit,
    onSeeLibrary: () -> Unit
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greetingRes = when {
        hour < 12 -> R.string.greeting_morning
        hour < 18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    val tips = listOf(R.string.tip_1, R.string.tip_2, R.string.tip_3, R.string.tip_4, R.string.tip_5)
    val tip = tips[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % tips.size]

    val inProgress = books.filter { it.started && !it.finished }.sortedByDescending { it.addedAt }
    val recent = books.sortedByDescending { it.addedAt }.take(10)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Text(
                    text = stringResource(greetingRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        item { LevelCard(profile = profile) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    emoji = "🔥",
                    value = profile.streak.toString(),
                    label = stringResource(R.string.streak_days, profile.streak),
                    colors = listOf(Brand.Coral, Brand.Sun),
                    modifier = Modifier.weight(1f)
                )
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
            }
        }

        if (books.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    EmptyState(
                        emoji = "📖",
                        title = stringResource(R.string.no_books_yet),
                        message = stringResource(R.string.no_books_hint),
                        action = {
                            Button(onClick = onImport) {
                                Text(stringResource(R.string.import_books))
                            }
                        }
                    )
                }
            }
        }

        if (inProgress.isNotEmpty()) {
            item { SectionHeader(title = stringResource(R.string.continue_reading)) }
            items(inProgress.take(4), key = { it.id }) { book ->
                ContinueCard(book = book, onOpen = { onOpenBook(book.id) }, onRead = { onRead(book.id) })
            }
        }

        if (recent.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.recently_added),
                    trailing = {
                        TextButton(onClick = onSeeLibrary) {
                            Text(stringResource(R.string.more))
                        }
                    }
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.id }) { book ->
                        Column(
                            modifier = Modifier
                                .width(120.dp)
                                .clickable { onOpenBook(book.id) }
                        ) {
                            BookCoverArt(
                                book = book,
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(168.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "💡 " + stringResource(R.string.tip_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelCard(profile: Profile) {
    val goalProgress = if (profile.dailyGoal <= 0) 0f
    else profile.minutesToday.toFloat() / profile.dailyGoal

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.linearGradient(listOf(Brand.VioletDeep, Brand.Violet, Brand.Bubblegum))
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingProgress(
                progress = profile.levelProgress,
                modifier = Modifier.size(88.dp),
                strokeWidth = 9.dp,
                trackColor = Color.White.copy(alpha = 0.22f),
                ringColors = listOf(Brand.Sun, Color.White, Brand.Mint),
                center = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = profile.level.toString(),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = stringResource(R.string.xp_short),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            )
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.level, profile.level),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.xp_to_next, profile.xpToNextLevel, profile.level + 1),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.minutes_today, profile.minutesToday, profile.dailyGoal),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { goalProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = Brand.Sun,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(book: Book, onOpen: () -> Unit, onRead: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCoverArt(
                book = book,
                modifier = Modifier
                    .width(64.dp)
                    .height(92.dp),
                corner = 16.dp,
                titleSize = 11
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.progress_pct, (book.progress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onRead, shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.read_now))
            }
        }
    }
}
