package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Cloud-Sync: gleicht Wiedergabeposition, «Weiterschauen»-Liste, Titel-Infos und
 * Gesehen-Markierungen zwischen Geräten ab.
 *
 * Zwei Varianten (Einstellung «Sync über»):
 * - GitHub Gist: Ein privater Gist im GitHub-Konto. Jedes Gerät schreibt nur seine eigene
 *   Datei «device-<id>-p<profil>.json» und liest die Dateien der anderen Geräte. Dadurch
 *   können sich Geräte nie gegenseitig überschreiben. Der Gist wird anhand der Datei
 *   «cloudstream-sync.json» gefunden oder beim ersten Mal automatisch angelegt.
 * - Eigener Server: siehe Ordner sync-server.
 *
 * Lokale Änderungen werden über [markDirty] vorgemerkt und gebündelt hochgeladen. Beim
 * Öffnen der App, jede Minute im Vordergrund und vor dem Laden von «Weiterschauen» wird
 * geholt. Es gewinnt jeweils der jüngste Stand (Zeitstempel).
 */
object CloudSync {
    private const val TAG = "CloudSync"
    private const val PREFS = "cloud_sync"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_SEQ = "last_seq"
    private const val KEY_SEQ_OWNER = "last_seq_owner"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_GIST_ID = "gist_id"
    private const val KEY_GIST_OWNER = "gist_owner"
    private const val KEY_GIST_OWN = "gist_own"
    private const val KEY_GIST_OWN_FOR = "gist_own_for"
    private const val TIME_PREFIX = "t/"

    const val MODE_GIST = "gist"
    const val MODE_SERVER = "server"

    private const val GITHUB_API = "https://api.github.com"
    private const val GIST_MARKER = "cloudstream-sync.json"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val PUSH_DELAY_SERVER_MS = 8_000L
    private const val PUSH_DELAY_GIST_MS = 30_000L
    private const val PULL_MIN_INTERVAL_MS = 5_000L
    private const val NET_TIMEOUT_MS = 10_000L
    private const val FOREGROUND_PULL_MS = 60_000L

    /** Logische Schlüsseltypen «typ/id». */
    private const val T_POS = "pos"        // Wiedergabeposition einer Episode / eines Films
    private const val T_RESUME = "resume"  // Eintrag in «Weiterschauen» (pro Serie/Film)
    private const val T_HEADER = "header"  // Titel, Poster, URL, Anbieter (für die Kachel)
    private const val T_WATCH = "ws"       // Gesehen-Markierung pro Episode
    private const val T_BOOKMARK = "bm"    // Lesezeichen-Status (Geplant, Am Schauen, …) pro Titel
    private const val T_BOOKMARK_DATA = "bmd" // Kachel-Daten zum Lesezeichen
    private const val T_FAVORITE = "fav"   // Favoriten
    private const val T_SUBSCRIPTION = "sub" // Abos (neue Folgen)
    private const val T_PROFILES = "prof"  // Profile mit Namen, Bild und PIN (für alle Profile gemeinsam)
    private const val PROFILES_KEY = "$T_PROFILES/0"

    @Serializable
    data class Record(
        @JsonProperty("k") @SerialName("k") val k: String,
        @JsonProperty("v") @SerialName("v") val v: String? = null,
        @JsonProperty("t") @SerialName("t") val t: Long = 0,
        @JsonProperty("d") @SerialName("d") val d: Boolean = false,
    )

    @Serializable
    data class PushBody(
        @JsonProperty("device") @SerialName("device") val device: String,
        @JsonProperty("records") @SerialName("records") val records: List<Record>,
    )

    @Serializable
    data class PullResponse(
        @JsonProperty("seq") @SerialName("seq") val seq: Long = 0,
        @JsonProperty("records") @SerialName("records") val records: List<Record> = emptyList(),
    )

    // --- GitHub Gist

    @Serializable
    data class GistFile(
        @JsonProperty("filename") @SerialName("filename") val filename: String? = null,
        @JsonProperty("content") @SerialName("content") val content: String? = null,
        @JsonProperty("truncated") @SerialName("truncated") val truncated: Boolean = false,
        @JsonProperty("raw_url") @SerialName("raw_url") val rawUrl: String? = null,
    )

    @Serializable
    data class Gist(
        @JsonProperty("id") @SerialName("id") val id: String,
        @JsonProperty("files") @SerialName("files") val files: Map<String, GistFile> = emptyMap(),
    )

