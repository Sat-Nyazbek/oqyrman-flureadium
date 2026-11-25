package dev.mulev.flureadium

/*
 * Modified version of kotlin-toolkit's example app MediaService.
 * See https://github.com/search?q=repo%3Areadium%2Fkotlin-toolkit%20mediaServiceFacade&type=code
 * and https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/media-navigator.md
 */

/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import org.readium.navigator.media.common.Media3Adapter
import org.readium.navigator.media.common.MediaNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalReadiumApi::class)
typealias AnyMediaNavigator = MediaNavigator<*, *, *>

private const val TAG = "Flutter_Readium.MediaService"

private const val CUSTOM_COMMAND_REWIND_ACTION_ID = "REWIND_CUSTOM"
private const val CUSTOM_COMMAND_FORWARD_ACTION_ID = "FORWARD_CUSTOM"

@UnstableApi
enum class NotificationPlayerCustomCommandButton(
    val customAction: String,
    val commandButton: CommandButton,
) {
    REWIND(
        customAction = CUSTOM_COMMAND_REWIND_ACTION_ID,
        commandButton = CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Rewind")
            .setSlots(CommandButton.SLOT_BACK)
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_REWIND_ACTION_ID, Bundle()))
            .setCustomIconResId(androidx.media3.session.R.drawable.media3_icon_skip_back)
            .build(),
    ),
    FORWARD(
        customAction = CUSTOM_COMMAND_FORWARD_ACTION_ID,
        commandButton = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Forward")
            .setSlots(CommandButton.SLOT_FORWARD)
            .setSessionCommand(SessionCommand(CUSTOM_COMMAND_FORWARD_ACTION_ID, Bundle()))
            .setCustomIconResId(androidx.media3.session.R.drawable.media3_icon_skip_forward)
            .build(),
    );
}

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PluginMediaService : MediaSessionService(), MediaSession.Callback {

    class Session(
        val navigator: AnyMediaNavigator,
        val mediaSession: MediaSession,
        var requestId: Long,
    ) {
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    /** A retained, explicitly releasable binding to [PluginMediaService]. */
    class Binding internal constructor(
        private val application: Application,
        val binder: Binder,
        private val connection: ServiceConnection,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                application.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // Android can report an already-disconnected binding during process teardown.
                Log.w(TAG, "MediaService was already unbound", e)
            }
        }
    }

    private val notificationPlayerCustomCommandButtons =
        NotificationPlayerCustomCommandButton.entries.map { command -> command.commandButton }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()

        /* Registering custom player command buttons for player notification. */
        notificationPlayerCustomCommandButtons.forEach { commandButton ->
            commandButton.sessionCommand?.let(availableSessionCommands::add)
        }

        return MediaSession.ConnectionResult.accept(
            availableSessionCommands.build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        super.onPostConnect(session, controller)
        if (notificationPlayerCustomCommandButtons.isNotEmpty()) {
            /* Setting custom player command buttons to mediaLibrarySession for player notification. */
            /* Set media-button preferences, so that skip buttons are replaces with seek */
            session.setCustomLayout(notificationPlayerCustomCommandButtons)
            session.setMediaButtonPreferences(notificationPlayerCustomCommandButtons)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        /* Handle custom command buttons from player notification. */
        if (customCommand.customAction == NotificationPlayerCustomCommandButton.REWIND.customAction) {
            CoroutineScope(Dispatchers.Main).async {
                ReadiumReader.previous()
            }
        }
        if (customCommand.customAction == NotificationPlayerCustomCommandButton.FORWARD.customAction) {
            CoroutineScope(Dispatchers.Main).async {
                ReadiumReader.next()
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * The service interface to be used by the app.
     */
    inner class Binder : android.os.Binder() {

        private val sessionMutable: MutableStateFlow<Session?> =
            MutableStateFlow(null)

        val session: StateFlow<Session?> =
            sessionMutable.asStateFlow()

        /**
         * MediaSession operations are short and are expected on the main thread, but a monitor is
         * still used because Flutter method calls can arrive from different coroutine dispatchers.
         */
        private val sessionLock = Any()

        /**
         * Closes only the session created by [requestId]. A delayed close from an older facade must
         * never tear down a newer reader session.
         */
        fun closeSession(requestId: Long, stopPlayer: Boolean = false): Boolean =
            synchronized(sessionLock) {
                val current = sessionMutable.value
                if (current == null || current.requestId != requestId) {
                    Log.d(TAG, "closeSession: ignoring stale request=$requestId")
                    return@synchronized false
                }
                closeSessionLocked(current, stopPlayer)
                true
            }

        /** Closes whichever session is active. Used only for terminal service lifecycle events. */
        private fun closeSessionLocked(session: Session, stopPlayer: Boolean) {
            val mediaSessionId = session.mediaSession.id
            Log.d(
                TAG,
                "closeSession: releasing mediaSession=$mediaSessionId request=${session.requestId}",
            )

            // Publish null before invoking third-party release callbacks. This makes re-entrant
            // close/open calls observe a consistent state.
            sessionMutable.value = null
            if (stopPlayer) {
                try {
                    session.mediaSession.player.stop()
                } catch (e: Throwable) {
                    Log.w(TAG, "closeSession: player.stop threw", e)
                }
            }
            try {
                this@PluginMediaService.removeSession(session.mediaSession)
            } catch (e: Throwable) {
                Log.w(TAG, "closeSession: removeSession threw: $e")
            }
            try {
                session.mediaSession.release()
            } catch (e: Throwable) {
                Log.w(TAG, "closeSession: MediaSession.release threw", e)
            } finally {
                session.coroutineScope.cancel()
            }
        }

        /**
         * Compatibility bridge for clients compiled against the original binder API.
         * New code should always close a specific request lease.
         */
        @Deprecated("Use closeSession(requestId) so stale clients cannot close a newer session")
        fun closeSession() {
            sessionMutable.value?.requestId?.let { requestId ->
                closeSession(requestId, stopPlayer = true)
            }
        }

        @OptIn(FlowPreview::class)
        fun <N> openSession(
            navigator: N,
            requestId: Long,
        ) where N : AnyMediaNavigator, N : Media3Adapter {
            synchronized(sessionLock) {
                Log.d(TAG, "openSession")

                val existing = sessionMutable.value
                if (existing != null && existing.requestId > requestId) {
                    throw IllegalStateException(
                        "Stale MediaSession open request $requestId; active=${existing.requestId}",
                    )
                }

                // Re-opening the same navigator only transfers the lease to the latest request.
                // Recreating MediaSession here can break playback during a chapter transition.
                if (existing != null && existing.navigator === navigator) {
                    existing.requestId = requestId
                    Log.d(TAG, "openSession: same navigator, lease=$requestId")
                    return@synchronized
                }

                if (existing != null) {
                    Log.d(TAG, "openSession: replacing request=${existing.requestId}")
                    // A different navigator cannot remain audible after replacement. Stop its
                    // Media3 player, but never close the navigator owned by its wrapper.
                    closeSessionLocked(existing, stopPlayer = true)
                }

                val activityIntent = createSessionActivityIntent()
                val player = navigator.asMedia3Player()
                val pluginForwardingPlayer =
                    PluginSimpleBasePlayer(player, ReadiumReader.audioPreferences)

                // 🔧 FIX v3: генерируем ID заранее и логируем его — если снова
                // увидим "ID=" в логе, значит setId не выполнился.
                val sessionId = "flureadium-${UUID.randomUUID()}"
                Log.d(TAG, "openSession: creating MediaSession id=$sessionId")

                val mediaSession = MediaSession.Builder(applicationContext, pluginForwardingPlayer)
                    .setId(sessionId)
                    .setSessionActivity(activityIntent)
                    .setCallback(this@PluginMediaService)
                    .setCustomLayout(notificationPlayerCustomCommandButtons)
                    .build()

                try {
                    Log.d(TAG, "openSession: addSession id=$sessionId")
                    addSession(mediaSession)

                    val session = Session(navigator, mediaSession, requestId)
                    sessionMutable.value = session

                    navigator.currentLocator
                        .sample(5000)
                        .onEach { locator ->
                            Log.d(TAG, "Progression update: $locator")
                        }.launchIn(session.coroutineScope)
                } catch (e: Throwable) {
                    // A failed registration must not retain the Media3 player/session.
                    runCatching { removeSession(mediaSession) }
                    runCatching { mediaSession.release() }
                    throw e
                }
            }
        }

        /** Compatibility bridge for the original binder API. */
        @Deprecated("Use openSession(navigator, requestId)")
        suspend fun <N> openSession(
            navigator: N,
        ) where N : AnyMediaNavigator, N : Media3Adapter =
            openSession(navigator, nextSessionRequestId())

        private fun createSessionActivityIntent(): PendingIntent {
            // This intent will be triggered when the notification is clicked.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }

            val intent = application.packageManager.getLaunchIntentForPackage(
                application.packageName
            )

            return PendingIntent.getActivity(applicationContext, 0, intent, flags)
        }

        /** Stops the service only when no newer session is active. */
        fun stopIfIdle() = synchronized(sessionLock) {
            if (sessionMutable.value != null) return@synchronized false
            stopServiceLocked()
            true
        }

        /** Terminal shutdown requested by Android (task removal/service destruction). */
        fun stopAll() = synchronized(sessionLock) {
            sessionMutable.value?.let { closeSessionLocked(it, stopPlayer = true) }
            stopServiceLocked()
        }

        /** Compatibility bridge for the original binder API. */
        @Deprecated("Use closeSession(requestId) and stopIfIdle()")
        fun stop() {
            stopAll()
            releaseLegacyBindings(this)
        }

        private fun stopServiceLocked() {
            ServiceCompat.stopForeground(
                this@PluginMediaService,
                ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            this@PluginMediaService.stopSelf()
        }
    }

    private val binder by lazy {
        Binder()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called with $intent")

        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
            // Readium-aware client.
            Log.d(TAG, "Returning custom binder.")
            binder
        } else {
            // External controller.
            Log.d(TAG, "Returning MediaSessionService binder.")
            super.onBind(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return binder.session.value?.mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed. Stopping session and service.")
        binder.stopAll()
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying MediaService.")
        binder.stopAll()
        // Remove only our media notification. cancelAll() also wiped unrelated app
        // notifications (e.g. Firebase messages).
        NotificationManagerCompat.from(this)
            .cancel(MEDIA_NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {

        private val sessionRequestSequence = AtomicLong()
        private val legacyBindingLock = Any()
        private val legacyBindings = WeakHashMap<Binder, MutableList<Binding>>()

        const val SERVICE_INTERFACE = "dev.mulev.flureadium.MediaService"

        /** Media3 DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID (no custom provider is set). */
        private const val MEDIA_NOTIFICATION_ID = 1001

        fun start(application: Application) {
            val intent = intent(application)
            try {
                application.startService(intent)
            } catch (e: IllegalStateException) {
                // Android 8+ refuses startService() while the app is in the background. The
                // session stays alive through the bound connection, and Media3 promotes the
                // service to foreground itself once playback starts.
                Log.w(TAG, "startService refused in background; relying on binding", e)
            }
        }

        fun stop(application: Application) {
            val intent = intent(application)
            application.stopService(intent)
        }

        internal fun nextSessionRequestId(): Long = sessionRequestSequence.incrementAndGet()

        /**
         * Compatibility bridge for the original API. The binding is retained until [Binder.stop]
         * is called. New code must use [bindRetained] and close the returned handle explicitly.
         */
        @Deprecated("Use bindRetained(application) and close the returned binding")
        suspend fun bind(application: Application): Binder {
            val binding = bindRetained(application)
            synchronized(legacyBindingLock) {
                legacyBindings.getOrPut(binding.binder) { mutableListOf() }.add(binding)
            }
            return binding.binder
        }

        internal fun releaseLegacyBindings(binder: Binder) {
            val bindings = synchronized(legacyBindingLock) {
                legacyBindings.remove(binder).orEmpty()
            }
            bindings.forEach(Binding::close)
        }

        suspend fun bindRetained(application: Application): Binding {
            val mediaServiceBinding: CompletableDeferred<Binding> =
                CompletableDeferred()

            lateinit var mediaServiceConnection: ServiceConnection
            mediaServiceConnection = object : ServiceConnection {

                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    Log.d(TAG, "MediaService bound.")
                    val mediaBinder = service as? Binder
                    if (mediaBinder == null) {
                        mediaServiceBinding.completeExceptionally(
                            IllegalStateException("Unexpected MediaService binder: ${service::class.java.name}"),
                        )
                        return
                    }
                    mediaServiceBinding.complete(
                        Binding(application, mediaBinder, mediaServiceConnection),
                    )
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    Log.d(TAG, "MediaService disconnected.")
                }

                override fun onBindingDied(name: ComponentName?) {
                    mediaServiceBinding.completeExceptionally(
                        IllegalStateException("MediaService binding died."),
                    )
                }

                override fun onNullBinding(name: ComponentName) {
                    if (mediaServiceBinding.isCompleted) {
                        return
                    }
                    val errorMessage = "Failed to bind to MediaService."
                    Log.e(TAG, errorMessage)
                    val exception = IllegalStateException(errorMessage)
                    mediaServiceBinding.completeExceptionally(exception)
                }
            }

            val intent = intent(application)
            val bound = application.bindService(
                intent,
                mediaServiceConnection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) {
                throw IllegalStateException("Failed to bind to MediaService.")
            }

            return try {
                mediaServiceBinding.await()
            } catch (e: Throwable) {
                try {
                    application.unbindService(mediaServiceConnection)
                } catch (_: IllegalArgumentException) {
                }
                throw e
            }
        }

        private fun intent(application: Application) =
            Intent(SERVICE_INTERFACE)
                .apply { setClass(application, PluginMediaService::class.java) }
    }
}

@UnstableApi
class PluginSimpleBasePlayer(player: Player, val preferences: FlutterAudioPreferences) :
    ForwardingSimpleBasePlayer(player) {

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        if (seekCommand == COMMAND_SEEK_TO_NEXT) {
            return super.handleSeek(mediaItemIndex, positionMs, COMMAND_SEEK_FORWARD)
        } else if (seekCommand == COMMAND_SEEK_TO_PREVIOUS) {
            return super.handleSeek(mediaItemIndex, positionMs, COMMAND_SEEK_BACK)
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun getState(): State {
        val state = State.Builder()
        if (player.isCommandAvailable(COMMAND_GET_AUDIO_ATTRIBUTES)) {
            state.setAudioAttributes(player.audioAttributes)
        }

        if (preferences.allowExternalSeeking) {
            state.setAvailableCommands(player.availableCommands)
        } else {
            val commandsWithoutSeeking = player.availableCommands
                .buildUpon()
                .remove(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .build()
            state.setAvailableCommands(commandsWithoutSeeking)
        }

        if (player.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            state.setContentPositionMs { player.contentPosition }
            state.setContentBufferedPositionMs { player.contentBufferedPosition }
        }
        if (player.isCommandAvailable(COMMAND_GET_TEXT)) {
            state.setCurrentCues(player.currentCues)
        }
        state.setCurrentMediaItemIndex(player.currentMediaItemIndex)
        state.setDeviceInfo(player.getDeviceInfo())
        if (player.isCommandAvailable(COMMAND_GET_DEVICE_VOLUME)) {
            state.setDeviceVolume(player.deviceVolume)
            state.setIsDeviceMuted(player.isDeviceMuted)
        }
        state.setIsLoading(player.isLoading)
        state.setMaxSeekToPreviousPositionMs(player.maxSeekToPreviousPosition)
        state.setPlaybackParameters(player.playbackParameters)
        state.setPlaybackState(player.playbackState)
        state.setPlaybackSuppressionReason(player.playbackSuppressionReason)
        state.setPlayerError(player.playerError)
        val tracks =
            if (player.isCommandAvailable(COMMAND_GET_TRACKS))
                player.currentTracks
            else
                Tracks.EMPTY
        val mediaMetadata =
            if (player.isCommandAvailable(COMMAND_GET_METADATA)) player.mediaMetadata else null
        state.setPlaylist(player.currentTimeline, tracks, mediaMetadata)
        if (player.isCommandAvailable(COMMAND_GET_METADATA)) {
            state.setPlaylistMetadata(player.playlistMetadata)
        }
        state.setPlayWhenReady(
            player.playWhenReady,
            PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
        )
        state.setRepeatMode(player.repeatMode)
        state.setSeekBackIncrementMs(player.seekBackIncrement)
        state.setSeekForwardIncrementMs(player.seekForwardIncrement)
        state.setShuffleModeEnabled(player.shuffleModeEnabled)
        state.setSurfaceSize(player.surfaceSize)
        if (player.isCommandAvailable(COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            state.setTotalBufferedDurationMs { player.totalBufferedDuration }
        }
        state.setTrackSelectionParameters(player.trackSelectionParameters)
        state.setVideoSize(player.videoSize)
        if (player.isCommandAvailable(COMMAND_GET_VOLUME)) {
            state.setVolume(player.volume)
        }
        return state.build()
    }
}
