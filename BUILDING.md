# Building CloudStream

This document describes how to build the CloudStream Android app (and the desktop
app) from a clean checkout, including on a headless Linux machine or in CI.

## Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | AGP 9.x requires JDK 17 or newer. The project pins the `JavaLanguageVersion` toolchain to 17. |
| Android SDK Platform | API 37 (`android-37`) | Matches `compileSdk` in `gradle/libs.versions.toml`. |
| Android SDK Build-Tools | 37.0.0 | Used for `aapt2`, `d8`, `apksigner` and `zipalign`. |
| Android SDK Platform-Tools | any recent | Only needed if you want `adb`. |
| Gradle | 9.4.1 | Provided by the wrapper (`./gradlew`), no manual install needed. |

Everything else (AGP, Kotlin, Compose, media3, …) is resolved automatically by
Gradle from `google()` and `mavenCentral()`.

> **Note on SDK platform naming**
> Since Android 16 the SDK ships *minor* platform versions, so the platform
> package is called `platforms;android-37.0` in `sdkmanager` and installs into
> `platforms/android-37.0/`. `compileSdk = 37` still resolves to it.

## Quick start

The repository ships a helper script that checks the environment, installs what is
missing and builds with sane defaults for small machines:

```bash
git clone https://github.com/recloudstream/cloudstream.git
cd cloudstream

./build.sh --install     # installs JDK 17 + Android SDK components if missing
./build.sh               # builds the prerelease debug APK
```

The APK is written to `app/build/outputs/apk/prerelease/debug/`.

### `build.sh` options

| Command | Result |
|---|---|
| `./build.sh` | `assemblePrereleaseDebug` |
| `./build.sh debug` | `assemblePrereleaseDebug` |
| `./build.sh release` | `assemblePrereleaseRelease` |
| `./build.sh stable` | `assembleStableRelease` |
| `./build.sh all` | debug + release |
| `./build.sh desktop` | `:desktopApp:build` (Kotlin Multiplatform desktop) |
| `./build.sh <task>` | any arbitrary Gradle task |

Flags:

| Flag | Effect |
|---|---|
| `--install` | Install missing JDK/Android SDK components and accept SDK licenses. |
| `--clean` | Run `clean` before building. |
| `--offline` | Gradle `--offline`, build from the local dependency cache only. |
| `--no-daemon` | Do not reuse the Gradle daemon. |
| `--cc` | Enable the Gradle configuration cache. |
| `--jobs N` | Limit Gradle worker parallelism (`--max-workers`). |

The script prints the build output live and additionally stores it in
`/root/build-logs/build-<timestamp>.log` (override with `BUILD_LOG_DIR`). On
failure it extracts the `FAILURE:` / `* What went wrong` section from the log.
On success it lists the produced APKs with size, version name and SHA-256.

Environment overrides: `REPO_DIR`, `ANDROID_HOME` / `ANDROID_SDK_ROOT`,
`JAVA_HOME`, `BUILD_LOG_DIR`.

## Manual build

```bash
# point the build at your SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

chmod +x gradlew
./gradlew assemblePrereleaseDebug
./gradlew assemblePrereleaseRelease
```

Useful extra tasks:

```bash
./gradlew lint                      # Android lint
./gradlew check                     # unit tests
./gradlew :library:checkKotlinAbi   # Kotlin ABI compatibility check
./gradlew :desktopApp:build         # desktop application
```

## Signed release builds

The `prerelease` flavour is only signed when a keystore is provided through the
environment, mirroring the release workflow:

| Variable | Description |
|---|---|
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore, e.g. `key0`. |
| `SIGNING_KEY_PASSWORD` | Password of the key. |
| `SIGNING_STORE_PASSWORD` | Password of the keystore. |

The keystore is read from `$HOME/work/_temp/keystore/`. Without these variables
the release variant still builds, but the resulting APK is **unsigned** and cannot
be installed without re-signing.

Optional API keys are read from the environment or from `local.properties`:

| Environment variable | `local.properties` key |
|---|---|
| `SIMKL_CLIENT_ID` | `simkl.id` |
| `SIMKL_CLIENT_SECRET` | `simkl.secret` |
| `MAL_KEY` | `mal.key` |
| `ANILIST_KEY` | `anilist.key` |
| `TRAKT_CLIENT_ID` | `trakt.id` |
| `MDL_API_KEY` | `mdl.key` |

## Low memory machines

The Gradle daemon and the Kotlin daemon are the two memory consumers. On a machine
with 7 GB of RAM the following overrides in `~/.gradle/gradle.properties` are
enough to avoid an OOM kill:

```properties
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
```

## Troubleshooting

* **`Failed to find package 'platforms;android-37'`** – the platform is published
  under its minor version, install `platforms;android-37.0` instead.
* **`Unsupported class file major version`** – you are on a JDK older than 17.
* **`SDK location not found`** – `local.properties` is missing or `sdk.dir` points
  to the wrong path.
* **Gradle killed during compilation** – lower `-Xmx` or build with `--no-daemon`
  and `--jobs 1`.
