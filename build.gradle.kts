// AGP 9 has built-in Kotlin support, so there is no `org.jetbrains.kotlin.android` plugin here —
// applying it is a hard error since AGP 9.0. See https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
