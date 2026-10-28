package com.lagradost.cloudstream3.ui.library

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.databinding.FlimmerLibraryCardBinding
import com.lagradost.cloudstream3.databinding.SearchResultGridExpandedBinding
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.flimmer.FlimmerLibraryInfo
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import kotlin.math.roundToInt

class PageAdapter(
    private val resView: AutofitRecyclerView,
    val clickCallback: (SearchClickCallback) -> Unit
) :
    NoStateAdapter<SyncAPI.LibraryItem>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
        if (a.id != null || b.id != null) {
            a.id == b.id
        } else {
            a.name == b.name && a.url == b.url
        }
    })) {
    private val coverHeight: Int get() = (resView.itemWidth / 0.68).roundToInt()

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        // Flimmer: eigene Kachel (Handy und Fernseher)
        if (true) {
            return ViewHolderState(FlimmerLibraryCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        return ViewHolderState(
            SearchResultGridExpandedBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is SearchResultGridExpandedBinding -> {
                clearImage(binding.imageView)
            }
            is FlimmerLibraryCardBinding -> {
                clearImage(binding.imageView)
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: SyncAPI.LibraryItem,
        position: Int
    ) {
        (holder.view as? FlimmerLibraryCardBinding)?.let { card ->
            bindFlimmerCard(card, holder, item, position)
            return
        }
        val binding = holder.view as? SearchResultGridExpandedBinding ?: return

        /** https://stackoverflow.com/questions/8817522/how-to-get-color-code-of-image-view */
        SearchResultBuilder.bind(
            this@PageAdapter.clickCallback,
            item,
            position,
            holder.itemView,
        )

        // See searchAdaptor for this, it basically fixes the height
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            coverHeight
        )
        if (params.height != binding.imageView.layoutParams.height || params.width != binding.imageView.layoutParams.width) {
            binding.imageView.layoutParams = params
        }

        val showProgress = item.episodesCompleted?.let{ it>0 } ?: false && item.episodesTotal != null
        binding.watchProgress.isVisible = showProgress
        if (showProgress) {
            binding.watchProgress.max = item.episodesTotal
            binding.watchProgress.progress = item.episodesCompleted
        }

        binding.imageText.text = item.name
    }

    private fun bindFlimmerCard(
        binding: FlimmerLibraryCardBinding,
        holder: ViewHolderState<Any>,
        item: SyncAPI.LibraryItem,
        position: Int,
    ) {
        SearchResultBuilder.bind(this@PageAdapter.clickCallback, item, position, holder.itemView)

        // Poster 2:3, Innenabstand der Kachel (2 × 4dp) abgezogen
        val inner = resView.itemWidth - (8 * binding.root.resources.displayMetrics.density).roundToInt()
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (inner * 1.5).roundToInt())
        if (params.height != binding.imageView.layoutParams.height) binding.imageView.layoutParams = params

        val isAnime = item.type == TvType.Anime || item.type == TvType.AnimeMovie ||
                item.tags?.any { it.equals("anime", true) } == true
        binding.flimmerCardType.text = when {
            item.type == TvType.Movie && isAnime -> "ANIME-FILM"
            isAnime -> "ANIME"
            item.type == TvType.Movie || item.type == TvType.AnimeMovie -> "FILM"
            else -> "SERIE"
        }

        // Eigene Bewertung bevorzugt, sonst die von Trakt/TMDB
        val own = item.personalRating?.toInt(10)?.takeIf { it > 0 }
        val community = item.score?.toInt(100)?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it / 10.0) }
        binding.flimmerCardRating.text = when {
            own != null -> "♥ $own"
            community != null -> "★ $community"
            else -> ""
        }
        binding.flimmerCardRating.isVisible = own != null || community != null

        val done = item.episodesCompleted ?: 0
        val total = item.episodesTotal
        val isMoviePercent = total == 100 && item.type == TvType.Movie
        val showProgress = done > 0 && total != null && total > 0 && done < total
        binding.watchProgress.isVisible = showProgress
        if (showProgress) {
            binding.watchProgress.max = total!!
            binding.watchProgress.progress = done
        }
        binding.flimmerCardProgressText.text = when {
            !showProgress -> ""
            isMoviePercent -> "$done %"
            else -> "$done / $total"
        }
        binding.flimmerCardProgressText.isVisible = showProgress
        binding.flimmerCardShade.isVisible = showProgress

        binding.imageText.text = item.name
        val year = item.releaseDate?.let {
            java.util.Calendar.getInstance().apply { time = it }.get(java.util.Calendar.YEAR)
        }
        val fallback = listOfNotNull(
            year?.toString(),
            item.tags?.firstOrNull { !it.equals("anime", true) }?.replaceFirstChar { it.uppercase() },
        ).joinToString(" · ")
        val info = FlimmerLibraryInfo.line(item.syncId) ?: fallback
        binding.flimmerCardInfo.text = info
        binding.flimmerCardInfo.isVisible = info.isNotBlank()
    }
}
