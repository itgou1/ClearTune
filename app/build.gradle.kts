plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.cleartune.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cleartune.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.contracts)
    implementation(projects.core.designsystem)
    implementation(projects.core.database)
    implementation(projects.core.network)
    implementation(projects.data.local)
    implementation(projects.data.webdav)
    implementation(projects.data.download)
    implementation(projects.playback)
    implementation(projects.feature.library)
    implementation(projects.feature.sources)
    implementation(projects.feature.downloads)
    implementation(projects.feature.player)
    implementation(projects.feature.playlists)
    implementation(projects.feature.settings)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
    debugImplementation(libs.compose.ui.tooling)
}
