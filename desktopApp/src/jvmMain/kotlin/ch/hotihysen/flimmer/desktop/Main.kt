package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.request.crossfade
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.flimmer.FlimmerKatalog
import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

sealed interface Screen {
    data class Home(val mode: FlimmerKatalog.Mode) : Screen
    data class Detail(val apiName: String, val url: String) : Screen
    data object Search : Screen
    data object Settings : Screen
}

/** Navigation und laufende Wiedergabe. */
class Nav(private val scope: CoroutineScope) {
    val stack = mutableStateListOf<Screen>(Screen.Home(FlimmerKatalog.Mode.ALL))
    var session by mutableStateOf<PlaybackSession?>(null)
    var lastQuery = ""
    val current: Screen get() = stack.last()

    fun go(s: Screen) {
        if (s is Screen.Home) {
            stack.clear(); stack += s
        } else if (current != s) stack += s
    }

    fun back() {
        if (session != null) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun play(load: LoadResponse, ep: Catalog.Ep?) {
        if (session != null) return
        val api = Catalog.api(load.apiName) ?: return
        val data = ep?.data ?: Catalog.movieData(load) ?: return
        val s = PlaybackSession(load, api, data, ep)
        session = s
        s.start(scope) { closeSession() }
    }

    /** «Abspielen»: angefangene Folge fortsetzen, sonst erste Folge bzw. den Film. */
    fun playDefault(load: LoadResponse) {
        val eps = Catalog.episodes(load)
        if (eps.isEmpty()) {
            play(load, null); return
        }
        val resume = Sync.resumeList().firstOrNull { it.header.id == Catalog.mainId(load) }
        val ep = resume?.resume?.episodeId?.let { id -> eps.firstOrNull { it.id == id } }?.let { e ->
            // Fast fertig geschaut: nächste Folge
            val p = Sync.position(e.id)
            if (p != null && p.duration > 0 && p.position > p.duration * 0.92) eps.getOrNull(eps.indexOf(e) + 1) ?: e else e
        } ?: eps.first()
        play(load, ep)
    }

    fun closeSession() {
        session?.dispose()
        session = null
    }
}

private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

fun main() {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    application {
        val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp))
        val nav = remember { Nav(appScope) }
        val icon = remember {
            Main::class.java.getResourceAsStream("/flimmer-icon.png")?.use {
                BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
            }
        }
        Window(
            onCloseRequest = {
                Store.save()
                exitApplication()
            },
            title = "Flimmer",
            state = windowState,
            icon = icon,
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    if (nav.session?.phase == PlaybackSession.Phase.Failed) nav.closeSession() else nav.back()
                    true
                } else if (e.type == KeyEventType.KeyDown && e.key == Key.F11) {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    true
                } else false
            },
        ) {
            FlimmerTheme { App(nav) }
        }
    }
}

private object Main

@Composable
fun App(nav: Nav) {
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .crossfade(true)
            .diskCache { DiskCache.Builder().directory(Store.cacheDir.resolve("bilder").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .build()
    }
    var startupDone by remember { mutableStateOf(false) }
    var startupText by remember { mutableStateOf("Erweiterungen werden geladen …") }

    LaunchedEffect(Unit) {
        Catalog.register()
        Extensions.loadAll()
        // Erster Start: deutsche Erweiterungen aus dem Standard-Repo installieren
        if (!Store.getBoolean("firstRunDone", false)) {
            startupText = "Erster Start: Erweiterungen werden eingerichtet …"
            try {
                val repo = Extensions.fetchRepo(Extensions.DEFAULT_REPO)
                repo.plugins.filter { p ->
                    val types = p.tvTypes.orEmpty()
                    types.any { it in setOf("Movie", "TvSeries", "Anime", "AnimeMovies", "Series") }
                }.forEach { p ->
                    startupText = "Installiere ${p.name} …"
                    try { Extensions.install(repo.url, p) } catch (_: Throwable) {}
                }
                Store.putBoolean("firstRunDone", true)
            } catch (t: Throwable) {
                System.err.println("Erster Start: ${t.message}")
            }
        }
        startupDone = true
        launch(Dispatchers.IO) { Sync.pull() }
        launch(Dispatchers.IO) { Extensions.updateAll() }
        launch(Dispatchers.IO) { Updates.check() }
        // Stand der anderen Geräte regelmässig holen
        launch(Dispatchers.IO) {
            while (true) {
                delay(120_000)
                if (nav.session == null) Sync.pull()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(FlimmerColors.Background)) {
        if (!startupDone) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FLIMMER", color = FlimmerColors.Accent, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Gap(h = 18)
                CircularProgressIndicator(color = FlimmerColors.Accent)
                Gap(h = 14)
                Dim(startupText)
            }
            return@Box
        }
        val screen = nav.current
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (screen) {
                    is Screen.Home -> HomeScreen(screen.mode, nav)
                    is Screen.Detail -> DetailScreen(screen.apiName, screen.url, nav)
                    Screen.Search -> Box(Modifier.padding(top = 72.dp)) { SearchScreen(nav) }
                    Screen.Settings -> Box(Modifier.padding(top = 72.dp)) { SettingsScreen(nav) }
                }
                TopBar(nav)
            }
        }
        nav.session?.let { PlaybackOverlay(it, nav) }
    }
}

