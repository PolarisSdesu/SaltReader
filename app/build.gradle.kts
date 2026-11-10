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
        // Play 要求每次上传统递增且不可回退；仅在发布新版本时 +1。
        versionCode = 2
        versionName = "1.1.0"
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

configurations.configureEach {
    // epub4j pulls an old `xmlpull:xmlpull` artifact that duplicates the platform
    // XML pull parser and breaks packaging. Android provides org.xmlpull.v1 itself.
    exclude(group = "xmlpull", module = "xmlpull")
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
    implementation(libs.io.documentnode.epub4j.core)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.commons.collections4)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pure-JVM unit tests: serialization round-trip, page-name ordering, model logic.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
