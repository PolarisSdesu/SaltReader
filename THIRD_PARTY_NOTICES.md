# Third-party notices

Salt Reader includes or depends on the following open-source components. All are
distributed under the Apache License 2.0 unless noted otherwise.

## mobi-api4java (vendored)
- Vendored source: `app/src/main/java/org/rr/mobi4java`
- License: Apache License 2.0; full license text at `third_party/mobi-api4java-LICENSE.txt`
- Project: https://github.com/Haoyi/mobi-api4java
- Used to parse MOBI/AZW3 text content for the native text reader (no file conversion).
- The sources are vendored and adapted for Salt Reader. Copyright remains with the
  original authors; Salt Reader does not claim ownership.
- Adaptation note: the Apache Commons (`commons-io`, `commons-lang3`,
  `commons-collections4`) dependency was removed. Commons calls were replaced with
  equivalent JDK/native implementations. Two small helper classes
  (`org.rr.mobi4java.util.TeeOutputStream`, `org.rr.mobi4java.util.ToStringBuilder`)
  were written for this project and are covered by the same Apache License 2.0.

## RemoteZipReader / EpubIndexer (first-party code)
- Source: `app/src/main/java/moe/gbxin/saltreader/data/RemoteZipReader.kt` and
  `EpubIndexer.kt`
- These are original Salt Reader implementations (`java.nio` + `java.util.zip` +
  `kotlin.text.Regex`). They read ZIP/CBZ and EPUB containers directly (EOCD/ZIP64
  central directory, per-entry inflate) and do not derive from any third-party
  library, so no external attribution is required.
- EPUB is no longer parsed with epub4j: `epub4j-core` and its transitive
  `zip4j`/`kxml2`/`minilog`/`xmlpull` dependencies were removed completely.

## AndroidX / Kotlin
- Jetpack Compose, Material 3, AndroidX libraries, Kotlin and kotlinx
  (coroutines/serialization) are Apache License 2.0, with the Kotlin standard
  library under Apache License 2.0 as well.
- Text reflow rendering uses the Android platform `WebView` (part of the OS, not a
  bundled third-party library).

## Material 3 design reference
- `hamen/material-3-skill` — MIT
- `ReadYouApp/ReadYou` — GPL-3.0 (used only as a visual/reference study for the settings page layout; no source copied)
