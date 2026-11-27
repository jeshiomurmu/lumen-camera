// Versions chosen to be mutually compatible (AGP 8.13 + Gradle 8.14 + Kotlin 2.2).
// Android Studio's AGP Upgrade Assistant can move the project to AGP 9.x later.
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
