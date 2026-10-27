import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.inkreader.nativeink"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.dpan538.inkreader.preview"
        ndk { abiFilters += "arm64-v8a" }
        minSdk = 33
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.0-preview.12"
        testInstrumentationRunner = "com.inkreader.nativeink.InkEvidence"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    val localPreviewKey = rootProject.file(".tooling/inkreader-debug.keystore")
    if (localPreviewKey.isFile) {
        signingConfigs { getByName("debug") { storeFile = localPreviewKey } }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    testImplementation("junit:junit:4.13.2")
}
