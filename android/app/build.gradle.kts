import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") apply false
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun quotedBuildConfig(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
val supabaseUrl = System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("supabase.url")?.takeIf { it.isNotBlank() }
    ?: "https://bnkdcrayaankvyezmbhh.supabase.co"
val resolvedSupabaseUrl = supabaseUrl.trim()
val supabaseAnonKey = System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }
    ?: System.getenv("SUPABASE_PUBLISHABLE_KEY")?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("supabase.anonKey")?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("supabase.publishableKey")?.takeIf { it.isNotBlank() }
    ?: "sb_publishable_aw2ucaH2LJE4Y2czo1USBw_I6RYQI43"
val resolvedSupabaseAnonKey = supabaseAnonKey.trim()
val firebaseConfigured = file("google-services.json").exists()

// Firebase lê as opções do projeto neste arquivo; o plugin é aplicado assim que ele existir.
if (file("google-services.json").exists()) {
    pluginManager.apply("com.google.gms.google-services")
}

android {
    namespace = "com.petguard.ai"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.petguard.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", quotedBuildConfig(resolvedSupabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", quotedBuildConfig(resolvedSupabaseAnonKey))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quotedBuildConfig(resolvedSupabaseAnonKey))
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
