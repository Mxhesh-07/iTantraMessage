// Root build file.
//
// No plugins are applied here. The versions are declared (apply false) so the module
// build files can reference them without repeating version strings, and so there is one
// place to change a version.
//
// WHY THESE VERSIONS -- every one is a version already present in this machine's Gradle
// artifact cache, which is what makes the build resolve offline and reproducibly rather
// than downloading whatever is newest today:
//
//   AGP        8.13.1   cached
//   Kotlin     2.2.20   cached; ships the Compose compiler plugin (org.jetbrains.kotlin.plugin.compose),
//                       so the separate androidx.compose.compiler Kotlin plugin is NOT needed
//   KSP        2.2.20-2.0.4  Room's annotation processor via KSP, not KAPT -- faster, and
//                       it avoids the KAPT/IR interaction problems that Kotlin 2.x brought
//
// Compose BOM 2025.06.01 is pinned by BOM (not by individual artifact versions) so that
// compose-ui, compose-material3 and compose-foundation can never drift apart, which is the
// usual cause of "cannot find material3" style failures.

plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
}
