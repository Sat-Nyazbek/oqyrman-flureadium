package dev.mulev.flureadium.navigators

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import dev.mulev.flureadium.FlutterNavigationConfig
import dev.mulev.flureadium.ReadiumReaderWidget.Companion.NAVIGATOR_FRAGMENT_TAG
import dev.mulev.flureadium.canScroll
import dev.mulev.flureadium.fragments.EpubReaderFragment
import dev.mulev.flureadium.jsonDecode
import dev.mulev.flureadium.models.EpubReaderViewModel
import dev.mulev.flureadium.throttleLatest
import dev.mulev.flureadium.withScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "EpubNavigator"
private const val currentVisualCurrentLocatorKey = "currentVisualCurrentLocator"
private const val epubPreferencesKey = "epubPreferences"
private const val RESTORE_PROGRESSION_TOLERANCE = 0.01
private const val NAVIGATOR_START_TIMEOUT_MS = 5_000L

/**
 * Waits for a foreground visual navigator to start without turning a normal
 * Activity lifecycle transition into an application crash.
 */
/** True unless the fragment is being removed or its Activity is in the background. */
internal fun canWaitForVisualStart(fragment: androidx.fragment.app.Fragment): Boolean =
    !fragment.isRemoving &&
        fragment.activity?.lifecycle?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != false

internal suspend fun awaitNavigatorStarted(
    started: StateFlow<Boolean>,
    canWait: () -> Boolean,
    timeoutMillis: Long = NAVIGATOR_START_TIMEOUT_MS,
): Boolean {
    if (started.value) return true
    if (!canWait()) return false

    return withTimeoutOrNull(timeoutMillis) {
        started.first { it }
        true
    } ?: false
}

/**
 * The progression tolerance is useful while restoring a saved position, where
 * WebView geometry can introduce a tiny amount of drift. It must not suppress
 * live TTS following: one percent of a long XHTML resource can span several
 * screens while the spoken locator already points at a different text range.
 */
internal fun shouldSkipCloseProgressionScroll(
    currentProgression: Double?,
    targetProgression: Double?,
    forceFollow: Boolean,
): Boolean =
    !forceFollow &&
        currentProgression != null &&
        targetProgression != null &&
        kotlin.math.abs(currentProgression - targetProgression) < RESTORE_PROGRESSION_TOLERANCE

