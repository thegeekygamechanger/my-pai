import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// My PAI for Linux and Windows. The screens and the app's logic are the Android app's own source
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
val appVersion = "1.1.1"
// A later build of the same version. People who already have that version are offered it, and still see the same
// version number. Keep in step with appRevision in app/build.gradle.kts; back to 0 when the version itself goes up.
val appRevision = 1

// Android-only files: each has a counterpart in this module under a different file name, or is not needed here.
val androidOnly = listOf(
    "app/pocketagent/AndroidApp.kt", "app/pocketagent/MainActivity.kt", "app/pocketagent/ai/RunService.kt", "app/pocketagent/ai/ScheduleWorker.kt",
    "app/pocketagent/ui/Back.kt", "app/pocketagent/ui/theme/Fonts.kt", "app/pocketagent/update/AndroidUpdates.kt",
)

val generatedDir = layout.buildDirectory.dir("generated/buildinfo")
val writeBuildInfo by tasks.registering {
    val out = generatedDir
    val version = appVersion
    val revision = appRevision
    inputs.property("version", version)
    inputs.property("revision", revision)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app/pocketagent/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package app.pocketagent.desktop\n\n/** Written by the build; never committed. */\ninternal object BuildInfo {\n    const val VERSION = \"$version\"\n    const val REVISION = $revision\n}\n")
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
        // A lighter Java runtime for a desktop app that spends most of its time waiting. Measured on the packaged app,
        // idle on a chat: total memory 367 MB -> about 265 MB, the app's own memory 253 MB -> about 155 MB, with the same
        // start time. The serial collector and the first-tier compiler need far less memory than the server defaults;
        // the heap gives memory back after use; -Xmx only caps a runaway, it costs nothing until it is needed.
        jvmArgs += listOf(
            "-XX:+UseSerialGC", "-Xms16m", "-Xmx768m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25",
            "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=48m", "-Xss512k",
            // Classes come ready-made from an archive (resources/mypai.jsa) instead of being read and checked from the
            // jars at every start, which about halves the time to the first screen. The archive only works in the folder
            // it was made in, so the app writes its own, once, in the background after its first start in a new place
            // or after an update (see StartArchive.kt). Until it exists, or if it does not match, the JVM starts the old
            // way without a word. It is mapped where it was written, so its pages are shared with the file.
            "-XX:SharedArchiveFile=\$APPDIR/resources/mypai.jsa", "-XX:+UnlockDiagnosticVMOptions", "-XX:ArchiveRelocationMode=0",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.AppImage)
            packageName = "MyPAI"
            packageVersion = appVersion
            description = "Chat and agent app for Claude and other AI services, with your own API keys"
            vendor = "My PAI"
            licenseFile.set(rootProject.file("LICENSE"))
            // A file placed beside the app in every kind of installer: the list of classes its start-up archive is made from.
            appResourcesRootDir.set(layout.buildDirectory.dir("cds-resources"))
            modules("java.sql", "java.desktop", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            linux { shortcut = true; menuGroup = "Utility"; iconFile.set(project.file("icons/icon.png")) }
            windows {
                shortcut = true; menu = true; menuGroup = "My PAI"; perUserInstall = true
                // Stays the same for every version, so a newer installer replaces the older app instead of sitting beside it.
                upgradeUuid = "91562554-15f9-44a6-952b-10630933042e"
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}

// ---- The class archive -------------------------------------------------------------------------
// What ships is the list of classes to put in it (resources/classes.lst); the app makes the archive from that list on
// the person's machine (StartArchive.kt). The list is recorded at build time from a short off-screen tour of the app:
// the first screen, Settings, the files screen and the task screen. Names that no longer exist are simply skipped.
val cdsDir = layout.buildDirectory.dir("cds")
val cdsTour = tasks.register<JavaExec>("cdsTour") {
    group = "build"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.pocketagent.desktop.RenderKt")
    val dir = cdsDir.get().asFile
    args = listOf(File(dir, "data").path, File(dir, "out").path, "key=anthropic:sk-ant-tour-000000000000000000", "wait:2500", "939,907", "type:hello", "wait:300", "212,958", "wait:900", "938,153", "wait:700", "324,44", "wait:500", "128,171", "wait:800", "130,231", "wait:800", "tour")
    jvmArgs("-Djava.awt.headless=true", "-Drender.size=1600x1000x1", "-Xmx512m", "-XX:DumpLoadedClassList=" + File(dir, "tour.lst").path)
    isIgnoreExitValue = true
    doFirst { dir.deleteRecursively(); dir.mkdirs() }
}
val classList = tasks.register("classList") {
    group = "build"
    dependsOn(cdsTour)
    val tour = cdsDir.map { it.file("tour.lst") }
    val out = layout.buildDirectory.file("cds-resources/common/classes.lst")
    outputs.file(out)
    doLast {
        // Class names once each, with the notes about lambdas, which refer to classes by name.
        val seen = HashSet<String>()
        val lines = listOf(tour.get().asFile).filter { it.exists() }.flatMap { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("@")) it else it.substringBefore(' ') }
            .filter { seen.add(it) }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Class list: ${lines.count { !it.startsWith("@") }} classes")
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(classList) }

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
