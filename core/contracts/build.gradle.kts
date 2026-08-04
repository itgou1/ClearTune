plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.core.contracts"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(projects.core.model)
    api(libs.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
}
