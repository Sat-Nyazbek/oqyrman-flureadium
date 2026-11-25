@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.mulev.flureadium.navigators

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.util.Language

/** Android owns playback; no audio file or second AudioTrack is created. Main-thread confined. */
internal class AndroidReadiumTtsEngine private constructor(
    private val tts: TextToSpeech,
) : TtsEngine<SherpaSettings, SherpaPreferences, SherpaError, SherpaVoice> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: TtsEngine.Listener<SherpaError>? = null
    private var preferences = SherpaPreferences()
    private var active: TtsEngine.RequestId? = null
    private var watchdog: Job? = null
    private var closed = false
    private var completionTimeoutMs = 30_000L
    override val settings = MutableStateFlow(SherpaSettings(Language("kk")))
    private val nativeVoices = tts.voices.orEmpty().filter {
        !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.associateBy { "system:${tts.defaultEngine}:${it.name}" }
    override val voices = nativeVoices.map { (id, voice) ->
        SherpaVoice(id, Language(voice.locale.toLanguageTag()), "Android · ${voice.name}",
            when { voice.quality >= Voice.QUALITY_VERY_HIGH -> "highest"
                voice.quality >= Voice.QUALITY_HIGH -> "high"
                voice.quality >= Voice.QUALITY_NORMAL -> "normal"
                else -> "low" })
    }.toSet()

    init {
        tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { dispatch(id) { armTimeout(it, completionTimeoutMs); listener?.onStart(it) } }
            override fun onDone(id: String) { dispatch(id) { finish(it) { listener?.onDone(it) } } }
            @Deprecated("Android requires this compatibility callback")
            override fun onError(id: String) { onError(id, TextToSpeech.ERROR_SYNTHESIS) }
            override fun onError(id: String, code: Int) {
                dispatch(id) { finish(it) { listener?.onError(it, SherpaError("Android TTS error $code")) } }
            }
            override fun onStop(id: String, interrupted: Boolean) {
                dispatch(id) { finish(it) { listener?.onInterrupted(it) } }
            }
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
                if (end > start) dispatch(id) { listener?.onRange(it, start until end) }
            }
        })
    }

    private fun dispatch(id: String, block: (TtsEngine.RequestId) -> Unit) {
        scope.launch { active?.takeIf { it.value == id && !closed }?.let(block) }
    }

    private fun finish(id: TtsEngine.RequestId, block: () -> Unit) {
        if (active != id) return
        active = null
        watchdog?.cancel()
        block()
    }

    override fun setListener(listener: TtsEngine.Listener<SherpaError>?) { this.listener = listener }
    override fun submitPreferences(preferences: SherpaPreferences) {
        this.preferences = preferences
        settings.value = SherpaSettings(preferences.language ?: Language("kk"), speed = speechSpeed(preferences.speed),
            voiceIdentifier = preferences.voiceIdentifier)
    }

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        if (closed) { listener?.onError(requestId, SherpaError("Android TTS closed")); return }
        val voice = nativeVoices[preferences.voiceIdentifier]
        if (voice == null || tts.isLanguageAvailable(voice.locale) < TextToSpeech.LANG_AVAILABLE ||
            text.length > TextToSpeech.getMaxSpeechInputLength()) {
            listener?.onError(requestId, SherpaError("Android voice unavailable for this phrase")); return
        }
        active = requestId
        completionTimeoutMs = (text.length * 250L + 15_000L).coerceIn(20_000L, 300_000L)
        armTimeout(requestId, 8_000)
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS ||
            tts.setSpeechRate(speechSpeed(preferences.speed)) != TextToSpeech.SUCCESS ||
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), requestId.value) != TextToSpeech.SUCCESS) {
            finish(requestId) { listener?.onError(requestId, SherpaError("Android TTS rejected phrase")) }
            return
        }
    }

    private fun armTimeout(requestId: TtsEngine.RequestId, milliseconds: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(milliseconds)
            if (active == requestId) {
                tts.stop()
                finish(requestId) { listener?.onError(requestId, SherpaError("Android TTS timed out")) }
            }
        }
    }

    override fun stop() { active = null; watchdog?.cancel(); tts.stop() }
    override fun close() {
        if (closed) return
        closed = true
        stop()
        listener = null
        tts.shutdown()
        scope.cancel()
    }

    companion object {
        suspend fun create(context: Context): AndroidReadiumTtsEngine? = withContext(Dispatchers.Main.immediate) {
            val ready = CompletableDeferred<Int>()
            val tts = try { TextToSpeech(context.applicationContext) { ready.complete(it) } }
                catch (_: Exception) { return@withContext null }
            try {
                if (withTimeout(5_000) { ready.await() } == TextToSpeech.SUCCESS) AndroidReadiumTtsEngine(tts)
                else { tts.shutdown(); null }
            } catch (e: TimeoutCancellationException) {
                tts.shutdown(); null
            } catch (e: CancellationException) {
                tts.shutdown(); throw e
            } catch (_: Exception) {
                tts.shutdown(); null
            }
        }
    }
}
