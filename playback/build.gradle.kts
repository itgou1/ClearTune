plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.playback"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.contracts)
    implementation(projects.core.network)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation("androidx.media3:media3-database:1.10.1")
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
}
