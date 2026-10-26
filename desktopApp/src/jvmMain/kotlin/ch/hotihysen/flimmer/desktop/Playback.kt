package ch.hotihysen.flimmer.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/** Eine Wiedergabe: Quellen suchen, besten Stream wählen (oder vom Nutzer wählen lassen), abspielen. */
class PlaybackSession(
    val load: LoadResponse,
    val api: MainAPI,
    val data: String,
    val ep: Catalog.Ep?,
) {
    enum class Phase { Searching, Playing, Failed, Done }

    val title: String = buildString {
        append(load.name)
        if (ep != null) append(" · S${ep.season ?: 1}:F${ep.episode}")
    }

    val links = mutableStateListOf<ExtractorLink>()
    private val subtitles = CopyOnWriteArrayList<SubtitleFile>()
    var report by mutableStateOf<String?>(null)
    var phase by mutableStateOf(Phase.Searching)
    var message by mutableStateOf<String?>(null)
    var searchFinished by mutableStateOf(false)

    private val userChoice = CompletableDeferred<ExtractorLink>()
    private val autoStart = CompletableDeferred<Unit>()

    fun choose(link: ExtractorLink) {
        if (phase == Phase.Searching) userChoice.complete(link)
        else if (phase == Phase.Failed) scopeRestart?.invoke(link)
    }

    private var scopeRestart: ((ExtractorLink) -> Unit)? = null

    fun sortedLinks(): List<ExtractorLink> = links.toList().sortedByDescending { FlimmerStreams.rank(it) }

    fun start(scope: CoroutineScope, onClose: () -> Unit) {
        FlimmerStreams.reportListener = { report = it }
        FlimmerStreams.startNowListener = { autoStart.complete(Unit) }
        val search = scope.launch(Dispatchers.IO) {
            try {
                api.loadLinks(data, false, { subtitles += it }) { link ->
                    scope.launch(Dispatchers.Main) { if (links.none { it.url == link.url }) links += link }
                }
            } catch (t: Throwable) {
                System.err.println("Quellensuche: ${t.message}")
            }
            withContext(Dispatchers.Main) { searchFinished = true }
            autoStart.complete(Unit)
        }
        // Erweiterungen direkt (ohne Katalog): einige Sekunden nach dem ersten Treffer starten
        scope.launch {
            while (links.isEmpty() && !searchFinished) delay(300)
            delay(8_000)
            autoStart.complete(Unit)
        }

        scope.launch {
            val first: ExtractorLink? = select {
                userChoice.onAwait { it }
                autoStart.onAwait { null }
            }
            if (first == null && links.isEmpty()) {
                // Suche fertig, aber nichts gefunden
                phase = Phase.Failed
                message = "Keine Quelle gefunden. Unter «Quellensuche» siehst du, was jede Erweiterung gemeldet hat."
                return@launch
            }
            scopeRestart = { link -> scope.launch { playFrom(link, onClose) } }
            playFrom(first ?: sortedLinks().first(), onClose)
            search.cancel()
        }
    }

    private suspend fun playFrom(firstLink: ExtractorLink, onClose: () -> Unit) {
        phase = Phase.Playing
        message = null
        val order = listOf(firstLink) + sortedLinks().filter { it.url != firstLink.url }
        val start = ep?.let { Sync.position(it.id) } ?: Sync.position(Catalog.mainId(load))
        val startMs = start?.let { if (it.duration > 0 && it.position < it.duration * 0.92) it.position else 0L } ?: 0L
        val ids = traktIds()

        val result = Player.play(title, order, subtitles.toList(), startMs) {
            ids?.let { (isTv, tmdb) ->
                val pct = start?.let { s -> if (s.duration > 0) s.position * 100.0 / s.duration else 0.0 } ?: 0.0
                Trakt.scrobble("start", isTv, tmdb, ep?.season, ep?.episode, pct)
            }
        }

        if (result.durationMs > 0 && result.positionMs > 0) {
            val mainId = Catalog.mainId(load)
            Sync.savePlayback(
                episodeId = ep?.id ?: mainId,
                parentId = mainId,
                positionMs = result.positionMs,
                durationMs = result.durationMs,
                header = Sync.Header(
                    apiName = load.apiName, url = load.url, type = load.type.name, name = load.name,
                    poster = load.posterUrl, id = mainId,
                ),
                season = ep?.season, episode = ep?.episode,
            )
            ids?.let { (isTv, tmdb) ->
                val pct = result.positionMs * 100.0 / result.durationMs
                Trakt.scrobble(if (pct >= 80) "stop" else "pause", isTv, tmdb, ep?.season, ep?.episode, pct)
            }
        }
        if (result.error != null) {
            phase = Phase.Failed
            message = "Abspielen fehlgeschlagen: ${result.error}. Wähle unten eine andere Quelle."
        } else {
            phase = Phase.Done
            onClose()
        }
    }

    /** (istSerie, TMDB-ID) für Trakt, nur bei Titeln aus dem Katalog. */
    private fun traktIds(): Pair<Boolean, Int>? = try {
        if (FlimmerKatalog.isKatalog(load.apiName)) {
            val d = parseJson<FlimmerKatalog.LinkData>(data)
            d.isTv to d.tmdbId
        } else null
    } catch (_: Throwable) {
        null
    }

    fun dispose() {
        FlimmerStreams.reportListener = null
        FlimmerStreams.startNowListener = null
    }
}
