package dev.mulev.flureadium.navigators

/**
 * Kazakh text -> IPA phonemes for the Piper voices (Iseke, Raya), without eSpeak NG.
 *
 * Original rule set written for OqyrMan (not derived from eSpeak NG code or data).
 * It reproduces the phoneme conventions the voices were trained with: on a reference
 * corpus of 15 727 words from Abai's "Qara sözder" it matches 99.4 % of words exactly,
 * stress included (tool/g2p/kzg2p_reference.py, tool/g2p/reference_kk.tsv).
 *
 * Rules:
 * - letters map one-to-one (Kazakh Cyrillic is almost phonemic); и -> əj, у -> w,
 *   я -> ja, ю -> ju, ь -> ʔ; л is l before е/ә/ү/і/э, otherwise ɫ;
 * - stress: on the vowel before the first ы (ə) of the word, otherwise on the last
 *   vowel; words starting with an ы/и syllable carry no stress mark;
 * - punctuation that the voices know is kept; other symbols become a pause (",").
 *
 * Run text through [KazakhTextNormalizer] first so numbers are already words.
 */
internal object KazakhPhonemizer {
    private val letters = mapOf(
        'а' to "ɑ", 'ә' to "æ", 'б' to "b", 'в' to "v", 'г' to "ɡ", 'ғ' to "ʀ", 'д' to "d",
        'е' to "e", 'ё' to "jo", 'ж' to "ʒ", 'з' to "z", 'и' to "əj", 'й' to "j", 'к' to "k",
        'қ' to "q", 'м' to "m", 'н' to "n", 'ң' to "ŋ", 'о' to "o", 'ө' to "ɵ", 'п' to "p",
        'р' to "ɾ", 'с' to "s", 'т' to "t", 'у' to "w", 'ұ' to "ʊ", 'ү' to "u", 'ф' to "f",
        'х' to "x", 'һ' to "h", 'ц' to "ts", 'ч' to "tʃ", 'ш' to "ʃ", 'щ' to "ʃ", 'ъ' to "",
        'ы' to "ə", 'і' to "ɪ", 'ь' to "ʔ", 'э' to "e", 'ю' to "ju", 'я' to "ja",
    )
    /** Rough reading of Latin letters that appear inside Kazakh text (names, abbreviations). */
    private val latin = mapOf(
        'a' to "ɑ", 'b' to "b", 'c' to "k", 'd' to "d", 'e' to "e", 'f' to "f", 'g' to "ɡ",
        'h' to "h", 'i' to "ɪ", 'j' to "ʒ", 'k' to "k", 'l' to "ɫ", 'm' to "m", 'n' to "n",
        'o' to "o", 'p' to "p", 'q' to "q", 'r' to "ɾ", 's' to "s", 't' to "t", 'u' to "u",
        'v' to "v", 'w' to "w", 'x' to "ks", 'y' to "j", 'z' to "z",
    )
    private const val L_BEFORE_SOFT = "еәүіэ"
    private const val VOWEL_LETTERS = "аеоуыіиәөұүяюэё"
    private const val VOWELS = "ɑæeoɵʊuəɪa"
    private const val KEPT_PUNCTUATION = ",.!?;:"
    private val token = Regex("[\\p{L}]+(?:-[\\p{L}]+)*|[,.!?;:…—–\\-()«»\"„“”']|\\s+")

    private val invisible = Regex("[\u00AD\u200B-\u200D\u2060\uFEFF]")
    private val anySpace = Regex("\\p{Z}")
    private const val PAUSES = ",;:"
    private const val TERMINATORS = ".!?"

    private class Clause(val words: MutableList<String> = mutableListOf(), var end: Char? = null)

    fun phonemize(text: String): String {
        // NFC: "й"/"ё" typed as letter + combining mark; drop soft hyphens and
        // zero-width characters (they split words); every Unicode space -> ' '.
        val clean = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .replace(invisible, "")
            .replace(anySpace, " ")
        val clauses = mutableListOf(Clause())
        fun close(mark: Char) {
            val current = clauses.last()
            if (current.words.isNotEmpty()) {
                current.end = mark
                clauses += Clause()
                return
            }
            // No words since the previous mark: collapse "?!", "...", ")." into one,
            // a sentence end wins over a pause.
            val previous = clauses.getOrNull(clauses.size - 2) ?: return
            if (previous.end != null && previous.end!! in PAUSES && mark in TERMINATORS) previous.end = mark
        }
        for (m in token.findAll(clean)) {
            val t = m.value
            when {
                t.isBlank() -> Unit
                t[0].isLetter() -> word(t).takeIf { it.isNotEmpty() }?.let { clauses.last().words += it }
                t in KEPT_PUNCTUATION -> close(t[0])
                t == "…" -> close('.')
                // Dashes and brackets: a short pause, like a comma.
                t in setOf("—", "–", "(", ")") -> close(',')
                else -> Unit // quotes and stray hyphens are silent
            }
        }
        val spoken = clauses.filter { it.words.isNotEmpty() }
        // eSpeak (the voices' training data) stresses a lone word that forms its own clause.
        for (clause in spoken) {
            if (clause.words.size == 1 && 'ˈ' !in clause.words[0]) {
                clause.words[0] = forceStress(clause.words[0])
            }
        }
        return spoken.mapIndexed { i, clause ->
            val end = clause.end
            // A pause after the last words (closing bracket, trailing dash) is dropped,
            // so the phrase still ends with a falling intonation.
            val mark = if (end == null || (i == spoken.lastIndex && end in PAUSES)) "" else end.toString()
            clause.words.joinToString(" ") + mark
        }.joinToString(" ")
    }

    /** Stress for a word that espeak leaves unstressed inside a phrase but stresses alone. */
    private fun forceStress(word: String): String {
        val positions = word.indices.filter { word[it] in VOWELS }
        if (positions.isEmpty()) return word
        val at = if (positions.size <= 2) positions.first() else positions.last()
        return word.substring(0, at) + "ˈ" + word.substring(at)
    }

    fun word(raw: String): String {
        val w = raw.lowercase()
        val only = w.filter { it.isLetter() }
        if (only.length == 1 && only[0] !in VOWEL_LETTERS && only[0] in letters) {
            return segment(only) + "ə" // a lone consonant letter is read as a syllable
        }
        return w.split('-').filter { it.isNotEmpty() }.joinToString("") { stress(segment(it)) }
    }

    private fun segment(w: String): String = buildString {
        for (i in w.indices) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            when {
                c == 'л' -> append(if (next != null && next in L_BEFORE_SOFT) "l" else "ɫ")
                c in letters -> append(letters.getValue(c))
                c in latin -> append(latin.getValue(c))
            }
        }
    }

    private fun stress(seg: String): String {
        val positions = seg.indices.filter { seg[it] in VOWELS }
        if (positions.isEmpty()) return seg
        val vowels = positions.map { seg[it] }
        if (vowels[0] == 'ə') return seg
        val firstSchwa = vowels.indexOf('ə')
        val k = if (firstSchwa >= 1) firstSchwa - 1 else vowels.lastIndex
        val at = positions[k]
        return seg.substring(0, at) + "ˈ" + seg.substring(at)
    }

    /** Unicode codepoints for sherpa-onnx `GenerationConfig.phonemeCodepoints` (one sentence). */
    fun codepoints(phonemes: String): IntArray = phonemes.codePoints().toArray()
}
