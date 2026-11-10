# ---------------------------------------------------------------------------
# Release builds use R8 full mode (proguard-android-optimize.txt) with resource
# shrinking. These rules are intentionally conservative: shrinking is meant to
# drop unused code/resources (most importantly the ~20k unused Material icons),
# not to break reflection used by the format parsers.
# ---------------------------------------------------------------------------

# --- kotlinx.serialization -------------------------------------------------
# Serializers for the @Serializable models are looked up reflectively through
# Companion.serializer(); generated $$serializer classes and their annotations
# must survive shrinking or books.json / settings JSON fail to load at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class moe.gbxin.saltreader.data.** { *** Companion; }
-keepclasseswithmembers class moe.gbxin.saltreader.data.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class moe.gbxin.saltreader.data.**$$serializer { *; }

# --- Vendored mobi parser --------------------------------------------------
# mobi-api4java is vendored in this repository and no longer depends on Apache
# Commons: its commons-io/lang3/collections4 calls were replaced with JDK
# equivalents, so no commons keep/dontwarn rules are needed. EPUB is now read
# directly by the in-repo RemoteZipReader/EpubIndexer (plain ZIP + Regex, no
# reflection), so epub4j and its kxml2/xmlpull transitives are gone as well.
-keep class org.rr.mobi4java.** { *; }

# --- Material icons --------------------------------------------------------
# androidx.compose.material:material-icons-extended is kept on purpose. The UI
# references icons through individually generated classes, so R8 removes every
# unused icon. Do NOT add `-keep class androidx.compose.material.icons.**` unless
# icons unexpectedly disappear: it would retain the whole icon set again.
