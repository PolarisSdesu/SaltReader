# Third-party notices

Salt Reader includes or depends on the following open-source components. All are
distributed under the Apache License 2.0 unless noted otherwise.

## epub4j
- Artifact: `io.documentnode:epub4j-core:4.2.2` (plus transitives `zip4j`, `kxml2`, `minilog`)
- License: Apache License 2.0
- Project: https://github.com/documentnode/epub4j
- Used to parse EPUB containers before pages are flattened into the derived CBZ cache.

## mobi-api4java (vendored)
- Vendored source: `app/src/main/java/org/rr/mobi4java`
- License: Apache License 2.0; full license text at `third_party/mobi-api4java-LICENSE.txt`
- Project: https://github.com/Haoyi/mobi-api4java
- The sources are vendored and adapted for Salt Reader. Copyright remains with the
  original authors; Salt Reader does not claim ownership.
- Adaptation note: the Apache Commons (`commons-io`, `commons-lang3`,
  `commons-collections4`) dependency was removed. Commons calls were replaced with
  equivalent JDK/native implementations. Two small helper classes
  (`org.rr.mobi4java.util.TeeOutputStream`, `org.rr.mobi4java.util.ToStringBuilder`)
  were written for this project and are covered by the same Apache License 2.0.

## Apache Commons — no longer a dependency
- Earlier builds depended on `commons-io`, `commons-lang3` and
  `commons-collections4`. They have been removed completely (no runtime or
  proguard references remain); the vendored mobi-api4java code now uses only the
  JDK. No third-party Commons attribution is required anymore.

## AndroidX / Kotlin
- Jetpack Compose, Material 3, AndroidX libraries, Kotlin and kotlinx
  (coroutines/serialization) are Apache License 2.0, with the Kotlin standard
  library under Apache License 2.0 as well.

## Material 3 design reference
- `hamen/material-3-skill` — MIT
- `ReadYouApp/ReadYou` — GPL-3.0 (used only as a visual/reference study for the settings page layout; no source copied)
