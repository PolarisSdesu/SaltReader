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

# --- Third-party parsers ---------------------------------------------------
# epub4j / mobi4java / commons are reflection- and annotation-heavy (XML DOM,
# record delegates, commons builders). Keeping these packages whole costs little
# (they are a few hundred KB) and avoids NoSuchMethodError/ClassNotFoundError in
# the EPUB/MOBI import path, which cannot be exercised by unit tests.
-keep class io.documentnode.epub4j.** { *; }
-keep class org.rr.mobi4java.** { *; }
-keep class org.apache.commons.** { *; }

# Optional / platform-provided dependencies of those libraries.
-dontwarn io.documentnode.epub4j.**
-dontwarn org.apache.commons.**
-dontwarn org.xmlpull.**
-dontwarn org.kxml2.**
-dontwarn org.slf4j.**

# --- Material icons --------------------------------------------------------
# androidx.compose.material:material-icons-extended is kept on purpose. The UI
# references icons through individually generated classes, so R8 removes every
# unused icon. Do NOT add `-keep class androidx.compose.material.icons.**` unless
# icons unexpectedly disappear: it would retain the whole icon set again.
