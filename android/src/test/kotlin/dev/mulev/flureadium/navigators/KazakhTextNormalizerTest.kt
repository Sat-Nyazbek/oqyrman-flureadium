package dev.mulev.flureadium.navigators

import kotlin.test.Test
import kotlin.test.assertEquals

internal class KazakhTextNormalizerTest {
    private fun n(text: String) = KazakhTextNormalizer.normalize(text)

    @Test fun cardinals() {
        assertEquals("нөл", KazakhTextNormalizer.cardinal(0))
        assertEquals("он бір", KazakhTextNormalizer.cardinal(11))
        assertEquals("қырық бес", KazakhTextNormalizer.cardinal(45))
        assertEquals("жүз", KazakhTextNormalizer.cardinal(100))
        assertEquals("екі жүз бес", KazakhTextNormalizer.cardinal(205))
        assertEquals("бір мың тоғыз жүз тоқсан бір", KazakhTextNormalizer.cardinal(1991))
        assertEquals("екі мың жиырма алты", KazakhTextNormalizer.cardinal(2026))
        assertEquals("бір миллион", KazakhTextNormalizer.cardinal(1_000_000))
    }

    @Test fun ordinals() {
        assertEquals("бірінші", KazakhTextNormalizer.ordinal(1))
        assertEquals("жиырмасыншы", KazakhTextNormalizer.ordinal(20))
        assertEquals("қырқыншы", KazakhTextNormalizer.ordinal(40))
        assertEquals("он тоғызыншы", KazakhTextNormalizer.ordinal(19))
    }

    @Test fun numbersInText() {
        assertEquals("Абай бірінші сөзінде", n("Абай 1-ші сөзінде"))
        assertEquals("біріншіден, ойланайық", n("1-шіден, ойланайық"))
        assertEquals("жиырмасыншы жылдары", n("20-жылдары"))
        assertEquals("беске дейін", n("5-ке дейін"))
        assertEquals("Ол бір мың сегіз жүз қырық бесте туған", n("Ол 1845-те туған"))
        assertEquals("елу пайыз", n("50%"))
        assertEquals("бір мың сегіз жүз қырық бес – бір мың тоғыз жүз төрт", n("1845–1904"))
        assertEquals("бір миллион адам", n("1 000 000 адам"))
        assertEquals("үш бүтін оннан бес", n("3,5"))
    }

    @Test fun abbreviationsAndDates() {
        assertEquals("кітап, дәптер тағы басқа", n("кітап, дәптер т.б."))
        assertEquals("біздің заманымыздан бұрын бесінші ғасыр", n("Б.з.б. бесінші ғасыр"))
        assertEquals("бір мың тоғыз жүз тоқсан бес жылы", n("1995 ж."))
        assertEquals("он тоғызыншы ғасыр", n("XIX ғасыр"))
        assertEquals("он тоғызыншы ғасыр", n("XIX ғ."))
        assertEquals("екі мың жиырма алты жылғы жиырма бес қараша", n("25.11.2026"))
        assertEquals("он тоғызыншы ғасыр", n("ХІХ ғасыр")) // Cyrillic Х and І
        assertEquals("он тоғызыншы ғасыр", n("19 ғ."))
        assertEquals("жиырмасыншы ғасырда", n("20 ғасырда"))
        assertEquals("мысалы, кітап", n("мыс., кітап"))
        assertEquals("Бұл мыс.", n("Бұл мыс."))
    }

    @Test fun leavesOtherTextAlone() {
        assertEquals("I love books", n("I love books"))
        assertEquals("нөл нөл жеті", n("007"))
        assertEquals("Mp3", n("Mp3"))
    }

    @Test fun fixesLatinLookalikesInsideCyrillicWords() {
        // Latin "a" and "o" inside Cyrillic words.
        assertEquals("қала орталығы", n("қaла oрталығы"))
        assertEquals("кітап", n("кiтап"))
        assertEquals("OqyrMan", n("OqyrMan"))
    }
}
