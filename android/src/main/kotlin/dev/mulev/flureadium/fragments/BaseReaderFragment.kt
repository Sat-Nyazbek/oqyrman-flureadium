package dev.mulev.flureadium.fragments

import androidx.fragment.app.Fragment
import dev.mulev.flureadium.models.ReaderViewModel
import org.readium.r2.navigator.Navigator
import org.readium.r2.shared.publication.Locator

abstract class BaseReaderFragment : Fragment() {
    var vm: ReaderViewModel? = null
    protected open var navigator: Navigator? = null

    val currentLocator get() = navigator?.currentLocator

    open fun go(locator: Locator?, animated: Boolean): Boolean {
        if (locator == null) return false
        return navigator?.go(locator, animated) ?: false
    }
}
