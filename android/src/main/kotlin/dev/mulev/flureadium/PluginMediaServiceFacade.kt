/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package dev.mulev.flureadium

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.navigator.media.common.Media3Adapter
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Enables to try to close a session without starting the [PluginMediaService] if it is not started.
 */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class PluginMediaServiceFacade(
    private val application: Application,
) {
    private val coroutineScope: CoroutineScope =
        MainScope()

    private val operationMutex = Mutex()

    private var binding: PluginMediaService.Binding? =
        null

    private var bindingJob: Job? =
        null

    @Volatile
    private var activeRequestId: Long? = null

    private val sessionMutable: MutableStateFlow<PluginMediaService.Session?> =
        MutableStateFlow(null)

    val session: StateFlow<PluginMediaService.Session?> =
        sessionMutable.asStateFlow()

    /**
     * Throws an IllegalStateException if binding to the MyMediaService fails.
     */
    suspend fun <N> openSession(
        navigator: N,
    ) where N : AnyMediaNavigator, N : Media3Adapter {
        operationMutex.withLock {
            // Allocate the request before binding. If a slower, older bind completes after a
            // newer reader has opened a session, the service rejects this stale request.
            val requestId = PluginMediaService.nextSessionRequestId()
            activeRequestId = requestId

            val currentBinding = binding ?: run {
                val newBinding = try {
                    PluginMediaService.bindRetained(application)
                } catch (e: Exception) {
                    activeRequestId = null
                    throw e
                }
                binding = newBinding
                bindingJob?.cancel()
                bindingJob = newBinding.binder.session
                    .onEach { current ->
                        sessionMutable.value = current?.takeIf {
                            it.requestId == activeRequestId
                        }
                    }
                    .launchIn(coroutineScope)
                newBinding
            }

            try {
                PluginMediaService.start(application)
                currentBinding.binder.openSession(navigator, requestId)
                sessionMutable.value = currentBinding.binder.session.value?.takeIf {
                    it.requestId == requestId
                }
            } catch (e: Throwable) {
                if (activeRequestId == requestId) activeRequestId = null
                bindingJob?.cancelAndJoin()
                bindingJob = null
                binding = null
                currentBinding.binder.stopIfIdle()
                currentBinding.close()
                sessionMutable.value = null
                throw e
            }
        }
    }

    /**
     * Fully closes this facade's session and binding before returning.
     *
     * The request id makes this idempotent and prevents a delayed close from affecting a newer
     * navigator owned by another facade.
     */
    suspend fun closeSessionAndAwait() {
        operationMutex.withLock {
            val requestId = activeRequestId
            activeRequestId = null

            bindingJob?.cancelAndJoin()
            bindingJob = null

            val currentBinding = binding
            binding = null
            if (requestId != null) {
                currentBinding?.binder?.closeSession(requestId)
            }
            currentBinding?.binder?.stopIfIdle()
            currentBinding?.close()
            sessionMutable.value = null
        }
    }

    /** Compatibility bridge for callers using the original fire-and-forget API. */
    @Deprecated("Use closeSessionAndAwait() from a coroutine")
    fun closeSession() {
        coroutineScope.launch {
            closeSessionAndAwait()
        }
    }
}
