package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen.toStringRes
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.isAppRestricted
import com.lagradost.cloudstream3.utils.CloudSync
import com.lagradost.cloudstream3.flimmer.FlimmerTrakt
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.showRequestIgnoreBatteryOptDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.lagradost.safefile.SafeFile
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SettingsGeneralScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_general)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        // TODO Refactor entirely to use a different file path selector ect like QuickNovel
        val selectFileSelector =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                // It lies, it can be null if file manager quits.
                if (uri == null) return@rememberLauncherForActivityResult
                val context = CloudStreamApp.context ?: return@rememberLauncherForActivityResult

                try {
                    val settings = AppSettings(context)
                    // RW perms for the path
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    val filePath = SafeFile.fromUri(context, uri)?.filePath()
                    println("Selected URI path: $uri - Full path: $filePath")

                    // store the actual URI instead of the path due to permissions.
                    // filePath should only be used for cosmetic purposes.
                    val visual = filePath ?: uri.toString()
                    settings.general.downloadPath.set(uri.toString())
                    settings.general.downloadPathVisual.set(visual)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

        val bananas by settings.general.bananas.collectAsState()
        val downloadPathVisual by settings.general.downloadPathVisual.collectAsState()
        //val downloadPath by settings.general.downloadPath.collectAsState()

        var isBatteryShown by remember { mutableStateOf(false) }
        val context = LocalContext.current

        // Cloud-Sync
        val syncScope = rememberCoroutineScope()
        val syncEnabled by settings.sync.enabled.collectAsState()
        val syncToken by settings.sync.token.collectAsState()
        val syncMode by settings.sync.mode.collectAsState()
        val syncIsServer = syncMode == CloudSync.MODE_SERVER
        var syncStatus by remember { mutableStateOf(CloudSync.statusText(context)) }
        var syncBusy by remember { mutableStateOf(false) }

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

        if (isBatteryShown) {
            ActionDialog(
                icon = painterResource(R.drawable.battery_alert_24px),
                title = stringResource(R.string.battery_dialog_title),
                text = stringResource(R.string.battery_dialog_message),
                confirmText = stringResource(R.string.ok),
                dismissText = stringResource(R.string.cancel),
                dismiss = {
                    isBatteryShown = false
                    settings.general.batterOptimization.set(false)
                },
                confirm = {
                    isBatteryShown = false
                    // The og impl never modified it to true?
                    // settings.general.batterOptimization.set(true)
                    context.showRequestIgnoreBatteryOptDialog()
                }
            )
        }

        val default = AllLanguagesName to stringResource(R.string.all_languages_preference)
        val languages = APIHolder.apis.withLock {
            APIHolder.apis.map { api -> api.lang }.distinct()
        }.sortedBy { fromTagToLanguageName(it) ?: it }

        val cloudSyncGroup = Preference.PreferenceGroup(
            title = "Cloud-Sync",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    title = "Fortschritt synchronisieren",
                    subtitle = "Position und «Weiterschauen» mit deinen anderen Geräten abgleichen",
                    icon = painterResource(R.drawable.baseline_sync_24),
                    preference = settings.sync.enabled,
                    onValueChanged = { on ->
                        settings.sync.enabled.set(on) // vor dem Start setzen, sonst sieht fullSync() noch «aus»
                        if (on) syncScope.launch(Dispatchers.IO) {
                            syncBusy = true
                            val n = CloudSync.fullSync()
                            syncStatus = CloudSync.statusText(context)
                            syncBusy = false
                            showToast(if (n == null) "Sync: $syncStatus" else "Sync aktiv, $n Einträge übernommen")
                        }
                        true
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    title = "Sync über",
                    icon = painterResource(R.drawable.cloud_2),
                    preference = settings.sync.mode,
                    entries = mapOf(
                        CloudSync.MODE_GIST to "GitHub Gist (kein Server nötig)",
                        CloudSync.MODE_SERVER to "Eigener Server",
                    ),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = "Server-Adresse",
                    subtitle = if (syncIsServer) "%s" else "Nur für «Eigener Server»",
                    enabled = syncIsServer,
                    preference = settings.sync.url,
                    onValueChanged = { v ->
                        val t = v.trim()
                        if (t.isNotEmpty() && !t.startsWith("https://") && !t.startsWith("http://")) {
                            showToast("Adresse muss mit https:// beginnen")
                            false
                        } else true
                    },
                ),
                Preference.PreferenceItem.EditTextPreference(
                    title = if (syncIsServer) "Zugangs-Token" else "GitHub-Token (Berechtigung «gist»)",
                    subtitle = if (syncToken.isBlank()) "Nicht gesetzt" else "Gesetzt (${syncToken.length} Zeichen)",
                    preference = settings.sync.token,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Verbindung testen",
                    subtitle = syncStatus,
                    icon = painterResource(R.drawable.wifi_proxy_24px),
                    enabled = !syncBusy,
                    onClick = {
                        syncScope.launch(Dispatchers.IO) {
                            syncBusy = true
                            val err = CloudSync.testConnection()
                            syncBusy = false
                            showToast(if (err == null) "Verbindung OK" else "Verbindung fehlgeschlagen: $err")
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Jetzt alles synchronisieren",
                    subtitle = if (syncBusy) "Läuft …" else "Lädt den ganzen Verlauf dieses Geräts hoch und holt den Stand der anderen",
                    icon = painterResource(R.drawable.baseline_sync_24),
                    enabled = syncEnabled && !syncBusy,
                    onClick = {
                        syncScope.launch(Dispatchers.IO) {
                            syncBusy = true
                            val n = CloudSync.fullSync()
                            syncStatus = CloudSync.statusText(context)
                            syncBusy = false
                            showToast(if (n == null) "Sync: $syncStatus" else "Fertig, $n Einträge übernommen")
                        }
                    },
                ),
            )
        )

        val traktGroup = Preference.PreferenceGroup(
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
                            syncScope.launch(Dispatchers.IO) {
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

        return persistentListOf(
            cloudSyncGroup,
            traktGroup,
            Preference.PreferenceGroup(title = stringResource(R.string.extension_language), preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = settings.general.locale,
                    entries = appLanguages.associate { (name, code) -> (code to (name to code).nameNextToFlagEmoji()) },
                    title = stringResource(R.string.app_language),
                    icon = painterResource(R.drawable.language_korean_latin_24px),
                    onValueChanged = { value ->
                        settings.general.locale.set(value)
                        activity?.recreate()
                        return@ListPreference false
                    },
                    subtitleProvider = { v, e -> e[v] ?: getCurrentLocale(LocalContext.current) }
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.provider_lang_settings),
                    icon = painterResource(R.drawable.plugin_lang),
                    entries = mapOf(default) + languages.associateWith { lang ->
                        (getNameNextToFlagEmoji(
                            lang
                        ) ?: lang)
                    },
                    preference = settings.provider.extensionLanguages
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.preferred_media_settings),
                    icon = painterResource(R.drawable.movie_edit_24px),
                    preference = settings.provider.preferredMedia,
                    entries = TvType.entries.associate {
                        it.ordinal.toString() to stringResource(it.toStringRes())
                    }
                        // Ok this looks strange af, but we do this to avoid double movie
                        .toPersistentMap().remove(TvType.AnimeMovie.ordinal.toString()), onValueChanged = { diff ->
                        if(diff.contains(TvType.Movie.ordinal.toString())) {
                            settings.provider.preferredMedia.set(diff + TvType.AnimeMovie.ordinal.toString())
                        } else {
                            settings.provider.preferredMedia.set(diff - TvType.AnimeMovie.ordinal.toString())
                        }
                        return@MultiSelectListPreference false
                    }),
            )),

            Preference.PreferenceGroup(
                title = stringResource(R.string.title_downloads),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        icon = painterResource(R.drawable.netflix_download),
                        subtitle = downloadPathVisual,
                        title = stringResource(R.string.download_path_pref),
                        onClick = {
                            // This is not a ListPreference because the old selection system is
                            // broken af. This needs to be refactored to QuickNovels download path
                            // system.
                            selectFileSelector.launch(Uri.EMPTY)
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_or_edge_24px),
                        preference = settings.general.parallelDownloads,
                        valueRange = 1..10,
                        title = stringResource(R.string.parallel_downloads),
                        subtitle = stringResource(R.string.download_parallel_settings_des),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_and_edge_24px),
                        preference = settings.general.concurrentConnections,
                        valueRange = 1..10,
                        title = stringResource(R.string.concurrent_connections),
                        subtitle = stringResource(R.string.concurrent_connections_settings_des),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.battery_dialog_title),
                        icon = painterResource(R.drawable.battery_alert_24px),
                        enabled = isLayout(PHONE),
                        onClick = {
                            if (isAppRestricted(context)) {
                                isBatteryShown = true
                            } else {
                                showToast(R.string.app_unrestricted_toast)
                            }
                        }
                    ),
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_bypass),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.add_site_pref),
                        subtitle = stringResource(R.string.add_site_summary),
                        icon = painterResource(R.drawable.copy_all_24px),
                        onClick = {
                            // TODO refactor into compose
                            if (SettingsGeneral.getCurrent().isEmpty()) {
                                SettingsGeneral.showAdd()
                            } else {
                                SettingsGeneral.showAddOrDelete()
                            }
                        }
                    ),
                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.dns_pref),
                        subtitle = stringResource(R.string.dns_pref_summary),
                        icon = painterResource(R.drawable.dns_24px),
                        preference = settings.general.dns,
                        entries = integerArrayResource(R.array.dns_pref_values).zip(
                            stringArrayResource(R.array.dns_pref)
                        ).toMap(),
                        onValueChanged = { newValue ->
                            settings.general.dns.set(newValue) // Must set the value before initClient is called
                            (CloudStreamApp.context)?.let { ctx ->
                                app.initClient(ctx, ignoreSSL = false)
                                @OptIn(UnsafeSSL::class)
                                insecureApp.initClient(ctx, ignoreSSL = true)
                            }
                            return@ListPreference true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.jsdelivr_proxy),
                        subtitle = stringResource(R.string.jsdelivr_proxy_summary),
                        icon = painterResource(R.drawable.wifi_proxy_24px),
                        preference = settings.general.jsdelivrProxy
                    )
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_links),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = "Flimmer auf GitHub",
                        subtitle = "github.com/hotihysen/cloudstream-sync",
                        icon = painterResource(R.drawable.ic_github_logo),
                        onClick = {
                            CloudStreamApp.openBrowser("https://github.com/hotihysen/cloudstream-sync")
                        }
                    ),
                )
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.benene),
                subtitle = if (bananas == 0) {
                    stringResource(R.string.benene_count_text_none)
                } else {
                    stringResource(R.string.benene_count_text, bananas)
                },
                onClick = {
                    settings.general.bananas.set(bananas + 1)
                },
                icon = painterResource(R.drawable.benene),
            ),
            Preference.PreferenceItem.InfoPreference(title = stringResource(R.string.legal_notice_text)),
        )
    }
}


@PreviewLightDark
@Composable
private fun SettingGeneralPreview() {
    CloudStreamPreviewTheme {
        SettingsGeneralScreen.Content()
    }
}
