# AGENTS.md

Guidance for AI coding agents working in this repository (Salt Reader, an Android comic/ebook
reader). Read this before changing code, then read `docs/library-saf-architecture.md` for the
data-layer contracts.

## Project at a glance

- Kotlin + Jetpack Compose (Material 3 Expressive) Android app; no network. One optional NDK
  component: the experimental Rust text-layout engine in `native/text-reader` →
  `libtext_reader.so` (see `docs/native-text-reader.md`); the default WebView reader does not need it.
- Books are indexed **in place** through the Storage Access Framework (SAF). The app declares no
  storage or network permissions and never copies user files; app-private storage only holds
  derived data (covers, text/chapter caches, EPUB page-map caches).
- Supported formats: CBZ/ZIP, PDF, image folders, image EPUB, reflowable text EPUB, MOBI/AZW3.
- `minSdk 26`, `compileSdk 37`, `targetSdk 36`, JDK 21, Gradle 9.7.1, AGP 9.4, Kotlin 2.3.21.
- Material 3 is pinned to `1.5.0-beta02` on purpose (Expressive components). Don't bump it
  casually; it is above the BOM version.

## Layout

```
app/src/main/java/moe/gbxin/saltreader/
  data/          indexing, SAF, ZIP/EPUB parsing, persistence, page sources
  ui/library/    shelf, sorting/filtering, covers
  ui/reader/     pager/vertical reader, text (WebView) reader, native (Rust) text reader, chrome
  ui/search/     search screen
  ui/settings/   settings activities, about (debug easter egg), licenses
  ui/components/ shared composables (covers, labels, color picker, TooltipIconButton)
  ui/theme/      Material 3 color/type
app/src/main/java/org/rr/mobi4java/   vendored MOBI parser (Java, do not rewrite lightly)
app/src/main/jniLibs/                 prebuilt libtext_reader.so (Rust native reader, arm64/x86_64)
native/text-reader/                   Rust crate + Android cross-compile script
app/src/test/    pure-JVM unit tests (no Robolectric)
docs/            architecture, i18n, versioning contracts
scripts/         version bump helper
third_party/     vendored notices
```

## Build and test

Use the Gradle wrapper; set `JAVA_HOME` to a JDK 21 if the shell default is older.

```bash
./gradlew :app:testDebugUnitTest              # JVM unit tests (fast, always run before handing off)
./gradlew :app:assembleDebug                  # debug APK
./gradlew :app:assembleRelease                # signed, R8-minified release APK
./gradlew :app:assembleRelease -PfastRelease  # local: no R8 / shrink / release lint (~5s warm)
./gradlew :app:lintDebug                      # lint (advisory in CI)
```

- CI (`.github/workflows/android.yml`) runs debug + release + unit tests on every push to `main`.
- Release R8 is the expensive step (~100s cold, mostly `material-icons-extended`, whose runtime
  jar is 83MB). Use `-PfastRelease` while iterating on device; never for shipping.
- Gradle configuration cache is enabled (`gradle.properties`); fall back with
  `--no-configuration-cache` if a task misbehaves after an AGP upgrade.
- Don't run `clean` between iterations — incremental Kotlin compilation and the build cache are
  what keep warm builds fast.
- Signing material (`keystore.properties`, `keystore/`) is git-ignored. CI builds an unsigned
  release; env vars `SALTREADER_KEYSTORE`, `SALTREADER_STORE_PASSWORD`, `SALTREADER_KEY_ALIAS`,
  `SALTREADER_KEY_PASSWORD` can inject signing in CI.

## Release workflow

Version lives only in `version.properties`; never hard-code it.

```bash
python3 scripts/bump_version.py alpha     # 1.0.0-alpha.N -> alpha.N+1, versionCode +1
./gradlew :app:assembleRelease            # verify the release build
git commit -am "release: <versionName> (versionCode <n>)"
git tag v<versionName> && git push origin main --tags
```

