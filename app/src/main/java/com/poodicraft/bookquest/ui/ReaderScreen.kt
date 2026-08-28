package com.poodicraft.bookquest.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.poodicraft.bookquest.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class PageStyle(val background: Color, val text: Color, val soft: Color)

private fun styleFor(name: String): PageStyle = when (name) {
    "sepia" -> PageStyle(Color(0xFFF6E7C6), Color(0xFF4A3A22), Color(0xFF8A7550))
    "dark" -> PageStyle(Color(0xFF14121F), Color(0xFFE9E4F8), Color(0xFF9C93BE))
    else -> PageStyle(Color(0xFFFDFBF7), Color(0xFF1E1A2E), Color(0xFF6C6488))
}

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

    val context = LocalContext.current
    var fontSize by remember { mutableFloatStateOf(prefs.readerFontSize) }
    var themeName by remember { mutableStateOf(prefs.readerTheme) }
    var showSettings by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val style = styleFor(themeName)

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

    val file = remember(bookId) { repository.bookFile(book) }

    androidx.compose.material3.Scaffold(
        containerColor = style.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = book.title,
                            maxLines = 1,
                            color = style.text,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(
                                R.string.progress_pct,
                                (progressState.floatValue * 100).toInt()
                            ),
                            color = style.soft,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = style.text
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.reader_settings),
                            tint = style.text
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = style.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(style.background)
        ) {
            LinearProgressIndicator(
                progress = { progressState.floatValue.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = style.background
            )
            when (book.format) {
                BookFormat.PDF -> PdfReader(
                    file = file,
                    style = style,
                    startPage = book.lastPage,
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
                    onProgress = { fraction -> progressState.floatValue = fraction }
                )
                else -> PlainTextReader(
                    file = file,
                    style = style,
                    fontSize = fontSize,
                    startIndex = book.lastPage,
                    onProgress = { fraction, index ->
                        progressState.floatValue = fraction
                        pageState.value = index
                    }
                )
            }
        }
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
                    onValueChange = {
                        fontSize = it
                        prefs.readerFontSize = it
                    },
                    valueRange = 13f..34f,
                    steps = 20
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.reader_theme),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "light" to R.string.reader_theme_light,
                        "sepia" to R.string.reader_theme_sepia,
                        "dark" to R.string.reader_theme_dark
                    ).forEach { (key, labelRes) ->
                        FilterChip(
                            selected = themeName == key,
                            onClick = {
                                themeName = key
                                prefs.readerTheme = key
                            },
                            label = { Text(stringResource(labelRes)) },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }
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

    val listState = rememberLazyListState()
    LaunchedEffect(file.path, content.size) {
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
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)
    ) {
        items(content.size) { index ->
            val line = content[index]
            if (line.isBlank()) {
                Spacer(Modifier.height((fontSize * 0.7f).dp))
            } else {
                Text(
                    text = line,
                    color = style.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.65f).sp,
                    fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
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
            }
        },
        update = { web ->
            web.setBackgroundColor(style.background.toArgb())
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
            line-height: 1.7;
            padding: 18px 20px 60px 20px;
            margin: 0;
            font-family: sans-serif;
            word-wrap: break-word;
          }
          h1, h2, h3 { line-height: 1.3; }
          a { color: ${hex(style.text)}; }
          img, svg, video { display: none; }
          hr.chapter-break { border: none; border-top: 2px dashed ${hex(style.soft)}; margin: 34px 0; }
          p { margin: 0 0 1em 0; }
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

    val listState = rememberLazyListState()
    LaunchedEffect(pageCount) {
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
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
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
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
