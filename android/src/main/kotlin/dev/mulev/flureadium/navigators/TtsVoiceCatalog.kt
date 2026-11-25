@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.mulev.flureadium.navigators

import org.readium.r2.shared.util.Language
import java.io.File
import java.util.Locale

private val RETIRED_SHERPA_MODELS = setOf("issai-high")

/** Languages with an in-app phonemizer for Sherpa voices. */
private val PHONEMIZED_LANGUAGES = setOf("kk")

internal fun baseLanguage(tag: String): String =
    Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
        .let { when (it) { "kaz" -> "kk"; "rus" -> "ru"; "eng" -> "en"; else -> it } }

internal fun installedSherpaVoices(dataDir: String): Set<SherpaVoice> {
    val root = File(dataDir, "app_flutter/tts")
    // Phonemes come from KazakhPhonemizer (no eSpeak NG), so only Kazakh voices can be used.
    return root.listFiles().orEmpty()
        .filter { it.isDirectory && baseLanguage(it.name) in PHONEMIZED_LANGUAGES }
        .flatMap { lang ->
            lang.listFiles().orEmpty().filter { dir ->
                dir.isDirectory && dir.name !in RETIRED_SHERPA_MODELS &&
                    File(dir, "tokens.txt").let { it.isFile && it.length() > 0 } &&
                    dir.listFiles().orEmpty().any { it.isFile && it.extension == "onnx" && it.length() > 0 }
            }.flatMap { dir ->
                val definitions = SHERPA_VOICES.filter { it.modelId == dir.name }
                if (definitions.isEmpty()) {
                    listOf(SherpaVoice(dir.name, Language(lang.name), "Sherpa · ${dir.name}"))
                } else {
                    definitions.map { voice ->
                        voice.copy(language = Language(lang.name), name = "Sherpa · ${voice.name}")
                    }
                }
            }
        }.toSet()
}

internal fun hybridVoiceCatalog(sherpa: Set<SherpaVoice>, system: Set<SherpaVoice>): Set<SherpaVoice> {
    val actual = system + sherpa
    val automatic = actual.map { baseLanguage(it.language.code) }.distinct().sorted().map {
        SherpaVoice("auto:$it", Language(it), "Auto · $it")
    }
    return (automatic + actual).toSet()
}

/** Routing never substitutes a model trained for an unrelated language. */
internal object HybridVoicePolicy {
    fun choose(language: String, preferred: String?, voices: Set<SherpaVoice>, systemFailed: Boolean): SherpaVoice? {
        val candidates = voices.filter { baseLanguage(it.language.code) == baseLanguage(language) && !it.id.startsWith("auto:") }
        candidates.firstOrNull { it.id == preferred && (!systemFailed || !it.id.startsWith("system:")) }?.let { return it }
        if (!systemFailed && (preferred == null || preferred.startsWith("auto:") || preferred.startsWith("system:"))) {
            candidates.filter { it.id.startsWith("system:") && !it.requiresNetwork && it.quality in setOf("normal", "high", "highest") }
                .maxByOrNull { when (it.quality) { "highest" -> 3; "high" -> 2; else -> 1 } }
                ?.let { return it }
        }
        return candidates.filterNot { it.id.startsWith("system:") }.maxByOrNull {
            when { it.id.contains("high") && !it.id.contains("x_low") -> 4
                it.id.contains("medium") -> 3
                it.id.contains("x_low") -> 1
                else -> 2 }
        }
    }
}
