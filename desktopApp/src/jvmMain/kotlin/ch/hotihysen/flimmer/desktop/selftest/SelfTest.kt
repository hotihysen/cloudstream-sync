package ch.hotihysen.flimmer.desktop.selftest

import ch.hotihysen.flimmer.desktop.Catalog
import ch.hotihysen.flimmer.desktop.Extensions
import ch.hotihysen.flimmer.desktop.Store

import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Selbsttest für den GitHub-Build: Erweiterungen installieren und laden, dann einen Film und eine
 * Serie über alle Erweiterungen suchen. Gibt einen Bericht aus; schlägt nur fehl, wenn gar nichts lädt.
 */
fun main(): Unit = runBlocking {
    println("== Flimmer Selbsttest · Daten: ${Store.dataDir}")
    Catalog.register()
    val repo = Extensions.fetchRepo(Extensions.DEFAULT_REPO)
    println("Repo «${repo.name}»: ${repo.plugins.size} Erweiterungen")
    var ok = 0
    for (p in repo.plugins) {
        val state = try { Extensions.install(repo.url, p) } catch (t: Throwable) {
            Extensions.LoadState(p.internalName, false, "Download: ${t.message}", emptyList())
        }
        if (state.ok) ok++
        println(" ${if (state.ok) "OK  " else "FEHL"} ${p.name}: ${state.message} ${state.providers}")
    }
    println("$ok von ${repo.plugins.size} Erweiterungen geladen")

    val katalog = Catalog.katalog(FlimmerKatalog.Mode.ALL)
    for ((query, isTv) in listOf("Inception" to false, "Breaking Bad" to true)) {
        println("\n== Suche: $query")
        val hit = katalog.search(query).firstOrNull() ?: run { println("TMDB: kein Treffer"); continue }
        val load = katalog.load(hit.url) ?: continue
        val data = if (isTv) Catalog.episodes(load).firstOrNull()?.data else Catalog.movieData(load)
        if (data == null) { println("keine Abspieldaten"); continue }
        var links = 0
        withTimeoutOrNull(110_000) {
            katalog.loadLinks(data, false, {}) { links++ }
        }
        println(FlimmerStreams.lastReport ?: "kein Bericht")
        println("-> $links Links")
    }
    Store.save()
    exitProcess(if (ok == 0) 1 else 0)
}
