package com.poodicraft.bookquest.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.AppEvent
import com.poodicraft.bookquest.data.Badges
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.data.Prefs
import com.poodicraft.bookquest.ui.components.AppBackground
import com.poodicraft.bookquest.ui.components.ConfettiBurst
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val BOOK = "book"
    const val READER = "reader"
    const val CARDS = "cards"
    const val QUIZ = "quiz"
}

private data class Celebration(val emoji: String, val title: String, val message: String)

private data class TabItem(val route: String, val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    TabItem(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    TabItem(Routes.LIBRARY, R.string.nav_library, Icons.Rounded.AutoStories),
    TabItem(Routes.STATS, R.string.nav_stats, Icons.Rounded.EmojiEvents),
    TabItem(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings)
)

@Composable
fun BookQuestRoot(
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { LibraryRepository.get(context) }
    val prefs = remember { Prefs(context) }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val books by repository.books.collectAsStateWithLifecycle()
    val profile by repository.profile.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var confettiTrigger by remember { mutableIntStateOf(0) }
    var celebration by remember { mutableStateOf<Celebration?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) repository.importUris(uris)
    }

    val openImporter: () -> Unit = {
        try {
            importLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.import_failed)) }
        }
    }

    LaunchedEffect(Unit) {
        repository.events.collect { event ->
            fun toast(message: String) {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message)
                }
            }
            when (event) {
                is AppEvent.Xp -> toast(context.getString(R.string.xp_earned, event.amount))
                is AppEvent.Imported -> toast(context.getString(R.string.imported_count, event.count))
                is AppEvent.Failed -> toast(context.getString(R.string.import_failed))
                is AppEvent.LevelUp -> {
                    confettiTrigger += 1
                    celebration = Celebration(
                        emoji = "🎉",
                        title = context.getString(R.string.level_up),
                        message = context.getString(R.string.level_up_message, event.level)
                    )
                }
                is AppEvent.BadgeUnlocked -> {
                    val badge = Badges.ALL.firstOrNull { it.id == event.badgeId }
                    if (badge != null) {
                        confettiTrigger += 1
                        celebration = Celebration(
                            emoji = badge.emoji,
                            title = context.getString(badge.titleRes),
                            message = context.getString(badge.descRes)
                        )
                    }
                }
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTab = TABS.any { it.route == currentRoute }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (onTab) {
                    BottomBar(navController = navController, currentRoute = currentRoute)
                }
            },
            floatingActionButton = {
                if (currentRoute == Routes.HOME || currentRoute == Routes.LIBRARY) {
                    ExtendedFloatingActionButton(
                        onClick = openImporter,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.import_books)) }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.HOME
                ) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            books = books,
                            profile = profile,
                            onOpenBook = { id -> navController.navigate("${Routes.BOOK}/$id") },
                            onRead = { id -> navController.navigate("${Routes.READER}/$id") },
                            onImport = openImporter,
                            onSeeLibrary = { navController.navigate(Routes.LIBRARY) }
                        )
                    }
                    composable(Routes.LIBRARY) {
                        LibraryScreen(
                            books = books,
                            onOpenBook = { id -> navController.navigate("${Routes.BOOK}/$id") },
                            onImport = openImporter
                        )
                    }
                    composable(Routes.STATS) {
                        StatsScreen(books = books, profile = profile)
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            language = language,
                            onLanguageChange = onLanguageChange,
                            profile = profile,
                            repository = repository
                        )
                    }
                    composable("${Routes.BOOK}/{bookId}") { entry ->
                        val id = entry.arguments?.getString("bookId")
                        BookDetailScreen(
                            book = books.firstOrNull { it.id == id },
                            repository = repository,
                            onBack = { navController.popBackStack() },
                            onRead = { navController.navigate("${Routes.READER}/$id") },
                            onCards = { navController.navigate("${Routes.CARDS}/$id") },
                            onQuiz = { navController.navigate("${Routes.QUIZ}/$id") }
                        )
                    }
                    composable("${Routes.READER}/{bookId}") { entry ->
                        val id = entry.arguments?.getString("bookId")
                        ReaderScreen(
                            book = books.firstOrNull { it.id == id },
                            repository = repository,
                            prefs = prefs,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("${Routes.CARDS}/{bookId}") { entry ->
                        val id = entry.arguments?.getString("bookId")
                        CardsScreen(
                            book = books.firstOrNull { it.id == id },
                            repository = repository,
                            onBack = { navController.popBackStack() },
                            onQuiz = { navController.navigate("${Routes.QUIZ}/$id") }
                        )
                    }
                    composable("${Routes.QUIZ}/{bookId}") { entry ->
                        val id = entry.arguments?.getString("bookId")
                        QuizScreen(
                            book = books.firstOrNull { it.id == id },
                            repository = repository,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }

                ConfettiBurst(trigger = confettiTrigger)
            }
        }
    }

    val currentCelebration = celebration
    if (currentCelebration != null) {
        AlertDialog(
            onDismissRequest = { celebration = null },
            confirmButton = {
                TextButton(onClick = { celebration = null }) {
                    Text(stringResource(R.string.awesome))
                }
            },
            icon = { Text(text = currentCelebration.emoji, fontSize = 40.sp) },
            title = { Text(currentCelebration.title) },
            text = { Text(currentCelebration.message) }
        )
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        TABS.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