    @Serializable
    data class GistContent(
        @JsonProperty("content") @SerialName("content") val content: String,
    )

    @Serializable
    data class GistWrite(
        @JsonProperty("description") @SerialName("description") val description: String? = null,
        @JsonProperty("public") @SerialName("public") val public: Boolean? = null,
        @JsonProperty("files") @SerialName("files") val files: Map<String, GistContent>,
    )

    @Serializable
    data class DeviceFile(
        @JsonProperty("device") @SerialName("device") val device: String = "",
        @JsonProperty("updated") @SerialName("updated") val updated: Long = 0,
        @JsonProperty("records") @SerialName("records") val records: Map<String, Record> = emptyMap(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pushJob: Job? = null
    private var foregroundJob: Job? = null
    @Volatile private var lastPull = 0L

    // ---------------------------------------------------------------- Einstellungen

    private val context: Context? get() = CloudStreamApp.context

    private fun settings(ctx: Context) = AppSettings(ctx).sync

    private fun mode(ctx: Context) =
        if (settings(ctx).mode.get() == MODE_SERVER) MODE_SERVER else MODE_GIST

    private fun token(ctx: Context) = settings(ctx).token.get().trim()

    fun isEnabled(ctx: Context? = context): Boolean {
        ctx ?: return false
        val s = settings(ctx)
        if (!s.enabled.get() || token(ctx).isBlank()) return false
        return mode(ctx) == MODE_GIST || s.url.get().isNotBlank()
    }

    private fun baseUrl(ctx: Context) = settings(ctx).url.get().trim().trimEnd('/')

    private fun serverHeaders(ctx: Context) = mapOf(
        "Authorization" to "Bearer ${token(ctx)}",
        "Content-Type" to "application/json",
        "Cache-Control" to "no-cache",
    )

    private fun githubHeaders(ctx: Context) = mapOf(
        "Authorization" to "Bearer ${token(ctx)}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "Content-Type" to "application/json",
        "Cache-Control" to "no-cache",
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Schlüssel für die PIN im Gist: aus Gist-ID bzw. Server-Adresse (auf allen Geräten gleich) */
    private fun cryptKey(ctx: Context): String =
        if (mode(ctx) == MODE_GIST) "gist:" + (prefs(ctx).getString(KEY_GIST_ID, null) ?: "") else "server:" + baseUrl(ctx)

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            p.edit { putString(KEY_DEVICE, it) }
        }
    }

    /** Server oder Token geändert: von vorne synchronisieren. */
    private fun seqOwner(ctx: Context) =
        "${baseUrl(ctx)}|${token(ctx)}|${DataStoreHelper.currentAccount}"

    private fun lastSeq(ctx: Context): Long {
        val p = prefs(ctx)
        return if (p.getString(KEY_SEQ_OWNER, null) == seqOwner(ctx)) p.getLong(KEY_SEQ, 0) else 0
    }

    private fun setLastSeq(ctx: Context, seq: Long) = prefs(ctx).edit {
        putString(KEY_SEQ_OWNER, seqOwner(ctx))
        putLong(KEY_SEQ, seq)
    }

    fun statusText(ctx: Context): String {
        val p = prefs(ctx)
        val err = p.getString(KEY_LAST_ERROR, null)
        val ok = p.getLong(KEY_LAST_OK, 0)
        return when {
            !isEnabled(ctx) -> "Aus"
            err != null -> "Fehler: $err"
            ok > 0 -> "Zuletzt synchronisiert: " +
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                    ).format(java.util.Date(ok))
            else -> "Noch nicht synchronisiert"
        }
    }

    private fun setStatus(ctx: Context, error: String?) = prefs(ctx).edit {
        if (error == null) {
            remove(KEY_LAST_ERROR)
            putLong(KEY_LAST_OK, System.currentTimeMillis())
        } else {
            putString(KEY_LAST_ERROR, error)
        }
    }

    private fun httpError(code: Int): String = when (code) {
        401 -> "Token ungültig oder abgelaufen"
        403 -> "Kein Zugriff (Token-Berechtigung «gist» fehlt?) oder Limit erreicht"
        404 -> "Nicht gefunden"
        else -> "HTTP $code"
    }

    // ---------------------------------------------------------------- Speicherorte

