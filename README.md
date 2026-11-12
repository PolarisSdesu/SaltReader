# Salt Reader ![Build status](https://github.com/PolarisSdesu/SaltReader/actions/workflows/android.yml/badge.svg?branch=main)

[English](README.md) | [简体中文](README.zh-CN.md)

<img src="readme/logo.png" align="left" width="120" hspace="10" vspace="10">

Salt Reader is a free, local-first comic and ebook reader for Android.

It reads your files in place and supports CBZ/ZIP, image and text EPUB, MOBI/AZW3, PDF and folders of images.

<br clear="all" />

## About

Most comic readers either copy your library into app-private storage or ask for broad storage permissions. Salt Reader does neither: you grant access to a folder through Android's Storage Access Framework, and the app indexes the original files in place using only the access you granted.

The reader is written with Jetpack Compose and Material 3 Expressive. Images are decoded on demand; text books are reflowed by the platform WebView, so font size, line spacing, page margins, paged/scroll layout, chapter navigation and progress work without converting anything to another format.

## Features

Salt Reader lets you:

- Add library folders through SAF; originals are indexed in place and never copied.
- Fast by design: archives are indexed from their central directory without being unpacked, pages are decoded only when visible, and the whole viewport scales on the GPU for smooth pinch zoom.
- Read CBZ/ZIP archives directly from the granted folder, including large files.
- Read image EPUBs straight from their spine, and reflow text EPUB/MOBI/AZW3 in a native reader with a WeChat-Reading-style toolbar (contents / progress / theme / type panels), a chapter contents sheet, adjustable font size, line spacing, page margins, a serif/sans/custom font picker (system fonts plus your own font folder, with ordered fallback fonts for mixing Latin and CJK), switchable page-number or progress indicator, finger-following page swipes and one shared reader settings screen for comics and novels.
- See real covers for text EPUBs (read from the book's OPF) and a clean solid placeholder when a book genuinely has none.
- Open PDFs through the system `PdfRenderer` and image folders in natural order.
- Page through comics horizontally or scroll continuously, with RTL, tap zones, pinch zoom and a page slider.
- Search the library, filter by all / reading / unread and customise the layout and book-card labels.
- Follow the system light/dark theme, Material You dynamic colours and an optional AMOLED black mode, and pick a reading paper tone (auto / white / beige / green / black / custom).
- Use the interface in English, Simplified Chinese, Traditional Chinese or Japanese.
- Run without storage or network permissions: the app can only see folders you explicitly granted.

## Screenshots

[<img src="readme/screenshot-library.png" width="220" hspace="10" vspace="10">](readme/screenshot-library.png)
[<img src="readme/screenshot-reader.png" width="220" hspace="10" vspace="10">](readme/screenshot-reader.png)
[<img src="readme/screenshot-settings.png" width="220" hspace="10" vspace="10">](readme/screenshot-settings.png)

## Permissions

Salt Reader declares no storage, all-files or network permissions. Everything it can read comes from folders you pick in the system file picker, and that access can be revoked at any time.

The text reader blocks network loads and remote resources; only local book content is rendered.

## Contributing

Salt Reader is a small open-source project; contributions are welcome:

- [Report bugs or suggest features](https://github.com/PolarisSdesu/SaltReader/issues).
- Improve translations (English, Simplified Chinese, Traditional Chinese and Japanese strings live in `app/src/main/res/values*/`).
- Send code. Please keep the existing style and make sure `./gradlew :app:testDebugUnitTest` passes.

## Build

Requires JDK 21 and Android SDK Platform 37.

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # R8-minified release APK
./gradlew :app:testDebugUnitTest # JVM unit tests
```

### Faster local builds

A full release build is dominated by R8 and release lint (about 1m45s from a cold cache on an
8-core laptop; roughly 25s warm, mostly `lintVitalAnalyzeRelease`). When you only need an
installable release APK for device testing, skip the shrinker and release lint:

```bash
./gradlew :app:assembleDebug                      # fastest: Compose debug build, no R8
./gradlew :app:assembleRelease -PfastRelease      # unminified release APK, no R8/lint/shrink
```

`-PfastRelease` only affects local builds: CI and Play builds never pass it, so they keep the
full R8 + resource-shrinking + lint pipeline. The configuration cache is enabled, so repeat
builds also skip the configuration phase; pass `--no-configuration-cache` if a task ever
misbehaves after an AGP upgrade. Avoid `clean` between iterations — incremental Kotlin
compilation and the Gradle build cache are what keep warm builds fast.

## Third-party components

Salt Reader builds on the AndroidX, Compose and Kotlin ecosystems and includes a vendored MOBI parser.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full list and their licenses.
