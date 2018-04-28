/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.browser

import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.content.ContextCompat
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.android.synthetic.main.browser_overlay.view.*
import kotlinx.android.synthetic.main.browser_overlay_top_nav.view.*
import kotlinx.android.synthetic.main.home_tile.view.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import org.mozilla.focus.R
import org.mozilla.focus.autocomplete.UrlAutoCompleteFilter
import org.mozilla.focus.home.HomeTilesManager
import org.mozilla.focus.home.pocket.Pocket
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.Settings
import org.mozilla.focus.widget.InlineAutocompleteEditText
import kotlin.properties.Delegates

private const val NAVIGATION_BUTTON_ENABLED_ALPHA = 1.0f
private const val NAVIGATION_BUTTON_DISABLED_ALPHA = 0.3f

private const val COL_COUNT = 5

enum class NavigationEvent {
    SETTINGS, BACK, FORWARD, RELOAD, LOAD_URL, LOAD_TILE, TURBO, PIN_ACTION, POCKET;

    companion object {
        fun fromViewClick(viewId: Int?) = when (viewId) {
            R.id.navButtonBack -> BACK
            R.id.navButtonForward -> FORWARD
            R.id.navButtonReload -> RELOAD
            R.id.navButtonSettings -> SETTINGS
            R.id.turboButton -> TURBO
            R.id.pinButton -> PIN_ACTION
            R.id.pocketVideoMegaTileView -> POCKET
            else -> null
        }

        const val VAL_CHECKED = "checked"
        const val VAL_UNCHECKED = "unchecked"
    }
}

