package com.jimberisolation.android.util

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.signature.Signature
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Base64
import java.security.SecureRandom

data class EdKeyPair(
    val pk: String,
    val sk: String,
)
data class WireguardKeys(
    val baseEncodedCloudcontrollerPkInX25519: String,
    val baseEncodedPrivateKeyInX25519: String,
)


// Function to generate Ed25519 key pair
fun generateEd25519KeyPair(): EdKeyPair {
    // Initialize the key pair generator
    val keyPairGenerator = Ed25519KeyPairGenerator()
    keyPairGenerator.init(Ed25519KeyGenerationParameters(SecureRandom())) // Use SecureRandom for key generation

    // Generate the key pair
    val keyPair: AsymmetricCipherKeyPair = keyPairGenerator.generateKeyPair()
    val privateKey = keyPair.private as Ed25519PrivateKeyParameters
    val publicKey = keyPair.public as Ed25519PublicKeyParameters

    return EdKeyPair(Base64.toBase64String(publicKey.encoded), Base64.toBase64String(privateKey.encoded))
}

@OptIn(ExperimentalUnsignedTypes::class)
fun generateWireguardKeys(sk: String, cloudControllerPk: String): WireguardKeys {
    var isInitialized = false;
    LibsodiumInitializer.initializeWithCallback {
        isInitialized = true;
    }

    while(!isInitialized) { }

    val curveNetworkControllerPk = Signature.ed25519PkToCurve25519((Base64.decode(cloudControllerPk).toUByteArray()));
    val baseEncodedCurveNetworkControllerPk = Base64.toBase64String(curveNetworkControllerPk.toByteArray());

    val curveSK = Signature.ed25519SkToCurve25519((Base64.decode(sk).toUByteArray()));
    val baseEncodedCurveSk = Base64.toBase64String(curveSK.toByteArray());

    return WireguardKeys(baseEncodedCloudcontrollerPkInX25519 = baseEncodedCurveNetworkControllerPk, baseEncodedPrivateKeyInX25519 = baseEncodedCurveSk)
}


