package com.poodicraft.bookquest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.Book
import com.poodicraft.bookquest.data.Subject
import com.poodicraft.bookquest.ui.components.BookCoverArt
import com.poodicraft.bookquest.ui.components.EmptyState

private enum class SortMode { RECENT, TITLE, PROGRESS }

@Composable
fun LibraryScreen(
    books: List<Book>,
    onOpenBook: (String) -> Unit,
    onImport: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var subjectFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var favouritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }

    val usedSubjects = remember(books) {
        Subject.values().filter { subject -> books.any { it.subjectId == subject.id } }
    }

    val visible = books
        .filter { book ->
            val matchesQuery = query.isBlank() ||
                book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true)
            val matchesSubject = subjectFilter == null || book.subjectId == subjectFilter
            val matchesFavourite = !favouritesOnly || book.favorite
            matchesQuery && matchesSubject && matchesFavourite
        }
        .let { list ->
            when (sortMode) {
                SortMode.RECENT -> list.sortedByDescending { it.addedAt }
                SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                SortMode.PROGRESS -> list.sortedByDescending { it.progress }
            }
        }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    text = stringResource(R.string.nav_library),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.books_count, books.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_books)) }
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = subjectFilter == null && !favouritesOnly,
                        onClick = {
                            subjectFilter = null
                            favouritesOnly = false
                        },
                        label = { Text(stringResource(R.string.all_subjects)) },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
                item {
                    FilterChip(
                        selected = favouritesOnly,
                        onClick = { favouritesOnly = !favouritesOnly },
                        label = { Text("⭐ " + stringResource(R.string.favorites)) },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
                items(usedSubjects, key = { it.id }) { subject ->
                    FilterChip(
                        selected = subjectFilter == subject.id,
                        onClick = {
                            subjectFilter = if (subjectFilter == subject.id) null else subject.id
                        },
                        label = { Text(subject.emoji + " " + stringResource(subject.labelRes)) },
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip(stringResource(R.string.sort_recent), sortMode == SortMode.RECENT) {
                    sortMode = SortMode.RECENT
                }
                SortChip(stringResource(R.string.sort_title), sortMode == SortMode.TITLE) {
                    sortMode = SortMode.TITLE
                }
                SortChip(stringResource(R.string.sort_progress), sortMode == SortMode.PROGRESS) {
                    sortMode = SortMode.PROGRESS
                }
            }
        }

        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (books.isEmpty()) {
                    EmptyState(
                        emoji = "📚",
                        title = stringResource(R.string.no_books_yet),
                        message = stringResource(R.string.no_books_hint),
                        action = {
                            Button(onClick = onImport) {
                                Text(stringResource(R.string.import_books))
                            }
                        }
                    )
                } else {
                    EmptyState(
                        emoji = "🔍",
                        title = stringResource(R.string.no_results),
                        message = stringResource(R.string.supported_formats)
                    )
                }
            }
        }

        items(visible, key = { it.id }) { book ->
            BookGridCard(book = book, onClick = { onOpenBook(book.id) })
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun BookGridCard(book: Book, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable { onClick() }) {
        BookCoverArt(
            book = book,
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (book.author.isBlank()) stringResource(book.subject.labelRes) else book.author,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (book.progress > 0f) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { book.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}
