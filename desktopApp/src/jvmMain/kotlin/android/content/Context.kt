package android.content

/**
 * Platzhalter für Android-Erweiterungen unter Windows.
 * Erweiterungen bekommen beim Laden einen Context, die meisten benutzen ihn nicht.
 */
open class Context {
    open fun getPackageName(): String = "ch.hotihysen.flimmer"
}
