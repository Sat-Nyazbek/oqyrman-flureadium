@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.mulev.flureadium.navigators

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import dev.mulev.flureadium.ReadiumReader
import kotlinx.coroutines.*
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try

internal class HybridEngineProvider(
    private val lookAhead: SpeechLookAhead,
) : TtsEngineProvider<SherpaSettings, SherpaPreferences, SherpaPreferencesEditor, SherpaError, SherpaVoice> {
    override suspend fun createEngine(publication: Publication, initialPreferences: SherpaPreferences):
        Try<SpeechEngine, org.readium.r2.shared.util.Error> {
        val system = AndroidReadiumTtsEngine.create(ReadiumReader.application)
        try {
            val installed = withContext(Dispatchers.IO) { installedSherpaVoices(ReadiumReader.application.applicationInfo.dataDir) }
            val resolver = try { OnDeviceSpeechLanguageResolver() }
                catch (_: Exception) { object : SpeechLanguageResolver {
                    override suspend fun resolve(text: String, hint: String) = hint
                } }
            return Try.success(HybridReadiumTtsEngine(initialPreferences, system,
                { SherpaReadiumTtsEngine(initialPreferences) }, hybridVoiceCatalog(installed, system?.voices.orEmpty()),
                resolver, lookAhead))
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.Main.immediate) { system?.close() }
            throw e
        }
    }
    override fun createPreferencesEditor(publication: Publication, initialPreferences: SherpaPreferences) = SherpaPreferencesEditor(initialPreferences)
    override fun createEmptyPreferences() = SherpaPreferences()
    override fun getPlaybackParameters(settings: SherpaSettings) = PlaybackParameters(settings.speed)
    override fun updatePlaybackParameters(preferences: SherpaPreferences, playbackParameters: PlaybackParameters) =
        preferences.copy(speed = playbackParameters.speed.toDouble())
    override fun mapEngineError(error: SherpaError) = PlaybackException(error.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
}
