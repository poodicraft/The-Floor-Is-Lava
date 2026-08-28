package com.poodicraft.bookquest.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.data.Profile
import com.poodicraft.bookquest.ui.components.SectionHeader

private data class LanguageOption(val tag: String, val labelRes: Int, val flag: String)

private val LANGUAGES = listOf(
    LanguageOption("he", R.string.language_hebrew, "🇮🇱"),
    LanguageOption("en", R.string.language_english, "🇬🇧"),
    LanguageOption("ar", R.string.language_arabic, "🇸🇦")
)

@Composable
fun SettingsScreen(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    profile: Profile,
    repository: LibraryRepository
) {
    var goal by remember { mutableFloatStateOf(profile.dailyGoal.toFloat()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item { SectionHeader(title = "🌍 " + stringResource(R.string.language)) }

        items(LANGUAGES.size) { position ->
            val option = LANGUAGES[position]
            val selected = language == option.tag
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!selected) onLanguageChange(option.tag)
                    },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = option.flag, fontSize = 26.sp)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Text(text = "✓", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item { SectionHeader(title = "🎨 " + stringResource(R.string.appearance)) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "system" to R.string.theme_system,
                    "light" to R.string.theme_light,
                    "dark" to R.string.theme_dark
                ).forEach { (key, labelRes) ->
                    FilterChip(
                        selected = themeMode == key,
                        onClick = { onThemeModeChange(key) },
                        label = { Text(stringResource(labelRes)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }

        item { SectionHeader(title = "🎯 " + stringResource(R.string.daily_goal)) }

        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.daily_goal_minutes, goal.toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = goal,
                        onValueChange = { goal = it },
                        onValueChangeFinished = { repository.setDailyGoal(goal.toInt()) },
                        valueRange = 5f..90f,
                        steps = 16
                    )
                }
            }
        }

        item { SectionHeader(title = "ℹ️ " + stringResource(R.string.about)) }

        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.version_label, "1.0"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.supported_formats),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}
