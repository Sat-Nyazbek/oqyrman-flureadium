package dev.mulev.flureadium.navigators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsVoiceCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retiredIssaiModelIsNotExposed() {
        val dataDir = temporaryFolder.newFolder("app-data")
        val modelDir = File(dataDir, "app_flutter/tts/kk/issai-high")
        listOf(
            "kk_KZ-issai-high.onnx",
            "tokens.txt",
        ).forEach { relative ->
            File(modelDir, relative).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        }

        val voices = installedSherpaVoices(dataDir.absolutePath)
        assertTrue(voices.none { it.modelId == "issai-high" })
    }

    @Test
    fun kazakhVoicesNeedNoEspeakData() {
        val dataDir = temporaryFolder.newFolder("no-espeak")
        val root = File(dataDir, "app_flutter/tts/kk")
        createModel(File(root, "iseke-x_low-int8"))
        createModel(File(root, "raya-x_low-int8"))

        val modelIds = installedSherpaVoices(dataDir.absolutePath).map { it.modelId }.toSet()

        assertEquals(setOf("iseke-x_low-int8", "raya-x_low-int8"), modelIds)
    }

    @Test
    fun languagesWithoutPhonemizerAreNotExposed() {
        val dataDir = temporaryFolder.newFolder("ru-model")
        createModel(File(dataDir, "app_flutter/tts/ru/irina-medium"))

        assertTrue(installedSherpaVoices(dataDir.absolutePath).isEmpty())
    }

    private fun createModel(modelDir: File) {
        listOf("voice.onnx", "tokens.txt").forEach { relative ->
            File(modelDir, relative).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
        }
    }
}