See `docs/versioning.md`. Reported bugs from alpha users should get a version bump only when a
new build is actually published.

## Architectural rules (do not break)

1. **SAF only.** Don't add `INTERNET`, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, or app
   copies of books. Everything accessed comes from a persisted tree URI.
2. **Main thread does no I/O.** File/provider/JSON work goes to `Dispatchers.IO`. Reader page
   sources are constructed lazily and only touched off the main thread.
3. **Two persistence files.** `library/books.json` is the structural index (compact JSON,
   `encodeDefaults = false`; only written on structural changes). Reading progress lives in
   `library/progress.json` (overlay, throttled, flushed on `ON_STOP`). Do not put per-page writes
   back into `books.json`, and do not re-enable `prettyPrint`.
4. **Derived caches are disposable and share one cleanup rule.** Cache files live under
   `cacheDir/text/` and are named `<bookId>_<modifiedAt>_<sizeBytes>.<suffix>`, so deletion by the
   `<bookId>_` prefix (and "clear cache") keeps working. New caches must follow this convention.
   The only other cache root is `cacheDir/fonts/` (copies of user fonts picked from the SAF font
   folder), keyed by the font reference's SHA-1 and wiped by `ReaderFonts.clearCache()`; it is
   counted in `appDataBytes()` and cleared by "clear cache". Don't add more cache roots.
5. **ZIP/EPUB parsers are in-repo.** `RemoteZipReader` parses the central directory and does local
   header + data reads; it also provides `EntryView` (byte range) reads used by the decode path.
   EPUB spine parsing/sanitizing is `EpubIndexer`; don't reintroduce epub4j/reflection parsers.
6. **Image decode budgets.** `PageImageSampling` caps target width, ~16MB byte budget and 8192px
   long side; API 28+ goes through `ImageDecoder`, older devices use the BitmapFactory + EXIF
   fallback. Keep both paths working.
7. **Concurrency model.** `LibraryRepository` has a state `mutex` (short critical sections, no
   serialization/disk inside) and a separate `writeMutex`. Page decoding uses a per-page lock plus
   a 2-permit semaphore. Don't collapse these back into one global lock.
8. **UI recomposition.** Keep work out of composition: list filtering/sorting runs on
   `Dispatchers.Default` in `LibraryViewModel`, settings are subscribed per-field, page position is
   read through `derivedStateOf` in small scopes. Avoid re-introducing top-level `settings` /
   `importState` reads in `LibraryScreen`.
9. **Serialization compatibility.** `ComicBook` and friends are `@Serializable`; new fields need
   defaults so older `books.json` keeps loading. ProGuard keep rules assume the generated
   `Companion.serializer()` / `$$serializer` pattern — don't add reflection-based serialization.
10. **No broad R8 keeps.** Icons are stripped by R8 in release; do not add
    `-keep class androidx.compose.material.icons.**`. Vendored `org.rr.mobi4java` is kept as a whole
    today; narrowing that keep is welcome but must be verified against real MOBI/AZW3 files.

## Reader UI conventions

- **Tooltips**: use the official Material 3 `TooltipBox` + `PlainTooltip` (animated, no custom
  overlay), wrapped by `TooltipIconButton` for icon buttons. Long-pressing shows the tooltip and
  fires `HapticFeedbackConstants.LONG_PRESS` without consuming the tap. Toolbars near the top of
  the screen anchor tooltips below (`anchorBelow = true`) so the status bar cannot clip them.
- **Debug menu**: hidden by default behind `AppSettings.debugUnlocked`. Unlock from the About screen
  (tap the app logo five times to open the dialog). The dialog only toggles the *visibility* of the
  Debug section in Appearance settings; turning it off also forces `debugMode = false`. The unlocked
  state persists in preferences.
- **Selection/copy**: the native reader selects whole paragraphs and drops the per-line breaks plus
  the full-width `　` indent when copying, so a paragraph pastes as a single line.

