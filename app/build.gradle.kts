import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The release counter, from version.properties.
 *
 * Read from a file rather than written here so there is exactly one place to bump, and so
 * the number in the app's About screen cannot drift away from the one Android shows in the
 * app's details page — both come from this.
 */
val releaseNumber: Int = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("versionCode").trim().toInt()

/**
 * A build-time value from a Gradle property or the environment, or "" if it was not given.
 *
 * Escaped on the way through because these end up inside a Kotlin string literal in the
 * generated `BuildConfig`, where a stray quote would be a compile error rather than a
 * mystery at runtime.
 */
val buildSecret: (String, String) -> String = { property, environmentVariable ->
    (findProperty(property) as String? ?: System.getenv(environmentVariable) ?: "")
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

android {
    namespace = "com.paperjump"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.paperjump"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseNumber
        versionName = "1.$releaseNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // How the AI level designer is wired up at build time, so a built APK can arrive
        // already working with nothing to type. All three are empty by default and none of
        // them is ever committed — see server/README.md.
        //
        // aiProxyUrl / aiAppSecret point the app at your own proxy, which holds the key.
        // Neither is a secret: a URL is a URL, and the app secret is only a speed bump.
        //
        // hfToken bakes a Hugging Face key straight into the APK instead. It works, and
        // anybody who gets the APK gets the key with it, so it belongs on a build that
        // stays on your own phone rather than one you hand out.
        buildConfigField("String", "AI_PROXY_URL", "\"${buildSecret("aiProxyUrl", "AI_PROXY_URL")}\"")
        buildConfigField("String", "AI_APP_SECRET", "\"${buildSecret("aiAppSecret", "AI_APP_SECRET")}\"")
        buildConfigField("String", "AI_TOKEN", "\"${buildSecret("hfToken", "HF_TOKEN")}\"")
    }

    // Signing for the release build. The key comes from the environment so no private key
    // is ever committed; without it, `assembleRelease` just produces an unsigned APK.
    val keystorePath: String? = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 is off until the app has been through a device test with it on: a bad
            // keep rule shows up as a crash at runtime, which CI here cannot catch.
            // Turning it on is a one-line change (shrinkResources needs minify enabled).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.VERSION_NAME, shown on the home and settings screens.
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ---- Compose ----------------------------------------------------------------
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ---- AndroidX core ----------------------------------------------------------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // ---- CameraX ----------------------------------------------------------------
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ---- Image / concurrency ----------------------------------------------------
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- Tests ------------------------------------------------------------------
    // The detector and the physics are plain Kotlin, so they run on the JVM.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
