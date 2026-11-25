package dev.mulev.flureadium.navigators

import android.os.Bundle
import android.util.Log
import dev.mulev.flureadium.ControlPanelInfoType
import dev.mulev.flureadium.FlutterTtsPreferences
import dev.mulev.flureadium.PluginMediaServiceFacade
import dev.mulev.flureadium.PublicationError
import dev.mulev.flureadium.ReadiumReader
import dev.mulev.flureadium.throttleLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.TtsNavigator.Listener
import org.readium.navigator.media.tts.TtsNavigatorFactory
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "TTSNavigator"

private const val TTS_DECORATION_ID_UTTERANCE = "tts-utterance"

private const val TTS_DECORATION_ID_CURRENT_RANGE = "tts-range"

private const val currentTimebasedLocatorKey = "currentTimebasedLocator"

private const val ttsPreferencesKey = "ttsPreferences"

/** State of the initial-start scroll suppression in the throttled location flow. */
private enum class ScrollSuppression {
    /** Normal operation — throttled scroll events pass through. */
    None,

    /**
     * TTS started at a known locator — skip the very next throttled scroll event to
     * prevent the WebView from jumping back to the start position.
     */
    SkipOnce,
}

// TODO: Extend locator with chapter info

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class TTSNavigator(
    publication: Publication,
    timebaseListener: TimebasedListener,
    initialLocator: Locator?,
    private val delegate: NavigatorDelegate,
    private var preferences: FlutterTtsPreferences = FlutterTtsPreferences()
) : TimebasedNavigator<TtsNavigator.Playback>(publication, timebaseListener, initialLocator) {
    val decorationGroup = "tts"

    /**
     * Scroll-suppression state for the throttled location flow.
     * Set to [ScrollSuppression.SkipOnce] when TTS starts at a known locator so that the
     * first throttled event does not scroll back to the start position.
     */
    private var scrollSuppression = ScrollSuppression.None

    /** Decoration controller: builds highlights, applies them atomically, persists locators. */
    private val decorationController = TimebasedDecorationController(
        group = decorationGroup,
        utteranceDecorationId = TTS_DECORATION_ID_UTTERANCE,
        rangeDecorationId = TTS_DECORATION_ID_CURRENT_RANGE,
        delegate = delegate,
    )

    /**
     * Href that the immediate (cross-page) flow already scrolled to.
     * The throttled flow skips scroll for this href to prevent double-navigation
     * (one from the immediate flow, one from the throttled flow 400 ms later).
     * Reset to null on manual navigation so same-page scrolls are not blocked.
     */
    private var lastCrossPageHref: String? = null

    private var ttsNavigator: TtsNavigator<SherpaSettings, SherpaPreferences, SherpaError, SherpaVoice>? =
        null

    private var mediaServiceFacade: PluginMediaServiceFacade? = null

    override suspend fun initNavigator() {
        val lookAhead = SpeechLookAhead()
        val engineProvider = HybridEngineProvider(lookAhead)
        val navigatorFactory = TtsNavigatorFactory.Companion.invoke(
            ReadiumReader.application,
            publication,
            engineProvider,
            { language -> LookAheadSentenceTokenizer(language, lookAhead) },
            { pub ->
                DatabaseMediaMetadataFactory(
                    publication = publication,
                    trackCount = pub.readingOrder.size,
                    controlPanelInfoType = preferences.controlPanelInfoType
                        ?: ControlPanelInfoType.STANDARD
                )
            }
        ) ?: throw Exception("This publication cannot be played with the TTS navigator")

        val listener = object : Listener {
            override fun onStopRequested() {
                mainScope.async { mediaServiceFacade?.closeSessionAndAwait() }
            }
        }

        val initialAndroidPreferences = preferences.toSherpaPreferences()

        // Fetch UI-dependent start locator on Main thread first.
        val startLocator: Locator? = withContext(Dispatchers.Main) {
            initialLocator ?: ReadiumReader.currentReaderWidget?.getFirstVisibleLocator()
        }

        // TtsNavigator internally creates TtsSessionAdapter which requires Main thread
        // for Media3 ListenerSet initialization. Running on Default causes NPE on first event.
        val nav = withContext(Dispatchers.Main.immediate) {
            navigatorFactory.createNavigator(listener, startLocator, initialAndroidPreferences)
        }.getOrElse {
            Log.e(TAG, "ttsEnable: failed to create navigator: $it")
            throw Exception("ttsEnable: failed to create navigator: $it")
        }

        // Listener setup and MediaSession wiring must run on Main thread.
        mainScope.async {
            scrollSuppression = if (startLocator != null) ScrollSuppression.SkipOnce else ScrollSuppression.None

            ttsNavigator = nav

            setupNavigatorListeners()

            mediaServiceFacade = PluginMediaServiceFacade(ReadiumReader.application)
                .apply {
                    session
                        .flatMapLatest { it?.navigator?.playback ?: MutableStateFlow(null) }
                        .onEach { playback ->
                            when (val state = (playback?.state as? TtsNavigator.State)) {
                                null, TtsNavigator.State.Ready -> {
                                    // Do nothing
                                }

                                is TtsNavigator.State.Ended -> {
                                    mediaServiceFacade?.closeSessionAndAwait()
                                }

                                is TtsNavigator.State.Failure -> {
                                    Log.e(TAG, "TTSNavigator failure: ${state.error}")
                                    timebaseListener.onTimebasedPlaybackFailure(
                                        PublicationError.invoke(state.error)
                                    )
                                }
                            }
                        }.launchIn(mainScope)
                }
        }.await()
    }

    override suspend fun play() {
        play(null)
    }

    override suspend fun play(fromLocator: Locator?) {
        mainScope.async {
            val navigator = ttsNavigator
                ?: throw IllegalStateException("Cannot play TTS: navigator is closed")
            if (fromLocator != null) {
                navigator.go(fromLocator)
            }

            try {
                mediaServiceFacade?.openSession(navigator)
                    ?: throw IllegalStateException("Cannot play TTS: media service is unavailable")
                navigator.play()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open MediaSession: $e")
                // openSession may already have installed a MediaSession before playback fails.
                // Tear down only that lease and keep ownership of the navigator for a retry.
                try {
                    withContext(NonCancellable) {
                        mediaServiceFacade?.closeSessionAndAwait()
                    }
                } catch (cleanupError: Throwable) {
                    e.addSuppressed(cleanupError)
                    Log.e(TAG, "Failed to clean up MediaSession after play error", cleanupError)
                }
                throw e
            }
        }.await()
    }

    override suspend fun pause() {
        mainScope.async {
            val navigator = ttsNavigator
                ?: throw IllegalStateException("Cannot pause TTS: navigator is closed")
            navigator.pause()
        }.await()
    }

    override suspend fun resume() {
        mainScope.async {
            val navigator = ttsNavigator
                ?: throw IllegalStateException("Cannot resume TTS: navigator is closed")
            try {
                // pause/onStopRequested is allowed to close the MediaSession. Re-open the lease
                // without changing the current locator before resuming the same utterance.
                mediaServiceFacade?.openSession(navigator)
                    ?: throw IllegalStateException("Cannot resume TTS: media service is unavailable")
                navigator.play()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume TTS playback: $e")
                try {
                    withContext(NonCancellable) {
                        mediaServiceFacade?.closeSessionAndAwait()
                    }
                } catch (cleanupError: Throwable) {
                    e.addSuppressed(cleanupError)
                    Log.e(TAG, "Failed to clean up MediaSession after resume error", cleanupError)
                }
                throw e
            }
        }.await()
    }

    /** DRY: clears scroll suppression state before manual navigation. */
    private fun clearScrollSuppression() {
        scrollSuppression = ScrollSuppression.None
        lastCrossPageHref = null
    }

    /** Skip to previous utterance (sentence). */
    override suspend fun goBack() {
        clearScrollSuppression()
        val navigator = ttsNavigator ?: return
        mainScope.async {
            if (navigator.hasPreviousUtterance()) {
                navigator.skipToPreviousUtterance()
            }
        }.await()
    }

    /**
     * Skip to next utterance (sentence).
     */
    override suspend fun goForward() {
        clearScrollSuppression()
        val navigator = ttsNavigator ?: return
        mainScope.async {
            if (navigator.hasNextUtterance()) {
                navigator.skipToNextUtterance()
            }
        }.await()
    }

    override suspend fun goToLocator(locator: Locator) {
        clearScrollSuppression()
        val navigator = ttsNavigator ?: return
        mainScope.async {
            navigator.go(locator)
        }.await()
    }

    override suspend fun seekTo(offset: Double) {
        Log.w(TAG, ":seekTo is not implemented for TTS playback")
    }

    /**
     * Called when decorations (e.g., highlights) need to be updated.
     */
    suspend fun decorationsUpdated() {
        val navigator = ttsNavigator ?: return
        val location = navigator.location.value
        decorationController.decorate(location.utteranceLocator, location.tokenLocator)
    }


    /// Updates TTS preferences, does not override current preferences if props are null
    suspend fun updatePreferences(prefs: FlutterTtsPreferences) {
        mainScope.async {
            preferences = preferences.plus(prefs)

            ttsNavigator?.submitPreferences(preferences.toSherpaPreferences())
        }.await()
    }

    /**
     * Set preferred voice for a given language. If lang is null, override voice for currently spoken language.
     */
    suspend fun setPreferredVoice(voiceId: String, lang: String?) {
        if (lang == null) return
        // Accumulate per-language voice map so per-language preferences are preserved.
        // Also set language so toSherpaPreferences() can look up the correct voice.
        val voices = preferences.voices?.toMutableMap() ?: mutableMapOf()
        voices[lang] = voiceId
        updatePreferences(FlutterTtsPreferences(language = lang, voices = voices))
    }

    /*
     * Get available voices from TTS engine
     */
    val voices: Set<SherpaVoice>
        get() = ttsNavigator?.voices ?: emptySet()

    override fun setupNavigatorListeners() {
        val navigator = ttsNavigator
        if (navigator == null) {
            return
        }

        // Listen to state changes
        navigator.playback
            .throttleLatest(100.milliseconds)
            .distinctUntilChangedBy { pb ->
                "${pb.state}|${pb.playWhenReady}"
            }
            .onEach { pb ->
                onPlaybackStateChanged(pb)
                timebaseListener.onTimebasedBufferChanged(null)
            }
            .launchIn(mainScope)
            .let { jobs.add(it) }

        // Listen to utterance updates and apply decorations.
        // When the utterance moves to a new page (href change):
        //   1. Record the href so the throttled flow below does not duplicate the scroll.
        //   2. Navigate to that page immediately (no throttle).
        //   3. Clear old decorations first to avoid cross-page stale highlights.
        navigator.location
            .map { Pair(it.utteranceLocator, it.tokenLocator) }
            .distinctUntilChanged()
            .onEach { (uttLocator, tokenLocator) ->
                val newHref = uttLocator.href.toString()
                if (newHref != decorationController.lastDecoratedHref) {
                    // Crossed a page boundary — record href, navigate, clear stale decorations.
                    lastCrossPageHref = newHref
                    delegate.scrollTo(uttLocator)
                    decorationController.clear()
                }
                decorationController.decorate(uttLocator, tokenLocator)
            }
            .launchIn(mainScope)
            .let { jobs.add(it) }

        // Listen to location changes within a page and scroll (throttled).
        // Cross-page scrolling is already handled above without throttle.
        // If the throttled event fires for the same href the immediate flow
        // just navigated to, skip it — that would cause a visible double-scroll.
        navigator.location
            .throttleLatest(200.milliseconds)
            .map { it.tokenLocator ?: it.utteranceLocator }
            .distinctUntilChanged()
            .onEach { locator ->
                if (scrollSuppression == ScrollSuppression.SkipOnce) {
                    scrollSuppression = ScrollSuppression.None
                    return@onEach
                }

                val locatorHref = locator.href.toString()

                // The immediate flow already scrolled to this href — skip once,
                // then clear so subsequent same-page events are not blocked.
                if (locatorHref == lastCrossPageHref) {
                    lastCrossPageHref = null
                    return@onEach
                }

                // Only scroll within the same page — cross-page navigation is
                // handled in the utterance flow above (immediate, no throttle).
                if (locatorHref == decorationController.lastDecoratedHref) {
                    delegate.scrollTo(locator)
                }
            }
            .launchIn(mainScope)
            .let { jobs.add(it) }

        navigator.currentLocator
            .throttleLatest(100.milliseconds)
            .distinctUntilChanged()
            .onEach { locator ->
                onCurrentLocatorChanges(locator)
                state[currentTimebasedLocatorKey] = locator
            }
            .launchIn(mainScope)
            .let { jobs.add(it) }
    }

    override fun storeState(): Bundle {
        return Bundle().apply {
            putString(
                currentTimebasedLocatorKey,
                (state[currentTimebasedLocatorKey] as? Locator)?.toJSON()?.toString()
            )

            putString(
                ttsPreferencesKey,
                FlutterTtsPreferences.toJSON(preferences).toString()
            )

            // Persist the last decorated locators so decorations can be restored after
            // Activity recreation (e.g. screen rotation).
            decorationController.storeInBundle(this)
        }
    }

    override suspend fun release() {
        super.dispose()

        val facade = mediaServiceFacade
        mediaServiceFacade = null
        try {
            facade?.closeSessionAndAwait()
        } finally {
            try {
                decorationController.clear()
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    ttsNavigator?.close()
                    ttsNavigator = null
                }
            }
        }
    }

    override fun dispose() {
        super.dispose()

        val facade = mediaServiceFacade
        mediaServiceFacade = null
        mainScope.async {
            try {
                facade?.closeSessionAndAwait()
            } finally {
                try {
                    decorationController.clear()
                } finally {
                    ttsNavigator?.close()
                    ttsNavigator = null
                }
            }
        }.let { jobs.add(it) }
    }

    override fun onPlaybackStateChanged(pb: TtsNavigator.Playback) {
        when (pb.state) {
            is TtsNavigator.State.Failure -> {
                val ttsState = pb.state as TtsNavigator.State.Failure
                val error = ttsState.error

                Log.e(
                    TAG,
                    ": onPlaybackStateChanged - TTS error: Message=${error.message} cause=${error.cause}"
                )

                ReadiumReader.ttsErrorType = classifyTtsErrorType(
                    error as? SherpaError
                )

                timebaseListener.onTimebasedPlaybackStateChanged(TimebasedState.Failure)
                timebaseListener.onTimebasedPlaybackFailure(
                    PublicationError.invoke(error)
                )
            }

            else -> {
                ReadiumReader.ttsErrorType = null
                super.onPlaybackStateChanged(pb)
            }
        }
    }

    companion object {
        fun classifyTtsErrorType(error: SherpaError?): String {
            return "unknown"
        }

        fun restoreState(
            publication: Publication,
            listener: TimebasedListener,
            delegate: NavigatorDelegate,
            state: Bundle
        ): TTSNavigator {
            val locator = state.getString(currentTimebasedLocatorKey)
                ?.let { Locator.fromJSON(JSONObject(it)) }
            val preferences = state.getString(ttsPreferencesKey)
                ?.let { FlutterTtsPreferences.fromJSON(it) }
                ?: FlutterTtsPreferences()

            return TTSNavigator(publication, listener, locator, delegate, preferences).also { nav ->
                // Restore last decorated locators so decorations reappear after screen rotation.
                nav.decorationController.restoreFromBundle(state)
            }
        }
    }
}
