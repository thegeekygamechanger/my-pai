plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
    // The Linux and Windows app (desktop/) is plain Kotlin on the JVM with Compose Multiplatform.
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}
