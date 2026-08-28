package com.poodicraft.bookquest.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.Book
import com.poodicraft.bookquest.data.Flashcard
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.ui.components.ConfettiBurst
import com.poodicraft.bookquest.ui.components.EmptyState
import com.poodicraft.bookquest.ui.theme.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    book: Book?,
    repository: LibraryRepository,
    onBack: () -> Unit
) {
    if (book == null || book.cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                emoji = "🧠",
                title = stringResource(R.string.no_cards),
                message = stringResource(R.string.no_cards_hint),
                action = { Button(onClick = onBack) { Text(stringResource(R.string.back)) } }
            )
        }
        return
    }

    var round by remember { mutableIntStateOf(0) }
    val deck: List<Flashcard> = remember(book.id, book.cards.size, round) { book.cards.shuffled() }

    var index by remember(round) { mutableIntStateOf(0) }
    var revealed by remember(round) { mutableStateOf(false) }
    var correct by remember(round) { mutableIntStateOf(0) }
    var finished by remember(round) { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quiz)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (finished) {
                QuizResult(
                    correct = correct,
                    total = deck.size,
                    onPlayAgain = { round += 1 },
                    onBack = onBack
                )
            } else {
                val card = deck[index.coerceIn(0, deck.size - 1)]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.quiz_progress, index + 1, deck.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (index.toFloat() / deck.size).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(Modifier.height(24.dp))

                    FlipCard(
                        front = card.front,
                        back = card.back.ifBlank { "…" },
                        revealed = revealed,
                        onClick = { revealed = !revealed },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )

                    Spacer(Modifier.height(24.dp))

                    if (!revealed) {
                        Button(
                            onClick = { revealed = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(stringResource(R.string.quiz_show_answer))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (index + 1 >= deck.size) {
                                        repository.recordQuiz(correct, deck.size)
                                        finished = true
                                    } else {
                                        index += 1
                                        revealed = false
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(stringResource(R.string.quiz_missed))
                            }
                            Button(
                                onClick = {
                                    val nextCorrect = correct + 1
                                    correct = nextCorrect
                                    if (index + 1 >= deck.size) {
                                        repository.recordQuiz(nextCorrect, deck.size)
                                        finished = true
                                    } else {
                                        index += 1
                                        revealed = false
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Brand.Mint,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(stringResource(R.string.quiz_knew_it))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlipCard(
    front: String,
    back: String,
    revealed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "flip"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    if (rotation <= 90f) listOf(Brand.Violet, Brand.Bubblegum)
                    else listOf(Brand.Mint, Brand.Sky)
                )
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (rotation <= 90f) {
            CardFace(text = front, caption = "❓")
        } else {
            Box(modifier = Modifier.graphicsLayer { rotationY = 180f }) {
                CardFace(text = back, caption = "💡")
            }
        }
    }
}

@Composable
private fun CardFace(text: String, caption: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = caption, fontSize = 30.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 30.sp
        )
    }
}

@Composable
private fun QuizResult(
    correct: Int,
    total: Int,
    onPlayAgain: () -> Unit,
    onBack: () -> Unit
) {
    val perfect = correct == total
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = if (perfect) "🏆" else "👏", fontSize = 72.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.quiz_done),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.quiz_score, correct, total),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onPlayAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.play_again))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.back_to_book))
            }
        }
        if (perfect) {
            ConfettiBurst(trigger = total + 1)
        }
    }
}
