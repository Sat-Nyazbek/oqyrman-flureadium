package dev.mulev.flureadium.navigators

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commitNow
import dev.mulev.flureadium.FlutterNavigationConfig
import dev.mulev.flureadium.FlutterPdfPreferences
import dev.mulev.flureadium.ReadiumReaderWidget.Companion.NAVIGATOR_FRAGMENT_TAG
import dev.mulev.flureadium.fragments.PdfReaderFragment
import dev.mulev.flureadium.models.PdfReaderViewModel
import dev.mulev.flureadium.throttleLatest
import dev.mulev.flureadium.withScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "PdfNavigator"
private const val currentVisualCurrentLocatorKey = "currentVisualCurrentLocator"
private const val pdfPreferencesKey = "pdfPreferences"

/**
 * PdfNavigator wraps PdfReaderFragment and provides methods to interact with PDF reading.
 * It follows the same pattern as EpubNavigator for consistency.
 */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class PdfNavigator : BaseNavigator, PdfReaderFragment.Listener {
    private val flutterPreferences: FlutterPdfPreferences

    constructor(
        publication: Publication,
        initialLocator: Locator?,
        visualListener: VisualListener,
        initialPreferences: FlutterPdfPreferences = FlutterPdfPreferences()
    ) : super(publication, initialLocator) {
        this.flutterPreferences = initialPreferences
        this.visualListener = visualListener

        this.state[currentVisualCurrentLocatorKey] = initialLocator
        this.state[pdfPreferencesKey] = initialPreferences
    }

    /**
     * VisualListener for PDF navigator events.
     * Mirrors the EpubNavigator.VisualListener interface.
     */
    interface VisualListener {
        /**
         * Called when a page has loaded.
         */
        fun onPageLoaded()

        /**
         * Called when the current page has changed.
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
     * PdfReaderFragment instance used as navigator.
     */
    private var pdfNavigator: PdfReaderFragment? = null

    /**
     * Engine provider for PDF rendering.
     */
    private var engineProvider: PdfiumEngineProvider? = null

    /**
     * Current locator in the PDF navigator.
     */
    val currentLocator
        get() = pdfNavigator?.currentLocator

    override suspend fun initNavigator() {
        // Keep the provider in a local value while building the nested view model.
        // Inside PdfReaderViewModel.apply, an unqualified `engineProvider` resolves
        // to the view model's nullable property, not PdfNavigator.engineProvider.
        val provider = PdfiumEngineProvider()
        engineProvider = provider

        pdfNavigator = PdfReaderFragment().apply {
            vm = PdfReaderViewModel().apply {
                navigatorFactory = PdfNavigatorFactory(
                    publication,
                    pdfEngineProvider = provider
                )
                locator = this@PdfNavigator.initialLocator
                fit = this@PdfNavigator.flutterPreferences.toReadiumFit()
                scroll = this@PdfNavigator.flutterPreferences.toReadiumScroll()
                spread = this@PdfNavigator.flutterPreferences.toReadiumSpread()
                offsetFirstPage = this@PdfNavigator.flutterPreferences.toReadiumOffsetFirstPage()
                this.engineProvider = provider
            }
            listener = this@PdfNavigator
        }
    }

    /**
     * Attach the PDF navigator fragment to the given FragmentManager and ViewGroup.
     */
    fun attachNavigator(fragmentManager: FragmentManager, viewGroup: ViewGroup) {
        val navigator = pdfNavigator ?: return
        mainScope.launch {
            fragmentManager.commitNow(allowStateLoss = fragmentManager.isStateSaved) {
                add(viewGroup, navigator, NAVIGATOR_FRAGMENT_TAG)
            }
        }
    }

    /**
     * Go to a specific locator in the PDF navigator.
     */
    suspend fun go(locator: Locator, animated: Boolean): Boolean {
        val navigator = pdfNavigator
        if (navigator == null) {
            Log.d(TAG, "::go - pdfNavigator is null!")
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
     * Dynamically update PDF navigator preferences.
     *
     * Most preference changes (fit, scroll, spread, offsetFirstPage) are applied live to the
     * running [PdfReaderFragment] via [PdfReaderFragment.updatePreferences].  No navigator
     * restart is needed for these properties.
     *
     * **Limitation**: changes take effect only after the fragment has fully started
     * ([PdfReaderFragment.started] == true).  Preferences submitted before the fragment
     * is ready are queued in [PdfReaderViewModel] and applied on first draw.
     */
    fun updatePreferences(preferences: FlutterPdfPreferences) {
        Log.d(TAG, "::updatePreferences")

        try {
            pdfNavigator?.updatePreferences(
                fit = preferences.toReadiumFit(),
                scroll = preferences.toReadiumScroll(),
                spread = preferences.toReadiumSpread(),
                offsetFirstPage = preferences.toReadiumOffsetFirstPage()
            )
            state[pdfPreferencesKey] = preferences
        } catch (ex: Exception) {
            Log.e(TAG, "Error applying PdfPreferences: $ex")
        }
    }

    override fun setupNavigatorListeners() {
        val navigator = pdfNavigator
        if (navigator == null) {
            Log.e(TAG, "::setupNavigatorListeners - pdfNavigator is null this should never happen")
            return
        }

        val currentLocator = navigator.currentLocator
        if (currentLocator != null) {
            currentLocator.throttleLatest(100.milliseconds)
                .distinctUntilChanged()
                .onEach { locator ->
                    onCurrentLocatorChanges(locator)
                    state[currentVisualCurrentLocatorKey] = locator
                }
                .launchIn(mainScope)
                .let { jobs.add(it) }
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

            (state[pdfPreferencesKey] as? FlutterPdfPreferences)?.let { prefs ->
                putString(
                    pdfPreferencesKey,
                    FlutterPdfPreferences.toJSON(prefs).toString()
                )
            }
        }
    }

    override fun onPageLoaded() {
        Log.d(TAG, "::onPageLoaded")
        visualListener.onPageLoaded()

        notifyIsReady()
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

        val fragment = pdfNavigator
        pdfNavigator = null
        fragment?.let {
            withContext(Dispatchers.Main) {
                removeFragmentSafely(it)
            }
        }
        state.clear()
    }

    override fun dispose() {
        super.dispose()

        val fragment = pdfNavigator
        pdfNavigator = null
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
            Log.w(TAG, "Unable to remove PDF navigator fragment during teardown", error)
        }
    }

    fun setNavigationConfig(config: FlutterNavigationConfig) {
        pdfNavigator?.setNavigationConfig(config)
    }

    private fun requirePdfNavigator(action: String): PdfReaderFragment? =
        pdfNavigator ?: run { Log.e(TAG, "::$action - pdfNavigator is null!"); null }

    fun goLeft(animated: Boolean) {
        val navigator = requirePdfNavigator("goLeft") ?: return
        Log.d(TAG, "::goLeft")
        navigator.goLeft(animated)
    }

    fun goRight(animated: Boolean) {
        val navigator = requirePdfNavigator("goRight") ?: return
        Log.d(TAG, "::goRight")
        navigator.goRight(animated)
    }

    private suspend fun afterFragmentStarted(navigator: PdfReaderFragment): Boolean =
        awaitNavigatorStarted(
            started = navigator.started,
            // onPause() detaches the child navigator while MediaService keeps
            // TTS alive. Do not wait while the app is in the background; do wait
            // through a foreground startup race, including the moment before the
            // fragment reaches RESUMED (or is attached at all).
            canWait = { canWaitForVisualStart(navigator) },
        )

    /**
     * Go to a specific locator in the PDF navigator.
     */
    suspend fun goToLocator(locator: Locator, animated: Boolean) {
        mainScope.async {
            go(locator, animated)
        }.await()
    }

    companion object {
        fun restoreState(
            publication: Publication,
            listener: VisualListener,
            state: Bundle
        ): PdfNavigator {
            val locator = state.getString(currentVisualCurrentLocatorKey)
                ?.let { json -> Locator.fromJSON(JSONObject(json)) }
            val preferences = state.getString(pdfPreferencesKey)
                ?.let { string -> FlutterPdfPreferences.fromJSON(string) }
                ?: FlutterPdfPreferences()

            Log.d(TAG, "::restoreState - locator: $locator, preferences: $preferences")

            return PdfNavigator(publication, locator, listener, preferences)
        }
    }
}
