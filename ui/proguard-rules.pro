#​ Needed for MSAL to work because MS can't make a proper library
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign$KeyPair
-dontwarn com.google.crypto.tink.subtle.Ed25519Sign
-dontwarn com.google.crypto.tink.subtle.Ed25519Verify
-dontwarn com.google.crypto.tink.subtle.X25519
-dontwarn org.bouncycastle.asn1.ASN1Encodable
-dontwarn org.bouncycastle.asn1.pkcs.PrivateKeyInfo
-dontwarn org.bouncycastle.asn1.x509.AlgorithmIdentifier
-dontwarn org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
-dontwarn org.bouncycastle.cert.X509CertificateHolder
-dontwarn org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
-dontwarn org.bouncycastle.crypto.BlockCipher
-dontwarn org.bouncycastle.crypto.CipherParameters
-dontwarn org.bouncycastle.crypto.InvalidCipherTextException
-dontwarn org.bouncycastle.crypto.engines.AESEngine
-dontwarn org.bouncycastle.crypto.modes.GCMBlockCipher
-dontwarn org.bouncycastle.crypto.params.AEADParameters
-dontwarn org.bouncycastle.crypto.params.KeyParameter
-dontwarn org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.openssl.PEMKeyPair
-dontwarn org.bouncycastle.openssl.PEMParser
-dontwarn org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
-dontwarn com.google.auto.value.AutoValue
-dontwarn edu.umd.cs.findbugs.annotations.NonNull
-dontwarn edu.umd.cs.findbugs.annotations.Nullable
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.*
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

-keep class com.sun.jna.** { *; }

-keep class org.libusb.** { *; }

-keep class com.neurotec.** { *; }

-keep class cn.com.aratek.** { *; }

-keep class com.aratek.** { *; }
-keep class com.cognaxon.** { *; }

-keep class com.digitalpersona.uareu.** { *; }
-keep class com.jimberisolation.android.** { *; }
-keep class com.futronictech.** { *; }

-keep class com.greenbit.** { *; }
-keep class com.integratedbiometrics.** { *; }
-keep class com.nextbiometrics.fingerprint.NXTSensor.** { *; }
-keep class com.SecuGen.** { *; }
-keep class com.smufsbio.** { *; }
-keep class com.startek.fp300u.** { *; }
-keep class com.android.biomini.** { *; }
-keep class com.suprema.** { *; }
-keep class com.zkteco.** { *; }
-keep class com.zkteco.** { *; }

-keep class com.jimberisolation.android.util.CryptoKt { *; }
-keep class com.jimberisolation.android.models.** { *; }

-keep class com.iritech.** { *; }
-keep class com.mantra.mis100.** { *; }
-keep class com.credenceid.** { *; }
-keep class com.gstreamer.** { *; }

# Keep all classes and methods in your crypto-related package
-keep class com.jimberisolation.android.util.** { *; }

# If you're using any specific libraries, you may also want to keep their classes
-keep class org.libsodium.jni.** { *; }

# Keep any classes/interfaces for which you want to retain their method names/fields
-keepclassmembers class * {
    <fields>;
    <methods>;
}

# Keep the LibsodiumInitializer class and its members
-keep class com.ionspin.kotlin.crypto.LibsodiumInitializer { *; }

# Keep the ToolbarActionBar class from being obfuscated
-keep class androidx.appcompat.app.ToolbarActionBar { *; }

-keep class com.ionspin.kotlin.crypto.** { *; }


-keepclassmembers class com.sun.jna.** {
    native <methods>;
}

-keep class com.jimberisolation.android.model.** { *; }
-keep interface com.jimberisolation.android.api.** { *; }

-keepclassmembers class com.jimberisolation.android.util.** { *; }

# Keep Retrofit interfaces
-keep class retrofit2.** { *; }

# Keep models and types used by Retrofit and Gson
-keep class com.jimberisolation.android.models.** { *; }

# Keep generic types for deserialization
-keepattributes Signature

# Keep Gson converter factory (if you're using Gson)
-keep class com.google.gson.** { *; }

# Keep OkHttp classes
-keep class okhttp3.** { *; }

# If you use suspend functions (coroutines), keep the required metadata
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep data classes
-keepclassmembers class com.jimberisolation.android.util.** {
    *;
}

# Keep classes used in JSON serialization/deserialization (e.g., Retrofit, Gson)
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all classes that extend or implement ParameterizedType
-keepclassmembers class * implements java.lang.reflect.ParameterizedType {
    *;
}

# Retrofit specific rules (if you are using Retrofit)
-keepattributes Signature
-keep class * implements retrofit2.Call
-keep class * implements retrofit2.Callback
-keep class * implements okhttp3.OkHttpClient

 # With R8 full mode generic signatures are stripped for classes that are not
 # kept. Suspend functions are wrapped in continuations where the type argument
 # is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

 # R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

 # With R8 full mode generic signatures are stripped for classes that are not kept.
-keep,allowobfuscation,allowshrinking class retrofit2.Response

-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-keep class com.sun.jna.Native**