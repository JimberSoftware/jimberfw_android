package com.jimberisolation.android.model

import com.jimberisolation.android.backend.Backend
import com.jimberisolation.android.backend.Statistics
import com.jimberisolation.android.backend.Tunnel
import com.jimberisolation.config.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TunnelAccessTest {
    private val tunnel = object : Tunnel {
        override fun getName() = "test"
        override fun onStateChange(state: Tunnel.State) = Unit
    }

    @Test
    fun `activation and toggle reject absent or mismatched ownership before touching backend`() = runBlocking {
        val identities = listOf(
            Triple(null, 10, 10), // Logged out; the saved tunnel and keys still exist.
            Triple(20, 10, 10), // Another account signed in.
            Triple(10, 10, null), // Missing daemon credentials.
            Triple(10, 10, 20), // Credentials belong to a different user.
            Triple(10, 20, 10) // Tunnel metadata belongs to a different user.
        )
        for ((currentUser, tunnelUser, keyOwner) in identities) {
            for (state in listOf(Tunnel.State.UP, Tunnel.State.TOGGLE)) {
                val backend = FakeBackend()
                expectDenied {
                    TunnelAccess.setState(backend, tunnel, state, null) {
                        TunnelAccess.isOwner(currentUser, tunnelUser, keyOwner)
                    }
                }
                assertTrue(backend.calls.isEmpty())
            }
        }
    }

    @Test
    fun `owner can start toggle and reconfigure an active tunnel`() = runBlocking {
        val backend = FakeBackend()
        for (state in listOf(Tunnel.State.UP, Tunnel.State.UP, Tunnel.State.TOGGLE, Tunnel.State.TOGGLE)) {
            TunnelAccess.setState(backend, tunnel, state, null) { TunnelAccess.isOwner(10, 10, 10) }
        }
        assertEquals(listOf(Tunnel.State.UP, Tunnel.State.UP, Tunnel.State.TOGGLE, Tunnel.State.TOGGLE), backend.calls)
        assertEquals(Tunnel.State.UP, backend.state)
    }

    @Test
    fun `logout can stop a tunnel after clearing the session`() = runBlocking {
        val backend = FakeBackend().apply { state = Tunnel.State.UP }
        assertEquals(Tunnel.State.DOWN, TunnelAccess.setState(backend, tunnel, Tunnel.State.DOWN, null) { false })
        assertEquals(listOf(Tunnel.State.DOWN), backend.calls)
    }

    @Test
    fun `session revoked during activation causes immediate teardown`() = runBlocking {
        val authorized = AtomicBoolean(true)
        val backend = FakeBackend().apply { onStart = { authorized.set(false) } }
        expectDenied {
            TunnelAccess.setState(backend, tunnel, Tunnel.State.UP, null) { authorized.get() }
        }
        assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), backend.calls)
        assertEquals(Tunnel.State.DOWN, backend.state)
    }

    @Test
    fun `cancelling caller during logout cannot skip teardown`() = runBlocking {
        val authorized = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val backend = FakeBackend().apply {
            onStart = {
                started.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        }
        val job = launch(Dispatchers.Default) {
            expectDenied {
                TunnelAccess.setState(backend, tunnel, Tunnel.State.UP, null) { authorized.get() }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            authorized.set(false)
            job.cancel()
        } finally {
            resume.countDown()
        }
        job.join()
        assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN), backend.calls)
        assertEquals(Tunnel.State.DOWN, backend.state)
    }

    private suspend fun expectDenied(block: suspend () -> Unit) {
        try {
            block()
            fail("Unauthorized operation was accepted")
        } catch (_: SecurityException) {
            // Expected; do not accept unrelated exceptions as an authorization denial.
        }
    }

    private class FakeBackend : Backend {
        val calls = mutableListOf<Tunnel.State>()
        var state = Tunnel.State.DOWN
        var onStart: () -> Unit = {}

        override fun getRunningTunnelNames() = emptySet<String>()
        override fun getState(tunnel: Tunnel) = state
        override fun getStatistics(tunnel: Tunnel): Statistics = error("Statistics are not used in authorization tests")
        override fun getVersion() = "test"
        override fun setState(tunnel: Tunnel, requested: Tunnel.State, config: Config?): Tunnel.State {
            calls.add(requested)
            state = if (requested == Tunnel.State.TOGGLE) {
                if (state == Tunnel.State.UP) Tunnel.State.DOWN else Tunnel.State.UP
            } else requested
            if (state == Tunnel.State.UP) onStart()
            return state
        }
    }
}
