package dev.mulev.flureadium

import android.graphics.Color
import android.os.Bundle
import org.readium.r2.navigator.Decoration

// TODO: Decision on appropriate defaults
// TODO: Can this be made configurable at built time?
// TODO: More complex styles? Like bold or italic plus background and text colors?
private val defaultUtteranceStyle = Decoration.Style.Highlight(tint = Color.YELLOW)
private val defaultCurrentRangeStyle = Decoration.Style.Underline(tint = Color.RED)

private const val uttStyleTypeKey = "uttStyleType"
private const val uttStyleTintKey = "uttStyleTint"
private const val rangeStyleTypeKey = "rangeStyleType"
private const val rangeStyleTintKey = "rangeStyleTint"

private fun Decoration.Style?.typeName(): String = when (this) {
    is Decoration.Style.Underline -> "underline"
    else -> "highlight"
}

private fun Decoration.Style?.tintInt(): Int = when (this) {
    is Decoration.Style.Underline -> this.tint
    is Decoration.Style.Highlight -> this.tint
    else -> Color.TRANSPARENT
}

private fun styleFromBundle(typeKey: String, tintKey: String, bundle: Bundle, default: Decoration.Style): Decoration.Style {
    val type = bundle.getString(typeKey) ?: return default
    val tint = bundle.getInt(tintKey, Color.TRANSPARENT)
    return when (type) {
        "underline" -> Decoration.Style.Underline(tint)
        else -> Decoration.Style.Highlight(tint)
    }
}

/**
 * Decoration preferences used in the Flutter Readium plugin.
 */
data class FlutterDecorationPreferences(
    /**
     * Style for utterance decoration.
     */
    var utteranceStyle: Decoration.Style? = defaultUtteranceStyle,

    /**
     * Style for current reading range decoration.
     */
    var currentRangeStyle: Decoration.Style? = defaultCurrentRangeStyle
) {
    /** Stores primitive (type + tint) values into [bundle]. No Serializable involved. */
    fun storeInBundle(bundle: Bundle) {
        bundle.putString(uttStyleTypeKey, utteranceStyle.typeName())
        bundle.putInt(uttStyleTintKey, utteranceStyle.tintInt())
        bundle.putString(rangeStyleTypeKey, currentRangeStyle.typeName())
        bundle.putInt(rangeStyleTintKey, currentRangeStyle.tintInt())
    }

    companion object {
        /** Reconstructs preferences from a Bundle written by [storeInBundle]. */
        fun fromBundle(bundle: Bundle): FlutterDecorationPreferences = FlutterDecorationPreferences(
            utteranceStyle = styleFromBundle(uttStyleTypeKey, uttStyleTintKey, bundle, defaultUtteranceStyle),
            currentRangeStyle = styleFromBundle(rangeStyleTypeKey, rangeStyleTintKey, bundle, defaultCurrentRangeStyle),
        )

        /**
         * Create Decoration.Style from map.
         */
        fun fromMap(
            uttDecoMap: Map<*, *>?,
            rangeDecoMap: Map<*, *>?
        ): FlutterDecorationPreferences {
            return FlutterDecorationPreferences(
                decorationStyleFromMap(uttDecoMap) ?: defaultUtteranceStyle,
                decorationStyleFromMap(rangeDecoMap) ?: defaultCurrentRangeStyle,
            )
        }
    }
}
