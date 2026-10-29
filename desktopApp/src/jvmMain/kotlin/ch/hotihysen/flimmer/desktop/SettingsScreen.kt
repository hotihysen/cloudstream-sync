package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.flimmer.FlimmerStreams
import kotlinx.coroutines.launch

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(FlimmerColors.Surface).padding(18.dp)
    ) { content() }
}

@Composable
internal fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            subtitle?.let { Dim(it, 13) }
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = FlimmerColors.Accent, checkedThumbColor = Color.White)
        )
    }
}

@Composable
internal fun field(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, fontSize = 14.sp) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = FlimmerColors.SurfaceHigh, cursorColor = FlimmerColors.Accent),
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 16.dp).widthIn(max = 1000.dp)
    ) {
        Text("Einstellungen", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        // Beim Verlassen: geänderte Repos, Erweiterungen und Einstellungen an die anderen Geräte melden
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { DeviceSetup.snapshot() } }

        // ------------------------------------------------------------ Profil
        SectionTitle("Profil")
        ProfileSection()

        // ------------------------------------------------------------ Erweiterungen
        SectionTitle("Erweiterungen")
        ExtensionsSection()

        // ------------------------------------------------------------ Sync
        SectionTitle("Weiterschauen auf allen Geräten (Gist-Sync)")
        Card {
            var enabled by remember { mutableStateOf(Sync.enabled) }
            var token by remember { mutableStateOf(Sync.token) }
            val status by Sync.status.collectAsState()
            var busy by remember { mutableStateOf(false) }
            SettingSwitch("Sync einschalten", "Gleicher GitHub-Token wie auf Handy und Fernseher (Berechtigung «gist»)", enabled) {
                enabled = it; Sync.enabled = it
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                field(token, { token = it }, "GitHub-Token (ghp_…)", password = true, modifier = Modifier.weight(1f))
                Gap(w = 12)
                FlimmerButton("Speichern") { Sync.token = token }
                Gap(w = 12)
                FlimmerButton(if (busy) "…" else "Jetzt synchronisieren", primary = true) {
                    if (!busy) scope.launch { busy = true; Sync.token = token; Sync.pull(); busy = false }
                }
            }
            if (status.isNotBlank()) { Gap(h = 8); Dim(status, 13) }
            Gap(h = 14)
            Dim("Synchronisiert werden auch Profile (mit PIN), Repos, Erweiterungen, Einstellungen und Merklisten.", 13)
            Gap(h = 10)
            RestoreFlow(showTokenField = false)
        }

        // ------------------------------------------------------------ Trakt
        SectionTitle("Trakt (Profil «${Profiles.current.name}»)")
        Card { TraktSection() }

        // ------------------------------------------------------------ Wiedergabe
        SectionTitle("Wiedergabe und Quellen")
        Card {
            var lang by remember { mutableStateOf(FlimmerStreams.language) }
            val order = listOf(FlimmerStreams.LANG_DE, FlimmerStreams.LANG_ORIGINAL, FlimmerStreams.LANG_ANY)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sprache", color = Color.White, fontSize = 15.sp)
                    Dim(FlimmerStreams.languageLabel(lang), 13)
                }
                FlimmerButton("Wechseln") {
                    lang = order[(order.indexOf(lang) + 1) % order.size]; FlimmerStreams.language = lang
                }
            }
            var fs by remember { mutableStateOf(Store.getBoolean("player/fullscreen", true)) }
            SettingSwitch("Im Vollbild starten", "Im Player: F = Vollbild, Leertaste = Pause, Pfeile = spulen, Q = schliessen", fs) {
                fs = it; Store.putBoolean("player/fullscreen", it)
            }
            Gap(h = 6)
            Dim("Videoplayer: " + (Player.mpvPath() ?: "nicht gefunden (Ordner «mpv» neben Flimmer.exe)"), 13)
        }

        SectionTitle("Letzte Quellensuche")
        Card {
            SelectionContainer {
                Text(FlimmerStreams.lastReport ?: "Noch keine Suche.", color = FlimmerColors.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }

        SectionTitle("Über Flimmer")
        Card {
            Text("Flimmer für Windows ${BuildConfig.FLIMMER_VERSION} · Build ${BuildConfig.FLIMMER_BUILD}", color = Color.White, fontSize = 15.sp)
            Gap(h = 4)
            Dim("Daten: ${Store.dataDir.absolutePath}", 13)
            Gap(h = 10)
            Row {
                FlimmerButton("Datenordner öffnen") { openPath(Store.dataDir.absolutePath) }
                Gap(w = 12)
                FlimmerButton("Nach Updates suchen") { scope.launch { Updates.check(force = true) } }
            }
            val update by Updates.available.collectAsState()
            update?.let {
                Gap(h = 10)
                Pill("Neue Version verfügbar: $it · herunterladen", FlimmerColors.Accent, Color.Black) { openUrl(Updates.DOWNLOAD_PAGE) }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun TraktSection() {
    val scope = rememberCoroutineScope()
    val profile = Sync.profile
    var loggedIn by remember(profile) { mutableStateOf(Trakt.isLoggedIn) }
    val lastReport by Trakt.lastReport.collectAsState()
    var bookmarks by remember { mutableStateOf(Store.getBoolean("trakt/bookmarks", true)) }
    var syncing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<Trakt.DeviceCode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var scrobble by remember { mutableStateOf(Store.getBoolean("trakt/scrobble", true)) }
    if (!Trakt.isConfigured) {
        Dim("Trakt-Client-ID fehlt in diesem Build."); return
    }
    if (loggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Angemeldet als ${Trakt.userName ?: "Trakt-Nutzer"}", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            FlimmerButton("Abmelden") { Trakt.logout(); loggedIn = false }
        }
        Gap(h = 6)
        SettingSwitch("Scrobbeln", "Was du schaust, wird an Trakt gemeldet: ab 10 Minuten «Am schauen», ab 80 % gesehen", scrobble) {
            scrobble = it; Store.putBoolean("trakt/scrobble", it)
        }
        SettingSwitch("Meine Liste an Trakt", "«Geplant» landet in der Trakt-Watchlist, «Abgeschlossen» als gesehen", bookmarks) {
            bookmarks = it; Store.putBoolean("trakt/bookmarks", it)
        }
        Gap(h = 6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlimmerButton(if (syncing) "…" else "Trakt jetzt abgleichen") {
                if (syncing) return@FlimmerButton
                scope.launch {
                    syncing = true
                    Trakt.forceRefresh()
                    val lib = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Trakt.library() } catch (_: Throwable) { null } }
                    message = if (lib == null) "Trakt nicht erreichbar" else
                        "Abgeglichen: " + lib.lists.joinToString(", ") { "${it.first} ${it.second.size}" }
                    syncing = false
                }
            }
        }
        message?.let { Gap(h = 6); Dim(it, 13) }
        Gap(h = 6)
        Dim("Letzte Meldung: ${lastReport ?: "noch keine in dieser Sitzung"}", 13)
        Dim("Die Trakt-Anmeldung gilt pro Profil. Deine Watchlist erscheint auf der Startseite, die ganze Übersicht unter «Bibliothek».", 13)
    } else {
        val c = code
        if (c == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dim("Nicht angemeldet.  ")
                FlimmerButton("Mit Trakt anmelden", primary = true) {
                    scope.launch {
                        try {
                            val dc = Trakt.requestCode()
                            code = dc
                            openUrl(dc.verificationUrl)
                            val ok = Trakt.waitForLogin(dc)
                            code = null
                            loggedIn = ok
                            message = if (ok) null else "Anmeldung abgebrochen oder abgelaufen."
                        } catch (t: Throwable) {
                            code = null; message = "Trakt nicht erreichbar: ${t.message}"
                        }
                    }
                }
            }
        } else {
            Text("Öffne ${c.verificationUrl} und gib diesen Code ein:", color = Color.White, fontSize = 15.sp)
            Gap(h = 8)
            SelectionContainer { Text(c.userCode, color = FlimmerColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
            Gap(h = 6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = FlimmerColors.Accent, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
                Gap(w = 10)
                Dim("Warte auf Bestätigung …", 13)
            }
        }
        message?.let { Gap(h = 6); Dim(it, 13) }
    }
}

@Composable
private fun ExtensionsSection() {
    val scope = rememberCoroutineScope()
    val installed by Extensions.installed.collectAsState()
    val states by Extensions.states.collectAsState()
    var repos by remember { mutableStateOf(Extensions.repos()) }
    var newRepo by remember { mutableStateOf("") }
    val repoData = remember { mutableStateMapOf<String, Extensions.Repo?>() }
    val repoError = remember { mutableStateMapOf<String, String>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }

    Card {
        Text("Installiert (${installed.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (installed.isEmpty()) Dim("Noch keine. Wähle unten in einem Repo «Installieren».", 13)
        installed.forEach { inst ->
            val st = states[inst.internalName]
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${inst.name}  ·  v${inst.version}", color = Color.White, fontSize = 15.sp)
                    Text(
                        st?.message ?: "wird geladen …",
                        color = if (st?.ok == false) FlimmerColors.Accent else FlimmerColors.TextDim,
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                FlimmerButton("Entfernen") { Extensions.uninstall(inst.internalName) }
            }
        }
    }
    Gap(h = 12)
    Card {
        Text("Repos", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Dim("Repo-Link einfügen, z.B. «cloudstreamrepo://…» oder «https://…/repo.json».", 13)
        Gap(h = 8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            field(newRepo, { newRepo = it }, "Repo-Link", modifier = Modifier.weight(1f))
            Gap(w = 12)
            FlimmerButton("Hinzufügen", primary = true) {
                if (newRepo.isNotBlank()) {
                    Extensions.addRepo(newRepo); newRepo = ""; repos = Extensions.repos()
                }
            }
        }
        repos.forEach { url ->
            Gap(h = 14)
            val data = repoData[url]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(data?.name ?: url.substringAfter("://").take(70), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Dim(url, 12)
                }
                FlimmerButton(if (data == null) "Anzeigen" else "Aktualisieren") {
                    scope.launch {
                        repoError.remove(url)
                        try { repoData[url] = Extensions.fetchRepo(url) } catch (t: Throwable) { repoError[url] = "Repo nicht lesbar: ${t.message}" }
                    }
                }
                Gap(w = 8)
                FlimmerButton("Entfernen") { Extensions.removeRepo(url); repos = Extensions.repos(); repoData.remove(url) }
            }
            repoError[url]?.let { Dim(it, 13) }
            data?.let { repo ->
                Row(Modifier.padding(top = 6.dp)) {
                    Pill("Alle installieren", FlimmerColors.Accent, Color.Black) {
                        scope.launch {
                            repo.plugins.filter { p -> installed.none { it.internalName == p.internalName } }.forEach { p ->
                                busy[p.internalName] = true
                                try { Extensions.install(repo.url, p) } catch (_: Throwable) {}
                                busy[p.internalName] = false
                            }
                        }
                    }
                }
                repo.plugins.sortedBy { it.name.lowercase() }.forEach { p ->
                    val inst = installed.firstOrNull { it.internalName == p.internalName }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 14.sp)
                            Text(
                                listOfNotNull(p.language, p.tvTypes?.joinToString(", "), p.description).joinToString(" · "),
                                color = FlimmerColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        when {
                            busy[p.internalName] == true -> Dim("installiere …", 13)
                            inst == null -> Pill("Installieren", Color.White, Color.Black) {
                                scope.launch {
                                    busy[p.internalName] = true
                                    try { Extensions.install(repo.url, p) } catch (t: Throwable) { repoError[url] = "${p.name}: ${t.message}" }
                                    busy[p.internalName] = false
                                }
                            }
                            inst.version < p.version -> Pill("Aktualisieren", FlimmerColors.Accent, Color.Black) {
                                scope.launch {
                                    busy[p.internalName] = true
                                    try { Extensions.install(repo.url, p) } catch (_: Throwable) {}
                                    busy[p.internalName] = false
                                }
                            }
                            else -> Dim("installiert", 13)
                        }
                    }
                }
            }
        }
    }
}

fun openUrl(url: String) {
    try {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    } catch (_: Throwable) {
    }
}

fun openPath(path: String) {
    try {
        java.awt.Desktop.getDesktop().open(java.io.File(path))
    } catch (_: Throwable) {
    }
}
