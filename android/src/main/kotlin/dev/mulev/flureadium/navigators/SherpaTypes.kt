package dev.mulev.flureadium.navigators

import android.util.Log
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.navigator.preferences.PreferencesEditor
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import java.io.File

/** Voice option for the Sherpa TTS engine. */
@OptIn(ExperimentalReadiumApi::class)
data class SherpaVoice(
    val id: String,
    override val language: Language,
    val name: String,
    val quality: String = "normal",
    val requiresNetwork: Boolean = false,
    val modelId: String = id,
    val speakerId: Int = 0,
) : TtsEngine.Voice

/** DRY: single source of truth for all available Sherpa voices.
 * Used by SherpaReadiumTtsEngine (Readium TTS) and PublicationChannel (Flutter channel). */
@OptIn(ExperimentalReadiumApi::class)
val SHERPA_VOICES: Set<SherpaVoice> = setOf(
    SherpaVoice("iseke-x_low-int8", Language("kk"), "Ер дауысы · жеңіл"),
    SherpaVoice("raya-x_low-int8", Language("kk"), "Әйел дауысы · жеңіл"),
)

internal fun sherpaVoiceDefinition(voiceId: String): SherpaVoice? =
    SHERPA_VOICES.firstOrNull { it.id == voiceId }

/** Error type for the Sherpa TTS engine. */
@OptIn(ExperimentalReadiumApi::class)
data class SherpaError(val reason: String) : TtsEngine.Error {
    override val message: String get() = reason
    override val cause: org.readium.r2.shared.util.Error? get() = null
}

/** Resolved settings snapshot for the Sherpa TTS engine. */
@OptIn(ExperimentalReadiumApi::class)
data class SherpaSettings(
    override val language: Language,
    override val overrideContentLanguage: Boolean = false,
    val speed: Float = 1.0f,
    val voiceIdentifier: String? = null,
) : TtsEngine.Settings

/** User-editable preferences for the Sherpa TTS engine. */
@OptIn(ExperimentalReadiumApi::class)
data class SherpaPreferences(
    override val language: Language? = null,
    val speed: Double? = null,
    val voiceIdentifier: String? = null,
) : TtsEngine.Preferences<SherpaPreferences> {
    /** Merge: other's non-null values override this. */
    override fun plus(other: SherpaPreferences): SherpaPreferences = SherpaPreferences(
        language = other.language ?: language,
        speed = other.speed ?: speed,
        voiceIdentifier = other.voiceIdentifier ?: voiceIdentifier,
    )
}

/** Preferences editor for the Sherpa TTS engine. */
@OptIn(ExperimentalReadiumApi::class)
class SherpaPreferencesEditor(
    initialPreferences: SherpaPreferences
) : PreferencesEditor<SherpaPreferences> {

    private var _preferences: SherpaPreferences = initialPreferences

    override val preferences: SherpaPreferences
        get() = _preferences

    override fun clear() {
        _preferences = SherpaPreferences()
    }
}
