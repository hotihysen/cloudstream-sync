package ch.hotihysen.flimmer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Profile, PIN-Abfrage und Profilwahl («Wer schaut?»), wie in der Android-App. */
object ProfileUi {
    /** Offene PIN-Abfrage (wird über allem angezeigt) */
    class PinRequest(
        val title: String,
        /** Erwartete PIN; null = neue PIN festlegen (zweimal eingeben) */
        val expected: String?,
        /** Bei einer Abfrage: «PIN vergessen» mit dem Gist-Token anbieten */
        val allowForgot: Boolean = true,
        val onResult: (pin: String?, forgot: Boolean) -> Unit,
    )

    var pin by mutableStateOf<PinRequest?>(null)
    var pickerOpen by mutableStateOf(false)
    /** Erster Start: Angebot «Von einem anderen Gerät übernehmen» */
    var firstRun by mutableStateOf(Store.getString("setup/firstRun") == null && !Sync.isActive && Extensions.repos().isEmpty())

    fun finishFirstRun() {
        firstRun = false
        Store.putString("setup/firstRun", "1")
    }

    /** Profil wählen; mit PIN erst nach richtiger Eingabe. */
    fun choose(a: Profiles.Account, onDone: () -> Unit = {}) {
        val p = a.lockPin
        if (p == null) {
            Profiles.switchTo(a); onDone(); return
        }
        pin = PinRequest("PIN für «${a.name}»", p) { ok, forgot ->
            if (ok != null) {
                // PIN vergessen: Sperre entfernen, damit das Profil wieder nutzbar ist
                if (forgot) Profiles.update(a.copy(lockPin = null))
                Profiles.switchTo(a)
                onDone()
            }
        }
    }
}

@Composable
fun ProfileAvatar(a: Profiles.Account, size: Int = 34, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(Profiles.color(a))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).handCursor() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(a.name.take(1).uppercase(), color = Color.White, fontSize = (size * 0.45).sp, fontWeight = FontWeight.Bold)
    }
}

/** «Wer schaut?» */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProfilePicker(canClose: Boolean, onDone: () -> Unit) {
    val list by Profiles.list.collectAsState()
    Box(Modifier.fillMaxSize().background(FlimmerColors.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Wer schaut?", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Gap(h = 36)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                list.forEach { a ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Column(
                        Modifier.width(140.dp).hoverable(interaction).clickable { ProfileUi.choose(a, onDone) }.handCursor(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(130.dp).clip(RoundedCornerShape(10.dp))
                                .then(if (hovered) Modifier.border(3.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                        ) { ProfileAvatar(a, 130) }
                        Gap(h = 10)
                        Text(
                            a.name + if (a.lockPin != null) "  🔒" else "",
                            color = if (hovered) Color.White else FlimmerColors.TextDim, fontSize = 17.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (canClose) {
                Gap(h = 36)
                FlimmerButton("Abbrechen") { onDone() }
            }
        }
    }
}

/** PIN-Abfrage über allem */
@Composable
fun PinOverlay() {
    val req = ProfileUi.pin ?: return
    var input by remember(req) { mutableStateOf("") }
    var first by remember(req) { mutableStateOf<String?>(null) }
    var error by remember(req) { mutableStateOf<String?>(null) }
    var forgotMode by remember(req) { mutableStateOf(false) }
    var token by remember(req) { mutableStateOf("") }
    val focus = remember(req) { FocusRequester() }
    LaunchedEffect(req, forgotMode) { try { focus.requestFocus() } catch (_: Throwable) { } }

    fun close(pin: String?, forgot: Boolean = false) {
        ProfileUi.pin = null
        req.onResult(pin, forgot)
    }

    fun submit(value: String) {
        if (value.length != 4) return
        if (req.expected != null) {
            if (value == req.expected) close(value) else { error = "Falsche PIN"; input = "" }
        } else if (first == null) {
            first = value; input = ""; error = null
        } else if (first == value) {
            close(value)
        } else {
            first = null; input = ""; error = "Die beiden Eingaben stimmen nicht überein. Bitte nochmals."
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xD9000000))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(14.dp)).background(FlimmerColors.Surface).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(req.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Gap(h = 8)
            if (forgotMode) {
                Dim("Gib deinen GitHub-Token ein (der gleiche wie für den Sync). Danach ist die PIN entfernt.", 13)
                Gap(h = 12)
                field(token, { token = it }, "GitHub-Token (ghp_…)", password = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                error?.let { Gap(h = 8); Text(it, color = FlimmerColors.Accent, fontSize = 13.sp) }
                Gap(h = 16)
                Row {
                    FlimmerButton("Entsperren", primary = true) {
                        if (token.trim() == Sync.token.trim() && Sync.token.isNotBlank()) close(req.expected, forgot = true)
                        else error = "Token stimmt nicht"
                    }
                    Gap(w = 12)
                    FlimmerButton("Zurück") { forgotMode = false; error = null }
                }
                return@Column
            }
            Dim(
                when {
                    req.expected != null -> "4-stellige PIN eingeben"
                    first == null -> "Neue 4-stellige PIN eingeben"
                    else -> "PIN zur Bestätigung nochmals eingeben"
                }, 14
            )
            Gap(h = 16)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(18.dp).clip(CircleShape).background(if (i < input.length) Color.White else FlimmerColors.SurfaceHigh))
                }
            }
            // Unsichtbar wirkendes Eingabefeld (Tastatur und Ziffernblock)
            field(
                input,
                { v ->
                    val d = v.filter { it.isDigit() }.take(4)
                    input = d
                    error = null
                    if (d.length == 4) submit(d)
                },
                "PIN", password = true, modifier = Modifier.width(160.dp).padding(top = 14.dp).focusRequester(focus),
            )
            error?.let { Gap(h = 8); Text(it, color = FlimmerColors.Accent, fontSize = 13.sp, textAlign = TextAlign.Center) }
            Gap(h = 12)
            // Ziffern zum Anklicken
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            Box(
                                Modifier.size(width = 64.dp, height = 46.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (k.isEmpty()) Color.Transparent else FlimmerColors.SurfaceHigh)
                                    .then(if (k.isEmpty()) Modifier else Modifier.clickable {
                                        error = null
                                        if (k == "⌫") input = input.dropLast(1)
                                        else if (input.length < 4) {
                                            input += k
                                            if (input.length == 4) submit(input)
                                        }
                                    }.handCursor()),
                                contentAlignment = Alignment.Center,
                            ) { Text(k, color = Color.White, fontSize = 19.sp) }
                        }
                    }
                }
            }
            Gap(h = 16)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlimmerButton("Abbrechen") { close(null) }
                if (req.expected != null && req.allowForgot && Sync.token.isNotBlank()) {
                    Gap(w = 12)
                    TabText("PIN vergessen", false) { forgotMode = true; error = null }
                }
            }
        }
    }
}

