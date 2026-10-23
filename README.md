# Android GUI for [WireGuard](https://www.wireguard.com/)

## Scripts

### Init

```./switch-env.sh staging```

### Build

```./build.sh staging```

### Switch environments

```./switch-env.sh staging```


## Dev

Create a keystore/ folder with 2 files

- the keystore jks file
- a file keystore.properties with the following content

```
storePassword=XXX
keyAlias=isolation-signing-key
keyPassword=XXX
storeFile=../keystore/signing-key.jks
```

**[Download from the Play Store](https://play.google.com/store/apps/details?id=com.jimberisolation.android)**

This is an Android GUI for [WireGuard](https://www.wireguard.com/). It [opportunistically uses the kernel implementation](https://git.zx2c4.com/android_kernel_wireguard/about/), and falls back to using the non-root [userspace implementation](https://git.zx2c4.com/wireguard-go/about/).

## Building

```
$ git clone --recurse-submodules git@github.com:JimberSoftware/jimberfw_android.git
$ cd jimberfw_android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).

### Upstream and validation

The fork incorporates WireGuard Android `e7b3a3c1` (2026-03-17), including
release `1.0.20260315`. Jimber's package name, app version, Android 11 minimum,
authentication, tunnel ownership checks, environments and branding are retained.
The Jimber `wireguard-tools` submodule includes the upstream version plus the
long-tunnel-name changes. The unused official WireGuard APK updater is removed;
Jimber updates are distributed separately.

Use JDK 17, Android SDK 36.1 and NDK `28.2.13676358`. The wrapper installs Gradle
9.3.1; Android Gradle Plugin 9.1 supplies Kotlin and legacy kapt support. Release
builds require the signing configuration described above.
SDK 36.1 is required by [AndroidX Core 1.18](https://developer.android.com/jetpack/androidx/releases/core#1.18.0),
including when R8 shrinks the release build. The target SDK remains 36.

Run local checks directly through Gradle (`build.sh` also distributes the APK):

```sh
./gradlew :ui:assembleDebug :ui:testDebugUnitTest :tunnel:testDebugUnitTest :ui:lintDebug
./gradlew :ui:assembleRelease :ui:assembleGoogleplay
./gradlew :ui:connectedDebugAndroidTest
./gradlew -PandroidTestBuildType=release :ui:connectedReleaseAndroidTest
```

Run instrumentation on a disposable emulator with no real accounts. Tests use
local API fixtures and cover sign-in screens, registration, HTTP serialization,
session renewal, native cryptography, and starting/stopping/restarting a real
WireGuard VPN service. The VPN test temporarily grants VPN consent to the test
application and routes only `192.0.2.1/32`, without an external VPN peer.
Actual Microsoft/Google sign-in and a handshake/data transfer with a Jimber
gateway still require a manual check with a test account and gateway.

The signed-release suite also checks that MSAL can initialize with the selected
environment's package, redirect URI and signing certificate. This check is
skipped for debug packages, which have a different package name and signing key.
For sideloaded APKs, the signing certificate must match the environment's
Microsoft redirect. For Play distribution, sign the AAB with the upload key and
keep the production redirect tied to the Play app-signing certificate. Play
re-signs installed APKs, so validate production Microsoft login from a Play test
track rather than an APK signed with the upload key.

Build a production bundle for Play internal testing:

```sh
./switch-env.sh production
./gradlew :ui:bundleGoogleplay
```

Increase `wireguardVersionCode` before each new Play upload. The signed bundle is
written to `ui/build/outputs/bundle/googleplay/ui-googleplay.aab`.

Validation on 2026-10-23: debug, release and Google Play APK builds passed,
as did 45 JVM tests, 20 debug emulator tests and 21 staging release emulator tests
on Android 15 (API 35). Lint completed with no errors and 220 warnings.
The release APK signature, ZIP alignment and all bundled ARM64/x86_64 native
library LOAD alignments were checked for 16 KB page compatibility.

Production version `2.2` (code `44`) also passed the 45 JVM tests and Google Play
lint (no errors, 218 warnings). The signed Google Play AAB passed bundletool
validation, production package/API/MSAL configuration checks, and 64-bit native
library alignment checks. Its upload certificate matches the previous production
v43 bundle. Account login and gateway traffic still need testing from Play.

## Embedding

The tunnel library is [on Maven Central](https://search.maven.org/artifact/com.jimberisolation.android/tunnel), alongside [extensive class library documentation](https://javadoc.io/doc/com.jimberisolation.android/tunnel).

```
implementation 'com.jimberisolation.android:tunnel:$wireguardTunnelVersion'
```

The library makes use of Java 8 features, so be sure to support those in your gradle configuration with [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring):

```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

## Translating

Please help us translate the app into several languages on [our translation platform](https://crowdin.com/project/WireGuard).