class BrowserNavigationOverlay @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyle: Int = 0 )
    : LinearLayout(context, attrs, defStyle), View.OnClickListener {

    interface BrowserNavigationStateProvider {
        fun isBackEnabled(): Boolean
        fun isForwardEnabled(): Boolean
        fun getCurrentUrl(): String?
        fun isURLPinned(): Boolean
        fun isPinEnabled(): Boolean
        fun isRefreshEnabled(): Boolean
    }

    /**
     * Used to cancel background->UI threads: we attach them as children to this job
     * and cancel this job at the end of the UI lifecycle, cancelling the children.
     */
    var uiLifecycleCancelJob: Job

    // Setting the onTileLongClick function in the HomeTileAdapter is fragile
    // since we init the tiles in View.init and Android is inflating the view for us,
    // thus we need to use Delegates.observable to update onTileLongClick.
    var openHomeTileContextMenu: (() -> Unit) by Delegates.observable({}) { _, _, newValue ->
        with (tileContainer) {
            (adapter as HomeTileAdapter).onTileLongClick = newValue
        }
    }

    var onNavigationEvent: ((event: NavigationEvent, value: String?,
                             autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?) -> Unit)? = null
    var navigationStateProvider: BrowserNavigationStateProvider? = null
    /** Called inside [setVisibility] right before super.setVisibility is called. */
    var onPreSetVisibilityListener: ((isVisible: Boolean) -> Unit)? = null

    private var isTurboEnabled: Boolean
        get() = Settings.getInstance(context).isBlockingEnabled
        set(value) {
            Settings.getInstance(context).isBlockingEnabled = value
        }

    private val pocketVideos = Pocket.getRecommendedVideos()

    private var hasUserChangedURLSinceEditTextFocused = false

    init {
        LayoutInflater.from(context)
                .inflate(R.layout.browser_overlay, this, true)
        listOf(navButtonBack, navButtonForward, navButtonReload, navButtonSettings,
                turboButton, pinButton, pocketVideoMegaTileView)
                .forEach {
                    it.setOnClickListener(this)
                }

        uiLifecycleCancelJob = Job()

        initTiles()
        initMegaTile()
        setupUrlInput()
        turboButton.isChecked = Settings.getInstance(context).isBlockingEnabled
        navButtonSettings.setImageResource(R.drawable.ic_settings) // Must be set in code for SVG to work correctly.

        val tintDrawable: (Drawable?) -> Unit = { it?.setTint(ContextCompat.getColor(context, R.color.tv_white)) }
        navCloseHint.compoundDrawablesRelative.forEach(tintDrawable)
        navUrlInput.compoundDrawablesRelative.forEach(tintDrawable)

        tileContainer.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                val gridLayoutManager = recyclerView?.layoutManager as GridLayoutManager
                val lastVisibleItem = gridLayoutManager.findLastCompletelyVisibleItemPosition()
                // We add a scroll offset, revealing the next row to hint that there are more home tiles
                if (dy > 0 && getFocusedTilePosition() > lastVisibleItem) {
                    val scrollOffset = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, home_tile.height.toFloat() / 2, context.resources.displayMetrics)
                    recyclerView.smoothScrollBy(0, scrollOffset.toInt())
                }
            }
        })

        Toast.makeText(context, R.string.homescreen_unpin_tutorial_toast, Toast.LENGTH_LONG).show()
    }

    private fun initTiles() = with (tileContainer) {
        val homeTiles = HomeTilesManager.getTilesCache(context)

        adapter = HomeTileAdapter(uiLifecycleCancelJob, homeTiles, loadUrl = { urlStr ->
            with (navUrlInput) {
                if (urlStr.isNotEmpty()) {
                    onNavigationEvent?.invoke(NavigationEvent.LOAD_TILE, urlStr, null)
                }
            }
        }, onTileLongClick = openHomeTileContextMenu)
        layoutManager = GridLayoutManager(context, COL_COUNT)
        setHasFixedSize(true)
    }

    private fun initMegaTile() {
        if (pocketVideos.isCompleted) {
            pocketVideoMegaTileView.pocketVideos = pocketVideos.getCompleted()
        } else {
            // TODO: #864 show loading screen
            launch(UI) {
                pocketVideoMegaTileView.pocketVideos = pocketVideos.await()
            }
        }
    }

    private fun setupUrlInput() = with (navUrlInput) {
        setOnCommitListener {
            val userInput = text.toString()
            if (userInput.isNotEmpty()) {
                val cachedAutocompleteResult = lastAutocompleteResult // setText clears the reference so we cache it here.
                setText(cachedAutocompleteResult.text)
                onNavigationEvent?.invoke(NavigationEvent.LOAD_URL, userInput, cachedAutocompleteResult)
            }
        }
        val autocompleteFilter = UrlAutoCompleteFilter()
        autocompleteFilter.load(context.applicationContext)
        setOnFilterListener { searchText, view -> autocompleteFilter.onFilter(searchText, view) }

        setOnUserInputListener { hasUserChangedURLSinceEditTextFocused = true }
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hasUserChangedURLSinceEditTextFocused = false
                updateOverlayForCurrentState() // Update URL to overwrite user input, ensuring the url's accuracy.
            }
        }
    }

    override fun onClick(view: View?) {
        val event = NavigationEvent.fromViewClick(view?.id) ?: return
        var value: String? = null

        val isTurboButtonChecked = turboButton.isChecked
        val isPinButtonChecked = pinButton.isChecked
        when (event) {
            NavigationEvent.TURBO -> {
                isTurboEnabled = isTurboButtonChecked
            }
            NavigationEvent.PIN_ACTION -> {
                value = if (isPinButtonChecked) NavigationEvent.VAL_CHECKED
                else NavigationEvent.VAL_UNCHECKED
            }
            else -> Unit // Nothing to do.
        }
        onNavigationEvent?.invoke(event, value, null)
        TelemetryWrapper.overlayClickEvent(event, isTurboButtonChecked, isPinButtonChecked)
    }

    fun updateOverlayForCurrentState() {
        fun updateOverlayButtonState(isEnabled: Boolean, overlayButton: ImageButton) {
            overlayButton.isEnabled = isEnabled
            overlayButton.isFocusable = isEnabled
            overlayButton.alpha =
                    if (isEnabled) NAVIGATION_BUTTON_ENABLED_ALPHA else NAVIGATION_BUTTON_DISABLED_ALPHA
        }

        val canGoBack = navigationStateProvider?.isBackEnabled() ?: false
        updateOverlayButtonState(canGoBack, navButtonBack)

        val canGoForward = navigationStateProvider?.isForwardEnabled() ?: false
        updateOverlayButtonState(canGoForward, navButtonForward)

        val isPinEnabled = navigationStateProvider?.isPinEnabled() ?: false
        updateOverlayButtonState(isPinEnabled, pinButton)
        pinButton.isChecked = navigationStateProvider?.isURLPinned() ?: false

        val isRefreshEnabled = navigationStateProvider?.isRefreshEnabled() ?: false
        updateOverlayButtonState(isRefreshEnabled, navButtonReload)

        // Prevent the focus from looping to the bottom row when reaching the last
        // focusable element in the top row
        navButtonReload.nextFocusLeftId = when {
            canGoForward -> R.id.navButtonForward
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonReload
        }
        navButtonForward.nextFocusLeftId = when {
            canGoBack -> R.id.navButtonBack
            else -> R.id.navButtonForward
        }

        maybeUpdateOverlayURLForCurrentState()

        if (findFocus() == null) {
            requestFocus()
        }
    }

    fun getFocusedTilePosition(): Int {
        return (rootView.findFocus().parent as? RecyclerView)?.getChildAdapterPosition(rootView.findFocus()) ?: RecyclerView.NO_POSITION
    }

    private fun maybeUpdateOverlayURLForCurrentState() {
        // The url can get updated in the background, e.g. if a loading page is redirected. We
        // don't want a url update to interrupt the user typing so we don't update the url from
        // the background if the user has already updated the url themselves.
        //
        // We revert this state when the view is unfocused: it ensures the URL is usually accurate
        // (for security reasons) and it's simple compared to other options which keep more state.
        //
        // One problem this solution has is that if the URL is updated in the background rapidly,
        // sometimes key events will be dropped, but I don't think there's much we can do about this:
        // we can't determine if the keyboard is up or not and focus isn't a good indicator because
        // we can focus the EditText without opening the soft keyboard and the user won't even know
        // these are inaccurate!
        if (!hasUserChangedURLSinceEditTextFocused) {
            navUrlInput.setText(navigationStateProvider?.getCurrentUrl())
        }
    }

    override fun setVisibility(visibility: Int) {
        onPreSetVisibilityListener?.invoke(visibility == View.VISIBLE)
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            navUrlInput.requestFocus()
            updateOverlayForCurrentState()
        }
    }

    fun refreshTilesForInsertion() {
        val adapter = tileContainer.adapter as HomeTileAdapter
        adapter.updateAdapterSingleInsertion(HomeTilesManager.getTilesCache(context))
    }

    fun removePinnedSiteFromTiles(tileId: String) {
        val adapter = tileContainer.adapter as HomeTileAdapter
        adapter.removeTileFromAdapter(tileId)
    }
}
