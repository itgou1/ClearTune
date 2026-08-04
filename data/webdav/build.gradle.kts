plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cleartune.data.webdav"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
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
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.work:work-testing:2.11.2")
}
