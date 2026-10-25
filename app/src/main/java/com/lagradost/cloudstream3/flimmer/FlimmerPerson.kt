package com.lagradost.cloudstream3.flimmer

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/** Infoseite zu einer Person: Bild, Lebensdaten, Biografie, Filme und Serien (TMDB, Deutsch). */
object FlimmerPerson {
    fun show(activity: Activity?, name: String, fallbackImage: String?) {
        activity ?: return
        if (!FlimmerKatalog.isConfigured) return
        val view = LayoutInflater.from(activity).inflate(R.layout.flimmer_person_sheet, null, false)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val image = view.findViewById<ImageView>(R.id.person_image)
        val nameView = view.findViewById<TextView>(R.id.person_name)
        val meta = view.findViewById<TextView>(R.id.person_meta)
        val bio = view.findViewById<TextView>(R.id.person_bio)
        val loading = view.findViewById<ProgressBar>(R.id.person_loading)
        val header = view.findViewById<TextView>(R.id.person_titles_header)
        val grid = view.findViewById<AutofitRecyclerView>(R.id.person_titles)

        nameView.text = name
        image.loadImage(fallbackImage)
        bio.isGone = true
        bio.setOnClickListener {
            bio.maxLines = if (bio.maxLines == 5) Int.MAX_VALUE else 5
        }

        grid.spanCount = 3
        grid.adapter = SearchAdapter(grid) { callback ->
            dialog.dismiss()
            SearchHelper.handleSearchClickCallback(callback)
        }
        dialog.show()

        ioSafe {
            val info = try {
                FlimmerKatalog().loadPerson(name)
            } catch (t: Throwable) {
                null
            }
            runOnMainThread {
                loading.isVisible = false
                if (info == null) {
                    showToast("Keine Infos zu $name gefunden")
                    return@runOnMainThread
                }
                nameView.text = info.name
                info.image?.let { image.loadImage(it) }
                meta.text = info.meta
                meta.isGone = info.meta.isBlank()
                bio.text = info.biography ?: ""
                bio.isGone = info.biography.isNullOrBlank()
                header.isVisible = info.titles.isNotEmpty()
                (grid.adapter as? SearchAdapter)?.submitList(info.titles)
            }
        }
    }
}
