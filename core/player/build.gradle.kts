plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.core.player"
    compileSdk = 37
    defaultConfig.minSdk = 26
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all { it.maxHeapSize = "256m" }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.guava)
    testImplementation(libs.junit)
}
