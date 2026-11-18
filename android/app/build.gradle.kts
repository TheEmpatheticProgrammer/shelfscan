import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Debug builds: server address and token. android/local.properties (git-ignored) wins over
// gradle.properties, which is committed to a public repo, so keep the real token in local.properties.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun devProp(name: String): String? =
    localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
val serverUrl = devProp("shelfscan.serverUrl") ?: "http://10.0.2.2:8000/"
val appToken = devProp("shelfscan.appToken") ?: ""

// Release builds: everything secret or deployment-specific comes from android/release.properties,
// which is git-ignored. See release.properties.example and docs/RELEASE.md.
val releaseProps = Properties().apply {
    val file = rootProject.file("release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun releaseProp(name: String): String? = releaseProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
val releaseServerUrl = releaseProp("serverUrl")
val releaseProblems: List<String> = buildList {
    if (releaseProp("storeFile") == null) add("storeFile: the path to your upload key (.jks) is missing")
    else if (!rootProject.file(releaseProp("storeFile")!!).exists()) add("storeFile: ${releaseProp("storeFile")} doesn't exist")
    if (releaseProp("storePassword") == null) add("storePassword is missing")
    if (releaseProp("keyAlias") == null) add("keyAlias is missing")
    if (releaseProp("keyPassword") == null) add("keyPassword is missing")
    when {
        releaseServerUrl == null -> add("serverUrl is missing")
        !releaseServerUrl.startsWith("https://") -> add("serverUrl must start with https:// (release builds refuse plain HTTP)")
        !releaseServerUrl.endsWith("/") -> add("serverUrl must end with /")
    }
}

android {
    namespace = "com.shelfscan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shelfsight.app"
        minSdk = 26
        targetSdk = 35
        // Bump versionCode by 1 for every upload to Google Play (it must always increase).
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
    }

    signingConfigs {
        create("release") {
            releaseProp("storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = releaseProp("storePassword")
            keyAlias = releaseProp("keyAlias")
            keyPassword = releaseProp("keyPassword")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["cleartext"] = "false"
            if (releaseProblems.isEmpty()) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "SERVER_URL", "\"${releaseServerUrl ?: "https://invalid.example/"}\"")
            buildConfigField("String", "APP_TOKEN", "\"${releaseProp("appToken").orEmpty()}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // Barcode scanning through Google Play services: no camera permission, no camera UI to maintain.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // ModuleInstall API: download the scanner module on demand (the manifest entry only works for Play installs).
    implementation("com.google.android.gms:play-services-base:18.5.0")

    testImplementation("junit:junit:4.13.2")
}

// A release that isn't signed or can't reach an HTTPS server would install and then fail, so refuse to
// build one. Debug builds are unaffected.
val checkReleaseConfig = tasks.register("checkReleaseConfig") {
    doLast {
        if (releaseProblems.isNotEmpty()) {
            throw GradleException(
                "android/release.properties isn't ready for a release build:\n - " +
                    releaseProblems.joinToString("\n - ") + "\nSee docs/RELEASE.md."
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(checkReleaseConfig) }
