import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing material is never committed. Local builds read the git-ignored
// `keystore.properties`; CI can inject the same values through environment
// variables instead. When neither is present the release APK is left unsigned so
// that configuration (and `assembleDebug`) never fails on a fresh checkout.
// TODO: Play 发布请使用独立的上传密钥，并把口令放进 CI/本地环境变量，不要写进仓库。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

// Version single source of truth: version.properties (managed by scripts/bump_version.py).
val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun versionPart(key: String, fallback: String): String =
    versionProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

val appVersionName: String = buildString {
    append(versionPart("versionMajor", "1")).append('.')
    append(versionPart("versionMinor", "0")).append('.')
    append(versionPart("versionPatch", "0"))
    val stage = versionPart("versionStage", "stable")
    if (stage != "stable") append('-').append(stage).append('.').append(versionPart("versionStageNumber", "1"))
}
val appVersionCode: Int = versionPart("versionCode", "1").toInt()

val releaseStoreFile = signingValue("storeFile", "SALTREADER_KEYSTORE")
val releaseStorePassword = signingValue("storePassword", "SALTREADER_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SALTREADER_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SALTREADER_KEY_PASSWORD")

android {
    namespace = "moe.gbxin.saltreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.gbxin.saltreader"
        minSdk = 26
        targetSdk = 36
        // Both are read from version.properties; use scripts/bump_version.py (or
        // `./gradlew bumpVersion -Paction=...`) instead of editing them by hand.
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        val storeFilePath = releaseStoreFile
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Allows a debug build to coexist with a Play-installed release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Play-ready release: shrink code and resources, then optimize.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // null on checkouts without signing material -> unsigned release APK.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.register<Exec>("bumpVersion") {
    group = "versioning"
    description = "Bump version.properties (use -Paction=alpha|beta|rc|release|patch|minor|major)"
    commandLine(
        "python3",
        rootProject.file("scripts/bump_version.py").absolutePath,
        providers.gradleProperty("action").getOrElse("alpha"),
    )
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.base)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.preview.tooling)
    implementation(libs.androidx.compose.foundation)
    // Pinned above the BOM on purpose: 1.5.0-beta02 provides the Material 3
    // Expressive APIs (LargeFlexibleTopAppBar, ButtonGroup, wavy progress) used by
    // the settings/reader UI. Track the stable 1.5.x line once it ships.
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pure-JVM unit tests: serialization round-trip, page-name ordering, model logic.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
