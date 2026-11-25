package dev.mulev.flureadium.navigators

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal interface SpeechLanguageResolver : AutoCloseable {
    suspend fun resolve(text: String, hint: String): String
    override fun close() {}
}

/** Bundled/on-device model. Short or uncertain strings inherit the EPUB language. */
internal class OnDeviceSpeechLanguageResolver : SpeechLanguageResolver {
    private val client = LanguageIdentification.getClient()
    override suspend fun resolve(text: String, hint: String): String {
        if (text.length < 40) return hint
        return withTimeoutOrNull(750) {
            suspendCancellableCoroutine { continuation ->
                client.identifyPossibleLanguages(text.take(1000))
                    .addOnSuccessListener { languages ->
                        val best = languages.maxByOrNull { it.confidence }
                        val tag = best?.takeIf { it.confidence >= 0.8f && it.languageTag != "und" }?.languageTag ?: hint
                        if (continuation.isActive) continuation.resume(tag)
                    }.addOnFailureListener {
                        if (continuation.isActive) continuation.resume(hint)
                    }
            }
        } ?: hint
    }
    override fun close() { client.close() }
}
