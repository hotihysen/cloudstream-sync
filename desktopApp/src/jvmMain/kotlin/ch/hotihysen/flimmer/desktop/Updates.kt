package ch.hotihysen.flimmer.desktop

import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Prüft, ob es eine neuere Windows-Version gibt (Release «windows» im eigenen Repo). */
object Updates {
    const val DOWNLOAD_PAGE = "https://github.com/hotihysen/cloudstream-sync/releases/tag/windows"
    private const val API = "https://api.github.com/repos/hotihysen/cloudstream-sync/releases/tags/windows"

    private val _available = MutableStateFlow<String?>(null)
    val available: StateFlow<String?> = _available

    suspend fun check(force: Boolean = false) {
        val last = Store.getLong("updates/lastCheck", 0)
        if (!force && System.currentTimeMillis() - last < 6 * 3_600_000L) return
        Store.putLong("updates/lastCheck", System.currentTimeMillis())
        try {
            val text = app.get(API, headers = mapOf("Accept" to "application/vnd.github+json")).text
            val build = Regex("""Build (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return
            val mine = BuildConfig.FLIMMER_BUILD.toIntOrNull() ?: return
            _available.value = if (build > mine) "Build $build" else null
        } catch (_: Throwable) {
        }
    }
}