/**
 * EpubNavigator is a wrapper around the EpubReaderFragment and provides methods to interact with it.
 * It also listens to events from the fragment and forwards them to the VisualListener.
 */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class EpubNavigator : BaseNavigator, EpubReaderFragment.Listener {
    private val initialPreferences: EpubPreferences

    constructor(
        publication: Publication,
        initialLocator: Locator?,
        visualListener: VisualListener,
        initialPreferences: EpubPreferences = EpubPreferences()
    ) : super(publication, initialLocator) {
        this.initialPreferences = initialPreferences
        this.visualListener = visualListener

        this.state[currentVisualCurrentLocatorKey] = initialLocator
        this.state[epubPreferencesKey] = initialPreferences
    }

    /**
     * A VisualListener is used to listen to events from the Visual navigators like EpubNavigator.
     */
    interface VisualListener {
        /**
         * Called when a page has loaded. Note: not necessarily the visible content, since
         * the Readium Navigator preloads neighboring charters.
         */
        fun onPageLoaded()

        /**
         * Called when the current page has changed. Can be a new file or a new page in the
         * same file.
         */
        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator)

        /**
         * Called when an external link has been tapped.
         */
        fun onExternalLinkActivated(url: AbsoluteUrl)

        /**
         * Called when the current locator has changed.
         */
        fun onVisualCurrentLocationChanged(locator: Locator)

        /**
         * Called when the visual reader is ready.
         */
        fun onVisualReaderIsReady()
    }

    val visualListener: VisualListener

    /**
     * EpubReaderFragment instance used as navigator.
     */
    private var epubNavigator: EpubReaderFragment? = null

    /**
     * Tracks which fragment instance we've subscribed to, to detect fragment recreation.
     */
    private var subscribedFragmentInstance: EpubReaderFragment? = null

    /**
     * Editor to modify EPUB preferences.
     */
    private var editor: EpubPreferencesEditor? = null

    /**
     * Pending scroll target to be applied when the page is loaded.
     */
    var pendingScrollToLocations: Locator.Locations? = null
    private var pendingFollowScroll = false

    /** Latest TTS locator received while the visual fragment is paused. */
    private var pendingBackgroundFollowLocator: Locator? = null

    /**
     * Current EPUB preferences.
     */
    val preferences: EpubPreferences?
        get() = editor?.preferences

    /**
     * Current locator in the EPUB navigator.
     */
    val currentLocator
        get() = epubNavigator?.currentLocator

    /**
     * Whether the EPUB navigator is in vertical scroll mode.
     */
    private val isVerticalScroll: Boolean
        get() {
            return editor?.preferences?.scroll ?: false
        }

    override suspend fun initNavigator() {
        pendingScrollToLocations =
            initialLocator?.locations?.let { locations ->
                if (canScroll(locations)) locations else null
            }
        pendingFollowScroll = false

        epubNavigator = EpubReaderFragment().apply {
            vm = EpubReaderViewModel().apply {
                navigatorFactory = EpubNavigatorFactory(publication)
                locator = this@EpubNavigator.initialLocator
                preferences = this@EpubNavigator.initialPreferences

                editor =
                    navigatorFactory!!.createPreferencesEditor(initialPreferences)
            }
            listener = this@EpubNavigator
        }
    }

    /**
     * Attach the EPUB navigator fragment to the given FragmentManager and ViewGroup.
     */
    fun attachNavigator(fragmentManager: FragmentManager, viewGroup: ViewGroup) {
        val navigator = epubNavigator ?: return
        mainScope.launch {
            fragmentManager.commitNow(allowStateLoss = fragmentManager.isStateSaved) {
                add(viewGroup, navigator, NAVIGATOR_FRAGMENT_TAG)
            }
        }
    }

    /**
     * Go to a specific locator in the EPUB navigator, this does not scroll to the locator position.
     */
    suspend fun go(locator: Locator, animated: Boolean): Boolean {
        val navigator = epubNavigator
        if (navigator == null) {
            Log.d(TAG, "::go - epubNavigator is null!")
            return false
        }

        return withScope(mainScope) {
            if (!afterFragmentStarted(navigator)) {
                Log.w(TAG, "::go - navigator is not started; ignoring navigation")
                return@withScope false
            }
            if (!navigator.go(locator, animated)) {
                Log.w(TAG, "::go -  FAILED!")
                return@withScope false
            }

            Log.d(TAG, "::go - returned true")

            return@withScope true
        }
    }

    /**
     * Update EPUB navigator preferences.
     */
    fun updatePreferences(preferences: EpubPreferences) {
        val currentLocatorValue = epubNavigator?.currentLocator?.value
        Log.d(TAG, "::updatePreferences - currentLocator BEFORE=${currentLocatorValue?.let {
            "href=${it.href}, prog=${it.locations.progression}"
        } ?: "null"}, preferences=$preferences")

        try {
            editor?.apply {
                fontFamily.set(preferences.fontFamily)
                fontSize.set(preferences.fontSize)
                fontWeight.set(preferences.fontWeight)
                scroll.set(preferences.scroll)
                backgroundColor.set(preferences.backgroundColor)
                textColor.set(preferences.textColor)

                mainScope.launch {
                    epubNavigator?.updatePreferences(preferences)

                    val afterLocatorValue = epubNavigator?.currentLocator?.value
                    Log.d(TAG, "::updatePreferences - currentLocator AFTER=${afterLocatorValue?.let {
                        "href=${it.href}, prog=${it.locations.progression}"
                    } ?: "null"}")
                }
                state[epubPreferencesKey] = preferences
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error applying EpubPreferences: $ex")
        }
    }

    override fun setupNavigatorListeners() {
        val navigator = epubNavigator
        if (navigator == null) {
            Log.e(TAG, "::setupNavigatorListeners - epubNavigator is null this should never happen")
            return
        }

        val currentLocator = navigator.currentLocator
        if (currentLocator != null) {
            // Log the current value before subscribing
            val currentValue = currentLocator.value
            val subscribeTime = System.currentTimeMillis()
            Log.d(TAG, "::setupNavigatorListeners - BEFORE subscribe at t=${subscribeTime}, currentLocator.value = " +
                "href=${currentValue.href}, progression=${currentValue.locations.progression}")

            var emissionCount = 0
            currentLocator.throttleLatest(100.milliseconds)
                .distinctUntilChanged()
                .onEach { locator ->
                    emissionCount++
                    val emitTime = System.currentTimeMillis()
                    val elapsedMs = emitTime - subscribeTime
                    Log.d(TAG, "::setupNavigatorListeners - StateFlow emit #$emissionCount at t=$emitTime (elapsed=${elapsedMs}ms): " +
                        "href=${locator.href}, progression=${locator.locations.progression}")
                    onCurrentLocatorChanges(locator)
                    state[currentVisualCurrentLocatorKey] = locator
                }
                .launchIn(mainScope)
                .let { jobs.add(it) }

            subscribedFragmentInstance = navigator  // Track subscribed fragment
        } else {
            Log.d(TAG, "::setupNavigatorListeners - currentLocator is null - navigator not ready?")
        }
    }

    override fun storeState(): Bundle {
        return Bundle().apply {
            putString(
                currentVisualCurrentLocatorKey,
                (state[currentVisualCurrentLocatorKey] as? Locator)?.toJSON()?.toString()
            )

            preferences?.let { prefs ->
                putString(
                    epubPreferencesKey,
                    Json.encodeToString(EpubPreferences.serializer(), prefs)
                )
            }
        }
    }

    private var pageLoadCount = 0

    override fun onPageLoaded() {
        pageLoadCount++
        val currentFragment = epubNavigator
        val currentLocatorValue = currentFragment?.currentLocator?.value
        Log.d(TAG, "::onPageLoaded #$pageLoadCount - " +
            "currentLocator=${currentLocatorValue?.let {
                "href=${it.href}, prog=${it.locations.progression}"
            } ?: "null"}, " +
            "pendingScroll=${pendingScrollToLocations != null}, " +
            "subscribedInstance=$subscribedFragmentInstance, " +
            "currentInstance=$currentFragment")

        visualListener.onPageLoaded()

        pendingScrollToLocations?.let { locations ->
            Log.d(TAG, "::onPageLoaded #$pageLoadCount - executing pendingScrollToLocations: $locations")

            val followSpokenRange = pendingFollowScroll
            pendingScrollToLocations = null
            pendingFollowScroll = false

            mainScope.async {
                if (followSpokenRange) {
                    scrollToSpokenRange(locations)
                } else {
                    scrollToLocations(locations, toStart = false)
                }
            }
        }

        // If fragment recreated (pause/resume), re-subscribe
        if (currentFragment != null && currentFragment !== subscribedFragmentInstance) {
            Log.d(TAG, "::onPageLoaded #$pageLoadCount - fragment changed detected! " +
                "current=$currentFragment, subscribed=$subscribedFragmentInstance, " +
                "currentLocator=${currentFragment.currentLocator?.value?.let {
                    "href=${it.href}, prog=${it.locations.progression}"
                }}")
            hasNotifiedIsReady = false
            jobs.forEach { it.cancel() }
            jobs.clear()
        }

        notifyIsReady()

        // TTS is intentionally allowed to continue while the Activity is in
        // the background. Bring the visual reader to the latest spoken locator
        // once its fragment is attached again.
        pendingBackgroundFollowLocator?.let { locator ->
            pendingBackgroundFollowLocator = null
            mainScope.launch {
                followLocator(locator, animated = false)
            }
        }
    }

    private var hasNotifiedIsReady = false

    /**
     * Notify that the navigator is ready only once.
     */
    private fun notifyIsReady() {
        if (hasNotifiedIsReady) return

        hasNotifiedIsReady = true
        visualListener.onVisualReaderIsReady()
        setupNavigatorListeners()
    }

    override fun onPageChanged(
        pageIndex: Int,
        totalPages: Int,
        locator: Locator
    ) {
        visualListener.onPageChanged(pageIndex, totalPages, locator)
        state[currentVisualCurrentLocatorKey] = locator
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        visualListener.onExternalLinkActivated(url)
    }

    override fun onCurrentLocatorChanges(locator: Locator) {
        visualListener.onVisualCurrentLocationChanged(locator)
    }

    override suspend fun release() {
        super.dispose()

        val fragment = epubNavigator
        epubNavigator = null
        fragment?.let {
            withContext(Dispatchers.Main) {
                removeFragmentSafely(it)
            }
        }
        state.clear()
    }

    override fun dispose() {
        super.dispose()

        val fragment = epubNavigator
        epubNavigator = null
        mainScope.launch {
            fragment?.let(::removeFragmentSafely)
        }.let { jobs.add(it) }

        state.clear()
    }

    private fun removeFragmentSafely(fragment: Fragment) {
        if (!fragment.isAdded) return
        try {
            val manager = fragment.parentFragmentManager
            manager.commitNow(allowStateLoss = manager.isStateSaved) {
                remove(fragment)
            }
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Unable to remove EPUB navigator fragment during teardown", error)
        }
    }

    suspend fun evaluateJavascript(script: String): String? {
        return withScope(mainScope) {
            val navigator = epubNavigator
            if (navigator == null) {
                Log.e(TAG, "::evaluateJavascript - epubNavigator is null!")
                return@withScope null
            }

            if (!afterFragmentStarted(navigator)) {
                Log.w(TAG, "::evaluateJavascript - navigator is not started; ignoring evaluation")
                return@withScope null
            }
            navigator.evaluateJavascript(script)
        }
    }

    fun setNavigationConfig(config: FlutterNavigationConfig) {
        epubNavigator?.setNavigationConfig(config)
    }

    fun setScrollMode(isScrollMode: Boolean) {
        epubNavigator?.setScrollMode(isScrollMode)
    }

    fun goLeft(animated: Boolean) {
        val navigator = epubNavigator
        if (navigator == null) {
            Log.e(TAG, "::goLeft - epubNavigator is null!")
            return
        }

        Log.d(TAG, "::goLeft")
        navigator.goLeft(animated)
    }

    fun goRight(animated: Boolean) {
        val navigator = epubNavigator
        if (navigator == null) {
            Log.e(TAG, "::goRight - epubNavigator is null!")
            return
        }

        Log.d(TAG, "::goRight")
        navigator.goRight(animated)
    }

    private suspend fun afterFragmentStarted(navigator: EpubReaderFragment): Boolean =
        awaitNavigatorStarted(
            started = navigator.started,
            // onPause() detaches the child navigator while MediaService keeps
            // TTS alive. Do not wait while the app is in the background; do wait
            // through a foreground startup race, including the moment before the
            // fragment reaches RESUMED (or is attached at all).
            canWait = { canWaitForVisualStart(navigator) },
        )

    suspend fun isReaderReady(): Boolean {
        return withScope(mainScope) {
            epubNavigator?.isReaderReady() ?: false
        }
    }

    suspend fun getLocatorFragments(locator: Locator): Locator? {
        val json =
            evaluateJavascript("window.epubPage.getLocatorFragments(${locator.toJSON()}, $isVerticalScroll)")
        try {
            if (json == null || json == "null" || json == "undefined") {
                Log.e(
                    TAG,
                    "getLocatorFragments: window.epubPage.getVisibleRange failed!"
                )
                return null
            }
            val jsonLocator = jsonDecode(json) as JSONObject
            val locatorWithFragments = Locator.fromJSON(jsonLocator)

            return locatorWithFragments
        } catch (e: Exception) {
            Log.e(
                TAG,
                "getLocatorFragments: window.epubPage.getVisibleRange json: $json failed! $e"
            )
        }
        return null
    }

    suspend fun firstVisibleElementLocator(): Locator? {
        val navigator = epubNavigator
        if (navigator == null) {
            Log.e(TAG, "::firstVisibleElementLocator - epubNavigator is null!")
            return null
        }

        return withScope(mainScope) {
            navigator.firstVisibleElementLocator()
        }
    }

    suspend fun applyDecorations(
        decorations: List<Decoration>,
        group: String
    ) {
        mainScope.async {
            epubNavigator?.applyDecorations(decorations, group)
        }.await()
    }

    private suspend fun scrollToLocations(
        locations: Locator.Locations,
        toStart: Boolean
    ) {
        val json = locations.toJSON().toString()
        val beforeScroll = epubNavigator?.currentLocator?.value
        Log.d(TAG, "::scrollToLocations: BEFORE scroll - currentLocator=${beforeScroll?.let {
            "href=${it.href}, prog=${it.locations.progression}"
        } ?: "null"}")
        Log.d(TAG, "::scrollToLocations: Go to locations $json, toStart: $toStart")

        evaluateJavascript("window.epubPage.scrollToLocations($json,$isVerticalScroll,$toStart);")

        val afterScroll = epubNavigator?.currentLocator?.value
        Log.d(TAG, "::scrollToLocations: AFTER scroll - currentLocator=${afterScroll?.let {
            "href=${it.href}, prog=${it.locations.progression}"
        } ?: "null"}")
    }

    /**
     * Scrolls to the exact TTS DOM range in continuous mode.
     *
     * epub.js normally prioritizes the approximate progression in vertical
     * mode. For live TTS that can leave the spoken range above the viewport,
     * so use its range processor first and fall back to normal progression.
     */
    private suspend fun scrollToSpokenRange(locations: Locator.Locations) {
        val json = locations.toJSON().toString()
        val script = """
            (() => {
                const page = window.epubPage;
                if (page == null) return false;
                try {
                    if (typeof page._processLocations === 'function' &&
                        typeof page._scrollToProcessedRange === 'function') {
                        const range = page._processLocations($json);
                        if (range != null) {
                            page._scrollToProcessedRange(range, $isVerticalScroll, true);
                            return true;
                        }
                    }
                } catch (_) {
                    // Fall through to progression-based scrolling.
                }
                return page.scrollToLocations($json, $isVerticalScroll, true);
            })();
        """.trimIndent()

        Log.d(TAG, "::scrollToSpokenRange: Go to exact spoken range $json")
        evaluateJavascript(script)
    }

    /**
     * Go to a specific locator in the EPUB navigator, this scrolls to the locator position if needed.
     */
    suspend fun goToLocator(locator: Locator, animated: Boolean): Boolean =
        goToLocator(locator, animated, forceFollow = false)

    /**
     * Keeps a live time-based locator (TTS/synchronized audio) in view.
     *
     * Unlike normal navigation, this deliberately bypasses the restore
     * progression tolerance and requests a forced range scroll. A spoken text
     * range is precise and must win over an approximate chapter progression.
     */
    suspend fun followLocator(locator: Locator, animated: Boolean): Boolean =
        withScope(mainScope) {
            val navigator = epubNavigator
            if (navigator == null || !navigator.started.value) {
                // Keep only the newest locator. There is no visual surface to
                // scroll while backgrounded, but audio playback must continue.
                pendingBackgroundFollowLocator = locator
                Log.d(TAG, "::followLocator - visual navigator is paused; deferring follow")
                return@withScope false
            }

            pendingBackgroundFollowLocator = null
            goToLocator(locator, animated, forceFollow = true)
        }

    private suspend fun goToLocator(
        locator: Locator,
        animated: Boolean,
        forceFollow: Boolean,
    ): Boolean =
        mainScope.async {
            val locations = locator.locations
            val shouldScroll = canScroll(locations)
            val locatorHref = locator.href
            val currentHref = currentLocator?.value?.href
            val shouldGo = currentHref?.isEquivalent(locatorHref) == false

            // TODO: Figure out why we can't just use rely on Readium's own go-function to scroll
            // the locator.
            if (shouldGo) {
                Log.d(TAG, "::goToLocator: Go to $locatorHref from $currentHref")
                pendingScrollToLocations = locations
                pendingFollowScroll = forceFollow
                val moved = go(locator, animated)
                if (!moved) {
                    pendingScrollToLocations = null
                    pendingFollowScroll = false
                }
                return@async moved
            } else if (!shouldScroll) {
                Log.d(TAG, "::goToLocator: Already at $locatorHref, no scroll data, staying put")
                return@async true
            } else {
                // Check if we're already at the correct progression to avoid unnecessary scroll
                // that would recalculate position from bounding rect and introduce drift.
                // This matches iOS behavior which doesn't re-scroll during restore.
                val currentProgression = currentLocator?.value?.locations?.progression
                val targetProgression = locations.progression

                if (shouldSkipCloseProgressionScroll(
                        currentProgression,
                        targetProgression,
                        forceFollow,
                    )
                ) {
                    val progressionDelta = kotlin.math.abs(currentProgression!! - targetProgression!!)
                    Log.d(TAG, "::goToLocator: Already at $locatorHref with correct progression " +
                        "(current=$currentProgression, target=$targetProgression, delta=$progressionDelta), " +
                        "skipping scroll to avoid restore drift")
                    return@async true
                }

                Log.d(TAG, "::goToLocator: Already at $locatorHref, scroll to position " +
                    "(current=${currentProgression}, target=${targetProgression})")

                if (forceFollow) {
                    scrollToSpokenRange(locations)
                } else {
                    scrollToLocations(locations, toStart = false)
                }
                return@async true
            }
        }.await()

    /**
     * Clears any deferred scroll that was queued before an explicit external restore/navigation call.
     */
    fun clearPendingScrollTarget() {
        if (pendingScrollToLocations != null) {
            Log.d(TAG, "::clearPendingScrollTarget")
        }
        pendingScrollToLocations = null
        pendingFollowScroll = false
        pendingBackgroundFollowLocator = null
    }

    companion object {
        fun restoreState(
            publication: Publication,
            listener: VisualListener,
            state: Bundle
        ): EpubNavigator {
            val locator = state.getString(currentVisualCurrentLocatorKey)
                ?.let { json -> Locator.fromJSON(JSONObject(json)) }
            val preferences = state.getString(epubPreferencesKey)
                ?.let { string -> Json.decodeFromString<EpubPreferences>(string) }
                ?: EpubPreferences()

            Log.d(TAG, "::restoreState - locator: $locator, preferences: $preferences")

            return EpubNavigator(publication, locator, listener, preferences)
        }
    }
}
