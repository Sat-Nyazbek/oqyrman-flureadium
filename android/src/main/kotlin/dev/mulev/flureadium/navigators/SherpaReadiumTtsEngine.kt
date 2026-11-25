package dev.mulev.flureadium.navigators

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.mulev.flureadium.ReadiumReader
import kotlinx.coroutines.flow.MutableStateFlow
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val SHERPA_SILENCE_SCALE = 0.35f

/** Bounded PCM streaming. Completion means AudioTrack drained, not synthesis finished. */
@OptIn(ExperimentalReadiumApi::class)
class SherpaReadiumTtsEngine(
    initialPreferences: SherpaPreferences = SherpaPreferences()
) : TtsEngine<SherpaSettings, SherpaPreferences, SherpaError, SherpaVoice>, SpeechPrefetcher {
    private data class Request(val id: TtsEngine.RequestId, val text: String,
        val preferences: SherpaPreferences, val epoch: Long)
    private sealed interface AudioMessage {
        val request: Request
        data class Start(override val request: Request, val sampleRate: Int) : AudioMessage
        data class Chunk(override val request: Request, val pcm: ShortArray) : AudioMessage
        data class Done(override val request: Request) : AudioMessage
        data class Failure(override val request: Request, val error: String) : AudioMessage
    }
    @Volatile private var preferences = initialPreferences
    @Volatile private var listener: TtsEngine.Listener<SherpaError>? = null
    @Volatile private var closed = false
    private val epoch = AtomicLong()
    private val active = AtomicReference<Request?>()
    private val queue = LinkedBlockingQueue<AudioMessage>(16)
    private var pendingMessage: AudioMessage? = null // playback thread only
    private val audioLock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackRate = -1
    private var engine: OfflineTts? = null // synthesis thread only
    private var engineKey: String? = null
    private val pcmCache = SpeechPcmCache()
    private val synth = Executors.newSingleThreadExecutor { Thread(it, "Sherpa-synth").apply { isDaemon = true } }
    private val playback = Executors.newSingleThreadExecutor { Thread(it, "Sherpa-play").apply { isDaemon = true } }
    override val settings = MutableStateFlow(resolveSettings(initialPreferences))
    override val voices: Set<SherpaVoice> get() = installedSherpaVoices(ReadiumReader.application.applicationInfo.dataDir)

    init { playback.submit { playbackLoop() } }
    override fun setListener(listener: TtsEngine.Listener<SherpaError>?) { this.listener = listener }
    override fun submitPreferences(preferences: SherpaPreferences) {
        this.preferences = this.preferences + preferences
        settings.value = resolveSettings(this.preferences)
    }
    private fun resolveSettings(p: SherpaPreferences) = SherpaSettings(
        p.language ?: Language("kk"), speed = speechSpeed(p.speed), voiceIdentifier = p.voiceIdentifier)
    private fun current(request: Request) = !closed && request.epoch == epoch.get() && active.get() === request

    override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
        if (closed) { listener?.onError(requestId, SherpaError("Sherpa closed")); return }
        // A normally completed request has already drained AudioTrack. Keep its epoch so a
        // queued look-ahead synthesis can finish and be consumed by this request. Explicit
        // pause/seek/skip still calls stop() and invalidates the prefetch epoch.
        if (active.get() != null) stop()
        val request = Request(requestId, text, preferences.copy(language = language ?: preferences.language), epoch.get())
        active.set(request)
        try {
            synth.submit {
                if (!current(request)) return@submit
                var announced = false
                try {
                    val tts = getEngine(request.preferences)
                    val speed = speechSpeed(request.preferences.speed)
                    val speakerId = getVoice(request.preferences).speakerId
                    announced = offer(AudioMessage.Start(request, tts.sampleRate()))
                    if (!announced) return@submit
                    // Unspeakable sentences ("* * *", "—") and phrases the voice renders as
                    // silence are skipped; the request still completes so reading continues.
                    for (phrase in speechSegments(request.text)) {
                        if (!current(request)) return@submit
                        if (!isSpeakable(phrase)) continue
                        val spoken = speakableText(phrase, request.preferences)
                        val pcm = synthesizeOrCached(tts, speakerId, speed, spoken, "speak") {
                            current(request)
                        }
                        if (pcm.isEmpty()) continue
                        if (!offer(AudioMessage.Chunk(request, pcm))) return@submit
                    }
                    offer(AudioMessage.Done(request))
                } catch (e: Exception) {
                    if (current(request)) {
                        if (announced) offer(AudioMessage.Failure(request, e.message ?: "Sherpa synthesis failed"))
                        else finish(request, e.message ?: "Sherpa initialization failed")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { finish(request, "Sherpa executor closed") }
    }

    /**
     * Synthesizes the first chunk of the next Readium sentence while the current one is playing.
     *
     * It runs on the same executor as normal synthesis, so OfflineTts and the PCM cache remain
     * single-thread confined. A stop/seek changes [epoch], making the result ineligible for the
     * cache; the native generate call itself is synchronous and cannot be cancelled midway.
     */
    override fun prefetch(text: String, language: Language?, voiceIdentifier: String?) {
        if (closed || text.isBlank()) return
        val scheduledEpoch = epoch.get()
        val snapshot = preferences.copy(
            language = language ?: preferences.language,
            voiceIdentifier = voiceIdentifier ?: preferences.voiceIdentifier,
        )
        try {
            synth.submit {
                if (closed || scheduledEpoch != epoch.get()) return@submit
                try {
                    val tts = getEngine(snapshot)
                    val speed = speechSpeed(snapshot.speed)
                    val speakerId = getVoice(snapshot).speakerId
                    // Only the first chunk controls time-to-first-audio. Prefetching an entire
                    // unusually long sentence could delay its real speak() behind needless work.
                    val phrase = speechSegments(text).firstOrNull(::isSpeakable) ?: return@submit
                    if (closed || scheduledEpoch != epoch.get()) return@submit
                    // Same normalization as speak(), so the cache key matches.
                    val spoken = speakableText(phrase, snapshot)
                    synthesizeOrCached(tts, speakerId, speed, spoken, "prefetch") {
                        !closed && scheduledEpoch == epoch.get()
                    }
                } catch (e: Exception) {
                    // Prefetch is an optimization. The following speak() will retry normally.
                    Log.w("SherpaTTS", "prefetch failed: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closing the reader may race with a location update; there is nothing to report.
        }
    }

    /** Kazakh voices get numbers, dates and abbreviations spelled out in words. */
    private fun speakableText(phrase: String, preferences: SherpaPreferences): String {
        val language = preferences.language?.code ?: "kk"
        return if (language.startsWith("kk")) KazakhTextNormalizer.normalize(phrase) else phrase
    }

    /** Must only be called on [synth]. */
    private fun synthesizeOrCached(
        tts: OfflineTts,
        speakerId: Int,
        speed: Float,
        phrase: String,
        source: String,
        canCache: () -> Boolean,
    ): ShortArray {
        val key = "$engineKey|speaker=$speakerId|$speed|$phrase"
        pcmCache[key]?.let {
            Log.d("SherpaTTS", "$source cache=hit chars=${phrase.length}")
            return it
        }
        // Our own Kazakh phonemizer replaces eSpeak NG (GPL) inside sherpa-onnx.
        val phonemes = KazakhPhonemizer.phonemize(phrase)
        if (phonemes.none { it.isLetter() }) {
            Log.w("SherpaTTS", "$source nothing to pronounce chars=${phrase.length}; skipping")
            return ShortArray(0)
        }
        val startedAt = SystemClock.elapsedRealtime()
        val audio = tts.generateWithConfig(
            "",
            GenerationConfig(
                sid = speakerId,
                speed = speed,
                silenceScale = SHERPA_SILENCE_SCALE,
                phonemeCodepoints = arrayOf(KazakhPhonemizer.codepoints(phonemes)),
            ),
        )
        if (audio.samples.isEmpty()) {
            Log.w("SherpaTTS", "$source produced no audio chars=${phrase.length}; skipping")
            return ShortArray(0)
        }
        val synthMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val audioMs = audio.samples.size.toLong() * 1000L / tts.sampleRate().coerceAtLeast(1)
        val rtfPermille = if (audioMs > 0) synthMs * 1000L / audioMs else 0L
        Log.d(
            "SherpaTTS",
            "$source cache=miss chars=${phrase.length} synthMs=$synthMs audioMs=$audioMs " +
                "rtf=${rtfPermille / 1000}.${(rtfPermille % 1000).toString().padStart(3, '0')}",
        )
        return pcm16(audio.samples).also { if (canCache()) pcmCache.put(key, it) }
    }
    private fun offer(message: AudioMessage): Boolean {
        while (current(message.request)) {
            if (queue.offer(message, 50, TimeUnit.MILLISECONDS)) return current(message.request)
        }
        return false
    }
    private fun finish(request: Request, error: String? = null) {
        if (!active.compareAndSet(request, null)) return
        if (error == null) listener?.onDone(request.id)
        else {
            flushTrack()
            listener?.onError(request.id, SherpaError(error))
        }
    }
    private fun flushTrack() = synchronized(audioLock) {
        try { track?.pause(); track?.flush() } catch (_: IllegalStateException) {}
        trackRate = -1
    }
    override fun stop() {
        epoch.incrementAndGet()
        val interrupted = active.getAndSet(null)
        queue.clear()
        if (interrupted != null || closed) flushTrack()
        interrupted?.let { listener?.onInterrupted(it.id) }
    }
    override fun close() {
        if (closed) return
        closed = true
        stop()
        listener = null
        try { synth.submit { engine?.release(); engine = null; pcmCache.clear() } }
        catch (_: java.util.concurrent.RejectedExecutionException) {}
        synth.shutdown()
        playback.shutdownNow()
    }
    private fun playbackLoop() {
        try {
            while (!closed && !Thread.currentThread().isInterrupted) {
                val message = pendingMessage?.also { pendingMessage = null }
                    ?: queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (message is AudioMessage.Start && current(message.request)) {
                    try { playRequest(message) }
                    catch (e: InterruptedException) { throw e }
                    catch (e: Exception) { finish(message.request, e.message ?: "Audio output failed") }
                }
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        finally { synchronized(audioLock) { track?.release(); track = null } }
    }
    private fun playRequest(start: AudioMessage.Start) {
        val request = start.request
        var output: AudioTrack? = null
        var startHead = 0L
        var written = 0L
        var done = false
        while (current(request) && !done) {
            val message = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (message.request !== request) {
                // A seek may replace this request while poll() is waiting. Preserve the
                // next Start ahead of its PCM instead of consuming/discarding it here.
                if (current(message.request)) { pendingMessage = message; return }
                continue
            }
            when (message) {
                is AudioMessage.Start -> Unit
                is AudioMessage.Failure -> { finish(request, message.error); return }
                is AudioMessage.Done -> done = true
                is AudioMessage.Chunk -> {
                    if (!current(request)) return
                    var offset = 0
                    val audio = output ?: synchronized(audioLock) {
                        if (!current(request)) return
                        getTrack(start.sampleRate).also {
                            output = it
                            startHead = it.playbackHeadPosition.toLong() and 0xffffffffL
                            // Pre-fill up to 50 ms; never block on a stopped full buffer.
                            val count = minOf(message.pcm.size, start.sampleRate / 20)
                            val initial = it.write(message.pcm, 0, count, AudioTrack.WRITE_NON_BLOCKING)
                            check(initial > 0) { "AudioTrack rejected initial PCM ($initial)" }
                            offset = initial
                            written += initial
                            if (!current(request)) return
                            it.play()
                            listener?.onStart(request.id)
                        }
                    }
                    written += write(audio, message.pcm, offset, request)
                }
            }
        }
        val audio = output ?: run {
            if (current(request)) {
                if (done) {
                    // Nothing speakable in this sentence (e.g. "* * *"): complete it silently so
                    // the navigator moves on to the next utterance instead of stopping.
                    listener?.onStart(request.id)
                    finish(request)
                } else finish(request, "No audio output")
            }
            return
        }
        val deadline = SystemClock.elapsedRealtime() + written * 1000 / start.sampleRate + 5_000
        while (current(request) && ((audio.playbackHeadPosition.toLong() - startHead) and 0xffffffffL) < written) {
            check(SystemClock.elapsedRealtime() < deadline) { "AudioTrack stopped draining" }
            Thread.sleep(10)
        }
        if (current(request)) {
            Log.d("SherpaTTS", "played=$written rate=${start.sampleRate} underruns=${audio.underrunCount}")
            finish(request)
        }
    }
    private fun write(audio: AudioTrack, samples: ShortArray, offset: Int, request: Request): Long {
        var index = offset
        while (index < samples.size && current(request)) {
            val count = audio.write(samples, index, minOf(4096, samples.size - index), AudioTrack.WRITE_BLOCKING)
            check(count > 0) { "AudioTrack.write failed ($count)" }
            index += count
        }
        return (index - offset).toLong()
    }
    private fun getTrack(sampleRate: Int): AudioTrack = synchronized(audioLock) {
        track?.takeIf { trackRate == sampleRate && it.state == AudioTrack.STATE_INITIALIZED }?.let { return it }
        track?.release()
        track = null
        trackRate = -1
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported PCM sample rate $sampleRate" }
        AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it; trackRate = sampleRate }
    }
    private fun getEngine(prefs: SherpaPreferences): OfflineTts {
        val dataDir = ReadiumReader.application.applicationInfo.dataDir
        val voice = getVoice(prefs)
        val directory = File(dataDir, "app_flutter/tts/${voice.language.code}/${voice.modelId}")
        val model = directory.listFiles().orEmpty().first { it.extension == "onnx" }
        val key = "${model.absolutePath}:${model.length()}:${model.lastModified()}"
        if (engineKey == key && engine != null) return engine!!
        engine?.release()
        engine = null
        engineKey = null
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = model.absolutePath, tokens = File(directory, "tokens.txt").path,
                noiseScale = 0.55f, noiseScaleW = 0.65f, lengthScale = 1f),
            numThreads = minOf(2, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1), provider = "cpu", debug = false),
            maxNumSentences = 1, silenceScale = SHERPA_SILENCE_SCALE)
        return OfflineTts(config = config).also { engine = it; engineKey = key }
    }

    private fun getVoice(prefs: SherpaPreferences): SherpaVoice {
        val dataDir = ReadiumReader.application.applicationInfo.dataDir
        val installed = installedSherpaVoices(dataDir)
        return installed.firstOrNull { it.id == prefs.voiceIdentifier }
            ?: installed.firstOrNull {
                baseLanguage(it.language.code) == baseLanguage(prefs.language?.code ?: "kk")
            }
            ?: error("No Sherpa model for ${prefs.language?.code}")
    }
}
