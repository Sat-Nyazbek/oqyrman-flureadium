package dev.mulev.flureadium.fragments

import android.util.Log
import androidx.fragment.app.commitNow
import dev.mulev.flureadium.R
import dev.mulev.flureadium.ReadiumReader
import dev.mulev.flureadium.models.PdfReaderViewModel
import dev.mulev.flureadium.models.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

private const val TAG = "PdfReaderFragment"
private var _instanceCounter = 0

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class PdfReaderFragment : VisualReaderFragment(), PdfNavigatorFragment.Listener,
    CoroutineScope by MainScope() {

    interface Listener {
        fun onPageLoaded()
        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator)
        fun onExternalLinkActivated(url: AbsoluteUrl)
    }

    var listener: Listener? = null

    override val instanceNo = ++_instanceCounter
    override val viewModel: ReaderViewModel? get() = vm as PdfReaderViewModel?

    private var pdfNavigator
        get() = navigator as? PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>
        set(value) { navigator = value }

    private val pdfVm get() = vm as PdfReaderViewModel?

    @ExperimentalReadiumApi
    fun onExternalLinkActivated(url: AbsoluteUrl) {
        listener?.onExternalLinkActivated(url)
    }

    fun updatePreferences(
        fit: org.readium.r2.navigator.preferences.Fit?,
        scroll: Boolean?,
        spread: org.readium.r2.navigator.preferences.Spread?,
        offsetFirstPage: Boolean?
    ) {
        Log.d(TAG, "::updatePreferences")
        // PDF preferences are applied at navigator creation time.
    }

    override fun goLeft(animated: Boolean) {
        Log.d(TAG, "::goLeft")
        val nav = pdfNavigator ?: return Unit.also { Log.d(TAG, "::goLeft. Navigator not ready.") }
        launch {
            if (nav.goBackward(animated)) Log.d(TAG, "::goLeft: Went back.")
            else Log.d(TAG, "::goLeft: Couldn't go back.")
        }
    }

    override fun goRight(animated: Boolean) {
        Log.d(TAG, "::goRight")
        val nav = pdfNavigator ?: return Unit.also { Log.d(TAG, "::goRight. Navigator not ready.") }
        launch {
            if (nav.goForward(animated)) Log.d(TAG, "::goRight: Went forward.")
            else Log.d(TAG, "::goRight: Couldn't go forward.")
        }
    }

    override fun onDetachNavigator() {
        pdfNavigator?.let { childFragmentManager.commitNow { remove(it) } }
        pdfNavigator = null
    }

    override fun attachNavigator() {
        Log.d(TAG, "::attachNavigator() - $instanceNo")
        if (navigator != null) { Log.d(TAG, "::attachNavigator() - $instanceNo - already attached"); return }

        val model = pdfVm ?: return Unit.also { Log.e(TAG, "::attachNavigator() - $instanceNo - missing view model") }
        if (ReadiumReader.currentPublication == null) return Unit.also { Log.e(TAG, "::attachNavigator() - $instanceNo - missing publication") }
        val navigatorFactory = model.navigatorFactory ?: return Unit.also { Log.e(TAG, "::attachNavigator() - $instanceNo - missing navigator factory") }

        val fragmentFactory = navigatorFactory.createFragmentFactory(
            initialLocator = model.locator,
            listener = this,
        )

        val pdfNavigatorFragment = fragmentFactory.instantiate(
            requireActivity().classLoader,
            PdfNavigatorFragment::class.java.name,
        ) as PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

        Log.d(TAG, "::attachNavigator - $instanceNo - add fragment")
        childFragmentManager.commitNow { add(R.id.fragment_reader_container, pdfNavigatorFragment, NAVIGATOR_FRAGMENT_TAG) }

        navigator = pdfNavigatorFragment
        started.value = true
        setupEdgeTapOverlay(view as? android.widget.FrameLayout)
        listener?.onPageLoaded()
        Log.d(TAG, "::attachNavigator() - $instanceNo - done, navigator=$navigator")
    }

    companion object {
        private const val NAVIGATOR_FRAGMENT_TAG = "READIUM_PDF_READER_FRAGMENT"
    }
}
