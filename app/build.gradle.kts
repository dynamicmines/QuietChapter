plugins {
    id("com.android.application")
}

android {
    namespace = "com.quietchapter"
    compileSdk = 36
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = "com.quietchapter"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1-architecture-review"
        testInstrumentationRunner = "com.quietchapter.SmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    testImplementation("junit:junit:4.13.2")
}
