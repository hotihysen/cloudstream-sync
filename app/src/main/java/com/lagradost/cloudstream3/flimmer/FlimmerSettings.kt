package com.lagradost.cloudstream3.flimmer

import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.addAccount
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.showLoginInfo
import com.mihon.presentation.settings.Preference
import kotlinx.collections.immutable.persistentListOf

/**
 * Einstellungsgruppe «Trakt-Optionen». Die Anmeldung selbst erfolgt unter Konten bei «Trakt»
 * (wie Simkl oder AniList), danach erscheint Trakt auch in der Bibliothek.
 */
@Composable
fun flimmerTraktGroup(): Preference.PreferenceGroup {
    val loggedIn = FlimmerTrakt.isLoggedIn()
    val user = FlimmerTrakt.userName()
    var scrobble by remember { mutableStateOf(FlimmerTrakt.scrobbleEnabled) }
    var bookmarks by remember { mutableStateOf(FlimmerTrakt.bookmarksEnabled) }

    return Preference.PreferenceGroup(
        title = "Trakt-Optionen · Profil «${com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount()?.name ?: "Standard"}»",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = if (loggedIn) "Trakt verbunden" else "Trakt nicht verbunden",
                subtitle = when {
                    !FlimmerTrakt.isConfigured -> "Trakt-Client-ID fehlt im Build"
                    loggedIn -> "Angemeldet als ${user ?: "Trakt-Nutzer"}. Bibliothek: Reiter «Bibliothek», oben «Trakt» wählen."
                    else -> "Hier tippen und mit Code auf trakt.tv/activate anmelden. Jedes Profil kann ein eigenes Trakt-Konto haben."
                },
                icon = painterResource(R.drawable.ic_trakt),
                enabled = FlimmerTrakt.isConfigured,
                onClick = {
                    val act = activity ?: return@TextPreference
                    val repo = AccountManager.syncApis.firstOrNull { it.idPrefix == AccountManager.traktApi.idPrefix }
                        ?: return@TextPreference
                    val info = repo.authUser()
                    if (repo.accounts.isNotEmpty()) {
                        val index = repo.accounts.indexOfFirst { it.user.id == info?.id }
                        showLoginInfo(act, repo, info, index)
                    } else {
                        addAccount(act, repo)
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Scrobbeln",
                subtitle = if (scrobble) "An: Was du schaust, wird an Trakt gemeldet. Ab 10 Minuten steht es unter «Am schauen», ab 80 % gilt es als gesehen"
                else "Aus",
                icon = painterResource(R.drawable.baseline_sync_24),
                enabled = loggedIn,
                onClick = {
                    scrobble = !scrobble
                    FlimmerTrakt.scrobbleEnabled = scrobble
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Jetzt synchronisieren",
                subtitle = "Trakt-Listen und Fortschritt sofort neu holen (sonst automatisch beim Öffnen der Bibliothek)",
                icon = painterResource(R.drawable.ic_refresh),
                enabled = loggedIn,
                onClick = {
                    AccountManager.traktApi.forceRefresh()
                    com.lagradost.cloudstream3.CommonActivity.showToast("Trakt wird synchronisiert …", android.widget.Toast.LENGTH_SHORT)
                    Unit.ioSafe {
                        val repo = AccountManager.syncApis.firstOrNull { it.idPrefix == AccountManager.traktApi.idPrefix }
                        val ok = repo?.library()?.isSuccess == true
                        AccountManager.traktApi.requireLibraryRefresh = true
                        com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread {
                            com.lagradost.cloudstream3.CommonActivity.showToast(
                                if (ok) "Trakt ist auf dem neusten Stand" else "Trakt nicht erreichbar",
                                android.widget.Toast.LENGTH_SHORT
                            )
                        }
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Letzte Meldung an Trakt",
                subtitle = FlimmerTrakt.lastReport ?: "Noch keine in dieser Sitzung. Nach dem Schauen steht hier, ob Trakt die Meldung angenommen hat.",
                icon = painterResource(R.drawable.ic_trakt),
                enabled = loggedIn,
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Lesezeichen an Trakt senden",
                subtitle = if (bookmarks) "An: «Später ansehen», «Am Schauen», «Abgeschlossen» usw. werden zu Trakt übertragen"
                else "Aus",
                icon = painterResource(R.drawable.ic_baseline_bookmark_24),
                enabled = loggedIn,
                onClick = {
                    bookmarks = !bookmarks
                    FlimmerTrakt.bookmarksEnabled = bookmarks
                },
            ),
        )
    )
}

/** Einstellungsgruppe «Stream-Auswahl» für den Flimmer Katalog. */
@Composable
fun flimmerStreamGroup(): Preference.PreferenceGroup {
    var lang by remember { mutableStateOf(FlimmerStreams.language) }
    val order = listOf(FlimmerStreams.LANG_DE, FlimmerStreams.LANG_ORIGINAL, FlimmerStreams.LANG_ANY)
    return Preference.PreferenceGroup(
        title = "Stream-Auswahl (Flimmer Katalog)",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = "Sprache",
                subtitle = FlimmerStreams.languageLabel(lang) + " (antippen zum Wechseln)",
                icon = painterResource(R.drawable.baseline_sync_24),
                onClick = {
                    val next = order[(order.indexOf(lang) + 1) % order.size]
                    lang = next
                    FlimmerStreams.language = next
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Letzte Quellensuche",
                subtitle = FlimmerStreams.lastReport ?: "Noch keine Suche. Spiele einen Titel aus dem Flimmer Katalog ab.",
                icon = painterResource(R.drawable.search_icon),
            ),
            Preference.PreferenceItem.InfoPreference(
                title = "Flimmer sortiert Streams nach Sprache, Qualität und danach, welche Quellen bei dir zuverlässig starten. " +
                        "Nach einem sehr guten Treffer startet die Wiedergabe nach kurzer Zeit, sonst wird bis zu 10 Sekunden auf bessere Quellen gewartet."
            ),
        )
    )
}

/** Einstellungsgruppe «Profil»: 4-stellige PIN für das aktuelle Profil (funktioniert auch auf dem Fernseher) */
@Composable
fun flimmerProfileGroup(): Preference.PreferenceGroup {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val current = com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount()
        ?: com.lagradost.cloudstream3.utils.DataStoreHelper.getDefaultAccount(ctx)
    var hasPin by remember(current.keyIndex) { mutableStateOf(current.lockPin != null) }

    fun save(pin: String?) {
        val acc = (com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount() ?: current).copy(lockPin = pin)
        val list = com.lagradost.cloudstream3.utils.DataStoreHelper.accounts.filter { it.keyIndex != acc.keyIndex } + acc
        com.lagradost.cloudstream3.utils.DataStoreHelper.accounts = list.sortedBy { it.keyIndex }.toTypedArray()
        hasPin = pin != null
    }

    val items = mutableListOf<Preference.PreferenceItem<out Any?, out Any?>>()
    if (!hasPin) {
        items += Preference.PreferenceItem.TextPreference(
            title = "PIN festlegen",
            subtitle = "Profil «${current.name}» mit einer 4-stelligen PIN schützen (zweimal eingeben). Sie wird beim Start und beim Wechsel ins Profil abgefragt",
            icon = painterResource(R.drawable.video_locked),
            onClick = {
                com.lagradost.cloudstream3.ui.account.AccountHelper.showNewPinDialog(ctx) { pin ->
                    if (pin != null && pin.length == 4) {
                        save(pin)
                        com.lagradost.cloudstream3.CommonActivity.showToast("PIN gesetzt", android.widget.Toast.LENGTH_SHORT)
                    }
                }
            },
        )
    } else {
        items += Preference.PreferenceItem.TextPreference(
            title = "PIN ändern",
            subtitle = "Zuerst die aktuelle PIN, dann die neue eingeben",
            icon = painterResource(R.drawable.video_locked),
            onClick = {
                val currentPin = com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount()?.lockPin
                com.lagradost.cloudstream3.ui.account.AccountHelper.showPinInputDialog(ctx, currentPin, true) { old ->
                    if (old == null || old != currentPin) return@showPinInputDialog
                    com.lagradost.cloudstream3.ui.account.AccountHelper.showNewPinDialog(ctx) { pin ->
                        if (pin != null && pin.length == 4) {
                            save(pin)
                            com.lagradost.cloudstream3.CommonActivity.showToast("PIN geändert", android.widget.Toast.LENGTH_SHORT)
                        }
                    }
                }
            },
        )
        items += Preference.PreferenceItem.TextPreference(
            title = "PIN entfernen",
            subtitle = "Profil «${current.name}» wieder ohne PIN öffnen",
            icon = painterResource(R.drawable.video_unlocked),
            onClick = {
                val currentPin = com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount()?.lockPin
                com.lagradost.cloudstream3.ui.account.AccountHelper.showPinInputDialog(ctx, currentPin, true) { old ->
                    if (old != null && old == currentPin) {
                        save(null)
                        com.lagradost.cloudstream3.CommonActivity.showToast("PIN entfernt", android.widget.Toast.LENGTH_SHORT)
                    }
                }
            },
        )
    }
    items += Preference.PreferenceItem.InfoPreference(
        title = "Die PIN gilt für dieses Profil auf allen Geräten (über den Gist-Sync). PIN vergessen? Beim Entsperren «PIN vergessen» wählen und den Gist-Token eingeben. " +
                "Die Sperre mit Fingerabdruck unter «Sicherheit» schützt dagegen die ganze App und nur auf diesem Gerät."
    )
    return Preference.PreferenceGroup(
        title = "Profil «${current.name}»",
        preferenceItems = items.toList(),
    )
}

