plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseStoreFile = providers.gradleProperty("CLEARTUNE_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("CLEARTUNE_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("CLEARTUNE_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("CLEARTUNE_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("CLEARTUNE_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("CLEARTUNE_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("CLEARTUNE_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("CLEARTUNE_RELEASE_KEY_PASSWORD"))
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { it.isPresent }
val releaseBuildRequested = gradle.startParameter.taskNames.any { task ->
    task.contains("release", ignoreCase = true)
}

if (releaseBuildRequested && !releaseSigningConfigured) {
    error(
        "Release signing is required. Set CLEARTUNE_RELEASE_STORE_FILE, " +
            "CLEARTUNE_RELEASE_STORE_PASSWORD, CLEARTUNE_RELEASE_KEY_ALIAS and " +
            "CLEARTUNE_RELEASE_KEY_PASSWORD as Gradle properties or environment variables.",
    )
}

android {
    namespace = "com.cleartune.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cleartune.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "256m"
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
