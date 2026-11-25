package dev.mulev.flureadium.navigators

internal fun speechSpeed(value: Double?): Float =
    (value?.takeIf { it.isFinite() && it > 0 } ?: 1.0).coerceIn(0.5, 3.0).toFloat()

/** Keeps normal sentences intact; only long input is split at punctuation/word boundaries. */
internal fun speechSegments(text: String, limit: Int = 240): List<String> {
    require(limit >= 40)
    val output = mutableListOf<String>()
    var rest = text.trim()
    while (rest.length > limit) {
        val window = rest.take(limit)
        val boundary = window.indexOfLast { it in ",;:!?…—–" }
        var cut = if (boundary >= limit / 3) boundary + 1 else window.indexOfLast { it.isWhitespace() }
        if (cut <= 0) cut = rest.indexOfFirst { it.isWhitespace() }.takeIf { it > 0 } ?: rest.length
        output += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) output += rest
    return output
}

/**
 * True when [text] contains something a voice can pronounce. Scene breaks such as
 * "* * *", lone dashes or ornaments are skipped silently instead of failing playback.
 */
internal fun isSpeakable(text: String): Boolean = text.any { it.isLetterOrDigit() }

/** Synthesis-thread confined; caches immutable PCM in memory, never files. */
internal class SpeechPcmCache(private val maxSamples: Int = 4 * 1024 * 1024) {
    private val entries = LinkedHashMap<String, ShortArray>(16, 0.75f, true)
    private var samples = 0
    operator fun get(key: String): ShortArray? = entries[key]
    fun put(key: String, pcm: ShortArray) {
        if (pcm.size > maxSamples) return
        entries.remove(key)?.let { samples -= it.size }
        entries[key] = pcm
        samples += pcm.size
        while (samples > maxSamples) {
            val oldest = entries.entries.iterator()
            samples -= oldest.next().value.size
            oldest.remove()
        }
    }
    fun clear() { entries.clear(); samples = 0 }
}

internal fun pcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
    val value = samples[index]
    when {
        !value.isFinite() -> 0
        value >= 1f -> Short.MAX_VALUE
        value <= -1f -> Short.MIN_VALUE
        else -> (value * 32767f).toInt().toShort()
    }
}
