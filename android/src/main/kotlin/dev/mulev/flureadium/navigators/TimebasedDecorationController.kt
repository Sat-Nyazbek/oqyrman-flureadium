package dev.mulev.flureadium.navigators

import android.os.Bundle
import android.util.Log
import dev.mulev.flureadium.letIfBothNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

private const val TAG = "DecorationController"
private const val KEY_UTT_LOCATOR = "dec_uttLocator"
private const val KEY_TOKEN_LOCATOR = "dec_tokenLocator"

/**
 * Manages TTS/sync-audio decorations (sentence highlight, word highlight) for a single
 * decoration group.
 *
 * - Applies decorations atomically via a [Mutex] so that concurrent clear + decorate calls
 *   from two coroutines never leave a partially-updated state visible.
 * - Tracks the last decorated [Locator]s so callers can reapply decorations after a style
 *   change or screen rotation without needing to remember the last position themselves.
 * - Persists the last locators into a [Bundle] (via [storeInBundle] / [restoreFromBundle])
 *   so decorations survive Activity recreation.
 *
 * @param group              Decoration group name, e.g. `"tts"` or `"sync-audio"`.
 * @param utteranceDecorationId  ID used for the full-sentence decoration.
 * @param rangeDecorationId  ID used for the current-word decoration, or `null` if not needed.
 * @param delegate           Host that owns the EPUB navigator used to apply decorations.
 */
@OptIn(ExperimentalReadiumApi::class)
class TimebasedDecorationController(
    val group: String,
    private val utteranceDecorationId: String,
    private val rangeDecorationId: String?,
    private val delegate: NavigatorDelegate,
) {
    private val mutex = Mutex()

    private var _lastUttLocator: Locator? = null
    private var _lastTokenLocator: Locator? = null

    /** Href of the resource that was last decorated, or `null` before first decoration. */
    val lastDecoratedHref: String?
        get() = _lastUttLocator?.href?.toString()

    /**
     * Build and apply decorations for [uttLocator] (the current sentence) and, optionally,
     * [tokenLocator] (the current word). Holds [mutex] during the apply call so that a
     * concurrent [clear] cannot interleave.
     */
    suspend fun decorate(uttLocator: Locator, tokenLocator: Locator?) {
        mutex.withLock {
            try {
                val decorations = buildDecorations(uttLocator, tokenLocator)
                delegate.applyDecorations(decorations, group)
                _lastUttLocator = uttLocator
                _lastTokenLocator = tokenLocator
            } catch (e: Throwable) {
                Log.e(TAG, "[$group] decorate failed: ${e.message}", e)
            }
        }
    }

    /** Remove all decorations in this group. */
    suspend fun clear() {
        mutex.withLock {
            try {
                delegate.applyDecorations(emptyList(), group)
            } catch (e: Throwable) {
                Log.e(TAG, "[$group] clear failed: ${e.message}", e)
            }
        }
    }

    /**
     * Re-apply the last known decorations. Useful when the decoration style changes (e.g. the
     * user switches highlight colour) while the same sentence is still being read.
     * Does nothing if no decoration has been applied yet.
     */
    suspend fun reapply() {
        val utt = _lastUttLocator ?: return
        decorate(utt, _lastTokenLocator)
    }

    // region Bundle persistence

    /**
     * Persist the last decorated locators into [bundle] so they can be restored after
     * Activity recreation (e.g. screen rotation).
     */
    fun storeInBundle(bundle: Bundle) {
        bundle.putString(KEY_UTT_LOCATOR, _lastUttLocator?.toJSON()?.toString())
        bundle.putString(KEY_TOKEN_LOCATOR, _lastTokenLocator?.toJSON()?.toString())
    }

    /**
     * Restore the last decorated locators from [bundle]. Call [reapply] afterwards to
     * actually render the decorations.
     */
    fun restoreFromBundle(bundle: Bundle) {
        _lastUttLocator = bundle.getString(KEY_UTT_LOCATOR)
            ?.let { Locator.fromJSON(JSONObject(it)) }
        _lastTokenLocator = bundle.getString(KEY_TOKEN_LOCATOR)
            ?.let { Locator.fromJSON(JSONObject(it)) }
    }

    // endregion

    // region Helpers

    private fun buildDecorations(uttLocator: Locator, tokenLocator: Locator?): List<Decoration> {
        val style = delegate.decorationStyle
        val decorations = mutableListOf<Decoration>()
        style.utteranceStyle?.let { s ->
            decorations += Decoration(id = utteranceDecorationId, locator = uttLocator, style = s)
        }
        if (rangeDecorationId != null) {
            letIfBothNotNull(tokenLocator, style.currentRangeStyle)?.let { (loc, s) ->
                decorations += Decoration(id = rangeDecorationId, locator = loc, style = s)
            }
        }
        return decorations
    }

    // endregion
}