    /** Ordnet einen logischen Schlüssel dem Pfad im Cloudstream-Speicher zu. */
    private fun storagePath(key: String): String? {
        val type = key.substringBefore('/')
        val id = key.substringAfter('/', "")
        if (id.isEmpty() || id.toIntOrNull() == null) return null
        val acc = DataStoreHelper.currentAccount
        return when (type) {
            T_POS -> "$acc/$VIDEO_POS_DUR/$id"
            T_RESUME -> "$acc/$RESULT_RESUME_WATCHING/$id"
            T_WATCH -> "$acc/$VIDEO_WATCH_STATE/$id"
            T_HEADER -> "$DOWNLOAD_HEADER_CACHE/$id"
            T_BOOKMARK -> "$acc/$RESULT_WATCH_STATE/$id"
            T_BOOKMARK_DATA -> "$acc/$RESULT_WATCH_STATE_DATA/$id"
            T_FAVORITE -> "$acc/$RESULT_FAVORITES_STATE_DATA/$id"
            T_SUBSCRIPTION -> "$acc/$RESULT_SUBSCRIBED_STATE_DATA/$id"
            T_PROFILES -> DataStoreHelper.PROFILES_STORAGE_KEY
            else -> null
        }
    }

    // ---------------------------------------------------------------- Lokale Änderungen

    fun onViewPos(id: Int?) = markDirty(T_POS, id)
    fun onBookmark(id: Int?) {
        markDirty(T_BOOKMARK, id)
        markDirty(T_BOOKMARK_DATA, id)
    }
    fun onFavorite(id: Int?) = markDirty(T_FAVORITE, id)
    fun onSubscription(id: Int?) = markDirty(T_SUBSCRIPTION, id)
    fun onProfiles() = markDirty(T_PROFILES, 0)
    fun onWatchState(id: Int?) = markDirty(T_WATCH, id)
    fun onResume(parentId: Int?) {
        markDirty(T_RESUME, parentId)
        markDirty(T_HEADER, parentId)
    }

    private fun markDirty(type: String, id: Int?) {
        id ?: return
        val ctx = context ?: return
        if (!isEnabled(ctx)) return
        val key = "$type/$id"
        val now = System.currentTimeMillis()
        try {
            val p = prefs(ctx)
            val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
            pending.add(key)
            p.edit {
                putStringSet(KEY_PENDING, pending)
                putLong(TIME_PREFIX + key, now)
            }
        } catch (t: Throwable) {
            logError(t)
        }
        schedulePush(ctx)
    }

    private fun schedulePush(ctx: Context) {
        if (pushJob?.isActive == true) return
        val wait = if (mode(ctx) == MODE_GIST) PUSH_DELAY_GIST_MS else PUSH_DELAY_SERVER_MS
        pushJob = scope.launch {
            delay(wait)
            push()
        }
    }

    /** Sofort hochladen, z.B. wenn die App in den Hintergrund geht. */
    fun flushNow() {
        val ctx = context ?: return
        if (!isEnabled(ctx)) return
        pushJob?.cancel()
        pushJob = scope.launch { push() }
    }

    // ---------------------------------------------------------------- Upload

