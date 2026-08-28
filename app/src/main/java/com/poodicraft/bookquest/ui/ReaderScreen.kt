package com.poodicraft.bookquest.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.poodicraft.bookquest.R
import com.poodicraft.bookquest.data.Book
import com.poodicraft.bookquest.data.BookFormat
import com.poodicraft.bookquest.data.LibraryRepository
import com.poodicraft.bookquest.data.Prefs
import com.poodicraft.bookquest.reader.EpubParser
import com.poodicraft.bookquest.reader.PdfBook
import com.poodicraft.bookquest.reader.TextLoader
import com.poodicraft.bookquest.ui.components.ConfettiBurst
import com.poodicraft.bookquest.ui.components.EmptyState
import com.poodicraft.bookquest.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PageStyle(
    val background: Color,
    val text: Color,
    val soft: Color,
    val accent: Color
)

private fun styleFor(name: String): PageStyle = when (name) {
    "sepia" -> PageStyle(Color(0xFFF6E7C6), Color(0xFF4A3A22), Color(0xFF8A7550), Color(0xFFC77B2B))
    "dark" -> PageStyle(Color(0xFF14121F), Color(0xFFE9E4F8), Color(0xFF9C93BE), Color(0xFF9C7BFF))
    else -> PageStyle(Color(0xFFFDFBF7), Color(0xFF1E1A2E), Color(0xFF6C6488), Color(0xFF6C4CF1))
}

/** Reading time is paid at three XP a minute; the chip shows it ticking up live. */
private const val XP_PER_MINUTE = 3

