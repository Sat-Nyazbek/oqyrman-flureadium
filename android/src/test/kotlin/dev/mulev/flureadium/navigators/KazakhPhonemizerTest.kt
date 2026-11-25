package dev.mulev.flureadium.navigators

import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values come from the reference corpus (tool/g2p/reference_kk.tsv). */
internal class KazakhPhonemizerTest {
    private val reference = listOf(
        "бір" to "bˈɪɾ",
        "екі" to "ekˈɪ",
        "жеті" to "ʒetˈɪ",
        "алты" to "ˈɑɫtə",
        "отыз" to "ˈotəz",
        "қырық" to "qəɾəq",
        "елу" to "ˈeɫw",
        "сексен" to "seksˈen",
        "бірінші" to "bɪɾɪnʃˈɪ",
        "оныншы" to "ˈonənʃə",
        "алматы" to "ɑɫmˈɑtə",
        "өскемен" to "ɵskemˈen",
        "үлкен" to "uɫkˈen",
        "әке" to "ækˈe",
        "ғылым" to "ʀəɫəm",
        "шаңырақ" to "ʃˈɑŋəɾɑq",
        "білем" to "bɪlˈem",
        "кәміл" to "kæmˈɪɫ",
        "болады" to "boɫˈɑdə",
        "адам" to "ɑdˈɑm",
        "керек" to "keɾˈek",
        "деген" to "deɡˈen",
        "басылмаған" to "bˈɑsəɫmɑʀɑn",
        "арамдықпенен" to "ɑɾˈɑmdəqpenen",
        "назначениесімен" to "nɑznɑtʃˈenəjesɪmen",
        "иманы" to "əjmɑnə",
        "қылып" to "qəɫəp",
        "партия" to "pˈɑɾtəjja",
        "сияны" to "səjjanə",
        "жаяу" to "ʒɑjˈaw",
        "көре-көре" to "kɵɾˈekɵɾˈe",
        "түгел" to "tuɡˈeɫ",
        "қолына" to "qˈoɫənɑ",
        "айлалылығынан" to "ɑjɫˈɑɫəɫəʀənɑn",
    )

    @Test fun matchesReferenceWords() {
        for ((word, expected) in reference) {
            assertEquals(expected, KazakhPhonemizer.word(word), word)
        }
    }

    @Test fun sentenceKeepsPunctuationAndSpaces() {
        assertEquals(
            "bˈɪɾ, ekˈɪ. ʒetˈɪ?",
            KazakhPhonemizer.phonemize("Бір, екі. Жеті?"),
        )
    }

    @Test fun dashBecomesPauseAndQuotesAreSilent() {
        assertEquals("ɑdˈɑm, keɾˈek", KazakhPhonemizer.phonemize("«Адам» — керек"))
    }

    @Test fun invisibleCharactersDoNotSplitWords() {
        assertEquals("qɑzɑqstˈɑn", KazakhPhonemizer.phonemize("Қазақ\u00ADстан"))
        assertEquals("mˈɑjɫə", KazakhPhonemizer.phonemize("ма\u0438\u0306лы")) // й as и + breve
        assertEquals("bˈes mˈəŋ", KazakhPhonemizer.phonemize("бес\u00A0мың"))
    }

    @Test fun bracketsAndRepeatedMarksGiveCleanPauses() {
        assertEquals(
            "sokɾˈɑt xɑkɪmnˈɪŋ sɵzˈɪ",
            KazakhPhonemizer.phonemize("(Сократ хакімнің сөзі)"),
        )
        assertEquals("nˈe? ˈəjæ.", KazakhPhonemizer.phonemize("Не?! Иә..."))
    }

    @Test fun loneWordInClauseIsStressed() {
        assertEquals("ʀˈəɫəm, bɪlˈɪm.", KazakhPhonemizer.phonemize("Ғылым, білім."))
    }

    @Test fun normalizedNumbersArePhonemized() {
        val text = KazakhTextNormalizer.normalize("1-ші сөз")
        assertEquals("bɪɾɪnʃˈɪ sˈɵz", KazakhPhonemizer.phonemize(text))
    }

    @Test fun codepointsRoundTrip() {
        val ipa = KazakhPhonemizer.phonemize("Қазақ")
        assertEquals(ipa, String(KazakhPhonemizer.codepoints(ipa), 0, KazakhPhonemizer.codepoints(ipa).size))
    }
}