/** Einstellungen: Profil, PIN, weitere Profile */
@Composable
fun ProfileSection() {
    val list by Profiles.list.collectAsState()
    Sync.version.collectAsState().value
    val cur = list.firstOrNull { it.keyIndex == Sync.profile } ?: list.first()
    var name by remember(cur.keyIndex, cur.name) { mutableStateOf(cur.name) }
    var newName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(cur, 48)
            Gap(w = 14)
            Column(Modifier.weight(1f)) {
                Text("Aktuelles Profil: ${cur.name}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Dim(if (cur.lockPin != null) "Mit PIN geschützt" else "Ohne PIN", 13)
            }
            if (list.size > 1) FlimmerButton("Profil wechseln") { ProfileUi.pickerOpen = true }
        }
        Gap(h = 14)
        Row(verticalAlignment = Alignment.CenterVertically) {
            field(name, { name = it.take(30) }, "Name", modifier = Modifier.weight(1f))
            Gap(w = 12)
            FlimmerButton("Name speichern") {
                if (name.isNotBlank()) { Profiles.update(cur.copy(name = name.trim())); message = "Name gespeichert" }
            }
        }
        Gap(h = 10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dim("Farbe:  ")
            Profiles.colors.forEachIndexed { i, c ->
                Box(
                    Modifier.padding(end = 8.dp).size(26.dp).clip(RoundedCornerShape(6.dp)).background(c)
                        .then(if (i == Math.floorMod(cur.defaultImageIndex, Profiles.colors.size)) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { Profiles.update(cur.copy(defaultImageIndex = i)) }.handCursor()
                )
            }
        }
        Gap(h = 12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (cur.lockPin == null) {
                FlimmerButton("PIN festlegen") {
                    ProfileUi.pin = ProfileUi.PinRequest("PIN für «${cur.name}» festlegen", null) { pin, _ ->
                        if (pin != null) { Profiles.update(cur.copy(lockPin = pin)); message = "PIN gespeichert" }
                    }
                }
            } else {
                FlimmerButton("PIN ändern") {
                    ProfileUi.pin = ProfileUi.PinRequest("Aktuelle PIN", cur.lockPin, allowForgot = false) { ok, _ ->
                        if (ok != null) {
                            ProfileUi.pin = ProfileUi.PinRequest("Neue PIN für «${cur.name}»", null) { pin, _ ->
                                if (pin != null) { Profiles.update(cur.copy(lockPin = pin)); message = "PIN geändert" }
                            }
                        }
                    }
                }
                Gap(w = 12)
                FlimmerButton("PIN entfernen") {
                    ProfileUi.pin = ProfileUi.PinRequest("PIN entfernen", cur.lockPin, allowForgot = false) { ok, _ ->
                        if (ok != null) { Profiles.update(cur.copy(lockPin = null)); message = "PIN entfernt" }
                    }
                }
            }
        }
        Gap(h = 6)
        Dim("Profile, Namen und PINs gelten auf allen Geräten mit dem gleichen Sync (PIN verschlüsselt im Gist).", 13)
        Gap(h = 16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            field(newName, { newName = it.take(30) }, "Name für ein neues Profil", modifier = Modifier.weight(1f))
            Gap(w = 12)
            FlimmerButton("Profil hinzufügen") {
                val a = Profiles.add(newName.trim())
                newName = ""
                message = "Profil «${a.name}» hinzugefügt"
            }
        }
        if (list.size > 1) {
            Gap(h = 10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                list.forEach { a ->
                    Row(
                        Modifier.padding(end = 14.dp).clip(RoundedCornerShape(8.dp)).clickable { ProfileUi.choose(a) }.handCursor().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(a, 26)
                        Gap(w = 6)
                        Text(a.name, color = if (a.keyIndex == cur.keyIndex) Color.White else FlimmerColors.TextDim, fontSize = 14.sp)
                    }
                }
            }
        }
        message?.let { Gap(h = 8); Dim(it, 13) }
    }
}
