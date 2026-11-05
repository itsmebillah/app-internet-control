plugins {
    id("com.android.application")
}

val defaultUpdateManifestUrl = "https://raw.githubusercontent.com/itsmebillah/app-internet-control/main/update-manifest.json"
val configuredUpdateManifestUrl = providers.gradleProperty("appUpdateManifestUrl")
    .orElse(defaultUpdateManifestUrl)
    .get()
    .trim()
val releaseStoreFile = providers.environmentVariable("APP_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("APP_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("APP_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("APP_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val escapedUpdateManifestUrl = configuredUpdateManifestUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.itsmebillah.appinternetcontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.itsmebillah.appinternetcontrol"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$escapedUpdateManifestUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    if (releaseSigningConfigured) {
        signingConfigs.create("release") {
            storeFile = file(checkNotNull(releaseStoreFile))
            storePassword = checkNotNull(releaseStorePassword)
            keyAlias = checkNotNull(releaseKeyAlias)
            keyPassword = checkNotNull(releaseKeyPassword)
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
}

gradle.taskGraph.whenReady {
    val buildingReleaseArtifact = allTasks.any { it.name == "assembleRelease" || it.name == "bundleRelease" }
    if (buildingReleaseArtifact && !releaseSigningConfigured) {
        throw GradleException("Release builds require APP_RELEASE_STORE_FILE, APP_RELEASE_STORE_PASSWORD, APP_RELEASE_KEY_ALIAS, and APP_RELEASE_KEY_PASSWORD in the process environment.")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
