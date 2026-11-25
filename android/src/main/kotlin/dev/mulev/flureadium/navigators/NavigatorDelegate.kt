package dev.mulev.flureadium.navigators

import dev.mulev.flureadium.FlutterDecorationPreferences
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * Abstraction over the host (typically [dev.mulev.flureadium.ReadiumReader]) that
 * timebased navigators use for scrolling and decorating. Decouples navigators from
 * the global ReadiumReader singleton, making isolated unit-testing possible.
 */
@OptIn(ExperimentalReadiumApi::class)
interface NavigatorDelegate {
    /** Scroll the EPUB viewport to [locator]. */
    fun scrollTo(locator: Locator)

    /**
     * Apply [decorations] for the given decoration [group] to the current EPUB view.
     * Suspend because the underlying navigator call may switch to Main dispatcher.
     */
    suspend fun applyDecorations(decorations: List<Decoration>, group: String)

    /** Current decoration preferences (highlight colours, styles). */
    val decorationStyle: FlutterDecorationPreferences
}
