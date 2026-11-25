package dev.mulev.flureadium

import android.content.Context
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val TAG = "FlureadiumPlugin"

@ExperimentalCoroutinesApi
class FlureadiumPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    /**
      * The MethodChannel that will the communication between Flutter and native Android
      *
      * This local reference serves to register the plugin with the Flutter Engine and unregister it
      * when the Flutter Engine is detached from the Activity
      */
    private lateinit var publicationChannel: MethodChannel

    private lateinit var publicationMethodCallHandler: PublicationMethodCallHandler

    private lateinit var binaryMessenger: BinaryMessenger

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
        binaryMessenger = flutterPluginBinding.binaryMessenger

        // Register reader view factory
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            viewTypeChannelName,
            ReadiumReaderViewFactory(binaryMessenger)
        )

        // Setup publication channel
        publicationMethodCallHandler = PublicationMethodCallHandler(flutterPluginBinding.applicationContext)
        publicationChannel = MethodChannel(binaryMessenger, publicationChannelName)
        publicationChannel.setMethodCallHandler(publicationMethodCallHandler)

        // The legacy "com.example.flureadium/tts" channel was removed: Dart never called it,
        // and its separate Sherpa engine forced the system volume to 50%. TTS goes through
        // the Readium TTS navigator (HybridReadiumTtsEngine) only.
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        result.notImplemented()
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        publicationMethodCallHandler.dispose()
        ReadiumReader.detach()
        publicationChannel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        ReadiumReader.attach(binding.activity, binaryMessenger)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        ReadiumReader.attach(binding.activity, binaryMessenger)
    }

    override fun onDetachedFromActivity() {
        // Keep publication and media session alive for background/lock-screen playback.
        // Full cleanup must happen only when engine detaches.
    }
}
