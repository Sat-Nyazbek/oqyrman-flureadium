@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package dev.mulev.flureadium.navigators

import org.readium.r2.shared.util.Language
import kotlin.test.*

internal class HybridVoicePolicyTest {
    private val system = SherpaVoice("system:package:ru", Language("ru-RU"), "Android", "high")
    private val sherpa = SherpaVoice("irina-medium-int8", Language("ru"), "Irina")
    private val kk = SherpaVoice("iseke-x_low-int8", Language("kk"), "Iseke")
    private val voices = setOf(system, sherpa, kk)
    @Test fun automaticUsesOfflineSystemVoice() { assertEquals(system, HybridVoicePolicy.choose("ru", "auto:ru", voices, false)) }
    @Test fun kazakhUsesInstalledSherpaWhenSystemAbsent() { assertEquals(kk, HybridVoicePolicy.choose("kaz-KZ", "auto:kk", voices, false)) }
    @Test fun explicitSherpaOverridesSystem() { assertEquals(sherpa, HybridVoicePolicy.choose("ru", sherpa.id, voices, false)) }
    @Test fun failureFallsBackToSameLanguage() { assertEquals(sherpa, HybridVoicePolicy.choose("ru", system.id, voices, true)) }
    @Test fun missingLanguageDoesNotUseUnrelatedVoice() { assertNull(HybridVoicePolicy.choose("fr", null, voices, false)) }
    @Test fun lowQualitySystemDoesNotWinAutomatically() {
        assertEquals(sherpa, HybridVoicePolicy.choose("ru", null, setOf(system.copy(quality = "low"), sherpa), false))
    }
    @Test fun networkVoiceDoesNotWinAutomatically() {
        assertEquals(sherpa, HybridVoicePolicy.choose("ru", null, setOf(system.copy(requiresNetwork = true), sherpa), false))
    }
    @Test fun manualLowQualityVoiceStillCanBeSelected() {
        val low = system.copy(quality = "low")
        assertEquals(low, HybridVoicePolicy.choose("ru", low.id, setOf(low, sherpa), false))
    }
}
