plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.data.local"
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
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
