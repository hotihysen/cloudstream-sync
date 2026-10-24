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
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Cloud-Sync: gleicht Wiedergabeposition, «Weiterschauen»-Liste, Titel-Infos und
 * Gesehen-Markierungen über einen eigenen Server zwischen Geräten ab.
 *
 * Funktionsweise
 * - Lokale Änderungen werden über [markDirty] vorgemerkt und gebündelt hochgeladen
 *   (spätestens [PUSH_DELAY_MS] nach der ersten Änderung sowie beim Verlassen der App).
 * - Beim Öffnen der App und vor dem Laden von «Weiterschauen» werden neue Einträge
 *   vom Server geholt ([pull]). Es gewinnt jeweils der jüngste Stand (Zeitstempel).
 * - Übertragen werden die Rohwerte (JSON) aus dem Cloudstream-Speicher; die IDs sind
 *   auf allen Geräten gleich, solange dieselben Erweiterungen installiert sind.
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
    private const val TIME_PREFIX = "t/"

    private const val PUSH_DELAY_MS = 8_000L
    private const val PULL_MIN_INTERVAL_MS = 5_000L
    private const val NET_TIMEOUT_MS = 6_000L

    /** Logische Schlüsseltypen; der Server kennt nur «typ/id». */
    private const val T_POS = "pos"        // Wiedergabeposition einer Episode / eines Films
    private const val T_RESUME = "resume"  // Eintrag in «Weiterschauen» (pro Serie/Film)
    private const val T_HEADER = "header"  // Titel, Poster, URL, Anbieter (für die Kachel)
    private const val T_WATCH = "ws"       // Gesehen-Markierung pro Episode

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pushJob: Job? = null
    @Volatile private var lastPull = 0L

    // ---------------------------------------------------------------- Einstellungen

    private val context: Context? get() = CloudStreamApp.context

    private fun settings(ctx: Context) = AppSettings(ctx).sync

    fun isEnabled(ctx: Context? = context): Boolean {
        ctx ?: return false
        val s = settings(ctx)
        return s.enabled.get() && s.url.get().isNotBlank() && s.token.get().isNotBlank()
    }

    private fun baseUrl(ctx: Context) = settings(ctx).url.get().trim().trimEnd('/')
    private fun authHeaders(ctx: Context) = mapOf(
        "Authorization" to "Bearer ${settings(ctx).token.get().trim()}",
        "Content-Type" to "application/json",
        "Cache-Control" to "no-cache",
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            p.edit { putString(KEY_DEVICE, it) }
        }
    }

    /** Server oder Token geändert: von vorne synchronisieren. */
    private fun seqOwner(ctx: Context) =
        "${baseUrl(ctx)}|${settings(ctx).token.get().trim()}|${DataStoreHelper.currentAccount}"

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
            else -> null
        }
    }

    // ---------------------------------------------------------------- Lokale Änderungen

    fun onViewPos(id: Int?) = markDirty(T_POS, id)
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
        schedulePush()
    }

    private fun schedulePush() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
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
            val raw = store.getString(path, null)
            Record(k = key, v = raw, t = p.getLong(TIME_PREFIX + key, System.currentTimeMillis()), d = raw == null)
        }

        val ok = try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.post(
                    "${baseUrl(ctx)}/v1/push",
                    headers = authHeaders(ctx),
                    requestBody = PushBody(deviceId(ctx), records).toJson().toRequestBody(),
                ).let { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    true
                }
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

    // ---------------------------------------------------------------- Download

    /**
     * Holt neue Einträge vom Server und schreibt sie in den lokalen Speicher.
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
                val since = lastSeq(ctx)
                val body = withTimeoutOrNull(NET_TIMEOUT_MS) {
                    val res = app.get(
                        "${baseUrl(ctx)}/v1/pull?since=$since&_=$nowMs",
                        headers = authHeaders(ctx),
                    )
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    res.text
                } ?: throw IllegalStateException("Zeitüberschreitung")

                val response = parseJson<PullResponse>(body)
                val p = prefs(ctx)
                val pending = p.getStringSet(KEY_PENDING, emptySet())!!
                val store = ctx.getSharedPrefs()
                var applied = 0

                store.edit {
                    val times = p.edit()
                    for (r in response.records) {
                        val path = storagePath(r.k) ?: continue
                        val localT = p.getLong(TIME_PREFIX + r.k, 0)
                        // Lokal neuer oder noch nicht hochgeladen: lokalen Stand behalten
                        if (r.t <= localT || (r.k in pending && localT >= r.t)) continue
                        if (r.d || r.v == null) remove(path) else putString(path, r.v)
                        times.putLong(TIME_PREFIX + r.k, r.t)
                        applied++
                    }
                    times.apply()
                }
                setLastSeq(ctx, response.seq)
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

    private var foregroundJob: Job? = null
    private const val FOREGROUND_PULL_MS = 60_000L

    /**
     * Beim Öffnen der App sofort holen und danach jede Minute, solange die App sichtbar ist.
     * So erscheint der Stand des Handys auch auf einem TV, auf dem die App schon offen ist.
     */
    fun pullInBackground() {
        if (!isEnabled()) return
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
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
     * danach wird vom Server geholt.
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
        // Kacheln nur für Titel, die auch in «Weiterschauen» stehen
        keys.filter { it.startsWith("$T_RESUME/") }
            .forEach { keys.add(T_HEADER + "/" + it.substringAfter('/')) }

        val p = prefs(ctx)
        val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        p.edit {
            keys.forEach { k ->
                if (!p.contains(TIME_PREFIX + k)) putLong(TIME_PREFIX + k, 1L) // alter Stand: verliert gegen jede echte Änderung
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
        val s = settings(ctx)
        if (s.url.get().isBlank()) return "Server-Adresse fehlt"
        if (s.token.get().isBlank()) return "Token fehlt"
        return try {
            withTimeoutOrNull(NET_TIMEOUT_MS) {
                val res = app.get("${baseUrl(ctx)}/v1/ping?_=${System.currentTimeMillis()}", headers = authHeaders(ctx))
                when {
                    res.code == 401 -> "Token ungültig"
                    !res.isSuccessful -> "HTTP ${res.code}"
                    else -> null
                }
            } ?: "Zeitüberschreitung"
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }
}
