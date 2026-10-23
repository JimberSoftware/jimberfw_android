package com.jimberisolation.android.authentication

import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jimberisolation.android.activity.SignInActivity
import com.jimberisolation.android.backend.GoBackend
import com.jimberisolation.android.backend.Tunnel
import com.jimberisolation.config.Config
import com.jimberisolation.crypto.KeyPair
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class WireGuardBackendTest {
    @Test fun nativeTunnelCanStartStopAndRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // This changes only the test application's VPN consent on the test device.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} ACTIVATE_VPN allow"
        )).use { it.readBytes() }
        try {
            ActivityScenario.launch(SignInActivity::class.java).use {
                assertNull("VPN consent must be granted on the test device", VpnService.prepare(context))
                val backend = GoBackend(context)
                val states = mutableListOf<Tunnel.State>()
                val tunnel = object : Tunnel {
                    override fun getName() = "upstream-test"
                    override fun onStateChange(state: Tunnel.State) { states.add(state) }
                }
                // Only a documentation subnet is routed; no external endpoint or account is used.
                val config = Config.parse(ByteArrayInputStream("""
                    [Interface]
                    PrivateKey = ${KeyPair().privateKey.toBase64()}
                    Address = 192.0.2.2/32
                    [Peer]
                    PublicKey = ${KeyPair().publicKey.toBase64()}
                    AllowedIPs = 192.0.2.1/32
                """.trimIndent().toByteArray()))
                try {
                    repeat(2) {
                        assertEquals(Tunnel.State.UP, backend.setState(tunnel, Tunnel.State.UP, config))
                        assertEquals(Tunnel.State.UP, backend.getState(tunnel))
                        assertTrue(backend.runningTunnelNames.contains(tunnel.name))
                        assertFalse(backend.isAlwaysOn)
                        assertFalse(backend.isLockdownEnabled)
                        assertNotNull(backend.getStatistics(tunnel))
                        assertEquals(Tunnel.State.DOWN, backend.setState(tunnel, Tunnel.State.DOWN, null))
                        instrumentation.waitForIdleSync()
                        assertTrue(backend.runningTunnelNames.isEmpty())
                    }
                    assertEquals(listOf(Tunnel.State.UP, Tunnel.State.DOWN, Tunnel.State.UP, Tunnel.State.DOWN), states)
                } finally {
                    backend.setState(tunnel, Tunnel.State.DOWN, null)
                }
            }
        } finally {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} ACTIVATE_VPN default"
            )).use { it.readBytes() }
        }
    }
}
