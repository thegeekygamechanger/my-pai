package app.pocketagent.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val notes: String, val url: String, val sizeBytes: Long, val fileName: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    /** Android has not yet been told that the app may install its own updates. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    /** The system's own "Update this app?" prompt is showing, or the installer has been started. */
    data class Confirming(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Updates come from the project's GitHub releases and from nowhere else: the app asks GitHub for the
 * newest release, downloads that release's file for this system, and hands it to the system to install.
 */
interface Updates {
    val state: StateFlow<UpdateState>
    /** Looks for a newer release. Without [force] it does so at most once a day. */
    suspend fun check(force: Boolean = false)
    fun install(info: UpdateInfo)
    fun onAppForeground() {}
    /** Opens the system setting that lets the app install updates (Android only). */
    fun openPermission() {}

    companion object {
        const val REPO = "thegeekygamechanger/pocket-agent"
        const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        const val PAGE = "https://github.com/$REPO/releases/latest"
        val http: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

        /** True when version [candidate] is newer than [current] ("1.10.0" beats "1.9.3"). */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.trim().split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** The names a release's files always have, for version [v]. */
        fun fileNames(v: String) = listOf("PocketAgent-$v.apk", "PocketAgent-$v-windows-x64-setup.exe", "PocketAgent-$v-windows-x64.msi", "PocketAgent-$v-linux-x64.tar.gz", "PocketAgent-$v-linux-x64.deb")

        /**
         * Finds the newest release and picks its file for this system. It asks the ordinary "latest release"
         * web address where it leads, rather than GitHub's API, because the API turns anonymous callers away
         * on busy shared networks.
         * @param pick chooses among the release's file names; null means this system has no file in the release
         * @return the update if the release is newer than [current], otherwise null (also when nothing is released yet)
         */
        suspend fun latest(current: String, pick: (List<String>) -> String?): UpdateInfo? = withContext(Dispatchers.IO) {
            val noFollow = http.newBuilder().followRedirects(false).build()
            val location = noFollow.newCall(Request.Builder().url(PAGE).header("User-Agent", "PocketAgent").build()).execute().use { resp ->
                when {
                    resp.code == 404 -> return@withContext null
                    resp.isRedirect -> resp.header("Location").orEmpty()
                    else -> throw java.io.IOException("HTTP ${resp.code}")
                }
            }
            // The address ends in /releases/tag/v1.2.3; without a tag there is no release yet.
            val version = location.substringAfter("/releases/tag/", "").removePrefix("v").trim('/')
            if (version.isEmpty() || !isNewer(version, current)) return@withContext null
            val name = pick(fileNames(version)) ?: return@withContext null
            UpdateInfo(version, "", "https://github.com/$REPO/releases/download/v$version/$name", 0, name)
        }
    }
}
