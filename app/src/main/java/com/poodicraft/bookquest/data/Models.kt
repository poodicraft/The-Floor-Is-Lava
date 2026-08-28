package com.poodicraft.bookquest.data

import com.poodicraft.bookquest.R

/** File types the reader knows how to open. */
enum class BookFormat(val id: String) {
    TXT("txt"),
    HTML("html"),
    EPUB("epub"),
    PDF("pdf"),
    UNKNOWN("unknown");

    companion object {
        fun fromExtension(ext: String): BookFormat = when (ext.lowercase()) {
            "txt", "text", "md", "markdown", "log", "csv" -> TXT
            "htm", "html", "xhtml" -> HTML
            "epub" -> EPUB
            "pdf" -> PDF
            else -> UNKNOWN
        }

        fun fromId(id: String): BookFormat =
            values().firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** School subject a book is filed under. */
enum class Subject(
    val id: String,
    val labelRes: Int,
    val emoji: String,
    val colorStart: Long,
    val colorEnd: Long
) {
    GENERAL("general", R.string.subject_general, "📚", 0xFF7C5CFF, 0xFFB388FF),
    MATH("math", R.string.subject_math, "➗", 0xFF2E86FF, 0xFF63C8FF),
    SCIENCE("science", R.string.subject_science, "🔬", 0xFF00B39B, 0xFF5FE0B5),
    HISTORY("history", R.string.subject_history, "🏛️", 0xFFB4622B, 0xFFF0A45B),
    LITERATURE("literature", R.string.subject_literature, "✒️", 0xFFD9316A, 0xFFFF8FB1),
    LANGUAGE("language", R.string.subject_language, "🗣️", 0xFF8E44E0, 0xFFE07BFF),
    ART("art", R.string.subject_art, "🎨", 0xFFE0521E, 0xFFFFB35C),
    TECH("tech", R.string.subject_tech, "💻", 0xFF1F6FEB, 0xFF6EE7F9),
    BIBLE("bible", R.string.subject_bible, "📜", 0xFF3F7D3B, 0xFF9BD97A);

    companion object {
        fun fromId(id: String): Subject = values().firstOrNull { it.id == id } ?: GENERAL
    }
}

data class Flashcard(
    val id: String,
    val front: String,
    val back: String
)

data class Book(
    val id: String,
    val title: String,
    val author: String = "",
    val subjectId: String = Subject.GENERAL.id,
    val format: BookFormat = BookFormat.UNKNOWN,
    val fileName: String = "",
    val addedAt: Long = 0L,
    val coverSeed: Int = 0,
    val progress: Float = 0f,
    val lastPage: Int = 0,
    val lastOffset: Int = 0,
    val minutesRead: Int = 0,
    val favorite: Boolean = false,
    val finished: Boolean = false,
    /** Set once the completion bonus has been paid, so re-ticking cannot farm XP. */
    val finishRewarded: Boolean = false,
    /** How many cards on this book have already earned XP. Never goes down. */
    val cardsRewarded: Int = 0,
    val cards: List<Flashcard> = emptyList()
) {
    val subject: Subject get() = Subject.fromId(subjectId)
    val started: Boolean get() = progress > 0.001f
}

data class Profile(
    val xp: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val lastReadDay: String = "",
    val today: String = "",
    val minutesToday: Int = 0,
    val totalMinutes: Int = 0,
    val booksFinished: Int = 0,
    val dailyGoal: Int = 20,
    val perfectQuizzes: Int = 0,
    val languagesTried: Set<String> = emptySet(),
    val badges: Set<String> = emptySet()
) {
    val level: Int get() = xp / XP_PER_LEVEL + 1
    val xpInLevel: Int get() = xp % XP_PER_LEVEL
    val xpToNextLevel: Int get() = XP_PER_LEVEL - xpInLevel
    val levelProgress: Float get() = xpInLevel.toFloat() / XP_PER_LEVEL

    companion object {
        const val XP_PER_LEVEL = 200
    }
}

/** An achievement the student can unlock. */
data class Badge(
    val id: String,
    val titleRes: Int,
    val descRes: Int,
    val emoji: String
)

object Badges {
    val ALL = listOf(
        Badge("first_book", R.string.badge_first_book, R.string.badge_first_book_desc, "📗"),
        Badge("shelf_builder", R.string.badge_shelf_builder, R.string.badge_shelf_builder_desc, "🗄️"),
        Badge("finisher", R.string.badge_finisher, R.string.badge_finisher_desc, "🏁"),
        Badge("bookworm", R.string.badge_bookworm, R.string.badge_bookworm_desc, "🐛"),
        Badge("streak_3", R.string.badge_streak_3, R.string.badge_streak_3_desc, "🔥"),
        Badge("streak_7", R.string.badge_streak_7, R.string.badge_streak_7_desc, "🚀"),
        Badge("deep_reader", R.string.badge_hour, R.string.badge_hour_desc, "⏳"),
        Badge("quiz_master", R.string.badge_quiz, R.string.badge_quiz_desc, "🧠"),
        Badge("polyglot", R.string.badge_polyglot, R.string.badge_polyglot_desc, "🌍"),
        Badge("scholar", R.string.badge_scholar, R.string.badge_scholar_desc, "🎓")
    )
}
