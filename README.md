# Salt Reader

[English](README.md) | [简体中文](README.zh-CN.md)

A local-first comic & ebook reader for Android, built with Jetpack Compose and Material 3 Expressive. Package: `moe.gbxin.saltreader`.

- Reads through SAF-granted folders only: indexes original files, no copying, no conversion
- No storage, all-files or network permissions
- Supports CBZ / ZIP / EPUB / MOBI / AZW3 / PDF / image folders
- English, Simplified Chinese, Traditional Chinese and Japanese; dynamic color, light / dark / AMOLED themes

## Features

- **Library**: SAF folder indexing, pull-to-refresh incremental updates, all / reading / unread filters, search, customizable layout and book-card labels
- **Image reader**: paged and continuous (vertical) modes, RTL, tap-zone paging, pinch zoom, page slider, persisted progress
- **Text reader**: EPUB / MOBI / AZW3 reflowed by the native WebView, font size, paged / scroll layout, character-offset progress
- **PDF**: rendered on demand via the system `PdfRenderer`
- **Settings**: appearance (theme, dynamic color, display language), reader, storage, open-source licenses, about

## Supported formats

| Format | How it is read |
|---|---|
| CBZ / ZIP | Custom `RemoteZipReader` reads the central directory directly and inflates pages on demand |
| Image EPUB | Images read in spine order from the archive |
| Text EPUB / MOBI / AZW3 | Text extracted and reflowed by the native WebView |
| PDF | System `PdfRenderer` |
| Image folder | Files read from the SAF folder in natural order |

## Build

Requires JDK 21 and Android SDK Platform 37.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # release APK (R8)
./gradlew :app:testDebugUnitTest # JVM unit tests
```

Release signing reads the git-ignored `keystore.properties` or `SALTREADER_*` environment variables; without them the release APK is unsigned.

## Tech stack

Kotlin 2.3.21 · AGP 9.4 · Gradle 9.7.1 · Compose BOM 2026.09 · Material 3 1.5 · `compileSdk 37` / `minSdk 26`. Source lives in `app/src/main/java/moe/gbxin/saltreader/` (`data` / `ui` / `util`).

## Versioning & docs

- `version.properties` is the single source of truth; bump with `scripts/bump_version.py` or `./gradlew bumpVersion -Paction=alpha`.
- Design notes: `docs/` (SAF architecture, i18n, versioning).
- Third-party notices: `THIRD_PARTY_NOTICES.md`.
