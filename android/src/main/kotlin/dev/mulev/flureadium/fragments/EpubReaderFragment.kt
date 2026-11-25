package dev.mulev.flureadium.fragments

import android.util.Log
import androidx.fragment.app.commitNow
import dev.mulev.flureadium.R
import dev.mulev.flureadium.ReadiumReader
import dev.mulev.flureadium.models.EpubReaderViewModel
import dev.mulev.flureadium.models.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

private const val TAG = "EpubReaderFragment"
private var _instanceCounter = 0

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderFragment : VisualReaderFragment(), EpubNavigatorFragment.Listener,
    EpubNavigatorFragment.PaginationListener, CoroutineScope by MainScope() {

    interface Listener {
        fun onPageLoaded()
        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator)
        fun onExternalLinkActivated(url: AbsoluteUrl)
    }

    var listener: Listener? = null

    override val instanceNo = ++_instanceCounter
    override val viewModel: ReaderViewModel? get() = vm as EpubReaderViewModel?

    private var epubNavigator
        get() = navigator as? EpubNavigatorFragment
        set(value) { navigator = value }

    private val epubVm get() = vm as EpubReaderViewModel?

    @ExperimentalReadiumApi
    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        listener?.onExternalLinkActivated(url)
    }

    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
        listener?.onPageChanged(pageIndex, totalPages, locator)
    }

    override fun onPageLoaded() {
        listener?.onPageLoaded()
    }

    suspend fun firstVisibleElementLocator(): Locator? {
        return epubNavigator?.firstVisibleElementLocator()
    }

    suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        epubNavigator?.applyDecorations(decorations, group)
    }

    suspend fun evaluateJavascript(script: String): String? {
        return epubNavigator?.evaluateJavascript(script)
    }

    suspend fun isReaderReady(): Boolean =
        started.value && evaluateJavascript("window.epubPage.isReaderReady();") == "true"

    fun updatePreferences(preferences: EpubPreferences) {
        epubNavigator?.submitPreferences(preferences)
    }

    fun setScrollMode(isScrollMode: Boolean) {
        edgeTapInterceptView?.setScrollMode(isScrollMode)
    }

    override fun goLeft(animated: Boolean) {
        val nav = epubNavigator ?: return
        launch {
            nav.goBackward(animated)
        }
    }

    override fun goRight(animated: Boolean) {
        val nav = epubNavigator ?: return
        launch {
            nav.goForward(animated)
        }
    }

    override fun onSaveLocator() {
        val saved = currentLocator?.value
        epubVm?.locator = saved
    }

    override fun onDetachNavigator() {
        epubNavigator?.let { childFragmentManager.commitNow { remove(it) } }
        epubNavigator = null
    }

    override fun attachNavigator() {
        if (navigator != null) { return }

        val model = epubVm ?: return Unit.also { Log.e(TAG, "::attachNavigator() - $instanceNo - missing view model") }
        if (ReadiumReader.currentPublication == null) return Unit.also { Log.e(TAG, "::attachNavigator() - $instanceNo - missing publication") }

        val preferences = model.preferences ?: EpubPreferences()
        model.preferences = preferences
        val fragmentFactory = model.navigatorFactory!!.createFragmentFactory(
            configuration = EpubNavigatorFragment.Configuration(
                shouldApplyInsetsPadding = false,
                servedAssets = listOf("flutter_assets/packages/flureadium/assets/.*"),
            ),
            initialLocator = model.locator,
            listener = this,
            paginationListener = this,
            initialPreferences = preferences,
        )

        val epubNavigator = fragmentFactory.instantiate(
            requireActivity().classLoader,
            EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment

        childFragmentManager.commitNow { add(R.id.fragment_reader_container, epubNavigator, NAVIGATOR_FRAGMENT_TAG) }

        navigator = epubNavigator
        started.value = true
        setupEdgeTapOverlay(view as? android.widget.FrameLayout)
    }

    companion object {
        private const val NAVIGATOR_FRAGMENT_TAG = "READIUM_EPUB_READER_FRAGMENT"
    }
}
