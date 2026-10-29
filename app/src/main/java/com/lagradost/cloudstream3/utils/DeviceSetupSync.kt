package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Geräte-Einrichtung über den Gist: Repos, installierte Erweiterungen und wichtige Einstellungen.
 *
 * - Repos und Erweiterungen werden nur ergänzt, nie entfernt (ein Gerät mit weniger soll die anderen
 *   nicht leeren).
 * - Einstellungen: nur eine feste Liste, die auf jedem Gerät Sinn ergibt. Gerätespezifisches
 *   (Layout, Download-Ordner, Helligkeit, Puffer, Akku) bleibt auf dem Gerät.
 */
object DeviceSetupSync {
    private const val TAG = "DeviceSetupSync"

    /** Speicherorte der Momentaufnahmen im App-Speicher (werden als Sync-Einträge hochgeladen) */
    const val PATH_SETTINGS = "flimmer_sync_settings"
    const val PATH_PLUGINS = "flimmer_sync_plugins"
    const val PATH_REPOS = REPOSITORIES_KEY

    /** Einstellungen (Standard-Einstellungen von Android), die mitgehen */
    private val SETTINGS_KEYS = setOf(
        "app_locale", "search_providers_list", "search_type_list", "auto_update", "auto_update_plugins",
        "fast_forward_button_time", "quality_pref_key", "quality_pref_mobile_data_key", "player_default_key",
        "prefer_limit_title_key", "swipe_enabled_key", "playback_speed_enabled_key", "player_resize_enabled_key",
        "double_tap_enabled_key", "double_tap_pause_enabled_key", "double_tap_seek_time_key2",
        "android_tv_interface_off_seek_key", "android_tv_interface_on_seek_key", "swipe_vertical_enabled_key",
        "autoplay_next_key", "display_sub_key", "show_fillers_key", "show_player_metadata_key", "show_trailers_key",
        "show_kitsu_posters_key", "show_cast_in_details_key", "random_button_key", "provider_lang_key", "dns_key",
        "jsdelivr_proxy_key", "primary_color_key", "prefer_media_type_key_2", "app_theme_key",
        "episode_sync_enabled_key", "bottom_title_key", "poster_ui_key", "subtitles_encoding_key",
        "filter_sub_lang_key", "pref_filter_search_quality_key", "enable_nsfw_on_providers_key",
        "skip_startup_account_select_key", "show_hd_key", "show_dub_key", "show_sub_key", "show_rating_key",
        "show_title_key", "show_episode_text_key", "hide_player_control_names_key", "preview_seekbar_key",
        "confirm_exit_key", "show_name", "show_resolution", "show_media_info", "prefer_title_limit",
        "enable_skip_op_from_database", "player_source_priority_key", "tv_layout_clock_key",
    )

    /** Einträge im App-Speicher (z.B. Untertitel-Stil) */
    private val DATASTORE_KEYS = setOf("subtitle_settings", "subs_auto_select", "subs_auto_download")

    /** Flimmer-eigene Einstellungsdateien und ihre Schlüssel */
    private val FLIMMER_PREFS = mapOf(
        "flimmer_streams" to setOf("language"),
        "flimmer_trakt" to setOf("scrobble", "bookmarks"),
    )

    @Volatile private var applying = false
    private var listening = false
    // Android hält Listener nur schwach, deshalb hier festhalten
    private val listeners = mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    // ---------------------------------------------------------------- Momentaufnahmen

    private fun put(o: JSONObject, name: String, value: Any?) {
        val entry = JSONObject()
        when (value) {
            is Boolean -> entry.put("t", "b").put("v", value)
            is Int -> entry.put("t", "i").put("v", value)
            is Long -> entry.put("t", "l").put("v", value)
            is Float -> entry.put("t", "f").put("v", value.toDouble())
            is String -> entry.put("t", "s").put("v", value)
            is Set<*> -> entry.put("t", "set").put("v", org.json.JSONArray(value.map { it.toString() }))
            else -> return
        }
        o.put(name, entry)
    }

    /** Aktuelle Einstellungen als JSON in den App-Speicher schreiben */
    fun snapshotSettings(ctx: Context) {
        try {
            val o = JSONObject()
            val def = PreferenceManager.getDefaultSharedPreferences(ctx).all
            SETTINGS_KEYS.forEach { k -> def[k]?.let { put(o, "p:$k", it) } }
            val store = ctx.getSharedPrefs()
            DATASTORE_KEYS.forEach { k -> store.getString(k, null)?.let { put(o, "d:$k", it) } }
            FLIMMER_PREFS.forEach { (file, keys) ->
                val all = ctx.getSharedPreferences(file, Context.MODE_PRIVATE).all
                keys.forEach { k -> all[k]?.let { put(o, "f:$file:$k", it) } }
            }
            store.edit { putString(PATH_SETTINGS, o.toString()) }
        } catch (t: Throwable) {
            Log.w(TAG, "snapshotSettings", t)
        }
    }

    /** Installierte Erweiterungen (Name, Datei-Adresse, Repo) als JSON in den App-Speicher schreiben */
    fun snapshotPlugins(ctx: Context) {
        try {
            val repos = RepositoryManager.getRepositories()
            val list = PluginManager.getPluginsOnline().mapNotNull { p ->
                val url = p.url ?: return@mapNotNull null
                val repo = repos.firstOrNull { r ->
                    PluginManager.getPluginPath(ctx, p.internalName, r.url).absolutePath == p.filePath
                } ?: return@mapNotNull null
                mapOf("n" to p.internalName, "u" to url, "r" to repo.url)
            }.sortedBy { it["n"] }
            ctx.getSharedPrefs().edit { putString(PATH_PLUGINS, list.toJson()) }
        } catch (t: Throwable) {
            Log.w(TAG, "snapshotPlugins", t)
        }
    }

