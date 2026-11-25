@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.mulev.flureadium.navigators

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.readium.navigator.media.tts.TtsEngine
import org.readium.r2.shared.util.Language
import kotlin.test.*

internal class HybridReadiumTtsEngineTest {
    private val systemVoice = SherpaVoice("system:package:ru", Language("ru"), "Android", "high")
    private val sherpaVoice = SherpaVoice("irina-medium-int8", Language("ru"), "Irina")
    private val kkVoice = SherpaVoice("iseke-x_low-int8", Language("kk"), "Iseke")
    private lateinit var system: FakeEngine
    private lateinit var sherpa: FakeEngine
    private lateinit var engine: HybridReadiumTtsEngine
    private val events = mutableListOf<String>()
    private var created = 0
    private lateinit var dispatcher: TestDispatcher
    private val id = TtsEngine.RequestId("reader-1")

    private class FakeEngine(override val voices: Set<SherpaVoice>) : SpeechEngine, SpeechPrefetcher {
        var callback: TtsEngine.Listener<SherpaError>? = null
        val spoken = mutableListOf<TtsEngine.RequestId>()
        val prefetched = mutableListOf<String>()
        var preferences = SherpaPreferences()
        var stopped = 0
        var closed = false
        override val settings = MutableStateFlow(SherpaSettings(Language("ru")))
        override fun setListener(listener: TtsEngine.Listener<SherpaError>?) { callback = listener }
        override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) { spoken += requestId }
        override fun submitPreferences(preferences: SherpaPreferences) { this.preferences = preferences }
        override fun prefetch(text: String, language: Language?, voiceIdentifier: String?) {
            prefetched += text
        }
        override fun stop() { stopped++ }
        override fun close() { closed = true }
    }
    private fun create(resolver: SpeechLanguageResolver = object : SpeechLanguageResolver {
        override suspend fun resolve(text: String, hint: String) = hint
    }, voices: Set<SherpaVoice> = setOf(systemVoice, sherpaVoice, kkVoice),
        lookAhead: SpeechLookAhead? = null) {
        system = FakeEngine(setOf(systemVoice))
        sherpa = FakeEngine(setOf(sherpaVoice, kkVoice))
        engine = HybridReadiumTtsEngine(SherpaPreferences(), system, { created++; sherpa },
            hybridVoiceCatalog(voices.filterNot { it.id.startsWith("system:") }.toSet(), voices.filter { it.id.startsWith("system:") }.toSet()), resolver, lookAhead)
        engine.setListener(object : TtsEngine.Listener<SherpaError> {
            override fun onStart(requestId: TtsEngine.RequestId) { events += "start" }
            override fun onRange(requestId: TtsEngine.RequestId, range: IntRange) { events += "range" }
            override fun onDone(requestId: TtsEngine.RequestId) { assertEquals(id, requestId); events += "done" }
            override fun onError(requestId: TtsEngine.RequestId, error: SherpaError) { events += "error" }
            override fun onInterrupted(requestId: TtsEngine.RequestId) { events += "interrupted" }
            override fun onFlushed(requestId: TtsEngine.RequestId) { events += "flushed" }
        })
    }
    @BeforeTest fun setup() { dispatcher = StandardTestDispatcher(); Dispatchers.setMain(dispatcher) }
    @AfterTest fun cleanup() {
        if (::engine.isInitialized) engine.close()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun systemRouteDoesNotLoadSherpa() = runTest {
        create()
        engine.speak(id, "Russian text", Language("ru"))
        runCurrent()
        assertEquals(1, system.spoken.size)
        assertEquals(0, created)
        system.callback!!.onStart(system.spoken.last())
        system.callback!!.onDone(system.spoken.last())
        runCurrent()
        assertEquals(listOf("start", "done"), events)
    }
    @Test fun completedPhrasesDoNotStopAndReinitializeIdleBackends() = runTest {
        create()
        engine.speak(id, "First", Language("ru")); runCurrent()
        system.callback!!.onDone(system.spoken.last()); runCurrent()
        engine.speak(id, "Second", Language("ru")); runCurrent()
        assertEquals(2, system.spoken.size)
        assertEquals(0, system.stopped)
        assertEquals(0, created)
    }
    @Test fun errorSwitchesToSherpaAndIgnoresLateSystemCallbacks() = runTest {
        create()
        engine.speak(id, "Russian text", Language("ru")); runCurrent()
        val previous = system.spoken.single()
        system.callback!!.onStart(previous)
        system.callback!!.onError(previous, SherpaError("offline voice failed")); runCurrent()
        assertEquals(1, created)
        assertEquals(sherpaVoice.id, sherpa.preferences.voiceIdentifier)
        system.callback!!.onDone(previous)
        sherpa.callback!!.onStart(sherpa.spoken.single())
        sherpa.callback!!.onDone(sherpa.spoken.single()); runCurrent()
        assertEquals(listOf("start", "done"), events)
    }
    @Test fun stopDuringLanguageDetectionNeverStartsEitherBackend() = runTest {
        val language = CompletableDeferred<String>()
        create(object : SpeechLanguageResolver {
            override suspend fun resolve(text: String, hint: String) = language.await()
        })
        engine.speak(id, "Text", Language("ru")); runCurrent()
        engine.stop()
        language.complete("ru"); runCurrent()
        assertTrue(system.spoken.isEmpty()); assertEquals(0, created)
        assertEquals(listOf("interrupted"), events)
    }
    @Test fun kazakhRoutesToSherpaAndManualChoiceBypassesDetection() = runTest {
        create()
        engine.submitPreferences(SherpaPreferences(language = Language("kk"), voiceIdentifier = kkVoice.id))
        engine.speak(id, "Қазақша мәтін", Language("ru")); runCurrent()
        assertTrue(system.spoken.isEmpty())
        assertEquals(kkVoice.id, sherpa.preferences.voiceIdentifier)
    }
    @Test fun sherpaPrefetchesTheFollowingSentenceAfterAudioStarts() = runTest {
        val lookAhead = SpeechLookAhead()
        LookAheadSentenceTokenizer(Language("kk"), lookAhead)
            .tokenize("Бірінші сөйлем. Екінші сөйлем. Үшінші сөйлем.")
        create(lookAhead = lookAhead)
        engine.submitPreferences(SherpaPreferences(language = Language("kk"), voiceIdentifier = kkVoice.id))
        engine.speak(id, "Бірінші сөйлем.", Language("kk"))
        runCurrent()

        assertTrue(sherpa.prefetched.isEmpty())
        sherpa.callback!!.onStart(sherpa.spoken.single())
        runCurrent()

        assertEquals(listOf("Екінші сөйлем."), sherpa.prefetched)
    }
    @Test fun unsupportedLanguageIsReportedWithoutWrongLanguageFallback() = runTest {
        create()
        engine.speak(id, "French text", Language("fr")); runCurrent()
        assertEquals(listOf("error"), events)
        assertEquals(0, created); assertTrue(system.spoken.isEmpty())
    }
    @Test fun failedSystemWithNoSherpaReportsSingleTerminalError() = runTest {
        create(voices = setOf(systemVoice))
        engine.speak(id, "Text", Language("ru")); runCurrent()
        system.callback!!.onError(system.spoken.single(), SherpaError("failed")); runCurrent()
        assertEquals(listOf("error"), events)
        assertEquals(0, created)
    }
    @Test fun reusedExternalRequestIdCannotAcceptStaleCompletion() = runTest {
        create()
        engine.speak(id, "Old", Language("ru")); runCurrent()
        val old = system.spoken.last()
        engine.speak(id, "New", Language("ru")); runCurrent()
        val fresh = system.spoken.last()
        assertNotEquals(old, fresh)
        system.callback!!.onDone(old); runCurrent()
        assertEquals(listOf("interrupted"), events)
        system.callback!!.onDone(fresh); runCurrent()
        assertEquals(listOf("interrupted", "done"), events)
    }
}
