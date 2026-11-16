import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Pocket Agent for Linux and Windows. The screens and the app's logic are the Android app's own source
// files (../app/src/main/java), compiled here unchanged; only what differs between a phone and a
// computer lives in this module, behind the Platform interface.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
}

// Keep in step with versionName in app/build.gradle.kts.
val appVersion = "1.1.0"

// Android-only files: each has a counterpart in this module under a different file name, or is not needed here.
val androidOnly = listOf(
    "app/pocketagent/AndroidApp.kt", "app/pocketagent/MainActivity.kt", "app/pocketagent/ai/RunService.kt", "app/pocketagent/ai/ScheduleWorker.kt",
    "app/pocketagent/ui/Back.kt", "app/pocketagent/ui/theme/Fonts.kt", "app/pocketagent/update/AndroidUpdates.kt",
)

val generatedDir = layout.buildDirectory.dir("generated/buildinfo")
val writeBuildInfo by tasks.registering {
    val out = generatedDir
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app/pocketagent/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package app.pocketagent.desktop\n\n/** Written by the build; never committed. */\ninternal object BuildInfo {\n    const val VERSION = \"$version\"\n}\n")
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir("../app/src/main/java")
    sourceSets["main"].kotlin.srcDir(writeBuildInfo)
    sourceSets["main"].kotlin.exclude(androidOnly)
}
// The fonts are the Android app's own files.
sourceSets["main"].resources.srcDir("../app/src/main/res")
sourceSets["main"].resources.srcDir("icons")
sourceSets["main"].resources.include("font/**", "icon.png")

ksp { arg("room.generateKotlin", "true") }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences-core:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    // QR codes for linking devices. Apache-2.0, no network.
    implementation("com.google.zxing:core:3.5.4")
    // Voice typing that runs on the computer itself, for people with no speech-to-text key.
    implementation("com.alphacephei:vosk:0.3.45")
}

compose.desktop {
    application {
        mainClass = "app.pocketagent.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.AppImage)
            packageName = "PocketAgent"
            packageVersion = appVersion
            description = "Chat and agent app for Claude and other AI services, with your own API keys"
            vendor = "Pocket Agent"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.desktop", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            linux { shortcut = true; menuGroup = "Utility"; iconFile.set(project.file("icons/icon.png")) }
            windows {
                shortcut = true; menu = true; menuGroup = "Pocket Agent"; perUserInstall = true
                // Stays the same for every version, so a newer installer replaces the older app instead of sitting beside it.
                upgradeUuid = "b3e1c7a4-52d6-4f0b-9a38-7c1d2e5f6a90"
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}

// Draws the app off-screen and saves pictures of it (see Render.kt):
//   ./gradlew :desktop:render -Pshots="<data dir> <out dir> wait:2500 home 300,200 chat" [-Psize=1600x1000x1]
tasks.register<JavaExec>("render") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.pocketagent.desktop.RenderKt")
    args = ((project.findProperty("shots") as String?) ?: "").split(' ').filter { it.isNotBlank() }
    jvmArgs("-Djava.awt.headless=true", "-Drender.size=" + ((project.findProperty("size") as String?) ?: "1600x1000x1"))
    (project.findProperty("relay") as String?)?.let { jvmArgs("-Dpocketagent.relay=$it") }
}

// Simulated devices link, sync and share through a relay (see SyncCheck.kt). Start server/relay.mjs first:
//   PORT=18790 node server/relay.mjs &   then   ./gradlew :desktop:syncCheck -Prelay=http://127.0.0.1:18790
tasks.register<JavaExec>("syncCheck") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.pocketagent.desktop.SyncCheckKt")
    args = listOf(layout.buildDirectory.dir("sync-check").get().asFile.path)
    jvmArgs("-Djava.awt.headless=true", "-Dpocketagent.relay=" + ((project.findProperty("relay") as String?) ?: "http://127.0.0.1:18790"))
}
