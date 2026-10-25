package com.lagradost.cloudstream3.flimmer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.compose.ActionDialog
import com.mihon.presentation.settings.Preference
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Einstellungsgruppe «Trakt»; erscheint unter Konten und unter Allgemein. */
@Composable
fun flimmerTraktGroup(): Preference.PreferenceGroup {
    val traktScope = rememberCoroutineScope()
    // Trakt
    var traktLoggedIn by remember { mutableStateOf(FlimmerTrakt.isLoggedIn()) }
    var traktUser by remember { mutableStateOf(FlimmerTrakt.userName()) }
    var traktScrobble by remember { mutableStateOf(FlimmerTrakt.scrobbleEnabled) }
    var traktCode by remember { mutableStateOf<FlimmerTrakt.DeviceCode?>(null) }
    var traktCancelled by remember { mutableStateOf(false) }

    traktCode?.let { code ->
        ActionDialog(
            title = "Mit Trakt verbinden",
            text = "Öffne auf dem Handy oder PC\n\n${code.verificationUrl}\n\nund gib diesen Code ein:\n\n${code.userCode}\n\nDieses Fenster schliesst sich automatisch, sobald du bestätigt hast.",
            confirmText = "Seite öffnen",
            dismissText = stringResource(R.string.cancel),
            dismiss = {
                traktCancelled = true
                traktCode = null
            },
            confirm = {
                CloudStreamApp.openBrowser(code.verificationUrl)
            },
        )
    }

    return Preference.PreferenceGroup(
        title = "Trakt",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = if (traktLoggedIn) "Trakt trennen" else "Mit Trakt verbinden",
                subtitle = when {
                    !FlimmerTrakt.isConfigured -> "Trakt-Client-ID fehlt im Build"
                    traktLoggedIn -> "Verbunden" + (traktUser?.let { " als $it" } ?: "")
                    else -> "Anmeldung mit Code über trakt.tv/activate"
                },
                icon = painterResource(R.drawable.baseline_sync_24),
                enabled = FlimmerTrakt.isConfigured,
                onClick = {
                    if (traktLoggedIn) {
                        FlimmerTrakt.logout()
                        traktLoggedIn = false
                        traktUser = null
                        showToast("Trakt getrennt")
                    } else {
                        traktScope.launch(Dispatchers.IO) {
                            try {
                                val code = FlimmerTrakt.requestDeviceCode()
                                traktCancelled = false
                                traktCode = code
                                val err = FlimmerTrakt.pollDeviceToken(code) { traktCancelled }
                                traktCode = null
                                if (err == null) {
                                    traktLoggedIn = true
                                    traktUser = FlimmerTrakt.userName()
                                    showToast("Mit Trakt verbunden")
                                } else if (!traktCancelled) {
                                    showToast("Trakt: $err")
                                }
                            } catch (t: Throwable) {
                                traktCode = null
                                showToast("Trakt: ${t.message}")
                            }
                        }
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = "Scrobbeln",
                subtitle = if (traktScrobble) "An: Titel aus dem Flimmer Katalog werden an Trakt gemeldet und ab 80 % als gesehen markiert"
                else "Aus",
                icon = painterResource(R.drawable.wifi_proxy_24px),
                enabled = traktLoggedIn,
                onClick = {
                    traktScrobble = !traktScrobble
                    FlimmerTrakt.scrobbleEnabled = traktScrobble
                },
            ),
        )
    )

}
