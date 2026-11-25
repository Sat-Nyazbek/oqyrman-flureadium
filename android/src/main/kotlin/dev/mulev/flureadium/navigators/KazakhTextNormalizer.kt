package dev.mulev.flureadium.navigators

/**
 * Turns written Kazakh into speakable Kazakh before Sherpa/eSpeak NG.
 *
 * eSpeak NG's Kazakh dictionary is tiny, so digits, ordinals ("1-ші"), Roman
 * numerals ("XIX ғасыр"), dates and abbreviations ("т.б.") were read badly or
 * spelled out. Only the text sent to the voice changes; highlighting still
 * uses the original book text.
 */
internal object KazakhTextNormalizer {
    private val units = arrayOf("нөл", "бір", "екі", "үш", "төрт", "бес", "алты", "жеті", "сегіз", "тоғыз")
    private val tens = arrayOf("", "он", "жиырма", "отыз", "қырық", "елу", "алпыс", "жетпіс", "сексен", "тоқсан")
    private val scales = listOf(
        1_000_000_000_000L to "триллион",
        1_000_000_000L to "миллиард",
        1_000_000L to "миллион",
        1_000L to "мың",
    )
    /** Ordinal of the last word of a number (vowel harmony and stem changes are irregular). */
    private val ordinalOfLast = mapOf(
        "нөл" to "нөлінші", "бір" to "бірінші", "екі" to "екінші", "үш" to "үшінші",
        "төрт" to "төртінші", "бес" to "бесінші", "алты" to "алтыншы", "жеті" to "жетінші",
        "сегіз" to "сегізінші", "тоғыз" to "тоғызыншы", "он" to "оныншы", "жиырма" to "жиырмасыншы",
        "отыз" to "отызыншы", "қырық" to "қырқыншы", "елу" to "елуінші", "алпыс" to "алпысыншы",
        "жетпіс" to "жетпісінші", "сексен" to "сексенінші", "тоқсан" to "тоқсаныншы",
        "жүз" to "жүзінші", "мың" to "мыңыншы", "миллион" to "миллионыншы",
        "миллиард" to "миллиардыншы", "триллион" to "триллионыншы",
    )
    private val months = arrayOf(
        "қаңтар", "ақпан", "наурыз", "сәуір", "мамыр", "маусым",
        "шілде", "тамыз", "қыркүйек", "қазан", "қараша", "желтоқсан",
    )
    /** Nouns written right after "N-" that mean "N-th <noun>" ("20-жылдары", "5-сынып"). */
    private val ordinalNouns = listOf(
        "жыл", "ғасыр", "том", "тарау", "бөлім", "сынып", "курс", "бап", "бет", "сөз",
        "кітап", "дәреже", "орын", "қатар", "нөмір", "күн", "ай", "апта",
    )
    private val latinToCyrillic = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у', 'k' to 'к',
        'A' to 'А', 'E' to 'Е', 'O' to 'О', 'P' to 'Р', 'C' to 'С', 'X' to 'Х', 'K' to 'К',
        'H' to 'Н', 'B' to 'В', 'M' to 'М', 'T' to 'Т',
        // Most common substitution in Kazakh typing: Latin i/I for і/І, h for һ.
        'i' to 'і', 'I' to 'І', 'h' to 'һ',
    )
    /** Roman numerals are often typed with Cyrillic Х, І, С in Kazakh books ("ХІХ ғасыр"). */
    private val cyrillicRoman = mapOf('Х' to 'X', 'І' to 'I', 'С' to 'C')

    private const val NUM = "(?<![\\p{L}\\d])"
    private val mixedWord = Regex("\\p{L}+")
    private val yearsAbbr = Regex("(\\d+)\\s*жж\\.")
    private val yearAbbr = Regex("(\\d+)\\s*ж\\.")
    private val centuryAbbr = Regex("(?<![\\p{L}\\d])(\\d+|[IVXLCХІС]+)\\s*ғ\\.")
    private val digitCentury = Regex("$NUM(\\d{1,2})\\s+(?=ғасыр)")
    private val abbreviations = listOf(
        Regex("(?<!\\p{L})[тТ]\\.\\s?с\\.\\s?с\\.") to "тағы сол сияқты",
        Regex("(?<!\\p{L})[бБ]\\.\\s?з\\.\\s?б\\.") to "біздің заманымыздан бұрын",
        Regex("(?<!\\p{L})[бБ]\\.\\s?з\\.") to "біздің заманымыз",
        Regex("(?<!\\p{L})[тТ]\\.\\s?б\\.") to "тағы басқа",
        Regex("(?<!\\p{L})т\\.\\s?т\\.") to "тағы тағы",
        // Only when the sentence continues: "Бұл мыс." (copper) stays as is.
        Regex("(?<!\\p{L})[мМ]ыс\\.(?=\\s*[\\p{Ll}\\d:,])") to "мысалы",
        Regex("(?<!\\p{L})проф\\.") to "профессор",
        Regex("(?<!\\p{L})акад\\.") to "академик",
    )
    private val roman = Regex(
        "(?<![\\p{L}])([IVXLCХІС]{1,7})(?=\\s+(?:" + ordinalNouns.joinToString("|") + "))",
    )
    private val date = Regex("$NUM(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)")
    private val groupedThousands = Regex("$NUM(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+)(?!\\d)")
    private val decimal = Regex("$NUM(\\d+),(\\d{1,2})(?!\\d)")
    private val percent = Regex("(\\d)\\s?%")
    private val numberSign = Regex("№\\s?(?=\\d)")
    private val range = Regex("(\\d)\\s?[-–—]\\s?(?=\\d)")
    private val ordinalSuffix = Regex("(ыншы|інші|ншы|нші|шы|ші)(\\p{L}*)")
    private val numberWithSuffix = Regex("$NUM(\\d+)-(\\p{L}+)")
    private val plainNumber = Regex("$NUM(\\d+)(?!\\d)")

    fun normalize(text: String): String {
        var s = fixLatinLookalikes(text)
        s = yearsAbbr.replace(s) { "${it.groupValues[1]} жылдары" }
        s = yearAbbr.replace(s) { "${it.groupValues[1]} жылы" }
        s = centuryAbbr.replace(s) { "${it.groupValues[1]} ғасыр" }
        for ((pattern, words) in abbreviations) s = pattern.replace(s, words)
        s = roman.replace(s) { m ->
            val latin = m.groupValues[1].map { cyrillicRoman[it] ?: it }.joinToString("")
            romanToInt(latin)?.let { ordinal(it.toLong()) } ?: m.value
        }
        // "19 ғасыр" / "19 ғ." mean the 19th century, not "nineteen centuries".
        s = digitCentury.replace(s) { "${ordinal(it.groupValues[1].toLong())} " }
        s = date.replace(s) { m -> dateWords(m) ?: m.value }
        s = groupedThousands.replace(s) { it.groupValues[1].filter(Char::isDigit) }
        s = decimal.replace(s) { m ->
            val whole = numberWords(m.groupValues[1])
            val frac = m.groupValues[2]
            val denominator = if (frac.length == 1) "оннан" else "жүзден"
            "$whole бүтін $denominator ${numberWords(frac.trimStart('0').ifEmpty { "0" })}"
        }
        s = percent.replace(s) { "${it.groupValues[1]} пайыз" }
        s = numberSign.replace(s, "нөмір ")
        s = range.replace(s) { "${it.groupValues[1]} – " }
        s = numberWithSuffix.replace(s) { m -> withSuffix(m.groupValues[1], m.groupValues[2]) }
        s = plainNumber.replace(s) { numberWords(it.groupValues[1]) }
        return s
    }

    fun cardinal(n: Long): String {
        require(n >= 0)
        if (n == 0L) return units[0]
        val parts = mutableListOf<String>()
        var rest = n
        for ((value, name) in scales) {
            if (rest >= value) {
                parts += below1000((rest / value).toInt())
                parts += name
                rest %= value
            }
        }
        if (rest > 0) parts += below1000(rest.toInt())
        return parts.joinToString(" ")
    }

    fun ordinal(n: Long): String {
        val words = cardinal(n).split(' ').toMutableList()
        words[words.lastIndex] = ordinalOfLast[words.last()] ?: words.last()
        return words.joinToString(" ")
    }

    private fun below1000(n: Int): List<String> {
        val out = mutableListOf<String>()
        val hundreds = n / 100
        val ten = (n % 100) / 10
        val unit = n % 10
        if (hundreds > 0) {
            if (hundreds > 1) out += units[hundreds]
            out += "жүз"
        }
        if (ten > 0) out += tens[ten]
        if (unit > 0) out += units[unit]
        return out
    }

    /** Leading zeros ("007") and very long digit runs (phone numbers) are read digit by digit. */
    private fun numberWords(digits: String): String =
        if ((digits.length > 1 && digits.startsWith('0')) || digits.length > 15) {
            digits.map { units[it - '0'] }.joinToString(" ")
        } else {
            cardinal(digits.toLong())
        }

    private fun withSuffix(digits: String, suffix: String): String {
        if (digits.length > 15) return "${numberWords(digits)} $suffix"
        val n = digits.toLong()
        ordinalSuffix.matchEntire(suffix)?.let { return ordinal(n) + it.groupValues[2] }
        if (ordinalNouns.any { suffix.startsWith(it) }) return "${ordinal(n)} $suffix"
        // Case endings attach directly: "5-ке" -> "беске", "1990-да" -> "...тоқсанда".
        return numberWords(digits) + suffix
    }

    private fun dateWords(m: MatchResult): String? {
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (day !in 1..31 || month !in 1..12) return null
        return "${cardinal(m.groupValues[3].toLong())} жылғы ${cardinal(day.toLong())} ${months[month - 1]}"
    }

    private fun romanToInt(value: String): Int? {
        val digits = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)
        var total = 0
        for (i in value.indices) {
            val current = digits[value[i]] ?: return null
            val next = if (i + 1 < value.length) digits[value[i + 1]] ?: return null else 0
            total += if (current < next) -current else current
        }
        // Reject non-canonical strings such as "IIII" or "VX".
        return total.takeIf { it in 1..399 && toRoman(it) == value }
    }

    private fun toRoman(n: Int): String {
        val table = listOf(100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var rest = n
        return buildString {
            for ((value, letters) in table) while (rest >= value) { append(letters); rest -= value }
        }
    }

    /** Latin look-alike letters inside a Cyrillic word (common in scanned/typed books). */
    private fun fixLatinLookalikes(text: String): String = mixedWord.replace(text) { m ->
        val word = m.value
        val hasCyrillic = word.any { it in 'Ѐ'..'ӿ' }
        val hasLatin = word.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (!hasCyrillic || !hasLatin) return@replace word
        val fixed = word.map { latinToCyrillic[it] ?: it }.joinToString("")
        // Keep words that still contain other Latin letters: probably not a typo.
        if (fixed.any { it in 'A'..'Z' || it in 'a'..'z' }) word else fixed
    }
}
