package app.pocketagent.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The class archive that makes the app start in about half the time.
 *
 * The JVM can map its classes ready-made from an archive instead of reading and checking them from the jars at
 * every start, but only an archive made in the very folder the app runs from. So the app makes its own: once,
 * some time after it has started and settled, in a separate low-priority process that writes
 * `resources/mypai.jsa` from the list of classes shipped beside it. The next start uses it. After an update the
 * jars are different, the JVM ignores the old archive by itself, and a new one is made the same way.
 *
 * Nothing depends on it: where the app's folder cannot be written to (a system-wide install), or if anything
 * here fails, the app simply keeps starting the way it always did.
 */
object StartArchive {
    private const val NAME = "mypai.jsa"

    fun keepFresh(scope: CoroutineScope) {
        // Only the packaged app has this folder; run from the build tool there is nothing to do.
        val resources = System.getProperty("compose.application.resources.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val wait = System.getenv("MYPAI_ARCHIVE_AFTER")?.toLongOrNull() ?: 30L
        scope.launch(Dispatchers.IO) {
            delay(wait * 1000)
            runCatching { refresh(resources) }.onFailure { note("failed: $it") }
        }
    }

    /** Says what happened, when start-up is being measured (MYPAI_TIMING); silent otherwise. */
    private fun note(text: String) { if (System.getenv("MYPAI_TIMING") != null) println("MYPAI_TIMING archive: $text") }

    /** What this install consists of: its jars by name, size and date. An archive is good for exactly one of these. */
    private fun fingerprint(appDir: File): String =
        appDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
            .joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" }

    private fun refresh(resources: File) {
        val list = File(resources, "classes.lst").takeIf { it.isFile } ?: return note("no class list")
        val archive = File(resources, NAME)
        val stamp = File(resources, "$NAME.for")
        val now = fingerprint(resources.parentFile)
        if (now.isBlank() || (archive.length() > 1_000_000 && stamp.isFile && stamp.readText() == now)) return note("already good")
        if (!resources.canWrite()) return note("folder cannot be written to")
        val launcher = ProcessHandle.current().info().command().orElse(null)?.let(::File)?.takeIf { it.isFile } ?: return note("launcher not found")
        stamp.delete()
        // An archive left from before an update is no longer in use (the JVM turned it down at start). It is written
        // read-only, which on Windows also stops it being replaced, so it is cleared out of the way first.
        if (archive.exists()) { archive.setWritable(true); archive.delete() }
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        // The same program as this one, told to write the archive and stop: it never gets as far as opening a window.
        // It writes to the place this app is set up to read from (see jvmArgs in build.gradle.kts).
        val command = if (windows) listOf(launcher.path) else listOf("nice", "-n", "19", launcher.path)
        val process = ProcessBuilder(command).apply {
            // The launcher leaves itself a marker in the environment (_JPACKAGE_LAUNCHER) that tells a second copy
            // "you are my other half, your settings are already loaded". This copy must read its own.
            environment().keys.removeAll { it.startsWith("MYPAI_") || it.contains("JPACKAGE", ignoreCase = true) }
            // Quoted, so a folder with a space in its name does not split an option in two.
            environment()["JAVA_TOOL_OPTIONS"] = "-Xshare:dump -XX:SharedClassListFile=\"${list.path}\""
            redirectErrorStream(true)
            if (System.getenv("MYPAI_TIMING") != null) redirectOutput(File(resources, "$NAME.log")) else redirectOutput(ProcessBuilder.Redirect.DISCARD)
        }.start()
        note("writing with ${command.joinToString(" ")}")
        if (!process.waitFor(5, TimeUnit.MINUTES)) { process.destroyForcibly(); return note("took too long") }
        note("writer ended with ${process.exitValue()}, archive ${archive.length() / 1_048_576} MB")
        // Marked as good only when it is whole; a half-written one is ignored by the JVM and made again next time.
        if (archive.length() > 1_000_000) stamp.writeText(now) else archive.delete()
    }
}
