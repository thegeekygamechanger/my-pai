package app.pocketagent.desktop

import java.io.File

/**
 * Makes sure Linux knows this app by name and by its icon: an entry in the applications menu, and the right
 * picture in the dock while the app is running.
 *
 * An app unpacked from the .tar.gz has no menu entry at all until one is written. An app installed from the .deb
 * has one, but it does not say which windows belong to it, so the dock shows a blank icon for the running app.
 * Both are put right the same way: a small file in the person's own applications folder that names the program,
 * its icon and its windows. It is rewritten only when something in it would change (after the app moved, say).
 */
object Launcher {
    /** What the window system calls this app's windows: Java names them after the class that started them. */
    private const val WINDOW_CLASS = "app-pocketagent-desktop-MainKt"

    fun register() {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return
        runCatching {
            // Only the packaged app registers itself; run from the build tool there is no program to point at.
            val program = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile } ?: return
            val home = program.parentFile?.parentFile ?: return
            val icon = File(home, "lib/MyPAI.png").takeIf { it.isFile } ?: return
            val apps = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "applications")
            // The .deb's own entry is called mypai-MyPAI.desktop; an entry of the same name here adds to it what it lacks.
            val entry = File(apps, if (program.path.startsWith("/opt/")) "mypai-MyPAI.desktop" else "mypai.desktop")
            val text = """
                [Desktop Entry]
                Type=Application
                Name=My PAI
                Comment=Your personal AI: chat or hand it work, with your own keys
                Exec="${program.path}"
                Icon=${icon.path}
                Terminal=false
                Categories=Utility;
                StartupWMClass=$WINDOW_CLASS
            """.trimIndent() + "\n"
            if (entry.isFile && entry.readText() == text) return
            apps.mkdirs()
            entry.writeText(text)
        }
    }
}
