package dev.mulev.flureadium.navigators

import kotlin.test.*
import org.readium.r2.shared.util.Language

internal class StreamingSpeechUtilsTest {
    @Test fun speedRejectsInvalidNativeInputs() {
        assertEquals(1f, speechSpeed(Double.NaN))
        assertEquals(1f, speechSpeed(-1.0))
        assertEquals(3f, speechSpeed(100.0))
        assertEquals(.5f, speechSpeed(.1))
    }
    @Test fun lookAheadUsesTheExactRangesReturnedToReadium() {
        val lookAhead = SpeechLookAhead()
        val tokenizer = LookAheadSentenceTokenizer(Language("kk"), lookAhead)
        val text = "Бірінші сөйлем. Екінші сөйлем толығымен осында. Үшінші сөйлем."

        assertEquals(3, tokenizer.tokenize(text).size)
        assertEquals("Екінші сөйлем толығымен осында.", lookAhead.followingSentence("Бірінші сөйлем."))
        assertEquals("Үшінші сөйлем.", lookAhead.followingSentence("Екінші сөйлем толығымен осында."))
        assertNull(lookAhead.followingSentence("Үшінші сөйлем."))
    }
    @Test fun ambiguousRepeatedSentenceIsNotPrefetched() {
        val lookAhead = SpeechLookAhead()
        val tokenizer = LookAheadSentenceTokenizer(Language("kk"), lookAhead)
        tokenizer.tokenize("Қайталау. Бірінші жалғасы.")
        tokenizer.tokenize("Қайталау. Басқа жалғасы.")

        assertNull(lookAhead.followingSentence("Қайталау."))
    }
    @Test fun sceneBreaksAndDashesAreNotSpeakable() {
        assertFalse(isSpeakable("* * *"))
        assertFalse(isSpeakable("—"))
        assertFalse(isSpeakable("  …  "))
        assertTrue(isSpeakable("Ә"))
        assertTrue(isSpeakable("1"))
    }
    @Test fun normalSentenceRetainsProsodyContext() {
        val sentence = "Бұл қазақ тіліндегі сөйлем, оны қысқа бөліктерге бөлу қажет емес."
        assertEquals(listOf(sentence), speechSegments(sentence))
    }
    @Test fun longTextPreservesWordsAndPunctuation() {
        val text = (1..80).joinToString(" ") { "сөз$it," }
        val chunks = speechSegments(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 240 })
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun longUnicodeWordIsNotTruncated() {
        val word = "ә".repeat(300)
        assertEquals(listOf(word), speechSegments(word))
    }
    @Test fun pcmHandlesNonfiniteSamplesAndClipping() {
        assertContentEquals(shortArrayOf(0, 0, 32767, -32768, 16383),
            pcm16(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 2f, -2f, .5f)))
    }
    @Test fun memoryCacheEvictsLeastRecentlyUsedAudio() {
        val cache = SpeechPcmCache(maxSamples = 6)
        cache.put("a", shortArrayOf(1, 2, 3))
        cache.put("b", shortArrayOf(4, 5, 6))
        assertNotNull(cache["a"])
        cache.put("c", shortArrayOf(7, 8, 9))
        assertNull(cache["b"])
        assertNotNull(cache["a"])
        cache.put("large", ShortArray(20))
        assertNull(cache["large"])
        cache.clear()
        assertNull(cache["a"])
    }
}
