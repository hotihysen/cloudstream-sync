package com.lagradost.cloudstream3

import ch.hotihysen.flimmer.desktop.Store

/** Kleiner Ersatz für den Schlüsselspeicher der Android-App (vom gemeinsamen Katalog benutzt). */
object CloudStreamApp {
    @Suppress("UNCHECKED_CAST")
    fun <T> getKey(folder: String, path: String): T? = Store.getString("$folder/$path") as T?

    fun setKey(folder: String, path: String, value: Any?) {
        Store.putString("$folder/$path", value?.toString())
    }
}
