import java.io.File
import java.net.URI
import java.util.Properties

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

fun expandHome(path: String): String {
    val home = System.getenv("HOME")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.home")
    return when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.removePrefix("~")
        else -> path
    }
}

val releaseSigningPropertiesFile = file(
    expandHome(
        ((findProperty("VOCAB_ANDROID_SIGNING_PROPERTIES") as String?)
            ?: System.getenv("VOCAB_ANDROID_SIGNING_PROPERTIES")
            ?: "~/Library/Application Support/VocabReminderAndroid/release-signing.properties")
            .trim()
    )
)
val releaseSigningProperties = Properties()
val releaseSigningPropertiesLoadError = if (releaseSigningPropertiesFile.isFile) {
    runCatching {
        releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
    }.exceptionOrNull()
} else {
    null
}

fun releaseSigningValue(name: String): String =
    releaseSigningProperties.getProperty(name).orEmpty().trim()

val releaseSigningPropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasCompleteReleaseSigningProperties = releaseSigningPropertiesFile.isFile &&
    releaseSigningPropertiesLoadError == null &&
    releaseSigningPropertyNames.all { releaseSigningValue(it).isNotBlank() }
val releaseSigningStoreFile = releaseSigningValue("storeFile").takeIf { it.isNotBlank() }?.let { path ->
    val configuredFile = File(expandHome(path))
    if (configuredFile.isAbsolute) configuredFile else releaseSigningPropertiesFile.parentFile.resolve(configuredFile)
}

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

fun validateReleaseSigningConfig() {
    val setupHint = "Run tools/setup_android_release_signing.sh or set " +
        "VOCAB_ANDROID_SIGNING_PROPERTIES to a repository-external properties file."
    if (!releaseSigningPropertiesFile.isFile) {
        throw GradleException(
            "Release signing properties are missing: ${releaseSigningPropertiesFile.absolutePath}. $setupHint"
        )
    }
    releaseSigningPropertiesLoadError?.let { error ->
        throw GradleException(
            "Cannot read release signing properties at ${releaseSigningPropertiesFile.absolutePath}. $setupHint",
            error
        )
    }
    val missingNames = releaseSigningPropertyNames.filter { releaseSigningValue(it).isBlank() }
    if (missingNames.isNotEmpty()) {
        throw GradleException(
            "Release signing properties are incomplete (${missingNames.joinToString()}) at " +
                "${releaseSigningPropertiesFile.absolutePath}. $setupHint"
        )
    }
    val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile.toPath()
    val propertiesPath = releaseSigningPropertiesFile.canonicalFile.toPath()
    if (propertiesPath.startsWith(repositoryRoot)) {
        throw GradleException(
            "Release signing properties must stay outside the repository: $propertiesPath. $setupHint"
        )
    }
    if (releaseSigningStoreFile?.isFile != true) {
        throw GradleException(
            "Release signing keystore is missing: ${releaseSigningStoreFile?.absolutePath ?: "<not configured>"}. " +
                setupHint
        )
    }
    val storePath = releaseSigningStoreFile.canonicalFile.toPath()
    if (storePath.startsWith(repositoryRoot)) {
        throw GradleException(
            "Release signing keystore must stay outside the repository: $storePath. $setupHint"
        )
    }
}

android {
    namespace = "com.vocabreminder.android"
    compileSdk = 33
    // Instrumentation must never target the installed production package: tests call
    // LocalVocabularyStore.clearAll() as part of fixture setup. Keep every device run
    // inside the side-by-side com.vocabreminder.android.qa application instead.
    testBuildType = "deviceQa"

    defaultConfig {
        applicationId = "com.vocabreminder.android"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SYNC_SERVER_URL", buildConfigString(defaultSyncServerUrl))
        buildConfigField("String", "DEFAULT_SYNC_BEARER_TOKEN", buildConfigString(defaultSyncBearerToken))
    }

    buildFeatures {
        compose = true
    }

    val externalReleaseSigningConfig = if (hasCompleteReleaseSigningProperties) {
        signingConfigs.create("externalRelease") {
            storeFile = releaseSigningStoreFile
            storePassword = releaseSigningValue("storePassword")
            keyAlias = releaseSigningValue("keyAlias")
            keyPassword = releaseSigningValue("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        create("deviceQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            // Device fixtures must remain offline even when a caller has release
            // credentials in the ambient environment.
            buildConfigField("String", "DEFAULT_SYNC_SERVER_URL", "\"\"")
            buildConfigField("String", "DEFAULT_SYNC_BEARER_TOKEN", "\"\"")
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            externalReleaseSigningConfig?.let { signingConfig = it }
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
        validateReleaseSigningConfig()
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
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
