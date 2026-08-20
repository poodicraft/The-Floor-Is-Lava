// Top-level build file. Versions are declared once here and applied in :app.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.x ships the Compose compiler as a Gradle plugin; its version must match Kotlin's.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
