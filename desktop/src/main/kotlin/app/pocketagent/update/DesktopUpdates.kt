package app.pocketagent.update

import app.pocketagent.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.system.exitProcess

/**
 * Updates on a computer, from GitHub releases. On Windows the new installer is downloaded and started.
 * On Linux, when the app was unpacked from the .tar.gz into a folder the user owns, the new one is
 * unpacked over it and the app restarts; an app installed from the .deb is updated by installing the new .deb.
 */
class DesktopUpdates(private val g: Graph) : Updates {
    override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The folder this app runs from when it is a packaged app image (bin/ and lib/ inside), and the user may write to it. */
    private fun installDir(): File? {
        val exe = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
        val dir = exe.parentFile?.parentFile ?: return null
        return dir.takeIf { !windows && File(dir, "lib").isDirectory && it.canWrite() && it.parentFile?.canWrite() == true }
    }

    override suspend fun check(force: Boolean) {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Confirming) return
        val s = g.settingsStore.flow.first()
        if (!force && (!s.autoUpdate || System.currentTimeMillis() - s.lastUpdateCheck < Updates.AUTO_CHECK_MS - 60_000)) return
        state.value = UpdateState.Checking
        try {
            val info = Updates.latest(g.platform.versionName) { names ->
                val wanted = if (windows) listOf(".exe", ".msi") else if (installDir() != null) listOf(".tar.gz", ".deb") else listOf(".deb", ".tar.gz")
                wanted.firstNotNullOfOrNull { ext -> names.firstOrNull { it.endsWith(ext, ignoreCase = true) && (windows || it.contains("linux", true) || ext == ".deb") } }
            }
            g.settingsStore.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            state.value = if (force) UpdateState.Failed("Could not reach GitHub to check for updates (${e.message ?: e.javaClass.simpleName}). Try again in a while.") else UpdateState.Idle
        }
    }

    private suspend fun download(info: UpdateInfo, to: File) = withContext(Dispatchers.IO) {
        Updates.http.newCall(Request.Builder().url(info.url).header("User-Agent", "PocketAgent").build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val total = res.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            to.outputStream().use { out ->
                val input = res.body.byteStream()
                val buffer = ByteArray(128 * 1024)
                var done = 0L
                var shown = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    if (pct != shown) { shown = pct; state.value = UpdateState.Downloading(info, pct) }
                }
            }
        }
    }

    override fun install(info: UpdateInfo) {
        if (state.value is UpdateState.Downloading) return
        val dir = installDir()
        val selfUpdate = dir != null && info.fileName.endsWith(".tar.gz", true)
        if (!windows && !selfUpdate) {
            // Installed from a system package: the browser fetches the new package and the system's installer does the rest.
            runCatching { Desktop.getDesktop().browse(URI(info.url)) }.onFailure { runCatching { ProcessBuilder("xdg-open", info.url).start() } }
            state.value = UpdateState.Confirming(info)
            return
        }
        state.value = UpdateState.Downloading(info, 0)
        g.scope.launch {
            try {
                val tmp = File(System.getProperty("java.io.tmpdir"), "PocketAgent-update").apply { deleteRecursively(); mkdirs() }
                val file = File(tmp, info.fileName)
                download(info, file)
                state.value = UpdateState.Confirming(info)
                g.runs.stopAll()
                if (windows) {
                    ProcessBuilder(file.path).start()
                } else {
                    // A small script waits for this app to close, swaps the folder, starts the new version and removes the download.
                    val target = dir!!
                    val script = File(tmp, "apply.sh")
                    script.writeText(
                        "#!/bin/sh\nsleep 2\nmkdir -p '${tmp.path}/new' && tar -xzf '${file.path}' -C '${tmp.path}/new' || exit 1\n" +
                            "src=\$(find '${tmp.path}/new' -maxdepth 2 -type d -name bin | head -1)\n[ -n \"\$src\" ] || exit 1\nsrc=\$(dirname \"\$src\")\n" +
                            "rm -rf '${target.path}.old' && mv '${target.path}' '${target.path}.old' && mv \"\$src\" '${target.path}' && rm -rf '${target.path}.old'\n" +
                            "nohup '${target.path}/bin/PocketAgent' >/dev/null 2>&1 &\nrm -rf '${tmp.path}'\n"
                    )
                    script.setExecutable(true)
                    ProcessBuilder("sh", script.path).inheritIO().start()
                }
                exitProcess(0)
            } catch (e: Exception) {
                state.value = UpdateState.Failed("The update could not be downloaded. Try again.", info)
            }
        }
    }
}
