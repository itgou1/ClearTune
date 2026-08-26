plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.core.model"
    compileSdk = 37
    defaultConfig.minSdk = 26
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all { it.maxHeapSize = "256m" }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
