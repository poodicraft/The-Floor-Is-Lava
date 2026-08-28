package com.poodicraft.bookquest.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poodicraft.bookquest.BuildConfig
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.AccountState
import com.poodicraft.bookquest.data.CloudSync
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.data.Profile
import com.poodicraft.bookquest.data.SyncState
import com.poodicraft.bookquest.ui.components.SectionHeader
import com.poodicraft.bookquest.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        item { SectionHeader(title = "☁️ " + stringResource(R.string.account)) }

        item { AccountCard() }

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
                        text = stringResource(
                            R.string.version_label,
                            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                        ),
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

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun AccountCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudSync.get(context) }
    val account by cloud.account.collectAsStateWithLifecycle()
    val sync by cloud.sync.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var signInFailed by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            when (val state = account) {
                is AccountState.NotConfigured -> {
                    Text(
                        text = "🔒 " + stringResource(R.string.cloud_not_configured),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.cloud_not_configured_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is AccountState.SignedOut -> {
                    Text(
                        text = stringResource(R.string.account_signed_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.account_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (signInFailed) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.sign_in_failed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val activity = context.findActivity() ?: return@Button
                            signInFailed = false
                            busy = true
                            scope.launch {
                                val result = cloud.signIn(activity)
                                signInFailed = result.isFailure
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("G  " + stringResource(R.string.sign_in_google))
                        }
                    }
                }

                is AccountState.SignedIn -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(23.dp))
                                .background(
                                    Brush.linearGradient(listOf(Brand.Violet, Brand.Sky))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.name.take(1).uppercase().ifBlank { "?" },
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.padding(horizontal = 7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.name.ifBlank { state.email },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.email,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when (val current = sync) {
                            is SyncState.Working -> stringResource(R.string.syncing)
                            is SyncState.Done -> stringResource(
                                R.string.sync_done,
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(current.at))
                            )
                            is SyncState.Failed -> stringResource(R.string.sync_failed, current.reason)
                            else -> stringResource(R.string.account_hint)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { cloud.signOut() },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.sign_out))
                        }
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    cloud.syncNow()
                                    busy = false
                                }
                            },
                            enabled = !busy && sync !is SyncState.Working,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.sync_now))
                        }
                    }
                }
            }
        }
    }
}