    /** Vergleichbare Form einer Momentaufnahme (Reihenfolge der Schlüssel egal) */
    private fun canonical(json: String?): String? = try {
        json?.let { j -> val o = JSONObject(j); o.keys().asSequence().sorted().joinToString("|") { "$it=${o.get(it)}" } }
    } catch (t: Throwable) {
        json
    }

    // ---------------------------------------------------------------- Übernehmen

    fun applySettings(ctx: Context, json: String) {
        applying = true
        try {
            val o = JSONObject(json)
            val def = PreferenceManager.getDefaultSharedPreferences(ctx)
            val store = ctx.getSharedPrefs()
            val defEdit = def.edit()
            val storeEdit = store.edit()
            val files = HashMap<String, SharedPreferences.Editor>()
            for (name in o.keys()) {
                val e = o.optJSONObject(name) ?: continue
                val editor = when {
                    name.startsWith("p:") -> defEdit
                    name.startsWith("d:") -> storeEdit
                    name.startsWith("f:") -> files.getOrPut(name.split(":")[1]) {
                        ctx.getSharedPreferences(name.split(":")[1], Context.MODE_PRIVATE).edit()
                    }
                    else -> continue
                }
                val key = name.substringAfterLast(":")
                if (name.startsWith("p:") && key !in SETTINGS_KEYS) continue
                when (e.optString("t")) {
                    "b" -> editor.putBoolean(key, e.getBoolean("v"))
                    "i" -> editor.putInt(key, e.getInt("v"))
                    "l" -> editor.putLong(key, e.getLong("v"))
                    "f" -> editor.putFloat(key, e.getDouble("v").toFloat())
                    "s" -> editor.putString(key, e.getString("v"))
                    "set" -> {
                        val arr = e.getJSONArray("v")
                        editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                    }
                }
            }
            defEdit.commit()
            storeEdit.commit()
            files.values.forEach { it.commit() }
            // Tatsächlichen Stand festhalten, damit die Änderungs-Meldungen nichts zurückschicken
            snapshotSettings(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "applySettings", t)
        } finally {
            applying = false
        }
    }

    /** Repos der anderen Geräte ergänzen (nichts entfernen). Gibt die Anzahl neuer Repos zurück. */
    fun applyRepos(json: String): Int = try {
        val remote = parseJson<Array<RepositoryData>>(json)
        val local = RepositoryManager.getRepositories()
        val added = remote.filter { r -> local.none { it.url == r.url } }
        if (added.isNotEmpty()) {
            applying = true
            CloudStreamApp.setKey(REPOSITORIES_KEY, (local + added).toTypedArray())
            applying = false
        }
        added.size
    } catch (t: Throwable) {
        applying = false
        Log.w(TAG, "applyRepos", t)
        0
    }

    private val installLock = Mutex()
    @Volatile var pendingPlugins: String? = null

    /** Fehlende Erweiterungen der anderen Geräte installieren. Gibt die Anzahl neu installierter zurück. */
    suspend fun installMissingPlugins(json: String? = pendingPlugins): Int = installLock.withLock {
        json ?: return@withLock 0
        val activity = CommonActivity.activity ?: return@withLock 0 // später erneut
        val wanted = try {
            parseJson<List<Map<String, String>>>(json)
        } catch (t: Throwable) {
            return@withLock 0
        }
        val installedNames = PluginManager.getPluginsOnline().map { it.internalName }.toSet()
        var count = 0
        applying = true
        try {
            for (p in wanted) {
                val name = p["n"] ?: continue
                val url = p["u"] ?: continue
                val repo = p["r"] ?: continue
                if (name in installedNames) continue
                if (PluginManager.getPluginPath(activity, name, repo).exists()) continue
                try {
                    if (PluginManager.downloadPlugin(activity, url, null, name, repo, true)) count++
                } catch (t: Throwable) {
                    Log.w(TAG, "install $name", t)
                }
            }
        } finally {
            applying = false
        }
        if (count > 0) snapshotPlugins(activity)
        if (json == pendingPlugins) pendingPlugins = null
        count
    }

    // ---------------------------------------------------------------- Änderungen bemerken

    fun onReposChanged() {
        if (applying) return
        CloudSync.onRepos()
    }

    fun onPluginsChanged() {
        if (applying) return
        CloudStreamApp.context?.let { snapshotPlugins(it) }
        CloudSync.onPlugins()
    }

    /** Einmal beim Start: Änderungen an den mitgehenden Einstellungen vormerken */
    fun startListening(ctx: Context) {
        if (listening) return
        listening = true
        fun watch(prefs: SharedPreferences, accept: (String) -> Boolean) {
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (applying || key == null || !accept(key)) return@OnSharedPreferenceChangeListener
                // Nur echte Änderungen weitergeben (sonst schicken sich Geräte dieselben Werte hin und her)
                val before = canonical(ctx.getSharedPrefs().getString(PATH_SETTINGS, null))
                snapshotSettings(ctx)
                val after = canonical(ctx.getSharedPrefs().getString(PATH_SETTINGS, null))
                if (before != after) CloudSync.onSettings()
            }
            prefs.registerOnSharedPreferenceChangeListener(l)
            listeners += prefs to l
        }
        watch(PreferenceManager.getDefaultSharedPreferences(ctx)) { it in SETTINGS_KEYS }
        watch(ctx.getSharedPrefs()) { it in DATASTORE_KEYS }
        FLIMMER_PREFS.forEach { (file, keys) -> watch(ctx.getSharedPreferences(file, Context.MODE_PRIVATE)) { it in keys } }
    }
}
