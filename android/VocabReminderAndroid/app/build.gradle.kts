import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun syncBuildValue(name: String): String {
    return ((findProperty(name) as String?) ?: System.getenv(name).orEmpty()).trim()
}

val defaultSyncServerUrl = syncBuildValue("VOCAB_SYNC_DEFAULT_URL")
val defaultSyncBearerToken = syncBuildValue("VOCAB_SYNC_DEFAULT_TOKEN")
val allowSyncReleaseDryRun = syncBuildValue("ALLOW_SYNC_RELEASE_DRY_RUN") == "1"

fun buildConfigString(value: String): String {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""
}

fun validateReleaseSyncConfig() {
    if (defaultSyncServerUrl.isBlank()) {
        throw GradleException("Release builds require VOCAB_SYNC_DEFAULT_URL.")
    }
    if (!defaultSyncServerUrl.startsWith("https://")) {
        throw GradleException("Release builds require an HTTPS VOCAB_SYNC_DEFAULT_URL: $defaultSyncServerUrl")
    }
    val host = runCatching { URI(defaultSyncServerUrl).host.orEmpty() }.getOrDefault("")
    val placeholderHost = host == "example.com" ||
        host.endsWith(".example.com") ||
        host == "example.net" ||
        host.endsWith(".example.net") ||
        host == "example.org" ||
        host.endsWith(".example.org") ||
        host == "example.invalid" ||
        host.endsWith(".example.invalid") ||
        host.endsWith(".invalid")
    if (placeholderHost && !allowSyncReleaseDryRun) {
        throw GradleException("Placeholder sync URLs require ALLOW_SYNC_RELEASE_DRY_RUN=1: $defaultSyncServerUrl")
    }
    if (defaultSyncBearerToken.isBlank()) {
        throw GradleException("Release builds require VOCAB_SYNC_DEFAULT_TOKEN.")
    }
}

android {
    namespace = "com.vocabreminder.android"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.vocabreminder.android"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SYNC_SERVER_URL", buildConfigString(defaultSyncServerUrl))
        buildConfigField("String", "DEFAULT_SYNC_BEARER_TOKEN", buildConfigString(defaultSyncBearerToken))
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        create("deviceQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.8"
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets {
        getByName("deviceQa") {
            java.srcDir("src/debug/java")
            res.srcDir("src/debug/res")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        validateReleaseSyncConfig()
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.06.01"))
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.work:work-testing:2.8.1")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.12.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}
