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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Eine Wiedergabe: Quellen suchen, besten Stream wählen (oder wählen lassen), im Player abspielen.
 * Bei Serien sucht Flimmer die nächste Folge schon während der laufenden; im Player geht es mit
 * «Nächste Folge» (oder automatisch am Ende) direkt weiter, ohne das Fenster zu schliessen.
 */
class PlaybackSession(
    val load: LoadResponse,
    val api: MainAPI,
    private val firstData: String,
    private val firstEp: Catalog.Ep?,
) {
    enum class Phase { Searching, Playing, Failed, Done }

    /** Folge in der Sitzung (Index = Position in [entries]) */
    private inner class Item(val ep: Catalog.Ep?, val data: String) {
        val links = CopyOnWriteArrayList<ExtractorLink>()
        val subs = CopyOnWriteArrayList<SubtitleFile>()
        @Volatile var searching = true
        @Volatile var started = false
        var lastPos = 0L
        var lastDur = 0L
        /** Tatsächlich geschaute Sekunden in dieser Sitzung (für «Am schauen» bei Trakt) */
        var watchedSec = 0
        var watchingSent = false
        val title: String get() = titleOf(ep)
        val startSec: Long
            get() {
                val p = (ep?.id ?: Catalog.mainId(load)).let { Sync.position(it) } ?: return 0
                return if (p.duration > 0 && p.position < p.duration * 0.92) p.position / 1000 else 0
            }
    }

    private fun titleOf(ep: Catalog.Ep?): String = buildString {
        append(load.name)
        if (ep != null) {
            append(" · S${ep.season ?: 1}:F${ep.episode}")
            ep.name?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
    }

    val title: String = titleOf(firstEp)

    /** Alle Folgen der Serie in Reihenfolge (für «Nächste Folge») */
    private val allEpisodes: List<Catalog.Ep> = if (firstEp != null) Catalog.episodes(load).sortedWith(
        compareBy({ it.season ?: 0 }, { it.episode })
    ) else emptyList()

    private val entries = CopyOnWriteArrayList(listOf(Item(firstEp, firstData)))

    // Für die Anzeige in Flimmer
    val links = mutableStateListOf<ExtractorLink>()
    var report by mutableStateOf<String?>(null)
    var phase by mutableStateOf(Phase.Searching)
    var message by mutableStateOf<String?>(null)
    var searchFinished by mutableStateOf(false)
    var nowPlaying by mutableStateOf(title)
    var nextInfo by mutableStateOf<String?>(null)

    private val userChoice = CompletableDeferred<ExtractorLink>()
    private val autoStart = CompletableDeferred<Unit>()
    private lateinit var scope: CoroutineScope
    private val jobs = mutableListOf<Job>()

    fun choose(link: ExtractorLink) {
        if (phase == Phase.Searching) userChoice.complete(link)
    }

    fun sortedLinks(): List<ExtractorLink> = links.toList().sortedByDescending { FlimmerStreams.rank(it) }

    // ---------------------------------------------------------------- Quellen suchen

    private fun search(index: Int) {
        val item = entries[index]
        jobs += scope.launch(Dispatchers.IO) {
            try {
                api.loadLinks(item.data, false, { s -> if (item.subs.none { it.url == s.url }) item.subs += s }) { link ->
                    if (item.links.none { it.url == link.url }) {
                        item.links += link
                        if (index == 0) scope.launch(Dispatchers.Main) { if (links.none { it.url == link.url }) links += link }
                        writeSessionSoon()
                    }
                }
            } catch (t: Throwable) {
                System.err.println("Quellensuche: ${t.message}")
            }
            item.searching = false
            writeSessionSoon()
            if (index == 0) {
                withContext(Dispatchers.Main) { searchFinished = true }
                autoStart.complete(Unit)
            } else {
                withContext(Dispatchers.Main) { updateNextInfo() }
            }
        }
    }

    // Sitzungsdatei höchstens einmal pro Sekunde schreiben
    @Volatile private var writePending = false
    @Volatile private var playerRunning = false

    private fun writeSessionSoon() {
        if (!playerRunning || writePending) return
        writePending = true
        scope.launch(Dispatchers.IO) {
            delay(800)
            writePending = false
            writeSession()
        }
    }

    private fun writeSession() {
        val list = entries.map { item ->
            // Laufende Folge: Reihenfolge nicht mehr ändern, neue Quellen hinten anhängen
            val ordered = if (item.started) item.links.toList() else item.links.toList().sortedByDescending { FlimmerStreams.rank(it) }
            Player.Entry(item.title, item.startSec, ordered, item.subs.toList(), item.searching)
        }
        try {
            Player.writeSession(list, 0)
        } catch (t: Throwable) {
            System.err.println("Sitzung: ${t.message}")
        }
    }

    /** Nächste Folge vorbereiten (Platzhalter in der Sitzung, Quellen im Hintergrund suchen) */
    private fun prepareNext(afterIndex: Int) {
        if (afterIndex + 1 < entries.size) return
        val ep = entries[afterIndex].ep ?: return
        val pos = allEpisodes.indexOfFirst { it.id == ep.id }
        val next = allEpisodes.getOrNull(pos + 1) ?: return
        entries += Item(next, next.data)
        writeSession()
        search(entries.lastIndex)
        updateNextInfo()
    }

    private fun updateNextInfo() {
        val cur = currentIndex
        val next = entries.getOrNull(cur + 1)
        nextInfo = when {
            next == null -> null
            next.links.isNotEmpty() -> "Nächste Folge bereit (S${next.ep?.season ?: 1}:F${next.ep?.episode}) · im Player N oder Knopf «Nächste Folge»"
            next.searching -> "Nächste Folge wird vorbereitet …"
            else -> "Für die nächste Folge wurde keine Quelle gefunden"
        }
    }

    // ---------------------------------------------------------------- Start

    fun start(scope: CoroutineScope, onClose: () -> Unit) {
        this.scope = scope
        FlimmerStreams.reportListener = { report = it }
        FlimmerStreams.startNowListener = { autoStart.complete(Unit) }
        search(0)
        // Erweiterungen direkt (ohne Katalog): einige Sekunden nach dem ersten Treffer starten
        jobs += scope.launch {
            while (links.isEmpty() && !searchFinished) delay(300)
            delay(8_000)
            autoStart.complete(Unit)
        }
        jobs += scope.launch {
            val first: ExtractorLink? = select {
                userChoice.onAwait { it }
                autoStart.onAwait { null }
            }
            if (first == null && links.isEmpty()) {
                phase = Phase.Failed
                message = "Keine Quelle gefunden. Unter «Quellensuche anzeigen» siehst du, was jede Erweiterung gemeldet hat."
                return@launch
            }
            runPlayer(first ?: sortedLinks().first(), onClose)
        }
    }

    @Volatile private var currentIndex = 0

    private suspend fun runPlayer(firstLink: ExtractorLink, onClose: () -> Unit) {
        phase = Phase.Playing
        message = null
        val item0 = entries[0]
        // Gewählte Quelle zuerst
        val ordered = listOf(firstLink) + item0.links.filter { it.url != firstLink.url }.sortedByDescending { FlimmerStreams.rank(it) }
        item0.links.clear(); item0.links.addAll(ordered)
        item0.started = true
        playerRunning = true
        writeSession()

        val process = try {
            withContext(Dispatchers.IO) { Player.launch() }
        } catch (t: Throwable) {
            phase = Phase.Failed
            message = t.message
            return
        }
        scrobble(0, "start")
        prepareNext(0)

        // Solange der Player läuft: Position und Folgenwechsel verfolgen
        withContext(Dispatchers.IO) {
            while (process.isAlive) {
                delay(1_000)
                val st = Player.readStatus() ?: continue
                val item = entries.getOrNull(st.index) ?: continue
                if (st.index != currentIndex) {
                    finish(currentIndex)
                    currentIndex = st.index
                    item.started = true
                    withContext(Dispatchers.Main) { nowPlaying = item.title; updateNextInfo() }
                    scrobble(st.index, "start")
                    prepareNext(st.index)
                }
                if (st.positionMs > item.lastPos && st.positionMs - item.lastPos < 5_000) item.watchedSec++
                item.lastPos = st.positionMs
                item.lastDur = st.durationMs
                // Nach 10 Minuten: bei Trakt «Am schauen» (Fortschritt speichern, aus «Geplant» nehmen)
                if (!item.watchingSent && item.watchedSec >= 600 && item.lastDur > 0) {
                    item.watchingSent = true
                    traktIds(item.data)?.let { (isTv, tmdb) ->
                        Trakt.markWatching(isTv, tmdb, item.ep?.season, item.ep?.episode, item.lastPos * 100.0 / item.lastDur)
                    }
                }
            }
        }
        Player.readStatus()?.let { st ->
            entries.getOrNull(st.index)?.let { it.lastPos = st.positionMs; it.lastDur = st.durationMs }
        }
        finish(currentIndex)
        playerRunning = false
        jobs.forEach { it.cancel() }
        phase = Phase.Done
        onClose()
    }

    /** Folge abschliessen: Weiterschauen/Sync speichern, Trakt melden */
    private fun finish(index: Int) {
        val item = entries.getOrNull(index) ?: return
        if (item.lastDur <= 0 || item.lastPos <= 0) return
        val mainId = Catalog.mainId(load)
        Sync.savePlayback(
            episodeId = item.ep?.id ?: mainId,
            parentId = mainId,
            positionMs = item.lastPos,
            durationMs = item.lastDur,
            header = Sync.Header(
                apiName = load.apiName, url = load.url, type = load.type.name, name = load.name,
                poster = load.posterUrl, id = mainId,
            ),
            season = item.ep?.season, episode = item.ep?.episode,
        )
        val pct = item.lastPos * 100.0 / item.lastDur
        scrobble(index, if (pct >= 80) "stop" else "pause", pct)
    }

    private fun scrobble(index: Int, action: String, pct: Double? = null) {
        val item = entries.getOrNull(index) ?: return
        val (isTv, tmdb) = traktIds(item.data) ?: return
        val progress = pct ?: item.startSec.let { s ->
            val d = item.lastDur
            if (d > 0) s * 100_000.0 / d else 0.0
        }
        Trakt.scrobble(action, isTv, tmdb, item.ep?.season, item.ep?.episode, progress)
    }

    /** (istSerie, TMDB-ID) für Trakt, nur bei Titeln aus dem Katalog. */
    private fun traktIds(data: String): Pair<Boolean, Int>? = try {
        if (FlimmerKatalog.isKatalog(load.apiName)) {
            val d = parseJson<FlimmerKatalog.LinkData>(data)
            d.isTv to d.tmdbId
        } else null
    } catch (_: Throwable) {
        null
    }

    fun dispose() {
        jobs.forEach { it.cancel() }
        FlimmerStreams.reportListener = null
        FlimmerStreams.startNowListener = null
    }
}
