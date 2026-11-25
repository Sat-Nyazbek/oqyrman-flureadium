@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.mulev.flureadium.navigators

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.util.Language
import java.util.concurrent.atomic.AtomicLong

internal typealias SpeechEngine = TtsEngine<SherpaSettings, SherpaPreferences, SherpaError, SherpaVoice>
interface SpeechPrefetcher {
    fun prefetch(text: String, language: Language?, voiceIdentifier: String?)
}

/** One Readium session; Android and Sherpa never own audible output simultaneously. */
internal class HybridReadiumTtsEngine(
    initialPreferences: SherpaPreferences,
    private val system: SpeechEngine?,
    private val sherpaFactory: () -> SpeechEngine,
    override val voices: Set<SherpaVoice>,
    private val languageResolver: SpeechLanguageResolver,
    private val lookAhead: SpeechLookAhead? = null,
) : SpeechEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicLong()
    private val sequence = AtomicLong()
    private var resolving: Job? = null
    private var sherpa: SpeechEngine? = null
    private var preferences = initialPreferences
    @Volatile private var listener: TtsEngine.Listener<SherpaError>? = null
    @Volatile private var closed = false
    private val failedSystemVoices = mutableSetOf<String>()
    private enum class Backend { SYSTEM, SHERPA }
    private data class Request(
        val id: TtsEngine.RequestId, val text: String, val preferences: SherpaPreferences,
        var language: String, var internalId: TtsEngine.RequestId,
        var backend: Backend = Backend.SYSTEM, var started: Boolean = false,
        var selectedVoice: String? = null,
        /** The book/voice language before auto-detection; used when detection picks a language without a voice. */
        val hint: String = language,
    )
    private var active: Request? = null
    private var dispatchedPrefetch: String? = null
    override val settings = MutableStateFlow(resolveSettings(initialPreferences))

    init { system?.setListener(backendListener(Backend.SYSTEM)) }

    override fun setListener(listener: TtsEngine.Listener<SherpaError>?) { this.listener = listener }
    override fun submitPreferences(preferences: SherpaPreferences) {
        scope.launch { applyPreferences(preferences) }
    }
    private fun applyPreferences(preferences: SherpaPreferences) {
        if (preferences.voiceIdentifier != this.preferences.voiceIdentifier) {
            preferences.voiceIdentifier?.let { failedSystemVoices.remove(it) }
        }
        this.preferences = this.preferences + preferences
        settings.value = resolveSettings(this.preferences)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        scope.launch { speakOnMain(requestId, text, language) }
    }

    private fun speakOnMain(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        if (closed) { listener?.onError(requestId, SherpaError("TTS engine closed")); return }
        if (active != null) stopOnMain()
        val epoch = generation.get()
        val snapshot = preferences
        val selected = voices.firstOrNull { it.id == snapshot.voiceIdentifier && !it.id.startsWith("auto:") }
        val hint = selected?.language?.code ?: language?.code ?: snapshot.language?.code ?: "kk"
        val request = Request(requestId, text, snapshot, hint, nextId())
        active = request
        resolving = scope.launch {
            try {
                val resolved = if (selected == null) languageResolver.resolve(text, hint) else hint
                if (closed || epoch != generation.get() || active !== request) return@launch
                request.language = resolved
                start(request, fallback = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(request, SherpaError(e.message ?: "TTS routing failed")) }
        }
    }

    private fun nextId() = TtsEngine.RequestId("hybrid-${sequence.incrementAndGet()}")

    private fun start(request: Request, fallback: Boolean) {
        if (active !== request || closed) return
        val available = voices.filterNot { it.id in failedSystemVoices }.toSet()
        val systemFailed = fallback || system == null
        var voice = HybridVoicePolicy.choose(request.language, request.preferences.voiceIdentifier, available,
            systemFailed = systemFailed)
        if (voice == null && request.language != request.hint) {
            // Language detection can mislabel Kazakh as a related language (ky, tt, mn...) for which
            // no voice is installed. Read it with the book's language instead of stopping playback.
            Log.i("HybridTTS", "no voice for detected ${request.language}; falling back to ${request.hint}")
            request.language = request.hint
            voice = HybridVoicePolicy.choose(request.language, request.preferences.voiceIdentifier, available,
                systemFailed = systemFailed)
        }
        if (voice == null) {
            fail(request, SherpaError("No installed offline TTS voice for ${request.language}")); return
        }
        request.selectedVoice = voice.id
        request.backend = if (voice.id.startsWith("system:")) Backend.SYSTEM else Backend.SHERPA
        request.internalId = nextId()
        val engine = if (request.backend == Backend.SYSTEM) system!! else sherpa ?: sherpaFactory().also {
            sherpa = it
            it.setListener(backendListener(Backend.SHERPA))
        }
        Log.i("HybridTTS", "route=${request.backend} language=${request.language} voice=${voice.id} fallback=$fallback")
        engine.submitPreferences(request.preferences.copy(language = Language(request.language), voiceIdentifier = voice.id))
        try { engine.speak(request.internalId, request.text, Language(request.language)) }
        catch (e: Exception) { onBackendError(request, SherpaError(e.message ?: "TTS request failed")) }
    }

    private fun backendListener(backend: Backend) = object : TtsEngine.Listener<SherpaError> {
        private fun event(id: TtsEngine.RequestId, action: (Request) -> Unit) {
            scope.launch { active?.takeIf { !closed && it.internalId == id && it.backend == backend }?.let(action) }
        }
        override fun onStart(requestId: TtsEngine.RequestId) = event(requestId) {
            if (!it.started) {
                it.started = true
                listener?.onStart(it.id)
            }
            prefetchIfPossible(it)
        }
        override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) = event(requestId) { listener?.onRange(it.id, range) }
        override fun onDone(requestId: TtsEngine.RequestId) = event(requestId) {
            active = null
            listener?.onDone(it.id)
        }
        override fun onInterrupted(requestId: TtsEngine.RequestId) = event(requestId) {
            active = null
            listener?.onInterrupted(it.id)
        }
        override fun onFlushed(requestId: TtsEngine.RequestId) = event(requestId) {
            active = null
            listener?.onFlushed(it.id)
        }
        override fun onError(requestId: TtsEngine.RequestId, error: SherpaError) = event(requestId) { onBackendError(it, error) }
    }

    private fun prefetchIfPossible(request: Request) {
        if (!request.started || request.backend != Backend.SHERPA || active !== request) return
        val nextSentence = lookAhead?.followingSentence(request.text) ?: return
        val signature = "${request.internalId.value}\u0000$nextSentence"
        if (signature == dispatchedPrefetch) return
        dispatchedPrefetch = signature
        (sherpa as? SpeechPrefetcher)
            ?.prefetch(nextSentence, Language(request.language), request.selectedVoice)
    }

    private fun onBackendError(request: Request, error: SherpaError) {
        if (active !== request) return
        if (request.backend == Backend.SYSTEM) {
            request.selectedVoice?.let { failedSystemVoices.add(it) }
            // Invalidate the Android utterance before stop() can emit more callbacks.
            request.internalId = nextId()
            system?.stop()
            try { start(request, fallback = true) }
            catch (e: Exception) { fail(request, SherpaError(e.message ?: "Sherpa fallback failed")) }
        } else fail(request, error)
    }

    private fun fail(request: Request, error: SherpaError) {
        if (active !== request) return
        active = null
        system?.stop()
        sherpa?.stop()
        listener?.onError(request.id, error)
    }

    override fun stop() {
        scope.launch { stopOnMain() }
    }
    private fun stopOnMain() {
        generation.incrementAndGet()
        resolving?.cancel()
        val interrupted = active
        active = null
        dispatchedPrefetch = null
        system?.stop()
        sherpa?.stop()
        interrupted?.let { listener?.onInterrupted(it.id) }
    }

    override fun close() {
        scope.launch { closeOnMain() }
    }
    private fun closeOnMain() {
        if (closed) return
        closed = true
        stopOnMain()
        system?.close()
        sherpa?.close()
        languageResolver.close()
        listener = null
        scope.cancel()
    }

    private fun resolveSettings(prefs: SherpaPreferences) = SherpaSettings(
        language = prefs.language ?: Language("kk"), speed = speechSpeed(prefs.speed),
        voiceIdentifier = prefs.voiceIdentifier,
    )
}