## Native text reader parity

`NativeTextReaderContent` + `native/text-reader` reproduce the WebView reader, and the **WebView is
the reference implementation** (`TextReaderContent.kt` CSS/JS). Compare against it before changing
pagination; the behaviour is documented in `docs/native-text-reader.md`.

- Rebuild `libtext_reader.so` with `native/text-reader/build-android.sh` and commit both ABIs
  whenever the crate changes — the app loads the prebuilt `.so`; Gradle never compiles Rust.
- `layout_blocks` starts every chapter on a new page, except when the previous page contains only
  images: WebView renders an illustration chapter together with the next chapter's heading and
  body, and a forced break there leaves a half-empty image page.
- Image sizing mirrors WebView `max-width` semantics: `min(page width, intrinsic width × density)`,
  capped at 0.92 × page height; a page holding a single image is full-bleed.
- Code: block code 0.86em at 1.5 line height, inline code 0.92em, both without a background; the
  column width is measured with real monospace advances so long code lines wrap instead of clipping.
- Justification only distributes space at CJK/space boundaries and never stretches inline code.

## Conventions

- Match the existing style: 4-space indent, trailing commas in multi-line argument lists, no wildcard
  imports. Comments: data-layer/parser comments are Chinese; UI/architecture comments are English.
  Follow the file you are editing.
- User-facing copy is localized in **four** locales (`values`, `values-zh-rCN`, `values-zh-rTW`,
  `values-ja`). Every `strings_*.xml` key must exist in all four; `I18nResourceConsistencyTest`
  enforces it. Prefer per-feature `strings_<module>.xml` files.
- Tests are plain JVM (`kotlin.test`/JUnit4) and must not depend on Android or Robolectric. Pure
  logic (sampling math, path comparison, classification decisions) should be extracted into
  testable objects when practical.
- Keep public behavior stable: file formats, `ComicBook` semantics, page ordering
  (`PageNameComparator`), and `progress` math are covered by tests.

## Pitfalls observed in practice

- Real-world EPUBs can be partially corrupt (e.g. entries whose local header is zeroed). The
  reader surfaces such pages as load errors; don't assume a central directory entry is readable.
- MOBI/AZW3 files using HUFF/CDIC compression are still rejected by the vendored parser (it throws
  `UnsupportedEncodingException`). Adding support needs real AZW3 samples to verify against; do not
  implement it blind.
- Some SAF providers don't support seek; `RemoteZipReader.open` returns null and indexing falls
  back to a slow streaming `ZipInputStream` pass. Keep that fallback.
- `material-icons-extended` makes cold R8 very slow. If release build time becomes a problem and
  someone is willing to vendor the ~30 icons actually used, removing the dependency is the single
  biggest build-time win (and shrinks the debug APK too).
- On some OEM ROMs (e.g. ColorOS/Android 17), shell `simpleperf` and `run-as` are blocked by
  SELinux; use app-level timing logs or instrumentation instead of assuming they work.
- Some WebView builds resolve `100vh` to 0 (blank/zero-height text pages). The text reader keeps a
  JS-maintained `--app-vh` custom property instead of relying on `vh`; keep it that way when
  touching `TextReaderContent.kt`/its CSS.
- Device testing: `adb install -r app/build/outputs/apk/release/app-release.apk` preserves the
  library, because book data lives in app-private storage and is not part of the APK.

## Definition of done for a change

1. `./gradlew :app:testDebugUnitTest` passes.
2. `./gradlew :app:assembleDebug` builds (and `assembleRelease` if you touched minification,
   ProGuard, serialization, or release-only code paths).
3. No new permissions, no main-thread I/O, no new user-file copies.
4. New strings exist in all four locales; new caches follow the `<id>_<mtime>_<size>` naming.
5. If behavior/contracts changed, update the relevant doc (`docs/library-saf-architecture.md`,
   `docs/versioning.md`, `README.md`).
