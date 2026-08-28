package com.poodicraft.bookquest.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Something worth celebrating on screen. */
sealed class AppEvent {
    data class Xp(val amount: Int) : AppEvent()
    data class LevelUp(val level: Int) : AppEvent()
    data class BadgeUnlocked(val badgeId: String) : AppEvent()
    data class Imported(val count: Int) : AppEvent()
    data class Failed(val reason: String) : AppEvent()
}

/**
 * Single source of truth for the library. State lives in memory and is mirrored
 * to a small JSON file, which keeps the app dependency free and quick to start.
 */
class LibraryRepository private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _profile = MutableStateFlow(Profile())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    private val dataFile: File get() = File(appContext.filesDir, "library.json")
    private val booksDir: File
        get() = File(appContext.filesDir, "books").apply { if (!exists()) mkdirs() }

    fun bookFile(book: Book): File = File(booksDir, book.fileName)

    fun bookById(id: String?): Book? = _books.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- loading

    private fun load() {
        try {
            if (!dataFile.exists()) {
                rollDay()
                return
            }
            val root = JSONObject(dataFile.readText())
            _books.value = root.optJSONArray("books")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { bookFromJson(it) }
                }
            } ?: emptyList()
            root.optJSONObject("profile")?.let { _profile.value = profileFromJson(it) }
            rollDay()
        } catch (e: Exception) {
            _books.value = emptyList()
            _profile.value = Profile()
        }
    }

    private fun persist() {
        scope.launch {
            try {
                val root = JSONObject()
                val array = JSONArray()
                _books.value.forEach { array.put(bookToJson(it)) }
                root.put("books", array)
                root.put("profile", profileToJson(_profile.value))
                dataFile.writeText(root.toString())
            } catch (e: Exception) {
                // Losing a save is not worth crashing the app over.
            }
        }
    }

    private fun bookToJson(book: Book): JSONObject = JSONObject().apply {
        put("id", book.id)
        put("title", book.title)
        put("author", book.author)
        put("subject", book.subjectId)
        put("format", book.format.id)
        put("file", book.fileName)
        put("addedAt", book.addedAt)
        put("coverSeed", book.coverSeed)
        put("progress", book.progress.toDouble())
        put("lastPage", book.lastPage)
        put("lastOffset", book.lastOffset)
        put("minutesRead", book.minutesRead)
        put("favorite", book.favorite)
        put("finished", book.finished)
        put("cards", JSONArray().also { arr ->
            book.cards.forEach { card ->
                arr.put(JSONObject().apply {
                    put("id", card.id)
                    put("front", card.front)
                    put("back", card.back)
                })
            }
        })
    }

    private fun bookFromJson(json: JSONObject): Book? {
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val cardsArray = json.optJSONArray("cards")
        val cards = if (cardsArray == null) emptyList() else
            (0 until cardsArray.length()).mapNotNull { i ->
                cardsArray.optJSONObject(i)?.let {
                    Flashcard(
                        id = it.optString("id", UUID.randomUUID().toString()),
                        front = it.optString("front"),
                        back = it.optString("back")
                    )
                }
            }
        return Book(
            id = id,
            title = json.optString("title", "?"),
            author = json.optString("author", ""),
            subjectId = json.optString("subject", Subject.GENERAL.id),
            format = BookFormat.fromId(json.optString("format", "unknown")),
            fileName = json.optString("file", ""),
            addedAt = json.optLong("addedAt", 0L),
            coverSeed = json.optInt("coverSeed", 0),
            progress = json.optDouble("progress", 0.0).toFloat(),
            lastPage = json.optInt("lastPage", 0),
            lastOffset = json.optInt("lastOffset", 0),
            minutesRead = json.optInt("minutesRead", 0),
            favorite = json.optBoolean("favorite", false),
            finished = json.optBoolean("finished", false),
            cards = cards
        )
    }

    private fun profileToJson(profile: Profile): JSONObject = JSONObject().apply {
        put("xp", profile.xp)
        put("streak", profile.streak)
        put("bestStreak", profile.bestStreak)
        put("lastReadDay", profile.lastReadDay)
        put("today", profile.today)
        put("minutesToday", profile.minutesToday)
        put("totalMinutes", profile.totalMinutes)
        put("booksFinished", profile.booksFinished)
        put("dailyGoal", profile.dailyGoal)
        put("perfectQuizzes", profile.perfectQuizzes)
        put("languages", JSONArray(profile.languagesTried.toList()))
        put("badges", JSONArray(profile.badges.toList()))
    }

    private fun profileFromJson(json: JSONObject): Profile {
        fun stringSet(name: String): Set<String> {
            val array = json.optJSONArray(name) ?: return emptySet()
            return (0 until array.length()).map { array.optString(it) }
                .filter { it.isNotEmpty() }.toSet()
        }
        return Profile(
            xp = json.optInt("xp", 0),
            streak = json.optInt("streak", 0),
            bestStreak = json.optInt("bestStreak", 0),
            lastReadDay = json.optString("lastReadDay", ""),
            today = json.optString("today", ""),
            minutesToday = json.optInt("minutesToday", 0),
            totalMinutes = json.optInt("totalMinutes", 0),
            booksFinished = json.optInt("booksFinished", 0),
            dailyGoal = json.optInt("dailyGoal", 20),
            perfectQuizzes = json.optInt("perfectQuizzes", 0),
            languagesTried = stringSet("languages"),
            badges = stringSet("badges")
        )
    }

    // ------------------------------------------------------------- importing

    fun importUris(uris: List<Uri>) {
        scope.launch {
            var added = 0
            for (uri in uris) {
                val book = importSingle(uri)
                if (book != null) added++
            }
            if (added > 0) {
                _events.emit(AppEvent.Imported(added))
                awardXp(added * 5)
                refreshBadges()
                persist()
            } else {
                _events.emit(AppEvent.Failed("import"))
            }
        }
    }

    private suspend fun importSingle(uri: Uri): Book? = withContext(Dispatchers.IO) {
        try {
            val display = displayName(uri)
            val extension = display.substringAfterLast('.', "").lowercase()
            val format = BookFormat.fromExtension(extension)
            if (format == BookFormat.UNKNOWN) return@withContext null

            val id = UUID.randomUUID().toString()
            val target = File(booksDir, "$id.${format.id}")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            val title = prettyTitle(display)
            val book = Book(
                id = id,
                title = title,
                author = "",
                subjectId = guessSubject(title).id,
                format = format,
                fileName = target.name,
                addedAt = System.currentTimeMillis(),
                coverSeed = title.hashCode()
            )
            _books.value = listOf(book) + _books.value
            book
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            name = null
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name ?: "book.txt"
    }

    private fun prettyTitle(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.', fileName)
        return withoutExtension.replace('_', ' ').replace('-', ' ').trim()
            .ifEmpty { fileName }
    }

    private fun guessSubject(title: String): Subject {
        val lower = title.lowercase()
        val table = listOf(
            Subject.MATH to listOf("math", "algebra", "geometry", "מתמט", "חשבון", "הנדסה", "رياض", "جبر"),
            Subject.SCIENCE to listOf("science", "biology", "physics", "chemistry", "מדע", "ביולוג", "פיזיק", "כימ", "علوم", "فيزياء", "كيمياء", "أحياء"),
            Subject.HISTORY to listOf("history", "היסטור", "تاريخ"),
            Subject.LITERATURE to listOf("story", "novel", "poem", "ספרות", "סיפור", "שיר", "أدب", "قصة", "شعر"),
            Subject.LANGUAGE to listOf("english", "grammar", "arabic", "hebrew", "אנגלית", "עברית", "ערבית", "דקדוק", "لغة", "عربي", "انجليزي", "قواعد"),
            Subject.ART to listOf("art", "music", "אמנות", "מוזיק", "فن", "موسيقى"),
            Subject.TECH to listOf("computer", "code", "tech", "מחשב", "תכנות", "טכנולוג", "حاسوب", "برمجة", "تكنولوجيا"),
            Subject.BIBLE to listOf("bible", "תנך", "תנ\"ך", "תורה", "משנה", "قرآن", "دين", "تراث")
        )
        table.forEach { (subject, keys) ->
            if (keys.any { lower.contains(it) }) return subject
        }
        return Subject.GENERAL
    }

    /** Copies the welcome books shipped in assets into the library on first run. */
    fun seedStarterBooks(assets: List<Triple<String, String, Subject>>) {
        scope.launch {
            var added = 0
            for ((assetPath, title, subject) in assets) {
                try {
                    val id = UUID.randomUUID().toString()
                    val target = File(booksDir, "$id.txt")
                    appContext.assets.open(assetPath).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    val book = Book(
                        id = id,
                        title = title,
                        author = "BookQuest",
                        subjectId = subject.id,
                        format = BookFormat.TXT,
                        fileName = target.name,
                        addedAt = System.currentTimeMillis(),
                        coverSeed = title.hashCode()
                    )
                    _books.value = _books.value + book
                    added++
                } catch (e: Exception) {
                    // Skip a starter book we cannot read rather than failing startup.
                }
            }
            if (added > 0) {
                refreshBadges()
                persist()
            }
        }
    }

    // -------------------------------------------------------------- mutations

    private fun mutate(id: String, block: (Book) -> Book) {
        _books.value = _books.value.map { if (it.id == id) block(it) else it }
        persist()
    }

    fun updateDetails(id: String, title: String, author: String, subject: Subject) {
        mutate(id) { it.copy(title = title.trim().ifEmpty { it.title }, author = author.trim(), subjectId = subject.id) }
    }

    fun toggleFavorite(id: String) = mutate(id) { it.copy(favorite = !it.favorite) }

    fun delete(book: Book) {
        scope.launch {
            try {
                bookFile(book).delete()
            } catch (e: Exception) {
                // The metadata entry still goes away below.
            }
            _books.value = _books.value.filterNot { it.id == book.id }
            persist()
        }
    }

    fun saveProgress(id: String, progress: Float, page: Int, offset: Int) {
        val clamped = progress.coerceIn(0f, 1f)
        mutate(id) {
            it.copy(
                progress = maxOf(it.progress, clamped).coerceIn(0f, 1f),
                lastPage = page,
                lastOffset = offset
            )
        }
    }

    fun setFinished(id: String, finished: Boolean) {
        val wasFinished = bookById(id)?.finished ?: false
        mutate(id) { it.copy(finished = finished, progress = if (finished) 1f else it.progress) }
        val profile = _profile.value
        val count = _books.value.count { it.finished }
        _profile.value = profile.copy(booksFinished = count)
        if (finished && !wasFinished) awardXp(50)
        refreshBadges()
        persist()
    }

    fun recordReading(bookId: String, seconds: Int) {
        if (seconds < 30) return
        val minutes = (seconds / 60).coerceAtLeast(1)
        mutate(bookId) { it.copy(minutesRead = it.minutesRead + minutes) }
        rollDay()
        val today = dayKey(Date())
        val profile = _profile.value
        val newStreak = when {
            profile.lastReadDay == today -> profile.streak.coerceAtLeast(1)
            profile.lastReadDay == dayKey(yesterday()) -> profile.streak + 1
            else -> 1
        }
        _profile.value = profile.copy(
            streak = newStreak,
            bestStreak = maxOf(profile.bestStreak, newStreak),
            lastReadDay = today,
            minutesToday = profile.minutesToday + minutes,
            totalMinutes = profile.totalMinutes + minutes
        )
        awardXp(minutes * 3)
        refreshBadges()
        persist()
    }

    fun addCard(bookId: String, front: String, back: String) {
        if (front.isBlank()) return
        val card = Flashcard(UUID.randomUUID().toString(), front.trim(), back.trim())
        mutate(bookId) { it.copy(cards = it.cards + card) }
        awardXp(5)
        refreshBadges()
    }

    fun deleteCard(bookId: String, cardId: String) {
        mutate(bookId) { book -> book.copy(cards = book.cards.filterNot { it.id == cardId }) }
    }

    fun recordQuiz(correct: Int, total: Int) {
        if (total <= 0) return
        awardXp(correct * 5 + if (correct == total) 25 else 0)
        if (correct == total) {
            _profile.value = _profile.value.copy(perfectQuizzes = _profile.value.perfectQuizzes + 1)
        }
        refreshBadges()
        persist()
    }

    fun setDailyGoal(minutes: Int) {
        _profile.value = _profile.value.copy(dailyGoal = minutes.coerceIn(5, 120))
        persist()
    }

    fun noteLanguage(tag: String) {
        val current = _profile.value
        if (current.languagesTried.contains(tag)) return
        _profile.value = current.copy(languagesTried = current.languagesTried + tag)
        refreshBadges()
        persist()
    }

    // ------------------------------------------------------------------- xp

    private fun awardXp(amount: Int) {
        if (amount <= 0) return
        val before = _profile.value
        val after = before.copy(xp = before.xp + amount)
        _profile.value = after
        _events.tryEmit(AppEvent.Xp(amount))
        if (after.level > before.level) {
            _events.tryEmit(AppEvent.LevelUp(after.level))
        }
        persist()
    }

    private fun refreshBadges() {
        val profile = _profile.value
        val books = _books.value
        val unlocked = profile.badges.toMutableSet()
        fun unlock(id: String, condition: Boolean) {
            if (condition && unlocked.add(id)) _events.tryEmit(AppEvent.BadgeUnlocked(id))
        }
        unlock("first_book", books.isNotEmpty())
        unlock("shelf_builder", books.size >= 5)
        unlock("finisher", books.any { it.finished })
        unlock("bookworm", books.count { it.finished } >= 5)
        unlock("streak_3", profile.streak >= 3)
        unlock("streak_7", profile.streak >= 7)
        unlock("deep_reader", profile.totalMinutes >= 60)
        unlock("quiz_master", profile.perfectQuizzes >= 1)
        unlock("polyglot", profile.languagesTried.size >= 3)
        unlock("scholar", profile.level >= 5)
        if (unlocked.size != profile.badges.size) {
            _profile.value = _profile.value.copy(badges = unlocked)
        }
    }

    // ----------------------------------------------------------------- dates

    private fun rollDay() {
        val today = dayKey(Date())
        val profile = _profile.value
        if (profile.today != today) {
            _profile.value = profile.copy(today = today, minutesToday = 0)
        }
    }

    private fun yesterday(): Date {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return calendar.time
    }

    private fun dayKey(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository {
            return instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext)
                    .also { it.load(); instance = it }
            }
        }
    }
}
