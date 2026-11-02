plugins {
    id("com.android.application")
}

fun supabaseBuildConfigValue(property: String): String {
    val value = rootProject.file("supabase.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.firstOrNull { it.startsWith("$property=") }
        ?.substringAfter('=')
        .orEmpty()
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .let { "\"$it\"" }
}

android {
    namespace = "com.itsmebillah.appinternetcontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.itsmebillah.appinternetcontrol"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", supabaseBuildConfigValue("supabase.url"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", supabaseBuildConfigValue("supabase.publishableKey"))
    }

    buildFeatures {
        buildConfig = true
    }

}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
