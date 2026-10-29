package ch.hotihysen.flimmer.desktop

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch

/**
 * Profile wie in der Android-App (gleiche Nummern, Namen, Bild-Farbe, PIN), über den Gist abgeglichen.
 * Gelöscht wird über den Sync nie; neue und geänderte Profile werden ergänzt.
 */
object Profiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Serializable
    data class Account(
        @SerialName("keyIndex") val keyIndex: Int,
        @SerialName("name") val name: String,
        @SerialName("customImage") val customImage: String? = null,
        @SerialName("defaultImageIndex") val defaultImageIndex: Int = 0,
        @SerialName("lockPin") val lockPin: String? = null,
    )

    /** Farben der Profilbilder in der App (dunkelblau, blau, orange, pink, lila, rot, türkis) */
    val colors = listOf(
        Color(0xFF1E3A8A), Color(0xFF3B82F6), Color(0xFFF97316), Color(0xFFEC4899),
        Color(0xFF8B5CF6), Color(0xFFEF4444), Color(0xFF14B8A6),
    )

    fun color(a: Account) = colors[Math.floorMod(a.defaultImageIndex, colors.size)]

    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Account>> = _list

    private fun load(): List<Account> {
        val stored = Sync.global(Sync.KEY_PROFILES)?.let {
            try { json.decodeFromString(ListSerializer(Account.serializer()), it) } catch (_: Throwable) { null }
        } ?: emptyList()
        // Das Hauptprofil (Nummer 0) gibt es immer
        val withDefault = if (stored.none { it.keyIndex == 0 }) listOf(Account(0, "Standard")) + stored else stored
        return withDefault.sortedBy { it.keyIndex }
    }

    fun reload() {
        _list.value = load()
    }

    val current: Account get() = _list.value.firstOrNull { it.keyIndex == Sync.profile } ?: _list.value.first()

    private fun save(list: List<Account>) {
        Sync.putGlobal(Sync.KEY_PROFILES, json.encodeToString(ListSerializer(Account.serializer()), list.sortedBy { it.keyIndex }))
        _list.value = load()
    }

    fun update(a: Account) = save(_list.value.filter { it.keyIndex != a.keyIndex } + a)

    fun add(name: String): Account {
        val next = (_list.value.maxOfOrNull { it.keyIndex } ?: 0) + 1
        val a = Account(next, name.ifBlank { "Profil $next" }, defaultImageIndex = next % colors.size)
        update(a)
        return a
    }

    /** Profile von einem anderen Gerät: gleiche Nummer aktualisieren, neue ergänzen */
    fun mergeRemote(remoteJson: String) {
        try {
            val remote = json.decodeFromString(ListSerializer(Account.serializer()), remoteJson)
            val merged = _list.value.associateBy { it.keyIndex }.toMutableMap()
            remote.forEach { merged[it.keyIndex] = it }
            // Ergebnis speichern, ohne es als eigene Änderung erneut zu senden
            Sync.replaceGlobalSilently(
                Sync.KEY_PROFILES,
                json.encodeToString(ListSerializer(Account.serializer()), merged.values.sortedBy { it.keyIndex })
            )
        } catch (t: Throwable) {
            System.err.println("Profile: ${t.message}")
        }
        reload()
    }

    /** Auswahl beim Start nötig: mehrere Profile oder eine PIN */
    val needsPicker: Boolean get() = _list.value.size > 1 || _list.value.any { it.lockPin != null }

    /** Zu einem Profil wechseln (PIN vorher prüfen) */
    fun switchTo(a: Account) {
        if (Sync.profile != a.keyIndex) {
            Sync.profile = a.keyIndex
            Trakt.forceRefresh()
            // Stand dieses Profils von den anderen Geräten holen
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Sync.pull() }
        }
        Sync.changed()
    }

    /** Beim ersten Start mit Sync: eigene Profile anbieten */
    fun offer() {
        val custom = _list.value.filter { it.keyIndex != 0 || it.name != "Standard" || it.lockPin != null }
        if (custom.isNotEmpty()) Sync.offerGlobal(Sync.KEY_PROFILES, json.encodeToString(ListSerializer(Account.serializer()), _list.value))
    }
}
