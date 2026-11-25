package dev.mulev.flureadium

import dev.mulev.flureadium.navigators.AudiobookNavigator
import dev.mulev.flureadium.navigators.EpubNavigator
import dev.mulev.flureadium.navigators.PdfNavigator
import dev.mulev.flureadium.navigators.SyncAudiobookNavigator
import dev.mulev.flureadium.navigators.TTSNavigator
import dev.mulev.flureadium.navigators.TimebasedNavigator
import dev.mulev.flureadium.navigators.NavigatorDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito
import org.readium.adapter.exoplayer.audio.ExoPlayerPreferences
import org.readium.adapter.exoplayer.audio.ExoPlayerSettings
import org.readium.navigator.media.audio.AudioNavigator
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that ReadiumReader's cleanup methods (closePublication, etc.)
 * properly call release() on active navigators instead of fire-and-forget dispose().
 *
 * Uses reflection to set private navigator fields on the ReadiumReader singleton.
 * openPublication() cleanup is tested via integration tests (Phase 5) since it
 * requires full Readium infrastructure for loadPublication().
 */
@OptIn(ExperimentalReadiumApi::class, ExperimentalCoroutinesApi::class)
internal class ReadiumReaderCleanupTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        // Clean up ReadiumReader singleton state
        setReaderField("audiobookNavigator", null)
        setReaderField("syncAudiobookNavigator", null)
        setReaderField("ttsNavigator", null)
        setReaderField("pdfNavigator", null)
        setReaderField("epubNavigator", null)
        setReaderField("_currentPublication", null)
        ReadiumReader.currentReaderWidget = null

        @Suppress("UNCHECKED_CAST")
        (getReaderField("currentTimebasedState") as kotlinx.coroutines.flow.MutableStateFlow<TimebasedNavigator.TimebasedState?>).value = null
        Dispatchers.resetMain()
    }

    private fun setReaderField(name: String, value: Any?) {
        val field = ReadiumReader::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(ReadiumReader, value)
    }

    private fun getReaderField(name: String): Any? {
        val field = ReadiumReader::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(ReadiumReader)
    }

    private fun createMockAudiobookNavigator(): AudiobookNavigator {
        return AudiobookNavigator(
            mock(Publication::class.java),
            mock(TimebasedNavigator.TimebasedListener::class.java),
            null,
            FlutterAudioPreferences()
        )
    }

    private fun createMockTtsNavigator(): TTSNavigator {
        return TTSNavigator(
            mock(Publication::class.java),
            mock(TimebasedNavigator.TimebasedListener::class.java),
            null,
            mock(NavigatorDelegate::class.java),
            FlutterTtsPreferences()
        )
    }

    @Test
    fun closePublication_releasesAudiobookNavigator() = runTest {
        val navigator = createMockAudiobookNavigator()
        setReaderField("audiobookNavigator", navigator)

        ReadiumReader.closePublication()

        assertNull(getReaderField("audiobookNavigator"))
    }

    @Test
    fun closePublication_releasesTtsNavigator() = runTest {
        val navigator = createMockTtsNavigator()
        setReaderField("ttsNavigator", navigator)

        ReadiumReader.closePublication()

        assertNull(getReaderField("ttsNavigator"))
    }

    @Test
    fun closePublication_nullsSyncAudiobookNavigator() = runTest {
        val navigator = SyncAudiobookNavigator(
            mock(Publication::class.java),
            emptyList(),
            mock(TimebasedNavigator.TimebasedListener::class.java),
            null,
            FlutterAudioPreferences(),
            delegate = mock(NavigatorDelegate::class.java)
        )
        setReaderField("syncAudiobookNavigator", navigator)

        ReadiumReader.closePublication()

        assertNull(getReaderField("syncAudiobookNavigator"))
    }

    @Test
    fun closePublication_releasesEpubNavigator() = runTest {
        val navigator = mock(EpubNavigator::class.java)
        setReaderField("epubNavigator", navigator)

        ReadiumReader.closePublication()

        verify(navigator).release()
        assertNull(getReaderField("epubNavigator"))
    }

    @Test
    fun closePublication_releasesPdfNavigator() = runTest {
        val navigator = mock(PdfNavigator::class.java)
        setReaderField("pdfNavigator", navigator)

        ReadiumReader.closePublication()

        verify(navigator).release()
        assertNull(getReaderField("pdfNavigator"))
    }

    @Test
    fun goToLocator_routesToPdfNavigatorAndReportsSuccess() = runTest {
        val navigator = mock(PdfNavigator::class.java)
        val locator = mock(Locator::class.java)
        Mockito.`when`(navigator.go(locator, true)).thenReturn(true)
        setReaderField("pdfNavigator", navigator)

        val handled = ReadiumReader.goToLocator(locator)

        assertTrue(handled)
        verify(navigator).go(locator, true)
    }

    @Test
    fun goToLocator_propagatesEpubNavigationFailure() = runTest {
        val navigator = mock(EpubNavigator::class.java)
        val locator = mock(Locator::class.java)
        Mockito.`when`(navigator.goToLocator(locator, true)).thenReturn(false)
        setReaderField("epubNavigator", navigator)

        val handled = ReadiumReader.goToLocator(locator)

        assertFalse(handled)
        verify(navigator).goToLocator(locator, true)
    }

    @Test
    fun pausedTtsIsNotConsideredActiveForVisualNavigation() {
        setReaderField("ttsNavigator", mock(TTSNavigator::class.java))

        ReadiumReader.onTimebasedPlaybackStateChanged(TimebasedNavigator.TimebasedState.Playing)
        assertTrue(ReadiumReader.isTtsActive)

        ReadiumReader.onTimebasedPlaybackStateChanged(TimebasedNavigator.TimebasedState.Paused)
        assertFalse(ReadiumReader.isTtsActive)
    }
}
