# Build SweepMyTV from source

Use this guide to build and test the Android TV launcher or install a local development APK on your own TV. To install the published preview, follow the [installation guide](INSTALL.md).

- [Set up your tools](#set-up-your-tools)
- [Build and test](#build-and-test)
- [Install on a test TV](#install-on-a-test-tv)
- [Signing and updates](#signing-and-updates)
- [Maintainer release workflow](#maintainer-release-workflow)

## Set up your tools

| Tool | Required version or purpose |
| --- | --- |
| Git | Clone the source and work on a branch |
| JDK | 17 |
| Android SDK | Android 35 platform and Build-Tools 35.0.0 |
| Android Platform-Tools | Provides `adb` for installing and testing on a TV |
| Python | Python 3 for the maintainer release checks and packager |

Use Android Studio’s SDK Manager or install the SDK packages with the [Android command-line tools](https://developer.android.com/tools/sdkmanager). Point `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` to your SDK. Add `platform-tools` to your `PATH` for the TV commands.

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
sdkmanager --licenses
git clone https://github.com/akhiltaneja/SweepMyTv.git
cd SweepMyTv
```

The Gradle wrapper in [launcher/](../launcher/) downloads the pinned Gradle version and build dependencies on the first run. To work in Android Studio, open that directory as the project and select JDK 17 as its Gradle JDK.

## Build and test

From the repository root, create the development key once. Keep the existing file if you have already built this checkout. The password below is the standard Android debug password for this local key.

```sh
mkdir -p tools/signing
keytool -genkeypair -keystore tools/signing/sweep-debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=SweepMyTV Local Development"
```

On Windows PowerShell, use `New-Item -ItemType Directory -Force tools/signing` and run the `keytool` command on one line. The following Gradle commands are for macOS and Linux.

```sh
cd launcher
./gradlew assembleRelease
./gradlew lintRelease
./gradlew testDebugUnitTest
```

On Windows, run the same tasks from `launcher` with `.\gradlew.bat` in place of `./gradlew`. To run one suite while developing, use a filter such as `./gradlew testDebugUnitTest --tests '*RecommendationsTest'`.

The build writes these files under `launcher/`.

| Output | Path |
| --- | --- |
| Optimized APK | `app/build/outputs/apk/release/app-release.apk` |
| Unit test report | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint report | `app/build/reports/lint-results-release.html` |

Run `assembleDebug` to produce `app/build/outputs/apk/debug/app-debug.apk` for debugging in Android Studio.

## Install on a test TV

Use an Android TV or Google TV running Android 8.0 or newer. Follow the [ADB connection guide](INSTALL.md#connect-to-the-tv) to pair or connect, then confirm your TV appears as `device` in the output of `adb devices -l`.

Run the install from `launcher/` with the exact serial or address reported for your TV in place of the example.

```sh
adb devices -l
adb -s 192.168.1.100:5555 install -r app/build/outputs/apk/release/app-release.apk
```

Open Sweep TV from Apps. Check that it launches, that the remote can navigate the dock, and that a streaming app opens. If you want to use it as your Home screen, follow the [first-launch guide](INSTALL.md#first-launch). Disconnect when finished and turn off TV debugging. For a hardware issue, include the model, Android version, firmware, and behavior in your [TV test report](https://github.com/akhiltaneja/SweepMyTv/issues/new?template=tv-test.yml).

## Signing and updates

Both the debug build and a local release build use the development key by default. The APK keeps the package name `com.sweepmytv.launcher`. An in-place update requires the same signing certificate and a compatible version code. Keep your development key for subsequent builds and use a separate test device if you already run the published preview. For an incompatible-signature error, check the existing APK’s origin and preserve any settings you need before replacing it.

The official release uses the project’s signing key. For your own release signing, Gradle reads `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` from `launcher/release.properties`. Keep this file and the keystore private.

## Maintainer release workflow

Review the latest stable CleanYourTV and Talkies changes against the pinned source and current SweepMyTV adaptations. Record the commits and reviewed scope in [Upstream updates](../launcher/UPSTREAM_UPDATES.md). Keep the app version and code in [app/build.gradle](../launcher/app/build.gradle) in sync with the [bundled release notes](../launcher/app/src/main/assets/sweep-release.json).

The maintainer scripts expect the SDK in `tools/android-sdk`. Packaging also expects the JDK under `tools/jdk17/Contents/Home`. From the repository root, use Bash to run the preview helper before packaging.

```sh
bash launcher/build-preview.sh
python3 launcher/package-release.py
```

The helper runs the online CleanYourTV source gate, Gradle tests, the release build, and lint. The packager verifies the built APK and writes the APK, notes, verification report, and checksums under `dist/releases/<version>/`. Check the signer against the official certificate before distributing an update, and test the exact signed build on a real TV before calling it device-verified. See [Upstream updates](../launcher/UPSTREAM_UPDATES.md) for the packaging and device-validation details.
