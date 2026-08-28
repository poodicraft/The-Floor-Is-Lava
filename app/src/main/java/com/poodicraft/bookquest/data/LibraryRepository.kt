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
        put("finishRewarded", book.finishRewarded)
        put("cardsRewarded", book.cardsRewarded)
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
            finishRewarded = json.optBoolean("finishRewarded", json.optBoolean("finished", false)),
            cardsRewarded = json.optInt("cardsRewarded", cards.size),
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
            val restored = restoreFromSnapshot(book)
            _books.value = listOf(restored) + _books.value
            restored
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
        // The completion bonus is paid once per book, ever. Ticking the button off
        // and on again re-marks the book but does not hand out XP a second time.
        val alreadyRewarded = bookById(id)?.finishRewarded ?: false
        val payBonus = finished && !alreadyRewarded
        mutate(id) {
            it.copy(
                finished = finished,
                progress = if (finished) 1f else it.progress,
                finishRewarded = it.finishRewarded || finished
            )
        }
        val count = _books.value.count { it.finished }
        _profile.value = _profile.value.copy(booksFinished = count)
        if (payBonus) awardXp(FINISH_BONUS_XP)
        refreshBadges()
        persist()
    }

    fun recordReading(bookId: String, seconds: Int) {
        // Anything under a full minute is not a reading session worth paying for.
        val minutes = seconds / 60
        if (minutes < 1) return
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
        // Only the first few cards on a book pay out, and the counter never drops,
        // so deleting and re-adding a card cannot be used to farm XP.
        val rewarded = bookById(bookId)?.cardsRewarded ?: 0
        val payCard = rewarded < REWARDED_CARDS_PER_BOOK
        val card = Flashcard(UUID.randomUUID().toString(), front.trim(), back.trim())
        mutate(bookId) {
            it.copy(
                cards = it.cards + card,
                cardsRewarded = if (payCard) it.cardsRewarded + 1 else it.cardsRewarded
            )
        }
        if (payCard) awardXp(CARD_XP)
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

    // ------------------------------------------------------------ cloud sync

    private val snapshotFile: File get() = File(appContext.filesDir, "cloud_snapshot.json")

    private fun bookKey(title: String, format: BookFormat): String =
        title.trim().lowercase() + "|" + format.id

    /** Everything worth backing up to a Google account, as one JSON document. */
    fun exportSnapshot(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("profile", profileToJson(_profile.value))
        val array = JSONArray()
        for (book in _books.value) {
            val entry = JSONObject()
            entry.put("key", bookKey(book.title, book.format))
            entry.put("title", book.title)
            entry.put("author", book.author)
            entry.put("subject", book.subjectId)
            entry.put("format", book.format.id)
            entry.put("progress", book.progress.toDouble())
            entry.put("lastPage", book.lastPage)
            entry.put("minutesRead", book.minutesRead)
            entry.put("favorite", book.favorite)
            entry.put("finished", book.finished)
            entry.put("finishRewarded", book.finishRewarded)
            entry.put("cardsRewarded", book.cardsRewarded)
            val cards = JSONArray()
            for (card in book.cards) {
                val cardJson = JSONObject()
                cardJson.put("front", card.front)
                cardJson.put("back", card.back)
                cards.put(cardJson)
            }
            entry.put("cards", cards)
            array.put(entry)
        }
        root.put("books", array)
        return root.toString()
    }

    /**
     * Folds a snapshot from the cloud into local state. Every field takes the more
     * advanced of the two sides, so a merge can never roll reading progress back.
     */
    fun mergeSnapshot(json: String) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }

        val remote = HashMap<String, JSONObject>()
        val array = root.optJSONArray("books")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val key = entry.optString("key")
                if (key.isNotEmpty()) remote[key] = entry
            }
        }

        _books.value = _books.value.map { book ->
            val entry = remote[bookKey(book.title, book.format)]
            if (entry == null) book else applyEntry(book, entry)
        }

        val remoteProfile = root.optJSONObject("profile")
        if (remoteProfile != null) {
            val local = _profile.value
            val incoming = profileFromJson(remoteProfile)
            _profile.value = local.copy(
                xp = maxOf(local.xp, incoming.xp),
                streak = maxOf(local.streak, incoming.streak),
                bestStreak = maxOf(local.bestStreak, incoming.bestStreak),
                totalMinutes = maxOf(local.totalMinutes, incoming.totalMinutes),
                perfectQuizzes = maxOf(local.perfectQuizzes, incoming.perfectQuizzes),
                booksFinished = _books.value.count { it.finished },
                lastReadDay = maxOf(local.lastReadDay, incoming.lastReadDay),
                languagesTried = local.languagesTried + incoming.languagesTried,
                badges = local.badges + incoming.badges
            )
        }

        try {
            snapshotFile.writeText(json)
        } catch (e: Exception) {
            // The merge already happened; only the offline copy is lost.
        }
        refreshBadges()
        persist()
    }

    private fun applyEntry(book: Book, entry: JSONObject): Book {
        val cards = book.cards.toMutableList()
        val seen = book.cards.mapTo(HashSet()) { it.front.trim().lowercase() }
        val remoteCards = entry.optJSONArray("cards")
        if (remoteCards != null) {
            for (i in 0 until remoteCards.length()) {
                val card = remoteCards.optJSONObject(i) ?: continue
                val front = card.optString("front")
                if (front.isBlank()) continue
                if (seen.add(front.trim().lowercase())) {
                    cards.add(
                        Flashcard(UUID.randomUUID().toString(), front, card.optString("back"))
                    )
                }
            }
        }
        return book.copy(
            progress = maxOf(book.progress, entry.optDouble("progress", 0.0).toFloat()),
            lastPage = maxOf(book.lastPage, entry.optInt("lastPage", 0)),
            minutesRead = maxOf(book.minutesRead, entry.optInt("minutesRead", 0)),
            favorite = book.favorite || entry.optBoolean("favorite", false),
            finished = book.finished || entry.optBoolean("finished", false),
            finishRewarded = book.finishRewarded || entry.optBoolean("finishRewarded", false),
            cardsRewarded = maxOf(book.cardsRewarded, entry.optInt("cardsRewarded", 0)),
            cards = cards
        )
    }

    /**
     * Re-attaches progress from the last cloud snapshot to a freshly imported book.
     * That is what makes "reinstall, sign in, add your files again" bring the
     * reading history back with them.
     */
    private fun restoreFromSnapshot(book: Book): Book {
        val root = try {
            if (!snapshotFile.exists()) return book
            JSONObject(snapshotFile.readText())
        } catch (e: Exception) {
            return book
        }
        val array = root.optJSONArray("books") ?: return book
        val key = bookKey(book.title, book.format)
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("key") == key) return applyEntry(book, entry)
        }
        return book
    }

    companion object {
        const val FINISH_BONUS_XP = 50
        const val CARD_XP = 5
        const val REWARDED_CARDS_PER_BOOK = 10

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
