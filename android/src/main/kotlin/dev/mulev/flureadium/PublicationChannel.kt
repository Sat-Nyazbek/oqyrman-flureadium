@file:OptIn(ExperimentalReadiumApi::class)

package dev.mulev.flureadium

import android.util.Log
import dev.mulev.flureadium.navigators.SherpaVoice
import dev.mulev.flureadium.navigators.AndroidReadiumTtsEngine
import dev.mulev.flureadium.navigators.installedSherpaVoices
import dev.mulev.flureadium.navigators.hybridVoiceCatalog
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.getOrElse
import kotlin.time.Duration

private const val TAG = "PublicationChannel"

internal const val publicationChannelName = "dev.mulev.flureadium/main"

@ExperimentalCoroutinesApi
internal class PublicationMethodCallHandler(private val context: android.content.Context) :
    MethodChannel.MethodCallHandler {

    private val methodScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val methodQueueLock = Any()
    private var previousMethodCall = CompletableDeferred(Unit)

    fun dispose() {
        methodScope.cancel()
    }

    @OptIn(InternalReadiumApi::class, ExperimentalReadiumApi::class)
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // MethodChannel delivers calls in order, but launching each one in an unrelated
        // coroutine used to let slow open/close/stop calls overtake each other. Chain each
        // operation to its predecessor so publication and navigator lifecycle changes remain
        // in the same order in which Flutter issued them.
        val completion = CompletableDeferred<Unit>()
        val predecessor = synchronized(methodQueueLock) {
            previousMethodCall.also { previousMethodCall = completion }
        }

        methodScope.launch {
            try {
                predecessor.await()
                val res = handleMethodCallsQueue(
                    call.method,
                    call.arguments
                ).getOrElse { error ->
                    result.publicationError(call.method, error)
                    return@launch
                }

                if (res is Unit) {
                    result.success(null)
                    return@launch
                }

                result.success(res)
            } catch (_: NotImplementedError) {
                result.notImplemented()
            } catch (e: Throwable) {
                Log.e(TAG, "Throwable: $e")
                Log.e(TAG, "${e.stackTrace}")

                result.error(
                    e.javaClass.toString(),
                    e.toString(),
                    e.stackTraceToString()
                )
            } finally {
                completion.complete(Unit)
            }
        }
    }

    /**
     * OCP: dispatch table — add new methods here without modifying handleMethodCallsQueue.
     */
    private val handlers: Map<String, suspend (Any?) -> Try<Any?, PublicationError>> = buildMap {
        put("setCustomHeaders") { args ->
            @Suppress("UNCHECKED_CAST")
            val httpHeaders = (args as? Map<String, Map<String, String>> ?: emptyMap())["httpHeaders"] ?: emptyMap()
            ReadiumReader.setDefaultHttpHeaders(httpHeaders)
            Try.success(null)
        }
        put("loadPublication") { args -> loadPublication((args as List<Any?>)[0] as String) }
        put("openPublication") { args -> openPublication((args as List<Any?>)[0] as String) }
        put("closePublication") { _ ->
            ReadiumReader.closePublication()
            Try.success(null)
        }
        put("ttsEnable") { args ->
            val a = args as List<*>
            val locator = (a[1] as? Map<*, *>)?.let { Locator.fromJSON(JSONObject(it)) }
            ttsEnable(locator, FlutterTtsPreferences.fromMap(a[0] as Map<*, *>?))
        }
        put("ttsSetPreferences") { args -> ttsSetPreferences(FlutterTtsPreferences.fromMap(args as Map<*, *>?)) }
        put("setDecorationStyle") { args ->
            val a = args as List<*>
            setDecorationStyle(FlutterDecorationPreferences.fromMap(a[0] as Map<*, *>?, a[1] as Map<*, *>?))
        }
        put("ttsCanSpeak") { _ -> Try.success(ReadiumReader.ttsCanSpeak()) }
        put("ttsRequestInstallVoice") { _ -> ReadiumReader.ttsRequestInstallVoice(); Try.success(null) }
        put("ttsGetAvailableVoices") { _ -> Try.success(ttsGetAvailableVoices()) }
        put("ttsGetSystemVoices") { _ -> Try.success(ttsGetSystemVoices()) }
        put("ttsSetVoice") { args ->
            val a = args as List<*>
            ReadiumReader.ttsSetPreferredVoice(a[0] as String?, a[1] as String?)
            Try.success(null)
        }
        put("play") { args ->
            val locator = (args as List<*>)[0]?.let { (it as? Map<*, *>)?.let { m -> Locator.fromJSON(JSONObject(m)) } }
            ReadiumReader.play(locator); Try.success(null)
        }
        put("pause") { _ -> ReadiumReader.pause(); Try.success(null) }
        put("resume") { _ -> ReadiumReader.resume(); Try.success(null) }
        put("stop") { _ -> ReadiumReader.stop(); Try.success(null) }
        put("next") { _ -> ReadiumReader.next(); Try.success(null) }
        put("previous") { _ -> ReadiumReader.previous(); Try.success(null) }
        put("goToLocator") { args ->
            val a = args as List<*>
            val locator = (a[0] as? Map<*, *>)?.let { Locator.fromJSON(JSONObject(it)) }
                ?: throw Exception("goToLocator: Missing locator: ${a[0]}")
            Try.success(ReadiumReader.goToLocator(locator))
        }
        put("getLinkContent") { args ->
            val a = args as List<Any?>
            val link = Link.fromJSON(JSONObject(a[0] as String))
                ?: throw Exception("getLinkContent: Missing link")
            getLinkContent(link, a[1] as? Boolean ?: true)
        }
        put("audioEnable") { args ->
            val a = args as List<*>
            val preferences = (a[0] as? Map<*, *>)?.let { FlutterAudioPreferences.fromMap(it) } ?: FlutterAudioPreferences()
            val locator = (a[1] as? Map<*, *>)?.let { Locator.fromJSON(JSONObject(it)) }
            audioEnable(locator, preferences)
        }
        put("audioSetPreferences") { args ->
            val preferences = (args as? Map<*, *>)?.let { FlutterAudioPreferences.fromMap(it) } ?: FlutterAudioPreferences()
            ReadiumReader.audioUpdatePreferences(preferences); Try.success(null)
        }
        put("audioSeekBy") { args -> ReadiumReader.audioSeek((args as Int).toDouble()); Try.success(null) }
        put("renderFirstPage") { args ->
            val a = args as List<Any?>
            renderFirstPage(a[0] as String, a[1] as Int, a[2] as Int)
        }
    }

    private suspend fun handleMethodCallsQueue(
        method: String,
        arguments: Any?
    ): Try<Any?, PublicationError> =
        (handlers[method] ?: throw NotImplementedError()).invoke(arguments)

    /**
     * Load and return the publication manifest from a URL without opening it.
     */
    private suspend fun loadPublication(pubUrlStr: String): Try<String, PublicationError> {
        val publication =
            ReadiumReader.loadPublicationFromUrl(pubUrlStr).getOrElse { error ->
                return Try.failure(error)
            }

        val pubJsonManifest =
            publication.manifest.toJSON().toString().replace("\\/", "/")

        // Close the publication to avoid leaks.
        publication.close()
        return Try.success(pubJsonManifest)
    }

    /**
     * Open a publication from a URL. If another publication is already opened, it will be closed first.
     *
     * There can be only one... opened publication at a time.
     */
    private suspend fun openPublication(pubUrlStr: String): Try<String, PublicationError> {
        val publication =
            ReadiumReader.openPublicationFromUrl(pubUrlStr).getOrElse { error ->
                return Try.failure(error)
            }

        val pubJsonManifest =
            publication.manifest.toJSON().toString().replace("\\/", "/")

        return Try.success(pubJsonManifest)
    }

    /**
     * Enable TTS reading with the provided preferences.
     */
    private suspend fun ttsEnable(
        locator: Locator?,
        prefs: FlutterTtsPreferences
    ): Try<Any?, PublicationError> {
        val publication = ReadiumReader.currentPublication
        if (publication == null) {
            return Try.failure(
                PublicationError.Unavailable()
            )
        }

        ReadiumReader.ttsEnable(locator, prefs)
        return Try.success(null)
    }

    /**
     * Update the TTS preferences. The TTS must be enabled first.
     */
    private suspend fun ttsSetPreferences(ttsPrefs: FlutterTtsPreferences): Try<Any?, PublicationError> {
        val publication = ReadiumReader.currentPublication
        if (publication == null) {
            return Try.failure(
                PublicationError.Unavailable()
            )
        }

        ReadiumReader.ttsSetPreferences(ttsPrefs)
        return Try.success(null)
    }

    suspend fun setDecorationStyle(
        decorationPreferences: FlutterDecorationPreferences
    ): Try<Any?, PublicationError> {
        try {
            ReadiumReader.setDecorationStyle(decorationPreferences)
            return Try.success(null)
        } catch (_: Error) {
            return Try.failure(PublicationError.Unknown("Failed to set decoration style"))
        }
    }

    /**
     * Get the list of available TTS voices on the device.
     */
    fun ttsGetAvailableVoices(): List<String> {
        val sherpaVoices = ReadiumReader.ttsGetAvailableVoices()
            ?: return listOf()

        return sherpaVoices.map {
            JSONObject().apply {
                put("identifier", it.id)
                put("name", it.name)
                put("quality", it.quality)
                put("requiresNetwork", it.requiresNetwork)
                put("language", it.language.code)
            }.toString()
        }
    }

    /** Probe actual Android voices and installed Sherpa models without starting playback. */
    private suspend fun ttsGetSystemVoices(): List<String> {
        val android = AndroidReadiumTtsEngine.create(context)
        return try {
            hybridVoiceCatalog(installedSherpaVoices(context.applicationInfo.dataDir), android?.voices.orEmpty()).map { v ->
                JSONObject().apply {
                    put("identifier", v.id)
                    put("name", v.name)
                    put("quality", v.quality)
                    put("requiresNetwork", v.requiresNetwork)
                    put("language", v.language.code)
                }.toString()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { android?.close() }
        }
    }

    /**
     * Get the content of a publication resource via a Link.
     * If asString is true the content is returned as a String, otherwise as ByteArray
     */
    private suspend fun getLinkContent(link: Link, asString: Boolean): Try<Any, PublicationError> {
        val publication = ReadiumReader.currentPublication
            ?: return Try.failure(
                PublicationError.Unavailable()
            )

        val resource = publication.get(link) ?: run {
            throw Exception("getLinkContent: failed to find pub resource via link: pubId=${publication.metadata.identifier},link=$link")
        }
        val resourceBytes = resource.read().getOrElse {
            throw Exception("getLinkContent: failed to read resource. ${it.message}")
        }

        return Try.success(if (asString) String(resourceBytes) else resourceBytes)
    }

    /**
     * Enable audio (audiobook) reading with optional locator to start from and audio preferences.
     */
    private suspend fun audioEnable(
        locator: Locator?,
        preferences: FlutterAudioPreferences
    ): Try<Any?, PublicationError> {
        val publication = ReadiumReader.currentPublication
        if (publication == null) {
            return Try.failure(
                PublicationError.Unavailable()
            )
        }

        ReadiumReader.audioEnable(locator, preferences)
        return Try.success(null)
    }

    /**
     * Render the first page of a PDF file as a JPEG image.
     * Uses Android's PdfRenderer (available since API 21).
     * Returns null if the file doesn't exist or rendering fails.
     */
    private fun renderFirstPage(
        pubUrlStr: String,
        maxWidth: Int,
        maxHeight: Int
    ): Try<ByteArray?, PublicationError> {
        val filePath = pubUrlStr.removePrefix("file://")
        val file = java.io.File(filePath)
        if (!file.exists()) {
            Log.w(TAG, "File not found for renderFirstPage: $filePath")
            return Try.success(null)
        }

        return try {
            val fd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = android.graphics.pdf.PdfRenderer(fd)
            val page = renderer.openPage(0)

            val scale = minOf(
                maxWidth.toFloat() / page.width,
                maxHeight.toFloat() / page.height,
                1f
            )
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()

            val bitmap = android.graphics.Bitmap.createBitmap(
                width,
                height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(
                bitmap,
                null,
                null,
                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            page.close()
            renderer.close()
            fd.close()

            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
            bitmap.recycle()

            Try.success(stream.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render first page: $e")
            Try.success(null)
        }
    }
}

/**
 * Send a PublicationError back to Flutter via MethodChannel.Result
 */
fun MethodChannel.Result.publicationError(method: String, error: PublicationError) {
    Log.e(
        TAG,
        "$method: PublicationError<${error.errorCode}>: ${error.message}, cause=${error.cause}"
    )

    this.error(
        error.errorCode.name,
        error.message,
        error.cause?.toString()
    )
}