private val TOP_INSET = 96.dp
private val BOTTOM_INSET = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book?,
    repository: LibraryRepository,
    prefs: Prefs,
    onBack: () -> Unit
) {
    if (book == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                emoji = "📄",
                title = stringResource(R.string.reader_error),
                message = stringResource(R.string.supported_formats),
                action = { Button(onClick = onBack) { Text(stringResource(R.string.back)) } }
            )
        }
        return
    }

    var fontSize by remember { mutableFloatStateOf(prefs.readerFontSize) }
    var themeName by remember { mutableStateOf(prefs.readerTheme) }
    var showSettings by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState()
    val style = remember(themeName) { styleFor(themeName) }
    val scope = rememberCoroutineScope()

    // Progress and reading time are written back when the reader closes.
    val progressState = remember { mutableFloatStateOf(book.progress) }
    val pageState = remember { mutableStateOf(book.lastPage) }
    val startedAt = remember { SystemClock.elapsedRealtime() }
    val bookId = book.id

    DisposableEffect(bookId) {
        onDispose {
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L).toInt()
            repository.saveProgress(bookId, progressState.floatValue, pageState.value, 0)
            repository.recordReading(bookId, seconds)
        }
    }

    // A live session counter makes the earned XP visible while you read. It is
    // polled rather than ticked so the screen only recomposes when it changes.
    var sessionMinutes by remember { mutableIntStateOf(0) }
    LaunchedEffect(bookId) {
        while (true) {
            delay(15_000L)
            sessionMinutes = ((SystemClock.elapsedRealtime() - startedAt) / 60_000L).toInt()
        }
    }

    // Celebrate every quarter of the book, but not for ground already covered.
    var milestone by remember { mutableIntStateOf((book.progress * 4).toInt().coerceIn(0, 4)) }
    var milestoneText by remember { mutableStateOf<Int?>(null) }
    var confetti by remember { mutableIntStateOf(0) }
    val reached = (progressState.floatValue * 4).toInt().coerceIn(0, 4)
    LaunchedEffect(reached) {
        if (reached > milestone && reached > 0) {
            milestone = reached
            milestoneText = when (reached) {
                1 -> R.string.milestone_quarter
                2 -> R.string.milestone_half
                3 -> R.string.milestone_three_quarters
                else -> R.string.milestone_end
            }
            confetti += 1
            delay(2800L)
            milestoneText = null
        }
    }

    val file = remember(bookId) { repository.bookFile(book) }
    val listState = rememberLazyListState()
    val webView = remember { mutableStateOf<WebView?>(null) }
    val itemCount = remember { mutableIntStateOf(0) }
    val isWebFormat = book.format == BookFormat.EPUB || book.format == BookFormat.HTML

    val seek: (Float) -> Unit = { fraction ->
        scope.launch {
            if (isWebFormat) {
                webView.value?.let { view ->
                    val height = view.contentHeight * view.scale
                    view.scrollTo(0, (height * fraction).toInt())
                }
            } else if (itemCount.intValue > 1) {
                listState.scrollToItem(((itemCount.intValue - 1) * fraction).toInt())
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bookId) {
                    detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                }
        ) {
            when (book.format) {
                BookFormat.PDF -> PdfReader(
                    file = file,
                    style = style,
                    startPage = book.lastPage,
                    listState = listState,
                    itemCount = itemCount,
                    onProgress = { fraction, page ->
                        progressState.floatValue = fraction
                        pageState.value = page
                    }
                )

                BookFormat.EPUB, BookFormat.HTML -> WebReader(
                    isEpub = book.format == BookFormat.EPUB,
                    file = file,
                    style = style,
                    fontSize = fontSize,
                    startProgress = book.progress,
                    webViewRef = webView,
                    onProgress = { fraction -> progressState.floatValue = fraction }
                )

                else -> PlainTextReader(
                    file = file,
                    style = style,
                    fontSize = fontSize,
                    startIndex = book.lastPage,
                    listState = listState,
                    itemCount = itemCount,
                    onProgress = { fraction, index ->
                        progressState.floatValue = fraction
                        pageState.value = index
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                book = book,
                style = style,
                progress = progressState.floatValue,
                sessionMinutes = sessionMinutes,
                onBack = onBack,
                onSettings = { showSettings = true }
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                style = style,
                progress = progressState.floatValue,
                showFinish = progressState.floatValue >= 0.97f && !book.finished,
                onSeek = seek,
                onFinish = {
                    repository.setFinished(bookId, true)
                    confetti += 1
                }
            )
        }

        val banner = milestoneText
        AnimatedVisibility(
            visible = banner != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 110.dp)
        ) {
            if (banner != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Brand.Violet, Brand.Coral)))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "🎉  " + stringResource(banner),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        ConfettiBurst(trigger = confetti)
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 36.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_settings),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.font_size) + "  " + fontSize.toInt(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = { prefs.readerFontSize = fontSize },
                    valueRange = 13f..34f,
                    steps = 20
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.reader_theme),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "light" to R.string.reader_theme_light,
                        "sepia" to R.string.reader_theme_sepia,
                        "dark" to R.string.reader_theme_dark
                    ).forEach { (key, labelRes) ->
                        val preview = styleFor(key)
                        FilterChip(
                            selected = themeName == key,
                            onClick = {
                                themeName = key
                                prefs.readerTheme = key
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(preview.background)
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(stringResource(labelRes))
                                }
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "👆 " + stringResource(R.string.tap_to_hide),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ------------------------------------------------------------------- chrome

@Composable
private fun ReaderTopBar(
    book: Book,
    style: PageStyle,
    progress: Float,
    sessionMinutes: Int,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "progress"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(style.background, style.background.copy(alpha = 0.94f))
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = style.text
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.subject.emoji + "  " + book.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = style.text,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.progress_pct, (progress * 100).toInt()),
                    color = style.soft,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            SessionChip(style = style, minutes = sessionMinutes)
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.reader_settings),
                    tint = style.text
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(style.soft.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Brand.Violet, Brand.Coral, Brand.Sun))
                    )
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SessionChip(style: PageStyle, minutes: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(style.accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "⏱", fontSize = 13.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = minutes.toString(),
            color = style.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "⚡", fontSize = 13.sp)
        Spacer(Modifier.width(3.dp))
        Text(
            text = (minutes * XP_PER_MINUTE).toString(),
            color = style.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ReaderBottomBar(
    style: PageStyle,
    progress: Float,
    showFinish: Boolean,
    onSeek: (Float) -> Unit,
    onFinish: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(progress) }
    val shown = if (dragging) dragValue else progress.coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(style.background.copy(alpha = 0.94f), style.background)
                )
            )
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        if (showFinish) {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("🏁  " + stringResource(R.string.finish_book_now))
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.jump_to),
                color = style.soft,
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = shown,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragValue)
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            Text(
                text = (shown * 100).toInt().toString() + "%",
                color = style.text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ------------------------------------------------------------------ plain text

@Composable
private fun PlainTextReader(
    file: java.io.File,
    style: PageStyle,
    fontSize: Float,
    startIndex: Int,
    listState: LazyListState,
    itemCount: MutableState<Int>,
    onProgress: (Float, Int) -> Unit
) {
    val lines by produceState<List<String>?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            val text = TextLoader.read(file)
            if (text.isBlank()) emptyList() else text.split("\n")
        }
    }

    val content = lines
    if (content == null) {
        LoadingBox(style)
        return
    }
    if (content.isEmpty()) {
        ErrorBox(style)
        return
    }

    LaunchedEffect(file.path, content.size) {
        itemCount.value = content.size
        val target = startIndex.coerceIn(0, (content.size - 1).coerceAtLeast(0))
        if (target > 0) listState.scrollToItem(target)
    }
    LaunchedEffect(listState, content.size) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val fraction = if (content.size <= 1) 1f else index.toFloat() / (content.size - 1)
            onProgress(fraction.coerceIn(0f, 1f), index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
        contentPadding = PaddingValues(
            start = 24.dp,
            end = 24.dp,
            top = TOP_INSET,
            bottom = BOTTOM_INSET
        )
    ) {
        items(content.size) { index ->
            val line = content[index]
            if (line.isBlank()) {
                Spacer(Modifier.height((fontSize * 0.7f).dp))
            } else if (index == 0) {
                Text(
                    text = line,
                    color = style.accent,
                    fontSize = (fontSize * 1.45f).sp,
                    lineHeight = (fontSize * 1.9f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            } else {
                Text(
                    text = line,
                    color = style.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.7f).sp,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(20.dp))
                Text(text = "🌟", fontSize = 30.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.milestone_end),
                    color = style.soft,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ------------------------------------------------------------------- epub/html

@Composable
private fun WebReader(
    isEpub: Boolean,
    file: java.io.File,
    style: PageStyle,
    fontSize: Float,
    startProgress: Float,
    webViewRef: MutableState<WebView?>,
    onProgress: (Float) -> Unit
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val body by produceState<String?>(initialValue = null, file.path, isEpub) {
        value = withContext(Dispatchers.IO) {
            if (isEpub) {
                EpubParser.parse(file)?.html ?: ""
            } else {
                EpubParser.cleanHtml(TextLoader.read(file))
            }
        }
    }

    val loaded = body
    if (loaded == null) {
        LoadingBox(style)
        return
    }
    if (loaded.isBlank()) {
        ErrorBox(style)
        return
    }

    val documentKey = file.path + "|" + loaded.length + "|" + fontSize.toInt() + "|" + style.background.value + "|" + rtl
    val document = remember(documentKey) { buildHtml(loaded, fontSize, style, rtl) }
    val lastLoaded = remember { mutableStateOf("") }
    val restored = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { webViewRef.value = null }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(style.background.toArgb())
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val target = view ?: return
                        if (!restored.value && startProgress > 0.001f) {
                            restored.value = true
                            target.postDelayed({
                                try {
                                    val height = target.contentHeight * target.scale
                                    target.scrollTo(0, (height * startProgress).toInt())
                                } catch (e: Exception) {
                                    // Reading simply starts from the top.
                                }
                            }, 260L)
                        }
                    }
                }
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    try {
                        val total = contentHeight * scale - height
                        if (total > 0f) onProgress((scrollY / total).coerceIn(0f, 1f))
                    } catch (e: Exception) {
                        // Progress stays where it was.
                    }
                }
                webViewRef.value = this
            }
        },
        update = { web ->
            web.setBackgroundColor(style.background.toArgb())
            webViewRef.value = web
            if (lastLoaded.value != documentKey) {
                lastLoaded.value = documentKey
                web.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        }
    )
}

