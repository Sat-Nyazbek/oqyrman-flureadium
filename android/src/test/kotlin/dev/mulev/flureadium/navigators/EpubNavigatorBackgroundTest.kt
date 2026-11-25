package dev.mulev.flureadium.navigators

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression coverage for visual navigator calls while TTS continues in background. */
internal class EpubNavigatorBackgroundTest {

    @Test
    fun awaitNavigatorStarted_whenAlreadyStarted_returnsTrue() = runTest {
        val started = MutableStateFlow(true)

        val result = awaitNavigatorStarted(
            started = started,
            canWait = { true },
            timeoutMillis = 100,
        )

        assertTrue(result)
    }

    @Test
    fun awaitNavigatorStarted_whenVisualHostIsInactive_returnsFalseImmediately() = runTest {
        val started = MutableStateFlow(false)

        val result = awaitNavigatorStarted(
            started = started,
            canWait = { false },
            timeoutMillis = 5_000,
        )

        assertFalse(result)
        assertTrue(testScheduler.currentTime == 0L)
    }

    @Test
    fun awaitNavigatorStarted_whenStartTimesOut_returnsFalseWithoutThrowing() = runTest {
        val started = MutableStateFlow(false)

        val result = awaitNavigatorStarted(
            started = started,
            canWait = { true },
            timeoutMillis = 100,
        )

        assertFalse(result)
    }

    @Test
    fun awaitNavigatorStarted_whenFragmentStartsBeforeTimeout_returnsTrue() = runTest {
        val started = MutableStateFlow(false)
        backgroundScope.launch {
            delay(50)
            started.value = true
        }

        val result = awaitNavigatorStarted(
            started = started,
            canWait = { true },
            timeoutMillis = 100,
        )

        assertTrue(result)
    }

    @Test
    fun awaitNavigatorStarted_whenCallerCancels_propagatesCancellation() = runTest {
        val started = MutableStateFlow(false)
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitNavigatorStarted(
                started = started,
                canWait = { true },
                timeoutMillis = 5_000,
            )
        }

        wait.cancel(CancellationException("external cancellation"))

        assertFailsWith<CancellationException> { wait.await() }
    }
}
