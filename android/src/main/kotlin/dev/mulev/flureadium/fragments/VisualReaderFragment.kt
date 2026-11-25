package dev.mulev.flureadium.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import dev.mulev.flureadium.EdgeTapInterceptView
import dev.mulev.flureadium.FlutterNavigationConfig
import dev.mulev.flureadium.ReadiumReader
import dev.mulev.flureadium.databinding.FragmentReaderBinding
import dev.mulev.flureadium.models.ReaderViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** DRY: shared lifecycle + helpers for EpubReaderFragment and PdfReaderFragment. */
abstract class VisualReaderFragment : BaseReaderFragment() {
    private var _binding: FragmentReaderBinding? = null
    protected val binding: FragmentReaderBinding get() = _binding!!

    val started = MutableStateFlow(false)
    var edgeTapInterceptView: EdgeTapInterceptView? = null
    var storedNavigationConfig: FlutterNavigationConfig? = null
    var attachingNavigatorFragment = false

    protected abstract val viewModel: ReaderViewModel?
    protected abstract val instanceNo: Int

    fun setNavigationConfig(config: FlutterNavigationConfig) {
        storedNavigationConfig = config
        edgeTapInterceptView?.applyConfig(config)
    }

    protected fun setupEdgeTapOverlay(rootView: FrameLayout?) {
        rootView ?: return
        val overlay = EdgeTapInterceptView(requireContext())
        overlay.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        overlay.wireCallbacks(
            onLeft        = { goLeft(animated = true) },
            onRight       = { goRight(animated = true) },
            onSwipeLeft   = { goRight(animated = true) },
            onSwipeRight  = { goLeft(animated = true) },
        )
        storedNavigationConfig?.let { overlay.applyConfig(it) }
        rootView.addView(overlay)
        edgeTapInterceptView = overlay
    }

    protected fun tearDownEdgeTapOverlay() {
        edgeTapInterceptView?.let { (view as? FrameLayout)?.removeView(it) }
        edgeTapInterceptView = null
    }

    abstract fun goLeft(animated: Boolean)
    abstract fun goRight(animated: Boolean)
    protected abstract fun attachNavigator()

    override fun onResume() {
        super.onResume()
        if (viewModel == null) { return }
        if (attachingNavigatorFragment) { return }
        attachNavigator()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (viewModel == null) { return }
        attachingNavigatorFragment = true
        lifecycleScope.launch {
            if (ReadiumReader.currentPublication != null) {
                attachNavigator()
            }
            attachingNavigatorFragment = false
        }
    }

    override fun onPause() {
        onSaveLocator()
        onDetachNavigator()
        started.value = false
        attachingNavigatorFragment = false
        tearDownEdgeTapOverlay()
        super.onPause()
    }

    protected open fun onSaveLocator() {
        viewModel?.locator = currentLocator?.value
    }

    protected abstract fun onDetachNavigator()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