private fun buildHtml(body: String, fontSize: Float, style: PageStyle, rtl: Boolean): String {
    fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
    val direction = if (rtl) "rtl" else "ltr"
    return """
        <html dir="$direction">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          body {
            background: ${hex(style.background)};
            color: ${hex(style.text)};
            font-size: ${fontSize.toInt()}px;
            line-height: 1.75;
            padding: 104px 22px 104px 22px;
            margin: 0;
            font-family: sans-serif;
            word-wrap: break-word;
          }
          h1, h2, h3 { line-height: 1.3; color: ${hex(style.accent)}; }
          a { color: ${hex(style.accent)}; }
          img, svg, video { display: none; }
          hr.chapter-break {
            border: none;
            border-top: 2px dashed ${hex(style.soft)};
            margin: 36px 0;
          }
          p { margin: 0 0 1em 0; }
          blockquote {
            margin: 1em 0;
            padding: 0 1em;
            border-inline-start: 4px solid ${hex(style.accent)};
            opacity: 0.9;
          }
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

// ------------------------------------------------------------------------ pdf

@Composable
private fun PdfReader(
    file: java.io.File,
    style: PageStyle,
    startPage: Int,
    listState: LazyListState,
    itemCount: MutableState<Int>,
    onProgress: (Float, Int) -> Unit
) {
    val document by produceState<PdfBook?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) { PdfBook.open(file) }
    }
    val pdf = document

    DisposableEffect(pdf) {
        onDispose { pdf?.close() }
    }

    if (pdf == null) {
        LoadingBox(style)
        return
    }
    val pageCount = pdf.pageCount
    if (pageCount <= 0) {
        ErrorBox(style)
        return
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidth = remember(configuration.screenWidthDp) {
        with(density) { (configuration.screenWidthDp.dp.toPx()).toInt() }
    }

    LaunchedEffect(pageCount) {
        itemCount.value = pageCount
        val target = startPage.coerceIn(0, pageCount - 1)
        if (target > 0) listState.scrollToItem(target)
    }
    LaunchedEffect(listState, pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val fraction = if (pageCount <= 1) 1f else index.toFloat() / (pageCount - 1)
            onProgress(fraction.coerceIn(0f, 1f), index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
        contentPadding = PaddingValues(top = TOP_INSET, bottom = BOTTOM_INSET),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(pageCount) { index ->
            PdfPageView(
                pdf = pdf,
                index = index,
                pageCount = pageCount,
                widthPx = targetWidth,
                style = style
            )
        }
    }
}

@Composable
private fun PdfPageView(
    pdf: PdfBook,
    index: Int,
    pageCount: Int,
    widthPx: Int,
    style: PageStyle
) {
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        bitmap = withContext(Dispatchers.IO) { pdf.renderPage(index, widthPx) }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val image = bitmap
        if (image == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = style.accent)
            }
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.FillWidth
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.page_of, index + 1, pageCount),
            color = style.soft,
            fontSize = 12.sp
        )
    }
}

// --------------------------------------------------------------------- shared

@Composable
private fun LoadingBox(style: PageStyle) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "📖", fontSize = 44.sp)
            Spacer(Modifier.height(14.dp))
            CircularProgressIndicator(color = style.accent)
            Spacer(Modifier.height(14.dp))
            Text(text = stringResource(R.string.loading), color = style.soft)
        }
    }
}

@Composable
private fun ErrorBox(style: PageStyle) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(style.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(30.dp)
        ) {
            Text(text = "😕", fontSize = 46.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.reader_error),
                color = style.text,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.supported_formats),
                color = style.soft,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
