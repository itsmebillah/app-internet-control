import java.util.Base64

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
    if (property == "supabase.publishableKey" && value.isNotBlank() && !value.startsWith("YOUR_")) {
        require(!value.startsWith("sb_secret_")) {
            "A Supabase secret key cannot be packaged in the Android app. Use a publishable key."
        }
        if (value.startsWith("sb_publishable_")) {
            // Current Supabase publishable key format.
        } else {
            val claims = runCatching {
                val payload = value.split('.')[1]
                String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            }.getOrElse {
                error("Supabase key must be a publishable key or a legacy anon JWT.")
            }
            val role = Regex("\"role\"\\s*:\\s*\"([^\"]+)\"")
                .find(claims)?.groupValues?.get(1)
            require(role == "anon") {
                "Only a legacy Supabase anon JWT may be used here; service-role and other keys are rejected."
            }
        }
    }
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
    testImplementation("org.json:json:20240303")
}