@Composable
private fun TopBar(nav: Nav) {
    val screen = nav.current
    val update by Updates.available.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(68.dp)
            .background(Brush.verticalGradient(0f to Color(0xFF141414), 0.6f to Color(0xE6141414), 1f to Color(0x00141414)))
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "FLIMMER", color = FlimmerColors.Accent, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
            modifier = Modifier.clickable { nav.go(Screen.Home(FlimmerKatalog.Mode.ALL)) }.handCursor()
        )
        Gap(w = 28)
        Catalog.modes.forEach { (mode, label) ->
            TabText(label, screen is Screen.Home && screen.mode == mode) { nav.go(Screen.Home(mode)) }
        }
        Box(Modifier.weight(1f))
        if (nav.stack.size > 1) {
            TabText("‹ Zurück", false) { nav.back() }
        }
        update?.let { TabText("Update verfügbar", false) { openUrl(Updates.DOWNLOAD_PAGE) } }
        TabText("Suche", screen is Screen.Search) { nav.go(Screen.Search) }
        TabText("Einstellungen", screen is Screen.Settings) { nav.go(Screen.Settings) }
    }
}

@Composable
private fun PlaybackOverlay(s: PlaybackSession, nav: Nav) {
    var showReport by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(760.dp).heightIn(max = 760.dp).clip(RoundedCornerShape(14.dp))
                .background(FlimmerColors.Surface).padding(26.dp)
        ) {
            Text(s.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(h = 8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.phase == PlaybackSession.Phase.Searching || s.phase == PlaybackSession.Phase.Playing) {
                    CircularProgressIndicator(color = FlimmerColors.Accent, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
                    Gap(w = 10)
                }
                Text(
                    when (s.phase) {
                        PlaybackSession.Phase.Searching -> "Quellen werden gesucht … ${s.links.size} gefunden" +
                                if (s.searchFinished) " (Suche fertig)" else ""
                        PlaybackSession.Phase.Playing -> "Läuft im Player. Schliesse das Player-Fenster, um zurückzukehren."
                        PlaybackSession.Phase.Failed -> s.message ?: "Fehler"
                        PlaybackSession.Phase.Done -> "Fertig"
                    },
                    color = if (s.phase == PlaybackSession.Phase.Failed) FlimmerColors.Accent else FlimmerColors.TextDim,
                    fontSize = 14.sp,
                )
            }
            if (s.phase != PlaybackSession.Phase.Playing) {
                Gap(h = 14)
                Dim("Klicke eine Quelle, um sofort damit zu starten. Sonst wählt Flimmer automatisch die beste.", 13)
                Gap(h = 8)
                val sorted = s.sortedLinks()
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp)) {
                    items(sorted, key = { it.url }) { link ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { s.choose(link) }.handCursor()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(link.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Gap(w = 10)
                            Dim(qualityText(link.quality), 13)
                            if (FlimmerStreams.bonus(link) >= 2) {
                                Gap(w = 8)
                                Pill("DE", Color(0x3346D369), FlimmerColors.Good)
                            }
                        }
                    }
                }
            }
            Gap(h = 14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.phase == PlaybackSession.Phase.Searching && s.links.isNotEmpty()) {
                    FlimmerButton("▶  Jetzt abspielen", primary = true) { s.choose(s.sortedLinks().first()) }
                    Gap(w = 12)
                }
                if (s.phase != PlaybackSession.Phase.Playing) {
                    FlimmerButton("Abbrechen") { nav.closeSession() }
                    Gap(w = 12)
                }
                TabText(if (showReport) "Bericht ausblenden" else "Quellensuche anzeigen", showReport) { showReport = !showReport }
            }
            if (showReport) {
                Gap(h = 8)
                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(s.report ?: "Noch kein Bericht.", color = FlimmerColors.TextDim, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

fun qualityText(q: Int): String = when {
    q <= 0 || q == Qualities.Unknown.value -> "?"
    q >= 2160 -> "4K"
    else -> "${q}p"
}
