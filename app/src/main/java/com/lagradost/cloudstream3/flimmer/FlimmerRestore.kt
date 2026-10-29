package com.lagradost.cloudstream3.flimmer

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.CloudSync
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Von einem anderen Gerät übernehmen»: Gist-Token eingeben, dann holt Flimmer Profile, Repos,
 * Erweiterungen, Einstellungen sowie Weiterschauen und Merklisten.
 */
object FlimmerRestore {
    fun show(fragment: Fragment, onDone: () -> Unit) {
        val ctx = fragment.context ?: return
        val settings = AppSettings(ctx).sync
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()

        val input = EditText(ctx).apply {
            hint = "GitHub-Token (ghp_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(settings.token.get())
        }
        val info = TextView(ctx).apply {
            text = "Gleicher Token wie auf deinem anderen Gerät (Einstellungen, Allgemein, Cloud-Sync). " +
                    "Vorhandenes auf diesem Gerät bleibt erhalten und wird ergänzt."
            setPadding(0, 0, 0, pad / 2)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info)
            addView(input)
        }

        AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setTitle("Von einem anderen Gerät übernehmen")
            .setView(box)
            .setPositiveButton("Übernehmen") { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) return@setPositiveButton
                settings.mode.set(CloudSync.MODE_GIST)
                settings.token.set(token)
                settings.enabled.set(true)
                run(fragment, onDone)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun run(fragment: Fragment, onDone: () -> Unit) {
        val ctx = fragment.context ?: return
        val pad = (24 * ctx.resources.displayMetrics.density).toInt()
        val status = TextView(ctx).apply { text = "Profile, Repos, Erweiterungen und Einstellungen werden geholt …" }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(ctx), FrameLayout.LayoutParams(pad * 2, pad * 2))
            addView(status.apply { setPadding(pad / 2, 0, 0, 0) })
        }
        val progress = AlertDialog.Builder(ctx, R.style.AlertDialogCustom)
            .setView(box)
            .setCancelable(false)
            .show()

        fragment.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CloudSync.restoreFromOtherDevices() }
            progress.dismiss()
            val c = fragment.context ?: return@launch
            if (result.error != null) {
                AlertDialog.Builder(c, R.style.AlertDialogCustom)
                    .setTitle("Übernehmen fehlgeschlagen")
                    .setMessage(result.error + "\n\nPrüfe den Token (Berechtigung «gist») und die Internetverbindung.")
                    .setPositiveButton("Nochmals") { _, _ -> show(fragment, onDone) }
                    .setNegativeButton("Schliessen", null)
                    .show()
                return@launch
            }
            AlertDialog.Builder(c, R.style.AlertDialogCustom)
                .setTitle("Übernommen")
                .setMessage(
                    "${result.entries} Einträge übernommen (Profile, Repos, Einstellungen, Weiterschauen, Merklisten)." +
                            "\n${result.plugins} Erweiterungen installiert." +
                            "\n\nSprache und Design gelten nach einem Neustart der App. Trakt meldest du pro Profil einmal neu an."
                )
                .setPositiveButton("Weiter") { _, _ -> onDone() }
                .setCancelable(false)
                .show()
        }
    }
}
