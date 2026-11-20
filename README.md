# X Media Downloader

[![CI](https://github.com/adamobuosi/x-media-downloader/actions/workflows/ci.yml/badge.svg)](https://github.com/adamobuosi/x-media-downloader/actions/workflows/ci.yml)

A **native Android** app (Kotlin + Jetpack Compose) to download **photos and videos**
published on X/Twitter and save them to the device's local storage.

- **Accepts a URL** typed on the home screen **or** received via the *Share* menu of the X app.
- **Size/quality choice** in the UI before downloading.
- Files are saved to `Downloads/XMediaDownloader/`.
- **Unit tests** included (`./gradlew testDebugUnitTest`).
- **Open source, GPL-3.0 licensed** — use, study, modify and distribute it freely, under the same terms.

## Table of contents

1. [Features](#features)
2. [How it works](#how-it-works)
3. [Project structure](#project-structure)
4. [Development environment (prerequisites)](#development-environment-prerequisites)
5. [Build](#build)
6. [Unit tests](#unit-tests)
7. [Running on device/emulator](#running-on-deviceemulator)
8. [Building an APK](#building-an-apk)
9. [Quick start](#quick-start)
10. [Limitations](#limitations)
11. [Contributing](#contributing)
12. [License](#license)

---

## Features

- **Paste a URL** of a tweet in the text field and tap *Fetch media*.
- **Share:** on the X/browser app, tap *Share* → **X Media Downloader**.
  The app receives the link and fetches the media automatically.
- **Choose size before downloading:**
  - **Photos:** Original, Large, Medium, Small (with dimensions when available).
  - **Videos/GIFs:** all MP4 variants (resolution · bitrate · ~size), best to worst.
- **Real-time progress** (downloaded bytes, estimated total, speed) and **cancel**.
- **"Download all"** fetches every media of the tweet at once.

## How it works

1. The URL is parsed and the **tweet ID** is extracted (`TweetUrlParser`).
2. Data is fetched from three public sources without login (`TweetApi`), in this order:
   - **Public FxTwitter API** (`api.fxtwitter.com/status/<id>`, open source) — primary
     source, with photos (URL + dimensions) and videos (MP4 variants with bitrate/duration);
   - **X's own syndication endpoint** (`cdn.syndication.twimg.com/tweet-result`) —
     fallback, in case it starts accepting requests again;
   - **temporarily issued guest token** on the v1.1 API (`api.twitter.com`) — last resort.
3. From the JSON, the app extracts photos (sizes `orig/large/medium/small`) and videos
   (MP4 variants — `.m3u8` ones are skipped, since they cannot be downloaded directly).
4. The download streams with `HttpURLConnection` (`MediaDownloader`) and saves via
   **MediaStore** (Android 10+, no runtime permission) or the public folder (Android ≤ 9,
   requires `WRITE_EXTERNAL_STORAGE`). Directory: `Download/XMediaDownloader/`.

## Project structure

```
app/src/main/java/com/xmediadownloader/
├── MainActivity.kt      Activity + Compose UI (URL, intents, permissions)
├── MediaCard.kt         Card per media item: size/quality chips + progress
├── UiState.kt           Fetch/download states and formatters (MB, MB/s…)
├── Downloads.kt         Download orchestration (state, cancellation)
└── data/
    ├── TweetUrlParser.kt      extracts the tweet ID from any text/URL
    ├── TweetApi.kt            FxTwitter → syndication → guest + JSON parsing
    ├── TweetData.kt           models (TweetData, TweetMedia, MediaVariant…)
    └── MediaDownloader.kt     streaming + MediaStore/legacy public folder

app/src/test/java/com/xmediadownloader/     ← unit tests
```

## Development environment (prerequisites)

| Tool | Tested version | Notes |
|---|---|---|
| JDK | **17 or 21** (Temurin) | JDK 25+ not yet accepted by AGP |
| Android SDK | platform **34** + build-tools 34.0.0 | install via Android Studio or `sdkmanager` |
| Gradle | 8.7 (via wrapper) | the project already ships `gradlew` |
| AGP | 8.5.2 | set in the root `build.gradle.kts` |
| Kotlin | 1.9.24 | same |

> **`local.properties`** contains `sdk.dir=/path/to/Android/sdk` — Android Studio
> generates it automatically; on the command line, create it manually at the project root.

**Installing the SDK from the command line** (if you prefer not to use Studio):

```bash
mkdir -p ~/android-sdk/cmdline-tools
unzip commandlinetools-linux-*.zip -d /tmp
mv /tmp/cmdline-tools ~/android-sdk/cmdline-tools/latest
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=~/android-sdk \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## Build

```bash
export JAVA_HOME=/path/to/jdk-21        # only if needed
cd x-media-downloader
./gradlew assembleDebug                 # first build downloads dependencies (~5–10 min)
./gradlew :app:compileDebugKotlin       # only checks Kotlin compilation (faster)
```

## Unit tests

```bash
./gradlew testDebugUnitTest             # runs all of them (32 tests)
./gradlew testDebugUnitTest --tests "com.xmediadownloader.data.TweetUrlParserTest"
```

- HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`
- XML results: `app/build/test-results/testDebugUnitTest/`

Current coverage:

| Tested class | What it covers |
|---|---|
| `TweetUrlParserTest` | ID extraction (x.com, twitter.com, mobile/vx, /photo/, /video/, pasted text) |
| `TweetApiParsingTest` | photo parsing (FxTwitter and v1.1), videos (`variants`, `.m3u8` filtering, bitrate ordering, `duration` s/ms), file names in labels |
| `MediaDownloaderFileNameTest` | file-name generation for saved files (extension by MIME, sanitization, truncation) |
| `UiFormatTest` | byte/speed formatting (locale-independent) and media type labels |

> No instrumented tests yet (Robolectric/Espresso) — UI testing is left to
> `connectedDebugAndroidTest` when added.

## Running on device/emulator

**Via Android Studio:** *Run ▶* with a device selected (Studio creates the emulator for you).

**Via command line:**

```bash
# 1. Create an emulator (if needed) and start it
~/android-sdk/emulator/emulator -avd my_avd &

# 2. Run the app on a connected device (USB debug or emulator)
./gradlew installDebug
# or (equivalent, more flexible):
adb install app/build/outputs/apk/debug/app-debug.apk
```

On the app: paste a URL → *Fetch media* → pick size/quality → *Download*.
The file lands in **Downloads/XMediaDownloader/** (visible in the *Files* app).

## Building an APK

**Debug (for testing):**

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

**Signed release (for distribution):** create your own keystore (store it safely!):

```bash
keytool -genkey -v -keystore xdownloader.keystore \
    -alias xdownloader -keyalg RSA -keysize 2048 -validity 10000
```

Add to `app/build.gradle.kts` (or use environment variables):

```kotlin
import java.util.Properties
// inside the :app module's android { } block:
signingConfigs {
    create("release") {
        storeFile = file("xdownloader.keystore")
        storePassword = System.getenv("KS_PASS") ?: "password"
        keyAlias = "xdownloader"
        keyPassword = System.getenv("KEY_PASS") ?: "password"
    }
}
buildTypes {
    release {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName("release")
    }
}
```

Generate the artifacts:

```bash
./gradlew assembleRelease     # APK:  app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease       # AAB:  app/build/outputs/bundle/release/app-release.aab (Play Store)
```

## Quick start

1. Install the app.
2. On the X app, open a tweet with a photo/video → **Share** → **X Media Downloader**.
3. Pick the size (photos) or quality (videos) and tap **Download** — the file lands in
   *Downloads/XMediaDownloader*.

## Limitations

- **Protected/deleted tweets/live broadcasts** have no downloadable media (the app tells you).
- The primary source (FxTwitter API) is a community-run service — it may go offline.
- Downloads run in the foreground; if the system kills the app, the download is cancelled.

## Contributing

Contributions are more than welcome — bug fixes, UI improvements, support for new media
sources and translations. To contribute:

1. Fork the repo and create a descriptive branch: `git checkout -b my-feature`.
2. Follow the existing code style (idiomatic Kotlin, English doc comments).
3. Run the tests before opening the PR — CI runs automatically on every push/PR:

   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```

4. Open a Pull Request describing the problem solved or the improvement proposed.

The repository includes **GitHub Actions** (`.github/workflows/ci.yml`) which validates the
Gradle wrapper, builds the debug APK and runs all unit tests on pushes and PRs.
Bugs and suggestions: open an **Issue** with steps to reproduce.

## License

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)** — the full
text is in the [LICENSE](LICENSE) file at the repository root. In short: you may use, study,
modify and distribute the software, but any distributed derivative work must also be released
under GPL-3.0 with its source code available.

> This program is free software: you can redistribute it and/or modify it under the terms of
> the GNU General Public License as published by the Free Software Foundation, either version 3
> of the License, or (at your option) any later version. It is distributed in the hope that it
> will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
> or FITNESS FOR A PARTICULAR PURPOSE.
>
> Copyright © 2026 Adamo Buosi.
