package com.lagradost.cloudstream3.flimmer

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
        title = "Trakt-Optionen",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = if (loggedIn) "Trakt verbunden" else "Trakt nicht verbunden",
                subtitle = when {
                    !FlimmerTrakt.isConfigured -> "Trakt-Client-ID fehlt im Build"
                    loggedIn -> "Angemeldet als ${user ?: "Trakt-Nutzer"}. Bibliothek: Reiter «Bibliothek», oben «Trakt» wählen."
                    else -> "Hier tippen und mit Code auf trakt.tv/activate anmelden."
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