    suspend fun push(): Boolean = lock.withLock {
        val ctx = context ?: return@withLock false
        if (!isEnabled(ctx)) return@withLock false
        val p = prefs(ctx)
        val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toSet()
        if (pending.isEmpty()) return@withLock true

        val store = ctx.getSharedPrefs()
        val records = pending.mapNotNull { key ->
            val path = storagePath(key) ?: return@mapNotNull null
            var raw = store.getString(path, null)
            if (key == PROFILES_KEY && raw != null) raw = ProfileCrypt.protect(raw, cryptKey(ctx))
            Record(k = key, v = raw, t = p.getLong(TIME_PREFIX + key, System.currentTimeMillis()), d = raw == null)
        }

        val ok = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                if (mode(ctx) == MODE_GIST) gistPush(ctx, records) else serverPush(ctx, records)
                true
            } ?: throw IllegalStateException("Zeitüberschreitung")
        } catch (t: Throwable) {
            Log.w(TAG, "push failed", t)
            setStatus(ctx, t.message ?: t.javaClass.simpleName)
            false
        }

        if (ok) {
            // Nur entfernen, was hochgeladen wurde; neue Änderungen bleiben vorgemerkt.
            val now = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
            now.removeAll(pending)
            p.edit { putStringSet(KEY_PENDING, now) }
            setStatus(ctx, null)
        }
        ok
    }

    private suspend fun serverPush(ctx: Context, records: List<Record>) {
        val res = app.post(
            "${baseUrl(ctx)}/v1/push",
            headers = serverHeaders(ctx),
            requestBody = PushBody(deviceId(ctx), records).toJson().toRequestBody(JSON_TYPE),
        )
        if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
    }

    // ---------------------------------------------------------------- Download

    /**
     * Holt neue Einträge und schreibt sie in den lokalen Speicher.
     * @param notify Startseite neu laden, wenn sich etwas geändert hat
     * @return Anzahl übernommener Einträge, oder null bei Fehler / deaktiviert
     */
    suspend fun pull(notify: Boolean = false, force: Boolean = false): Int? {
        val ctx = context ?: return null
        if (!isEnabled(ctx)) return null
        val nowMs = System.currentTimeMillis()
        if (!force && nowMs - lastPull < PULL_MIN_INTERVAL_MS) return 0
        lastPull = nowMs

        // Eigene offene Änderungen zuerst hochladen, damit sie nicht überschrieben werden.
        push()

        return lock.withLock {
            try {
                val gist = mode(ctx) == MODE_GIST
                var newSeq: Long? = null
                val records = withTimeoutOrNull(NET_TIMEOUT_MS * 2) {
                    if (gist) {
                        gistFetch(ctx)
                    } else {
                        val r = serverFetch(ctx, lastSeq(ctx), nowMs)
                        newSeq = r.seq
                        r.records
                    }
                } ?: throw IllegalStateException("Zeitüberschreitung")

                val applied = applyRecords(ctx, records)
                newSeq?.let { setLastSeq(ctx, it) }
                setStatus(ctx, null)

                if (applied > 0 && notify) {
                    try {
                        MainActivity.bookmarksUpdatedEvent.invoke(true)
                    } catch (t: Throwable) {
                        logError(t)
                    }
                }
                applied
            } catch (t: Throwable) {
                Log.w(TAG, "pull failed", t)
                setStatus(ctx, t.message ?: t.javaClass.simpleName)
                null
            }
        }
    }

    private suspend fun serverFetch(ctx: Context, since: Long, nowMs: Long): PullResponse {
        val res = app.get(
            "${baseUrl(ctx)}/v1/pull?since=$since&_=$nowMs",
            headers = serverHeaders(ctx),
        )
        if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
        return parseJson<PullResponse>(res.text)
    }

    /** Übernimmt Einträge, die neuer sind als der lokale Stand. */
    private fun applyRecords(ctx: Context, records: List<Record>): Int {
        if (records.isEmpty()) return 0
        val p = prefs(ctx)
        val store = ctx.getSharedPrefs()
        var applied = 0
        store.edit {
            val times = p.edit()
            for (r in records) {
                val path = storagePath(r.k) ?: continue
                val localT = p.getLong(TIME_PREFIX + r.k, 0)
                if (r.t <= localT) continue // lokal gleich alt oder neuer
                if (r.k == PROFILES_KEY) {
                    // Profile nie löschen, nur ergänzen bzw. aktualisieren
                    r.v?.let { v -> DataStoreHelper.mergeSyncedProfiles(ProfileCrypt.reveal(v, cryptKey(ctx))) }
                    times.putLong(TIME_PREFIX + r.k, r.t)
                    applied++
                    continue
                }
                if (r.k.startsWith("$T_BOOKMARK") || r.k.startsWith("$T_FAVORITE/") || r.k.startsWith("$T_SUBSCRIPTION/")) {
                    com.lagradost.cloudstream3.syncproviders.AccountManager.localListApi.requireLibraryRefresh = true
                }
                if (r.d || r.v == null) remove(path) else putString(path, r.v)
                times.putLong(TIME_PREFIX + r.k, r.t)
                applied++
            }
            times.apply()
        }
        return applied
    }

    // ---------------------------------------------------------------- GitHub Gist

    private fun ownFileName(ctx: Context) =
        "device-${deviceId(ctx)}-p${DataStoreHelper.currentAccount}.json"

    private fun isProfileFile(name: String) =
        name.startsWith("device-") && name.endsWith("-p${DataStoreHelper.currentAccount}.json")

    /** Gist-ID passend zum aktuellen Token; sucht den Gist oder legt ihn an. */
    private suspend fun gistId(ctx: Context): String {
        val p = prefs(ctx)
        val owner = token(ctx).hashCode().toString()
        val cached = p.getString(KEY_GIST_ID, null)
        if (cached != null && p.getString(KEY_GIST_OWNER, null) == owner) return cached

        var found: String? = null
        for (page in 1..5) {
            val res = app.get("$GITHUB_API/gists?per_page=100&page=$page", headers = githubHeaders(ctx))
            if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
            val list = parseJson<List<Gist>>(res.text)
            found = list.firstOrNull { it.files.containsKey(GIST_MARKER) }?.id
            if (found != null || list.size < 100) break
        }

        val id = found ?: run {
            val body = GistWrite(
                description = "CloudStream Sync (nicht löschen)",
                public = false,
                files = mapOf(
                    GIST_MARKER to GistContent(
                        """{"app":"CloudStream Sync","info":"Sync-Daten deiner Geräte. Jedes Gerät schreibt eine eigene device-Datei."}"""
                    )
                )
            )
            val res = app.post(
                "$GITHUB_API/gists",
                headers = githubHeaders(ctx),
                requestBody = body.toJson().toRequestBody(JSON_TYPE),
            )
            if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
            parseJson<Gist>(res.text).id
        }

        p.edit {
            putString(KEY_GIST_ID, id)
            putString(KEY_GIST_OWNER, owner)
        }
        return id
    }

    /** Eigene Datei: lokal gespiegelt, damit zum Hochladen kein Abruf nötig ist. */
    private fun loadOwn(ctx: Context, gistId: String): MutableMap<String, Record> {
        val p = prefs(ctx)
        val forKey = "$gistId|${DataStoreHelper.currentAccount}"
        if (p.getString(KEY_GIST_OWN_FOR, null) != forKey) return mutableMapOf()
        val raw = p.getString(KEY_GIST_OWN, null) ?: return mutableMapOf()
        return try {
            parseJson<DeviceFile>(raw).records.toMutableMap()
        } catch (t: Throwable) {
            logError(t)
            mutableMapOf()
        }
    }

    private suspend fun gistPush(ctx: Context, records: List<Record>) {
        val id = gistId(ctx)
        val own = loadOwn(ctx, id)
        for (r in records) {
            val cur = own[r.k]
            if (cur == null || r.t >= cur.t) own[r.k] = r
        }
        val file = DeviceFile(deviceId(ctx), System.currentTimeMillis(), own)
        val content = file.toJson()
        val body = GistWrite(files = mapOf(ownFileName(ctx) to GistContent(content)))
        val res = app.patch(
            "$GITHUB_API/gists/$id",
            headers = githubHeaders(ctx),
            requestBody = body.toJson().toRequestBody(JSON_TYPE),
        )
        if (res.code == 404) {
            // Gist wurde gelöscht: beim nächsten Mal neu suchen bzw. anlegen
            prefs(ctx).edit { remove(KEY_GIST_ID) }
        }
        if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
        prefs(ctx).edit {
            putString(KEY_GIST_OWN, content)
            putString(KEY_GIST_OWN_FOR, "$id|${DataStoreHelper.currentAccount}")
        }
    }

    /** Liest die Dateien aller anderen Geräte (gleiches Profil) und liefert den jüngsten Stand je Eintrag. */
    private suspend fun gistFetch(ctx: Context): List<Record> {
        val id = gistId(ctx)
        val res = app.get(
            "$GITHUB_API/gists/$id?_=${System.currentTimeMillis()}",
            headers = githubHeaders(ctx),
        )
        if (res.code == 404) prefs(ctx).edit { remove(KEY_GIST_ID) }
        if (!res.isSuccessful) throw IllegalStateException(httpError(res.code))
        val gist = parseJson<Gist>(res.text)
        val own = ownFileName(ctx)

        val newest = HashMap<String, Record>()
        for ((name, f) in gist.files) {
            // Profil-Liste steht in den Dateien aller Profile; sonst nur das eigene Profil lesen
            val otherProfile = name.startsWith("device-") && name.endsWith(".json") && !isProfileFile(name)
            if (name == own || (!isProfileFile(name) && !otherProfile)) continue
            val text = if (!f.truncated && f.content != null) {
                f.content
            } else {
                val url = f.rawUrl ?: continue
                val raw = app.get(url, headers = mapOf("Authorization" to "Bearer ${token(ctx)}"))
                if (!raw.isSuccessful) continue
                raw.text
            }
            val device = try {
                parseJson<DeviceFile>(text)
            } catch (t: Throwable) {
                logError(t)
                continue
            }
            for (r in device.records.values) {
                if (otherProfile && r.k != PROFILES_KEY) continue
                val cur = newest[r.k]
                if (cur == null || r.t > cur.t) newest[r.k] = r
            }
        }
        return newest.values.toList()
    }

    // ---------------------------------------------------------------- Lebenszyklus

    /**
     * Beim Öffnen der App sofort holen und danach jede Minute, solange die App sichtbar ist.
     * So erscheint der Stand des Handys auch auf einem TV, auf dem die App schon offen ist.
     */
    fun pullInBackground() {
        if (!isEnabled()) return
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            // Flimmer 17.6: Lesezeichen und Profile einmalig hochladen
            context?.let { ctx ->
                val flag = "full_v2_${DataStoreHelper.currentAccount}"
                if (!prefs(ctx).getBoolean(flag, false)) {
                    if (fullSync() != null) prefs(ctx).edit { putBoolean(flag, true) }
                }
            }
            pull(notify = true, force = true)
            while (true) {
                delay(FOREGROUND_PULL_MS)
                pull(notify = true)
            }
        }
    }

    fun stopForegroundPull() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    /**
     * Lädt den gesamten lokalen Stand hoch (nach dem ersten Einrichten sinnvoll),
     * danach wird geholt.
     */
    suspend fun fullSync(): Int? {
        val ctx = context ?: return null
        if (!isEnabled(ctx)) return null
        val acc = DataStoreHelper.currentAccount
        val all = ctx.getSharedPrefs().all.keys
        val keys = mutableSetOf<String>()
        fun collect(folder: String, type: String) {
            val prefix = "$folder/"
            all.filter { it.startsWith(prefix) }
                .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
                .forEach { keys.add("$type/$it") }
        }
        collect("$acc/$VIDEO_POS_DUR", T_POS)
        collect("$acc/$RESULT_RESUME_WATCHING", T_RESUME)
        collect("$acc/$VIDEO_WATCH_STATE", T_WATCH)
        collect("$acc/$RESULT_WATCH_STATE", T_BOOKMARK)
        collect("$acc/$RESULT_WATCH_STATE_DATA", T_BOOKMARK_DATA)
        collect("$acc/$RESULT_FAVORITES_STATE_DATA", T_FAVORITE)
        collect("$acc/$RESULT_SUBSCRIBED_STATE_DATA", T_SUBSCRIPTION)
        if (all.contains(DataStoreHelper.PROFILES_STORAGE_KEY)) keys.add(PROFILES_KEY)
        // Kacheln nur für Titel, die auch in «Weiterschauen» stehen
        keys.filter { it.startsWith("$T_RESUME/") }
            .forEach { keys.add(T_HEADER + "/" + it.substringAfter('/')) }

        val p = prefs(ctx)
        val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        p.edit {
            keys.forEach { k ->
                // alter Stand ohne Zeitstempel: verliert gegen jede echte Änderung
                if (!p.contains(TIME_PREFIX + k)) putLong(TIME_PREFIX + k, 1L)
            }
            pending.addAll(keys)
            putStringSet(KEY_PENDING, pending)
        }
        if (!push()) return null
        return pull(notify = true, force = true)
    }

    /** Verbindung prüfen; liefert null bei Erfolg, sonst eine Fehlermeldung. */
    suspend fun testConnection(): String? {
        val ctx = context ?: return "Kein Kontext"
        if (token(ctx).isBlank()) return "Token fehlt"
        // "" = Erfolg, null = Zeitüberschreitung
        return try {
            val result: String? = withTimeoutOrNull(NET_TIMEOUT_MS * 2) {
                if (mode(ctx) == MODE_GIST) {
                    gistId(ctx)
                    ""
                } else if (settings(ctx).url.get().isBlank()) {
                    "Server-Adresse fehlt"
                } else {
                    val res = app.get(
                        "${baseUrl(ctx)}/v1/ping?_=${System.currentTimeMillis()}",
                        headers = serverHeaders(ctx)
                    )
                    if (res.isSuccessful) "" else httpError(res.code)
                }
            }
            when (result) {
                null -> "Zeitüberschreitung"
                "" -> null
                else -> result
            }
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }
}
