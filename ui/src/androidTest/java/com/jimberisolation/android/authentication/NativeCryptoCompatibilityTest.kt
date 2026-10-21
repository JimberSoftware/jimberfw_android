package com.jimberisolation.android.authentication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.jimberisolation.android.backend.GoBackend
import com.jimberisolation.android.util.generateSign
import com.jimberisolation.android.util.generateEd25519KeyPair
import com.jimberisolation.android.util.generateWireguardConfigurationKeys
import com.jimberisolation.crypto.Key
import com.jimberisolation.crypto.KeyPair
import com.jimberisolation.android.util.parseEdPrivateKeyToCurveX25519
import com.jimberisolation.android.util.parseEdPublicKeyToCurveX25519
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCryptoCompatibilityTest {
    // RFC 8032 test vector 1: represents the same 32-byte seeds stored by existing app versions.
    private val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    @Test fun existingPrivateSeedKeepsItsWireGuardKey() {
        val converted = parseEdPrivateKeyToCurveX25519(Base64.toBase64String(Hex.decode(seed)))
        assertEquals("307c83864f2833cb427a2ef1c00a013cfdff2768d980c0a3a520f006904de94f", Hex.toHexString(Base64.decode(converted)))
    }

    @Test fun existingPublicKeyKeepsItsWireGuardKey() {
        val converted = parseEdPublicKeyToCurveX25519(Base64.toBase64String(Hex.decode(publicKey)))
        assertEquals("d85e07ec22b0ad881537c2f44d662d1a143cf830c57aca4305d85c7a90f6b62e", Hex.toHexString(Base64.decode(converted)))
    }

    @Test fun daemonSignaturesStillMatchRfc8032() {
        val signature = generateSign(byteArrayOf(), Base64.toBase64String(Hex.decode(seed)))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
                "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            Hex.toHexString(signature)
        )
    }

    @Test fun newDeviceKeysCanBeGeneratedAndConverted() {
        repeat(5) {
            val deviceKeys = generateEd25519KeyPair()
            val wireGuardKeys = generateWireguardConfigurationKeys(deviceKeys.pk, deviceKeys.sk)
            val derived = KeyPair(Key.fromBase64(wireGuardKeys.base64EncodedSkCurveX25519))
            assertEquals(wireGuardKeys.base64EncodedPkCurveX25519, derived.publicKey.toBase64())
        }
    }

    @Test fun rebuiltWireGuardLibraryLoadsAndReportsVersion() {
        val backend = GoBackend(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(backend.getVersion().isNotBlank())
    }

    @Test fun applicationInitializesNativeCryptoBeforeSignIn() {
        assertTrue(LibsodiumInitializer.isInitialized())
    }
}
