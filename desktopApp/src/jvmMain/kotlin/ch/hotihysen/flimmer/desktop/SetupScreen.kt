package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ablauf «Von einem anderen Gerät übernehmen» (beim ersten Start und in den Einstellungen) */
@Composable
fun RestoreFlow(showTokenField: Boolean, onFinished: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Sync.token) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeviceSetup.RestoreResult?>(null) }

    val r = result
    if (r != null && r.error == null) {
        Text("Übernommen", color = FlimmerColors.Good, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Gap(h = 6)
        Dim("${r.entries} Einträge übernommen (Profile, Repos, Einstellungen, Weiterschauen, Merklisten).", 14)
        Dim("${r.plugins} Erweiterungen installiert. Trakt meldest du pro Profil einmal neu an.", 14)
        onFinished?.let { Gap(h = 14); FlimmerButton("Weiter", primary = true) { it() } }
        return
    }
    if (showTokenField) {
        field(token, { token = it }, "GitHub-Token (ghp_…)", password = true, modifier = Modifier.fillMaxWidth())
        Gap(h = 6)
        Dim("Gleicher Token wie auf deinem anderen Gerät (dort unter Einstellungen, Allgemein, Cloud-Sync). Vorhandenes bleibt erhalten und wird ergänzt.", 13)
        Gap(h = 12)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlimmerButton(if (busy) "Wird übernommen …" else "Alles von anderen Geräten übernehmen", primary = true) {
            if (busy) return@FlimmerButton
            val t = token.trim()
            if (t.isEmpty()) { result = DeviceSetup.RestoreResult(0, 0, "Token fehlt"); return@FlimmerButton }
            scope.launch {
                busy = true
                if (t != Sync.token) Sync.token = t
                Sync.enabled = true
                result = withContext(Dispatchers.IO) { DeviceSetup.restore() }
                busy = false
            }
        }
        if (busy) {
            Gap(w = 12)
            CircularProgressIndicator(color = FlimmerColors.Accent, strokeWidth = 2.dp, modifier = Modifier.width(18.dp).height(18.dp))
        }
    }
    r?.error?.let {
        Gap(h = 8)
        Text("Übernehmen fehlgeschlagen: $it. Prüfe den Token (Berechtigung «gist») und die Internetverbindung.", color = FlimmerColors.Accent, fontSize = 13.sp)
    }
}

/** Erster Start */
@Composable
fun FirstRunScreen(onDone: () -> Unit) {
    var restore by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(FlimmerColors.Background)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(620.dp).clip(RoundedCornerShape(14.dp)).background(FlimmerColors.Surface).padding(30.dp)) {
            Text("FLIMMER", color = FlimmerColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Gap(h = 10)
            Text("Willkommen", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Gap(h = 6)
            Dim("Nutzt du Flimmer schon auf dem Handy oder Fernseher? Dann übernimmt Flimmer von dort Profile, Repos, Erweiterungen, Einstellungen, Weiterschauen und Merklisten.", 14)
            Gap(h = 20)
            if (restore) {
                RestoreFlow(showTokenField = true, onFinished = onDone)
                Gap(h = 14)
                Row { TabText("Abbrechen, neu einrichten", false) { onDone() } }
            } else {
                Row {
                    FlimmerButton("Von einem anderen Gerät übernehmen", primary = true) { restore = true }
                    Gap(w = 12)
                    FlimmerButton("Neu einrichten") { onDone() }
                }
            }
        }
    }
}
