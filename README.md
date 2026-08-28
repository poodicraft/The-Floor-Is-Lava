# 📚 BookQuest — מסע הספרים / رحلة الكتب

A school books library app for Android. Students fill it with their own files,
read them inside the app, and level up while they do it.

The app speaks **Hebrew (default), English and Arabic**, and flips the whole
layout between right-to-left and left-to-right with the language.

## What it does

- **Load books from files** — pick one file or many with the system file
  picker. `TXT`, `PDF`, `EPUB`, `HTML` and `MD` are supported. Each book is
  copied into the app, so it stays readable offline.
- **A reader per format** — a paginated PDF viewer, a flowing EPUB/HTML reader
  and a plain text reader, all with adjustable text size and light / sepia /
  dark page styles. Hebrew and Arabic text files in legacy encodings
  (windows-1255 / windows-1256) are detected and decoded.
- **Progress that sticks** — every book remembers where you stopped, how far
  you got and how many minutes you spent in it.
- **Made to be fun** — XP for every minute read, levels, daily goals, day
  streaks, ten unlockable badges and a confetti burst when you level up.
- **Flashcards and quizzes** — write question/answer cards for any book, then
  play a flip-card quiz and earn XP for what you remember.
- **Generated covers** — no artwork needed: every book gets its own gradient
  cover from its subject and title.

## Getting the APK

Every push builds a debug APK in GitHub Actions:

1. Open the **Actions** tab → the latest **Build APK** run → download the
   `BookQuest-apk` artifact, **or**
2. Grab `BookQuest.apk` straight from the **`apk-latest`** release.

Install it on any phone running **Android 7.0 (API 24) or newer**. You will
need to allow installation from unknown sources, since this is a
self-signed debug build rather than a Play Store release.

## Building it yourself

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 34).

## Project layout

```
app/src/main/java/com/poodicraft/bookquest/
├── data/          models, preferences and the JSON-backed library repository
├── reader/        text decoding, EPUB flattening, PDF rendering
└── ui/            Compose screens, theme and shared components
app/src/main/res/values/       Hebrew strings (the default language)
app/src/main/res/values-en/    English strings
app/src/main/res/values-ar/    Arabic strings
```

`legacy/` holds the loose files from the earlier "Floor Is Lava" experiment
that used to sit in the repository root. Nothing in the app build reads them.
